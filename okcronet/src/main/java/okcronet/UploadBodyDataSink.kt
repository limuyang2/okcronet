/*
 * MIT License
 *
 * Copyright (c) 2023 LiMuYang
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package okcronet

import okio.Buffer
import okio.Sink
import okio.Timeout
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

internal class UploadBodyDataSink : Sink {
    /**
     * The read request calls to [org.chromium.net.UploadDataProvider.read] associated with this broker that we haven't started handling.
     *
     *
     * We don't expect more than one parallel read call for a single request body provider.
     */
    private val pendingRead: BlockingQueue<Pair<ByteBuffer, CompletableFutureCompat<ReadResult>>> =
        ArrayBlockingQueue(1)

    /**
     * Whether the sink has been closed.
     *
     * Calling close() has no practical use but we check that nobody tries to write to the sink
     * after closing it, which is an indication of misuse.
     *
     * skin 是否已经关闭。
     */
    private val isClosed = AtomicBoolean()

    /**
     * 存储后台写线程发生的异常，以便在 Cronet 下次调用 read 时抛出。
     */
    private val backgroundReadThrowable = AtomicReference<Throwable>(null)

    /**
     * Indicates that Cronet is ready to receive another body part.
     *
     *
     * This method is executed by Cronet's upload data provider.
     */
    fun enqueueBodyRead(readBuffer: ByteBuffer): Future<ReadResult> {
        var backgroundThrowable = backgroundReadThrowable.get()
        if (backgroundThrowable != null) {
            val future = CompletableFutureCompat<ReadResult>()
            future.completeExceptionally(backgroundThrowable)
            return future
        }
        val future = CompletableFutureCompat<ReadResult>()


        try {
            pendingRead.put(Pair(readBuffer, future))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException("Interrupted while enqueueing read", e)
        }

        backgroundThrowable = backgroundReadThrowable.get()
        if (backgroundThrowable != null) {
            future.completeExceptionally(backgroundThrowable)
        }
        return future
    }

    /**
     * Signals that reading the OkHttp body failed with the given throwable.
     *
     *
     * This method is executed by the background OkHttp body reading thread.
     */
    fun setBackgroundReadError(t: Throwable) {
        if (backgroundReadThrowable.compareAndSet(null, t)) {
            val read = pendingRead.poll()
            read?.second?.completeExceptionally(t)
        }
    }

    /**
     * Signals that reading the body has ended and no future bytes will be sent.
     *
     *
     * This method is executed by the background OkHttp body reading thread.
     */
    @Throws(IOException::class)
    fun handleEndOfStreamSignal() {
        if (isClosed.getAndSet(true)) return

        takeNextReadRequest().second.complete(ReadResult.END_OF_BODY)
    }

    /**
     * {@inheritDoc}
     *
     * This method is executed by the background RequestBody reading thread.
     * 该方法由后台 RequestBody 主体读取线程执行。
     */
    @Throws(IOException::class)
    override fun write(source: Buffer, byteCount: Long) {
        // This is just a safeguard, close() is a no-op if the body length contract is honored.
        check(!isClosed.get())
        var bytesRemaining = byteCount
        while (bytesRemaining > 0L) {
            // 获取下一个读取请求（阻塞等待 Cronet 提供 Buffer）
            val payload = takeNextReadRequest()
            val readBuffer = payload.first
            val future = payload.second

            try {
                // 计算本次能写入多少
                val bufferRemaining = readBuffer.remaining()

                if (bufferRemaining == 0) {
                    throw IOException("Cronet provided a full buffer!")
                }

                // 取 (Buffer剩余空间) 和 (待写入数据量) 的最小值
                val bytesToWrite = min(bufferRemaining.toLong(), bytesRemaining).toInt()

                // 限制 limit 以防止多写 (NIO Buffer 标准操作)
                val originalLimit = readBuffer.limit()
                readBuffer.limit(readBuffer.position() + bytesToWrite)

                // 执行读取 (从 Okio Buffer -> NIO Buffer)
                val bytesRead = source.read(readBuffer)

                // 恢复 limit
                readBuffer.limit(originalLimit)

                if (bytesRead == -1) {
                    throw IOException("Source exhausted prematurely")
                }

                bytesRemaining -= bytesRead

                // 通知 Cronet 这次读取完成
                // 只要写入了数据，就让 Cronet 去处理。
                // 如果 bytesRemaining > 0，循环回来会再次阻塞等待 Cronet 的下一次 read 请求。
                future.complete(ReadResult.SUCCESS)

            } catch (e: Throwable) {
                future.completeExceptionally(e)
                // 同时也标记全局错误，确保前台线程能感知
                setBackgroundReadError(e)
                if (e is IOException) throw e else throw IOException(e)
            }
        }
    }


    @Throws(IOException::class)
    private fun takeNextReadRequest(): Pair<ByteBuffer, CompletableFutureCompat<ReadResult>> {
        return try {
            pendingRead.take()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for a read to finish!")
        }
    }


    override fun close() {
        isClosed.set(true)
    }

    override fun flush() {
        // Not necessary, we "flush" by sending the data to Cronet straight away when write() is called.
        // Note that this class is wrapped with a okio buffer so writes to the outer layer won't be
        // seen by this class immediately.

        // 没有必要实现，我们通过在调用write() 时立即将数据发送到Cronet来 “刷新”。
        // 请注意，该类是用okio缓冲区包装的，因此该类不会立即看到对外层的写入。
    }

    override fun timeout(): Timeout {
        return Timeout.NONE
    }

    internal enum class ReadResult {
        SUCCESS, END_OF_BODY
    }
}