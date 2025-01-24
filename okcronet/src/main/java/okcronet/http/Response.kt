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
package okcronet.http

import okcronet.http.ResponseBody.Companion.asResponseBody
import okio.Buffer
import org.chromium.net.UrlResponseInfo
import java.io.IOException

/**
 * @author 李沐阳
 * @date 2023/2/27
 * @description
 */
class Response internal constructor(
    val request: Request,
    val urlResponseInfo: UrlResponseInfo,
    val body: ResponseBody?,
) {
    /**
     * Returns the URL the response is for. This is the URL after following redirects, so it may not
     * be the originally requested URL.
     *
     * 返回响应体所对应的 URL。这可能是跟踪重定向后的 URL，因此它可能不是最初请求的 URL。
     */
    val url: String
        get() = urlResponseInfo.url

    /**
     * Returns the URL chain. The first entry is the originally requested URL; the following entries
     * are redirects followed.
     *
     * 返回 URL 链。第一个条目是最初请求的 URL，之后的条目是遵循的重定向链接。
     */
    val urlChain: List<String>
        get() = urlResponseInfo.urlChain

    /** Returns true if [.code] is in the range [200..300).  */
    val isSuccessful: Boolean
        get() = urlResponseInfo.httpStatusCode in 200..299

    /** HTTP status code.  */
    val code: Int
    get() = urlResponseInfo.httpStatusCode

    /** HTTP status message.  */
    val message: String
        get() = urlResponseInfo.httpStatusText

    /** Returns the raw response headers.  */
    val headers: Headers
        get() {
            val headers = Headers.Builder()
            this.urlResponseInfo.allHeaders.forEach { map ->
                map.value.forEach {
                    headers.add(map.key, it)
                }
            }
            return headers.build()
        }

    /**
     * Returns {@code true} if the response came from the cache, including requests that were
     * revalidated over the network before being retrieved from the cache.
     *
     * 如果响应来自缓存，包括在从缓存中检索之前通过网络重新验证的请求，则返回 {@code true}，否则为 {@code false}。
     */
    val wasCached: Boolean
        get() = urlResponseInfo.wasCached()


    /**
     * Returns the protocol (for example 'quic/1+spdy/3') negotiated with the server. Returns an
     * empty string if no protocol was negotiated, the protocol is not known, or when using plain
     * HTTP or HTTPS.
     *
     * 返回与服务器协商的协议（例如 'quic/1+spdy3'）。如果未协商协议、协议未知，或者使用纯 HTTP 或 HTTPS 时，则返回空字符串。
     */
    val negotiatedProtocol: String?
        get() = urlResponseInfo.negotiatedProtocol

    /**
     * Returns the proxy server that was used for the request.
     *
     * 返回用于请求的代理服务器。
     */
    val proxyServer: String?
        get() = urlResponseInfo.proxyServer

    fun newBuilder(): Builder = Builder(this)

    /**
     * 从响应正文中查看字节数，并将其作为新的响应正文返回。
     * 如果响应正文中的字节数少于 [byteCount] 字节数，则返回完整的响应正文。
     * 如果响应正文中的字节数超过 [byteCount] 字节，则返回的值将被截断为 [byteCount] 字节。
     * 在消耗正文后调用此方法是错误的。
     *
     * 警告：此方法将请求的字节加载到内存中。应该对 [byteCount] 设置一个适度的限制，例如 1 MiB。
     */
    @Throws(IOException::class)
    fun peekBody(byteCount: Long): ResponseBody {
        val peeked = body!!.source().peek()
        val buffer = Buffer()
        peeked.request(byteCount)
        buffer.write(peeked, minOf(byteCount, peeked.buffer.size))
        return buffer.asResponseBody(body.contentType(), buffer.size)
    }

    class Builder {
        private var request: Request? = null
        private var urlResponseInfo: UrlResponseInfo? = null
        private var body: ResponseBody? = null

        internal constructor(response: Response) {
            this.request = response.request
            this.urlResponseInfo = response.urlResponseInfo
            this.body = response.body
        }

        constructor()

        fun body(body: ResponseBody?): Builder {
            this.body = body
            return this
        }

        fun requestParameter(request: Request): Builder {
            this.request = request
            return this
        }

        fun urlResponseInfo(urlResponseInfo: UrlResponseInfo): Builder {
            this.urlResponseInfo = urlResponseInfo
            return this
        }

        fun build(): Response {
            return Response(
                checkNotNull(request) { "request == null" },
                checkNotNull(urlResponseInfo) { "urlResponseInfo == null" },
                body
            )
        }
    }
}