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

import okcronet.http.Cookie
import okcronet.http.CookieJar
import okcronet.http.Headers
import okcronet.http.HttpUrl.Companion.toHttpUrl
import okio.*
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cronet 回调的实现类，这是涉及到异步同步转换的核心的桥梁。
 * 此类完成了核心的数据接收工作。可继承此类，用于实现想要的数据类型。
 */
open class SourceCallback(readTimeoutMillis: Long, private val cookieJar: CookieJar?) :
    UrlRequest.Callback() {

    constructor(readTimeoutMillis: Long) : this(readTimeoutMillis,null)

    /** BodySource 的 Future  */
    private val mSourceFuture = CompletableFutureCompat<Source>()

    private val mUrlResponseInfo = CompletableFutureCompat<UrlResponseInfo>()

    /** 指定的读取超时  */
    private val readTimeoutMillis: Long

    /** Cronet 的异步回调生成的阻塞数据流读 Source*/
    @Volatile
    private var cronetBodySource: CronetBodySource? = null

    init {
        require(readTimeoutMillis >= 0)

        if (readTimeoutMillis == 0L) {
            this.readTimeoutMillis = Long.MAX_VALUE
        } else {
            this.readTimeoutMillis = readTimeoutMillis
        }
    }

    /**
     * 返回与此 cronet 请求回调关联的 Source 。
     *
     * 请注意，在流式传输响应正文时，从此获取 Source 数据可能会阻塞。
     */
    val sourceFuture: Future<Source>
        get() = mSourceFuture

    /**
     * 同步等待获取 Source 数据。阻塞调用。
     */
    val source: Source
        @Throws(IOException::class)
        get() = mSourceFuture.getValue()

    /**
     * 返回与此 cronet 请求回调关联的 [UrlResponseInfo]
     */
    val urlResponseInfoFuture: Future<UrlResponseInfo>
        get() = mUrlResponseInfo

    val urlResponseInfo: UrlResponseInfo
        @Throws(IOException::class)
        get() = mUrlResponseInfo.getValue()

    /**
     * 网络返回的 Http Code。阻塞调用。
     */
    val code: Int get() = mUrlResponseInfo.getValue().httpStatusCode

    /**
     * 网络是否请求成功。阻塞调用。
     */
    fun isSuccess() = code in 200..299


    override fun onRedirectReceived(
        urlRequest: UrlRequest, urlResponseInfo: UrlResponseInfo, nextUrl: String
    ) {
        urlRequest.followRedirect()
    }

    override fun onResponseStarted(urlRequest: UrlRequest, urlResponseInfo: UrlResponseInfo) {
        cronetBodySource = CronetBodySource(urlRequest, readTimeoutMillis).apply {
            check(mSourceFuture.complete(this))
            check( mUrlResponseInfo.complete(urlResponseInfo))
        }
    }

    override fun onReadCompleted(
        urlRequest: UrlRequest, urlResponseInfo: UrlResponseInfo, byteBuffer: ByteBuffer
    ) {
        cronetBodySource?.add(
            CronetBodySource.CronetResult.ReadCompleted(byteBuffer)
        )
    }

    override fun onSucceeded(urlRequest: UrlRequest, urlResponseInfo: UrlResponseInfo) {
        // 保存cookie
        if (!urlResponseInfo.wasCached() && cookieJar != null) {
            val cronetHeaders = urlResponseInfo.allHeadersAsList
            if (cronetHeaders.isNotEmpty()) {
                // 保存 Cookie
                val headerBuilder: Headers.Builder = Headers.Builder()

                cronetHeaders.forEach {
                    headerBuilder.add(it.key.trim(), it.value.trim())
                }

                val url = urlResponseInfo.url.toHttpUrl()
                cookieJar.save(url, Cookie.parseAll(url, headerBuilder.build()))
            }
        }

        cronetBodySource?.add(CronetBodySource.CronetResult.Success)
    }

    override fun onFailed(
        urlRequest: UrlRequest, urlResponseInfo: UrlResponseInfo?, e: CronetException
    ) {
        mUrlResponseInfo.completeExceptionally(e)
        if (mSourceFuture.completeExceptionally(e)) {
            return
        }

        // If this was called as a reaction to a read() call, the read result will propagate
        // the exception.
        cronetBodySource?.add(
            CronetBodySource.CronetResult.Failed(e)
        )
    }

    override fun onCanceled(urlRequest: UrlRequest, responseInfo: UrlResponseInfo?) {
        cronetBodySource?.canceled()
        cronetBodySource?.add(
            CronetBodySource.CronetResult.Canceled
        )

        // If there's nobody listening it's possible that the cancellation happened before we even
        // received anything from the server. In that case inform the thread that's awaiting server
        // response about the cancellation as well. This becomes a no-op if the futures
        // were already set.
        val e = IOException("The request was canceled!")
        mUrlResponseInfo.completeExceptionally(e)
        mSourceFuture.completeExceptionally(e)
    }

    private class CronetBodySource(
        private val request: UrlRequest,
        private val readTimeoutMillis: Long
    ) : Source {

        /**
         * 一种内部的、阻塞的、线程安全的方法，用于在回调方法和 bodySourceFuture 之间传递数据。
         *  容量为 2: - 最多一个用于读取结果，最多 1 个插槽用于取消信号，这保证了所有插入都是非阻塞的。
         */
        private val cronetResults: BlockingQueue<CronetResult> = ArrayBlockingQueue(2)

        /** 请求是否已完成且响应是否已完全读取。  */
        private val finished = AtomicBoolean(false)

        /** 请求是否已经取消  */
        private val canceled = AtomicBoolean(false)

        private var buffer: ByteBuffer? = ByteBuffer.allocateDirect(CRONET_BYTE_BUFFER_CAPACITY)

        /** 是否已调用 close() 方法。 */
        @Volatile
        private var closed = false

        @Throws(IOException::class)
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (canceled.get()) {
                throw IOException("The request was canceled!")
            }

            // Using IAE instead of NPE (checkNotNull) for okio.RealBufferedSource consistency
            require(byteCount >= 0) {
                "byteCount < 0: $byteCount"
            }
            check(!closed) {
                "CronetBodySource closed"
            }

            if (finished.get()) {
                return -1
            }
            if (byteCount < (buffer?.limit() ?: 0)) {
                buffer?.limit(byteCount.toInt())
            }
            request.read(buffer)
            val result: CronetResult? = try {
                cronetResults.poll(readTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
            if (result == null) {
                // read cronetResults.poll() 被打断或超时
                request.cancel()
                throw CronetTimeoutException()
            }
            return when (result) {
                is CronetResult.Canceled -> {
                    // 已取消的标志已由外部 onCanceled 方法调用 canceled() 设置，因此此处不设置它。
                    buffer = null
                    throw IOException("The request was canceled!")
                }
                is CronetResult.Failed -> {
                    finished.set(true)
                    buffer = null
                    throw IOException(result.exception)
                }

                is CronetResult.Success -> {
                    finished.set(true)
                    buffer = null
                    -1
                }
                is CronetResult.ReadCompleted -> {
                    result.buffer.flip()
                    val bytesWritten = sink.write(result.buffer)
                    result.buffer.clear()
                    bytesWritten.toLong()
                }
            }
        }

        override fun timeout(): Timeout {
            // TODO(danstahr): This should likely respect the OkHttp timeout somehow
            return Timeout.NONE
        }

        override fun close() {
            if (closed) {
                return
            }
            closed = true
            if (!finished.get()) {
                request.cancel()
            }
        }

        fun add(callbackResult: CronetResult) {
            cronetResults.add(callbackResult)
        }

        fun canceled() {
            canceled.set(true)
        }

        sealed class CronetResult {
            class ReadCompleted(val buffer: ByteBuffer) : CronetResult()

            data object Success : CronetResult()

            class Failed(val exception: CronetException?) : CronetResult()

            data object Canceled : CronetResult()
        }
    }


    companion object {
        /**
         * The byte buffer capacity for reading Cronet response bodies. Each response callback will
         * allocate its own buffer of this size once the response starts being processed.
         *
         * 用于读取 Cronet 响应主体的字节缓冲区容量。一旦开始处理响应，每个响应回调将分配自己的此大小的缓冲区。
         */
        private const val CRONET_BYTE_BUFFER_CAPACITY = 32 * 1024


        /**
         * 扩展方法
         * 获取结果，并写入到 BufferedSink
         */
        fun SourceCallback.writeTo(bufferedSink: BufferedSink) =
            bufferedSink.use { sink ->
                source.use { s ->
                    sink.writeAll(s).apply {
                        sink.flush()
                    }
                }
            }

        /**
         * 扩展方法
         * 获取结果，并写入到 OutputStream
         */
        fun SourceCallback.writeTo(outputStream: OutputStream) = writeTo(outputStream.sink().buffer())

        /**
         * 扩展方法
         * 获取结果，并写入到 File
         */
        fun SourceCallback.writeTo(file: File) = writeTo(file.sink().buffer())


        /**
         * 扩展方法
         * 获取 String 类型的结果
         */
        fun SourceCallback.string(): String {
            return source.buffer().readString(
                getCharsetFromHeaders(urlResponseInfo)
            )
        }

        private fun getCharsetFromHeaders(info: UrlResponseInfo) : Charset {
            // Header 中获取 Content-Type，查找编码类型
            info.allHeaders["Content-Type"]?.forEach {
                it.split(";").forEach {type ->
                    if (type.trim().startsWith("charset", true)) {
                        type.split("=").lastOrNull()?.let {c ->
                            return Charset.forName(c.trim())
                        }
                    }
                }
            }

            return Charset.defaultCharset()
        }
    }
}