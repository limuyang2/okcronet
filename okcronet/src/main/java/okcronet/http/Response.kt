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