package io.github.limuyang2.okcronet

import android.util.Log
import okcronet.Interceptor
import okcronet.http.FileRequestBody
import okcronet.http.MultipartBody
import okcronet.http.Response
import okio.Buffer

/**
 * @author 李沐阳
 * @date 2024/3/27
 * @description 用于打印日志的拦截器
 */
class LogInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        val n = response.peekBody(Long.MAX_VALUE)
        val bodyString = n.string()
        val requestParameter = response.request
        val responseInfo = response.urlResponseInfo

        val string = buildString {
            append("🚀 Request [${requestParameter.method}] -->\n")
            append("- url: ${requestParameter.url}\n")
            append("- header: \n")
            requestParameter.headers.forEach {
                append("    ")
                append(it.first)
                append(": ")
                append(it.second)
                append("\n")
            }

            requestParameter.body?.let {
                if (it is MultipartBody || it is FileRequestBody) {
                    return@let
                }
                val buffer = Buffer()
                it.writeTo(buffer)

                append("- body: ${buffer.readByteString().utf8()}\n")
            }

            append("\n")

            append("🍒 Response -->\n")
            append("- url: ${responseInfo.url}\n")
            append("- is cache: ${responseInfo.wasCached()}\n")
            append("- protocol: ${responseInfo.negotiatedProtocol}\n")
            append("- http code: ${responseInfo.httpStatusCode}\n")
            append("- http msg: ${responseInfo.httpStatusText}\n")
            append("- header: \n")
            responseInfo.allHeadersAsList.forEach {
                append("    ")
                append(it.key).append(": ").append(it.value)
                append("\n")
            }
            append("- body: $bodyString \n")
        }

        Log.d("log", string)

        return response
    }
}