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
import okcronet.http.Request
import org.chromium.net.CronetEngine
import org.chromium.net.RequestFinishedInfo
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * @author 李沐阳
 * @date 2023/2/22
 * @description
 */
class CronetClient private constructor(
    val cronetEngine: CronetEngine,
    val responseCallbackExecutor: Executor,
    val readTimeoutMillis: Long,
    val writeTimeoutMillis: Long,
    val callTimeoutMillis: Long,
    val cookieJar: CookieJar?,
    val interceptors: List<Interceptor>,
    /**
     * 设置请求完成信息的监听
     */
    val requestFinishedInfoListener: RequestFinishedInfo.Listener?
) : Call.Factory {

    override fun newCall(request: Request): Call = RealCall(this, request)

    class Builder(private val cronetEngine: CronetEngine) {
        private var readTimeoutMillis: Long = 10_000L
        private var writeTimeoutMillis: Long = 25_000L

        private var callTimeoutMillis: Long = 0 // No timeout
        private var callbackExecutorService: ExecutorService? = null
        private var cookieJar: CookieJar? = null

        private val interceptors: MutableList<Interceptor> = ArrayList()

        private var requestFinishedInfoListener: RequestFinishedInfo.Listener? = null


        /**
         * Set read timeout millis
         * 读取超时
         *
         * @param readTimeoutMillis 毫秒
         * @return
         */
        fun setReadTimeoutMillis(readTimeoutMillis: Long): Builder {
            check(readTimeoutMillis >= 0) {
                "Read timeout mustn't be negative!"
            }
            this.readTimeoutMillis = readTimeoutMillis
            return this
        }

        /**
         * Set write timeout millis
         * 写入超时
         *
         * @param writeTimeoutMillis 毫秒
         * @return
         */
        fun setWriteTimeoutMillis(writeTimeoutMillis: Long): Builder {
            check(writeTimeoutMillis >= 0) {
                "Write timeout mustn't be negative!"
            }
            this.writeTimeoutMillis = writeTimeoutMillis
            return this
        }

        fun setCallbackExecutorService(callbackExecutorService: ExecutorService): Builder {
            this.callbackExecutorService = callbackExecutorService
            return this
        }

        /**
         * Set call timeout millis
         *
         * @param callTimeoutMillis milliseconds 毫秒
         * @return
         */
        fun setCallTimeoutMillis(callTimeoutMillis: Long): Builder {
            check(callTimeoutMillis >= 0) {
                "Call timeout mustn't be negative!"
            }
            this.callTimeoutMillis = callTimeoutMillis
            return this
        }


        fun setCookieJar(cookieJar: CookieJar): Builder {
            this.cookieJar = cookieJar
            return this
        }

        /**
         * Add interceptor
         * 添加拦截器
         *
         * @param interceptor
         * @return
         */
        fun addInterceptor(interceptor: Interceptor): Builder {
            interceptors += interceptor
            return this
        }

        fun setRequestFinishedInfoListener(listener: RequestFinishedInfo.Listener?): Builder {
            requestFinishedInfoListener = listener
            return this
        }


        fun build(): CronetClient {
            return CronetClient(
                cronetEngine,
                callbackExecutorService ?: fixedThreadPool,
                readTimeoutMillis,
                writeTimeoutMillis,
                callTimeoutMillis,
                cookieJar,
                interceptors,
                requestFinishedInfoListener
            )
        }

        companion object {
            private val fixedThreadPool = Executors.newFixedThreadPool(4)
        }
    }

}