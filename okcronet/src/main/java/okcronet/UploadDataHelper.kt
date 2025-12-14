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

import okcronet.http.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.buffer
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.concurrent.*
import kotlin.math.min

/**
 * 提供 Cornet 的 [UploadDataProvider]，用于将 RequestBody 进行上传。
 *
 * Provide Cornet's [UploadDataProvider] for uploading RequestBody.
 */
class UploadDataHelper private constructor() {

    companion object {

        private const val IN_MEMORY_BODY_LENGTH_THRESHOLD_BYTES = 1024L * 1024

        /**
         * 生成并获取 [UploadDataProvider]
         *
         * Generate and get [UploadDataProvider].
         *
         * @param requestBody 请求对象
         * @param writeTimeoutMillis 写入超时时间
         * @return
         */
        fun getUploadDataProvider(
            requestBody: RequestBody, writeTimeoutMillis: Long
        ): UploadDataProvider {
            val contentLength = requestBody.length()
            return if (contentLength == -1L || contentLength > IN_MEMORY_BODY_LENGTH_THRESHOLD_BYTES) {
                StreamingUploadDataProvider(
                    requestBody, UploadBodyDataSink(), writeTimeoutMillis
                )
            } else {
                InMemoryRequestBodyConverter(requestBody)
            }
        }

        private fun prepareBodyTooLongException(
            expectedLength: Long, minActualLength: Long
        ): IOException {
            return IOException(
                "Expected $expectedLength bytes but got at least $minActualLength"
            )
        }

        /**
         * [UploadDataProvider] 的实现之一：不需要将整个请求体保存在内存中
         *
         * 1. body 调用 [RequestBody.writeTo] 方法
         * 2. 当 [UploadDataProvider.read] 方法被 Cronet 调用后，接收一部分 body  (大小取决于缓冲区的容量) ，
         * 从而解除对sink的阻塞，然后再次阻塞等待新数据。缓冲区内容会被发送到 Cronet。
         *
         * 重复此过程，直到读取整个 body 为止。
         */
        private class StreamingUploadDataProvider(
            private val requestBody: RequestBody,
            private val sink: UploadBodyDataSink,
            writeTimeoutMillis: Long,
        ) : UploadDataProvider() {

            // 全局共享线程池，避免每个请求创建一个线程造成的资源枯竭
            companion object {
                private val SHARED_EXECUTOR: ExecutorService by lazy {
                    ThreadPoolExecutor(
                        0, Int.MAX_VALUE,
                        30L, TimeUnit.SECONDS,
                        SynchronousQueue()
                    ) { r ->
                        Thread(r, "OkCronet-Upload-Worker").apply { isDaemon = true }
                    }
                }
            }

            // So that we don't have to special case infinity. Int.MAX_VALUE is ~infinity for all
            // practical use cases.
            private val writeTimeoutMillis: Long = if (writeTimeoutMillis == 0L) Int.MAX_VALUE.toLong() else writeTimeoutMillis

            /** 此 Future 用于在后台读取 RequestBody */
            @Volatile
            private var readTaskFuture: Future<*>? = null

            /** The number of bytes we read from the RequestBody thus far.  */
            private var totalBytesReadFromHttp: Long = 0

            @Throws(IOException::class)
            override fun getLength(): Long = requestBody.length()

            @Throws(IOException::class)
            override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
                ensureReadTaskStarted()

                try {
                    if (length == -1L) {
                        readUnknownBodyLength(uploadDataSink, byteBuffer)
                    } else {
                        readKnownBodyLength(uploadDataSink, byteBuffer)
                    }
                } catch (e: Exception) {
                    // 统一捕获 TimeoutException, ExecutionException 等
                    handleReadException(uploadDataSink, e)
                }
            }

            @Throws(IOException::class)
            private fun readKnownBodyLength(
                uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer
            ) {
                val readResult = readFromByteBuffer(byteBuffer)
                if (totalBytesReadFromHttp > length) {
                    throw prepareBodyTooLongException(length, totalBytesReadFromHttp)
                } else if (totalBytesReadFromHttp < length) {
                    when (readResult) {
                        UploadBodyDataSink.ReadResult.SUCCESS -> uploadDataSink.onReadSucceeded(
                            false
                        )

                        UploadBodyDataSink.ReadResult.END_OF_BODY -> throw IOException("The source has been exhausted but we expected more data!")
                    }
                    return
                } else {
                    /*
                    Else we're handling what's supposed to be the last chunk.
                    反之，进行最后一块 body 内容的读取。
                     */
                    handleLastBodyRead(uploadDataSink, byteBuffer)
                }
            }

            /**
             * The last body read is special for fixed length bodies - if Cronet receives exactly the
             * right amount of data it won't ask for more, even if there is more data in the stream. As a
             * result, when we read the advertised number of bytes, we need to make sure that the stream
             * is indeed finished.
             */
            @Throws(IOException::class, TimeoutException::class, ExecutionException::class)
            private fun handleLastBodyRead(
                uploadDataSink: UploadDataSink, filledByteBuffer: ByteBuffer
            ) {
                // We reuse the same buffer for the END_OF_DATA read (it should be non-destructive and if
                // it overwrites what's in there we don't mind as that's an error anyway). We just need
                // to make sure we restore the original position afterwards. We don't use mark() / reset()
                // as the mark position can be invalidated by limit manipulation.
                val bufferPosition = filledByteBuffer.position()
                filledByteBuffer.position(0)

                if (readFromByteBuffer(filledByteBuffer) != UploadBodyDataSink.ReadResult.END_OF_BODY) {
                    throw prepareBodyTooLongException(length, totalBytesReadFromHttp)
                }

                check(filledByteBuffer.position() == 0) {
                    "END_OF_BODY reads shouldn't write anything to the buffer"
                }

                // 恢复位置更改
                filledByteBuffer.position(bufferPosition)
                uploadDataSink.onReadSucceeded(false)
            }

            private fun readUnknownBodyLength(
                uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer
            ) {
                val readResult = readFromByteBuffer(byteBuffer)
                uploadDataSink.onReadSucceeded(readResult == UploadBodyDataSink.ReadResult.END_OF_BODY)
            }

            @Synchronized
            private fun ensureReadTaskStarted() {
                // We don't expect concurrent calls so a simple flag is sufficient
                // 我们不期望并发调用，简单的标志就足够了
                if (readTaskFuture == null) {
                    readTaskFuture = SHARED_EXECUTOR.submit {
                        try {
                            val bufferedSink: BufferedSink = sink.buffer()
                            requestBody.writeTo(bufferedSink)
                            bufferedSink.flush()
                            sink.handleEndOfStreamSignal()
                        } catch (_: InterruptedIOException) {
                            // 忽略因 close() 导致的中断异常，避免误报错误
                        } catch (_: InterruptedException) {
                            // 忽略线程中断
                        } catch (e: Throwable) {
                            sink.setBackgroundReadError(e)
                        }
                    }
                }
            }

            @Throws(TimeoutException::class, ExecutionException::class)
            private fun readFromByteBuffer(byteBuffer: ByteBuffer): UploadBodyDataSink.ReadResult {
                val positionBeforeRead = byteBuffer.position()
                val readResult = sink.enqueueBodyRead(byteBuffer).getUninterruptibly(writeTimeoutMillis, TimeUnit.MILLISECONDS)
                val bytesRead = byteBuffer.position() - positionBeforeRead
                totalBytesReadFromHttp += bytesRead.toLong()
                return readResult
            }

            private fun handleReadException(uploadDataSink: UploadDataSink, e: Exception) {
                // 发生错误时取消后台任务
                close()
                val exceptionToReport = if (e is ExecutionException || e is TimeoutException) {
                    IOException(e)
                } else {
                    e
                }
                uploadDataSink.onReadError(exceptionToReport)
            }

            override fun rewind(uploadDataSink: UploadDataSink) {
                uploadDataSink.onRewindError(UnsupportedOperationException("Rewind is not supported!"))
            }

            override fun close() {
                // Cronet 请求结束或取消时，必须中断后台的 writeTo 线程
                readTaskFuture?.cancel(true)
            }
        }

