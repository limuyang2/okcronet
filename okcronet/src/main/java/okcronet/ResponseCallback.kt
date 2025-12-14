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

import okcronet.http.CookieJar
import okcronet.http.MediaType.Companion.toMediaTypeOrNull
import okcronet.http.Request
import okcronet.http.Response
import okcronet.http.ResponseBody
import okcronet.http.ResponseBody.Companion.asResponseBody
import okio.Source
import okio.buffer
import org.chromium.net.UrlResponseInfo
import java.io.IOException
import java.net.ProtocolException

/**
 * Cronet 回调的一个实现，获取[Response]类型。
 *
 * An implementation of the Cronet callback. Get [Response] type.
 */
class ResponseCallback(private val request: Request, readTimeoutMillis: Long, cookieJar: CookieJar? = null, isFollowRedirect: Boolean) :
    SourceCallback(readTimeoutMillis, cookieJar, isFollowRedirect) {

    /**
     * 注意：这里可能会发生阻塞，不建议在主线线程使用
     *
     * Note: Blocking may occur here. It is not recommended to use it on the main thread.
     */
    val response: Response
        @Throws(IOException::class)
        get() {
            val (source, urlResponseInfo) = responseFuture.getValue()
            return createResponse(request, urlResponseInfo, source)
        }


    /**
     * 通过Cronet回调创建 [Response]
     *
     * Create [Response] via Cronet callback.
     */
    @Throws(IOException::class)
    private fun createResponse(
        request: Request, cronetResponseInfo: UrlResponseInfo, bodySource: Source
    ): Response {
        val responseBuilder = Response.Builder()
            .urlResponseInfo(cronetResponseInfo)
            .requestParameter(request)

        val responseBody = createResponseBody(
            cronetResponseInfo,
            bodySource
        )
        return responseBuilder.body(responseBody).build()
    }

    /**
     * 通过Cronet回调创建 [ResponseBody]
     *
     * Create [ResponseBody] via Cronet callback.
     */
    @Throws(IOException::class)
    private fun createResponseBody(
        cronetResponseInfo: UrlResponseInfo,
        bodySource: Source
    ): ResponseBody {

        val contentType = getLastHeaderValue("Content-Type", cronetResponseInfo)

        val contentLengthString =
            getLastHeaderValue("Content-Length", cronetResponseInfo)

        val contentLength: Long = try {
            contentLengthString?.toLong() ?: -1
        } catch (_: NumberFormatException) {
            -1
        }

        val httpStatusCode = cronetResponseInfo.httpStatusCode
        if ((httpStatusCode == 204 || httpStatusCode == 205) && contentLength > 0) {
            throw ProtocolException(
                "HTTP $httpStatusCode had non-zero Content-Length: $contentLengthString"
            )
        }
        return bodySource.buffer()
            .asResponseBody(contentType?.toMediaTypeOrNull(), contentLength)
    }

    /**
     * 返回给定名称的最后一个值，如果没有值，则返回null。
     *
     * Returns the last header value for the given name, or null if the header isn't present.
     */
    private fun getLastHeaderValue(name: String, responseInfo: UrlResponseInfo): String? {
        return responseInfo.allHeaders[name]?.lastOrNull()
    }
}