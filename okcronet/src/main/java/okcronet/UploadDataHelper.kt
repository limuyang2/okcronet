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
import java.nio.ByteBuffer
import java.util.concurrent.*

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
            private val readTaskExecutor: ExecutorService = Executors.newSingleThreadExecutor()
        ) : UploadDataProvider() {
            private val writeTimeoutMillis: Long

            /** 此 Future 用于在后台读取 RequestBody */
            @Volatile
            private var readTaskFuture: Future<*>? = null

            /** The number of bytes we read from the RequestBody thus far.  */
            private var totalBytesReadFromHttp: Long = 0

            init {
                // So that we don't have to special case infinity. Int.MAX_VALUE is ~infinity for all
                // practical use cases.
                this.writeTimeoutMillis =
                    if (writeTimeoutMillis == 0L) Int.MAX_VALUE.toLong() else writeTimeoutMillis
            }

            @Throws(IOException::class)
            override fun getLength(): Long = requestBody.length()

            @Throws(IOException::class)
            override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
                ensureReadTaskStarted()
                if (length == -1L) {
                    readUnknownBodyLength(uploadDataSink, byteBuffer)
                } else {
                    readKnownBodyLength(uploadDataSink, byteBuffer)
                }
            }

            @Throws(IOException::class)
            private fun readKnownBodyLength(
                uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer
            ) {
                try {
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
                } catch (e: TimeoutException) {
                    readTaskFuture?.cancel(true)
                    uploadDataSink.onReadError(IOException(e))
                } catch (e: ExecutionException) {
                    readTaskFuture?.cancel(true)
                    uploadDataSink.onReadError(IOException(e))
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
                try {
                    val readResult = readFromByteBuffer(byteBuffer)
                    uploadDataSink.onReadSucceeded(readResult == UploadBodyDataSink.ReadResult.END_OF_BODY)
                } catch (e: TimeoutException) {
                    readTaskFuture?.cancel(true)
                    uploadDataSink.onReadError(IOException(e))
                } catch (e: ExecutionException) {
                    readTaskFuture?.cancel(true)
                    uploadDataSink.onReadError(IOException(e))
                }
            }

            private fun ensureReadTaskStarted() {
                // We don't expect concurrent calls so a simple flag is sufficient
                // 我们不期望并发调用，简单的标志就足够了
                if (readTaskFuture == null) {
                    readTaskFuture = readTaskExecutor.submit {
                        try {
                            val bufferedSink: BufferedSink = sink.buffer()
                            requestBody.writeTo(bufferedSink)
                            bufferedSink.flush()
                            sink.handleEndOfStreamSignal()
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

            override fun rewind(uploadDataSink: UploadDataSink) {
                uploadDataSink.onRewindError(UnsupportedOperationException("Rewind is not supported!"))
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

            @Volatile
            private var isMaterialized = false
            private val materializedBody = Buffer()

            override fun getLength(): Long = requestBody.length()

            @Throws(IOException::class)
            override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
                // We're not expecting any concurrent calls here so a simple flag should be sufficient.
                // 不希望这里有任何并发调用，一个简单的标志就足够了。
                if (!isMaterialized) {
                    requestBody.writeTo(materializedBody)
                    materializedBody.flush()
                    isMaterialized = true
                    val reportedLength = length
                    val actualLength = materializedBody.size
                    if (actualLength != reportedLength) {
                        uploadDataSink.onReadError(
                            IOException("Expected $reportedLength bytes but got $actualLength")
                        )
                        return
                    }
                }
                check(materializedBody.read(byteBuffer) != -1) {
                    // This should never happen - for known body length we shouldn't be called at all
                    // if there's no more data to read.
                    "The source has been exhausted but we expected more!"
                }
                uploadDataSink.onReadSucceeded(false)
            }

            override fun rewind(uploadDataSink: UploadDataSink) {
                isMaterialized = false
                materializedBody.clear()
                uploadDataSink.onRewindSucceeded()
            }
        }
    }
}