        /**
         * 在内存中实现的，将 okio 数据流的 [RequestBody] 转换为 Cronet 的 [UploadDataProvider]。
         *
         * 此策略不应用于大型请求 (以及长度不上限的请求)，以避免OOM问题。
         */
        private class InMemoryRequestBodyConverter(
            private val requestBody: RequestBody
        ) : UploadDataProvider() {

            private var cachedBuffer: ByteBuffer? = null

            override fun getLength(): Long = requestBody.length()

            @Throws(IOException::class)
            override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
                if (cachedBuffer == null) {
                    // 第一次读取，将数据完全读入内存

                    val contentLength = length

                    // 处理空 Body 的情况，避免分配 0 长度的 Buffer
                    if (contentLength == 0L) {
                        cachedBuffer = ByteBuffer.allocate(0)
                        uploadDataSink.onReadSucceeded(false)
                        return
                    }

                    if (contentLength != -1L) {
                        // 【已知长度，直接分配 ByteBuffer 并写入，0拷贝
                        try {
                            // 根据长度直接分配 NIO Buffer
                            val buffer = ByteBuffer.allocate(contentLength.toInt())

                            // 创建一个适配器，把 ByteBuffer 伪装成 Okio 的 Sink
                            val nioSink = object : okio.Sink {
                                override fun write(source: Buffer, byteCount: Long) {
                                    // 直接从 Okio Buffer 搬运到 NIO ByteBuffer
                                    // 必须确保从 source 中消耗掉 byteCount 个字节写入 buffer
                                    var bytesLeft = byteCount.toInt()
                                    while (bytesLeft > 0) {
                                        // 临时限制 buffer 的 limit，防止读取超过 bytesLeft
                                        // 同时也起到控制 read 读取量的作用
                                        val oldLimit = buffer.limit()
                                        val toRead = min(
                                            bytesLeft, oldLimit - buffer.position()
                                        )

                                        if (toRead == 0) {
                                            throw IOException("Buffer full, cannot write $bytesLeft more bytes")
                                        }

                                        buffer.limit(buffer.position() + toRead)

                                        val readCount = source.read(buffer)

                                        buffer.limit(oldLimit) // 恢复 limit

                                        if (readCount == -1) throw IOException("Source exhausted prematurely")
                                        bytesLeft -= readCount
                                    }
                                }

                                override fun flush() {}
                                override fun timeout() = okio.Timeout.NONE
                                override fun close() {}
                            }

                            // 写入数据
                            val bufferedSink = nioSink.buffer()
                            requestBody.writeTo(bufferedSink)
                            bufferedSink.flush()

                            // 准备读取（将 position 归零，limit 设为写入量）
                            buffer.flip()

                            // 校验实际写入长度
                            if (buffer.limit().toLong() != contentLength) {
                                throw IOException("Expected $contentLength bytes but wrote ${buffer.limit()}")
                            }

                            cachedBuffer = buffer
                        } catch (_: java.nio.BufferOverflowException) {
                            throw IOException("Request body wrote more bytes than Content-Length: $contentLength")
                        }
                    } else {
                        // 长度未知，必须使用自动扩容的 Buffer
                        val buffer = Buffer()
                        requestBody.writeTo(buffer)
                        cachedBuffer = ByteBuffer.wrap(buffer.readByteArray())
                    }
                }

                val source = cachedBuffer!!

                if (!source.hasRemaining()) {
                    // 数据已读完，理论上 Cronet 不应再调用 read，除非发生了意料之外的状态
                    uploadDataSink.onReadSucceeded(false)
                    return
                }

                // 将数据推入 Cronet 的 byteBuffer
                // 计算本次能读取的最大字节数：取 目标容器剩余空间 和 源数据剩余数据 的最小值
                val bytesToWrite = min(byteBuffer.remaining(), source.remaining())

                // 记录原始 limit，设置临时 limit 以进行批量 put 操作
                val originalLimit = source.limit()
                source.limit(source.position() + bytesToWrite)

                // 写入 cronet 的 byteBuffer 中
                byteBuffer.put(source)

                // 恢复 limit
                source.limit(originalLimit)

                uploadDataSink.onReadSucceeded(false)
            }

            override fun rewind(uploadDataSink: UploadDataSink) {
                // 重置 ByteBuffer 位置，实现 0 开销重试
                cachedBuffer?.position(0)
                uploadDataSink.onRewindSucceeded()
            }

            override fun close() {
                cachedBuffer?.clear()
                cachedBuffer = null
            }
        }
    }
}

