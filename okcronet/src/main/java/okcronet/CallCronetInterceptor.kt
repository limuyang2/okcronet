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
import okcronet.http.Response
import org.chromium.net.UrlRequest
import java.io.IOException


class CallCronetInterceptor(private val client: CronetClient) : Interceptor {

    var urlRequest: UrlRequest? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // 构建 Cronet 的 Callback
        val callback = ResponseCallback(request, client.readTimeoutMillis, client.cookieJar, client.isFollowRedirect)

        // 构建 Cronet 的 UrlRequest.Builder
        val urlRequestBuilder: UrlRequest.Builder = client.cronetEngine.newUrlRequestBuilder(
            request.url.toString(),
            callback,
            DirectExecutor.INSTANCE
        ).allowDirectExecutor()
            .setPriority(request.priority)
            .setHttpMethod(request.method)
            .addRequestAnnotation(AnnotationRequestInfo(request.method, request.priority))
            .setRequestFinishedListener(client.requestFinishedInfoListener)

        client.networkHandle?.let {
            urlRequestBuilder.bindToNetwork(it)
        }

        client.annotationList?.forEach {
            urlRequestBuilder.addRequestAnnotation(it)
        }

        if (request.disableCache) {
            urlRequestBuilder.disableCache()
        }

        request.body?.let {
            val contentLength: Long = try {
                it.length()
            } catch (e: IOException) {
                throw RuntimeException(e)
            }

            if (contentLength > 0) {
                urlRequestBuilder.setUploadDataProvider(
                    UploadDataHelper.getUploadDataProvider(
                        it, client.writeTimeoutMillis
                    ), DirectExecutor.INSTANCE
                )
            }
        }

        var headers = request.headers

        // 添加 cookie
        client.cookieJar?.let {
            // 设置 cookie
            val cookies: List<Cookie> = it.load(request.url)
            if (cookies.isNotEmpty()) {
                val stringBuilder = StringBuilder()
                for (cookie in cookies) {
                    stringBuilder.append(cookie.toString()).append(";")
                }
                if (stringBuilder.isNotEmpty()) {
                    headers = headers.newBuilder().add("Cookie", stringBuilder.toString()).build()
                }
            }
        }


        // 最后设置给 Cronet UrlRequest
        val s = headers.size
        for (i in 0 until s) {
            urlRequestBuilder.addHeader(headers.name(i), headers.value(i))
        }

        urlRequest = urlRequestBuilder.build()

        // 检查请求是否已经被取消了
        if (chain.call().isCanceled) {
            urlRequest?.cancel()
            throw IOException("This Request is canceled!")
        } else {
            urlRequest?.start()
        }

        return callback.response
    }


}