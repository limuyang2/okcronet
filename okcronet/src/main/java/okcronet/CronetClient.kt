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

import android.os.Build
import androidx.annotation.RequiresApi
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
    val isFollowRedirect: Boolean,
    val interceptors: List<Interceptor>,
    /**
     * 请求结束信息的监听.
     * Listener on the request finished message.
     */
    val requestFinishedInfoListener: RequestFinishedInfo.Listener?,
    val networkHandle: Long?,
    val trafficStatsTag: Int?,
    val annotationList: List<Any>?,
    val dispatcher: Dispatcher = Dispatcher()
) : Call.Factory {

    init {
        this
    }

    override fun newCall(request: Request): Call = RealCall(this, request)

    /**
     * Cancel all calls
     */
    fun cancelAll() {
        dispatcher.cancelAll()
    }

    class Builder(private val cronetEngine: CronetEngine) {
        private var readTimeoutMillis: Long = 5_000L
        private var writeTimeoutMillis: Long = 5_000L

        private var callTimeoutMillis: Long = 10_000L
        private var callbackExecutorService: ExecutorService? = null
        private var cookieJar: CookieJar? = null
        private var isFollowRedirect: Boolean = true

        private val interceptors: MutableList<Interceptor> = ArrayList()

        private var requestFinishedInfoListener: RequestFinishedInfo.Listener? = null
        private var networkHandle: Long? = null
        private var trafficStatsTag: Int? = null
        private var annotationList: MutableList<Any>? = null

        /**
         * Set read timeout millis
         * 读取超时
         *
         * @param readTimeoutMillis 毫秒
         * @return
         */
        fun setReadTimeoutMillis(readTimeoutMillis: Long) = apply {
            check(readTimeoutMillis >= 0) {
                "Read timeout mustn't be negative!"
            }
            this.readTimeoutMillis = readTimeoutMillis
        }

        /**
         * Set write timeout millis
         * 写入超时
         *
         * @param writeTimeoutMillis 毫秒
         * @return
         */
        fun setWriteTimeoutMillis(writeTimeoutMillis: Long) = apply {
            check(writeTimeoutMillis >= 0) {
                "Write timeout mustn't be negative!"
            }
            this.writeTimeoutMillis = writeTimeoutMillis
        }

        /**
         * Set follow redirect.
         * 设置跟随重定向。(默认true)
         * @param bool
         * @return
         */
        fun setFollowRedirect(bool: Boolean) = apply {
            this.isFollowRedirect = bool
        }

        /**
         * Set callback executor service
         *
         * @param callbackExecutorService
         * @return
         */
        fun setCallbackExecutorService(callbackExecutorService: ExecutorService) = apply {
            this.callbackExecutorService = callbackExecutorService
        }

        /**
         * Set call timeout millis
         *
         * @param callTimeoutMillis milliseconds 毫秒
         * @return
         */
        fun setCallTimeoutMillis(callTimeoutMillis: Long) = apply {
            check(callTimeoutMillis >= 0) {
                "Call timeout mustn't be negative!"
            }
            this.callTimeoutMillis = callTimeoutMillis
        }

        /**
         * Set cookie
         *
         * @param cookieJar
         * @return
         */
        fun setCookieJar(cookieJar: CookieJar) = apply {
            this.cookieJar = cookieJar
        }

        /**
         * Add interceptor.
         *
         * 添加拦截器.
         *
         * @param interceptor
         * @return
         */
        fun addInterceptor(interceptor: Interceptor) = apply {
            interceptors += interceptor
        }

        /**
         * Sets a listener that gets invoked after [org.chromium.net.UrlRequest.Callback.onCanceled],
         * [org.chromium.net.UrlRequest.Callback.onFailed] or
         * [org.chromium.net.UrlRequest.Callback.onSucceeded] return.
         *
         * <p>The listener is invoked  with the request finished info on an
         * [java.util.concurrent.Executor] provided by [RequestFinishedInfo.Listener.getExecutor].
         *
         * 设置在 [org.chromium.net.UrlRequest.Callback.onCanceled] 或
         * [org.chromium.net.UrlRequest.Callback.onFailed] 或
         * [org.chromium.net.UrlRequest.Callback.onSucceeded] 返回之后调用的监听器。
         *
         * <p> 使用请求完成的信息调用监听器
         * [RequestFinishedInfo.Listener.getExecutor] 提供的 [java.util.concurrent.Executor]。
         *
         * @param listener the listener for finished requests.
         * @return the builder to facilitate chaining.
         */
        fun setRequestFinishedInfoListener(listener: RequestFinishedInfo.Listener?) = apply {
            requestFinishedInfoListener = listener
        }

        /**
         * Binds the request to the specified network handle. Cronet will send this request only
         * using the network associated to this handle. If this network disconnects the request will
         * fail, the exact error will depend on the stage of request processing when the network
         * disconnects.
         *
         * Only available starting from Android Marshmallow.
         *
         * 将请求绑定到指定的网络句柄。Cronet将仅使用与此句柄关联的网络发送此请求。如果此网络断开连接，请求将
         * 失败，确切的错误将取决于网络断开时请求处理的阶段。[android.net.Network.getNetworkHandle]
         *
         * 仅从 Android M开始可用。
         *
         * @param networkHandle the network handle to bind the request to. Specify
         * [CronetEngine.UNBIND_NETWORK_HANDLE] to unbind.
         * @return the builder to facilitate chaining.
         */
        @RequiresApi(Build.VERSION_CODES.M)
        fun bindToNetwork(networkHandle: Long) = apply {
            this.networkHandle = networkHandle
        }

        /**
         * Sets {@link android.net.TrafficStats} tag to use when accounting socket traffic caused by
         * this request. See {@link android.net.TrafficStats} for more information. If no tag is set
         * (e.g. this method isn't called), then Android accounts for the socket traffic caused by
         * this request as if the tag value were set to 0. <p> <b>NOTE:</b>Setting a tag disallows
         * sharing of sockets with requests with other tags, which may adversely effect performance
         * by prohibiting connection sharing. In other words use of multiplexed sockets (e.g. HTTP/2
         * and QUIC) will only be allowed if all requests have the same socket tag.
         *
         * @param tag the tag value used to when accounting for socket traffic caused by this
         *         request.
         * Tags between 0xFFFFFF00 and 0xFFFFFFFF are reserved and used internally by system
         * services like {@link android.app.DownloadManager} when performing traffic on behalf of an
         * application.
         * @return the builder to facilitate chaining.
         */
        fun setTrafficStatsTag(tag: Int) = apply {
            trafficStatsTag = tag
        }

        /**
         * Associates the annotation object with this request. May add more than one. Passed through
         * to a [RequestFinishedInfo.Listener], see [RequestFinishedInfo.getAnnotations].
         *
         * 将annotation对象与此请求关联。可以添加多个。传递到 [RequestFinishedInfo.Listener]，
         * 请参阅 [RequestFinishedInfo.getAnnotations]。
         *
         * @param annotation an object to pass on to the {@link RequestFinishedInfo.Listener} with a
         * {@link RequestFinishedInfo}.
         * @return the builder to facilitate chaining.
         */
        fun addRequestAnnotation(annotation: Any) = apply {
            (annotationList ?: ArrayList<Any>().apply {
                annotationList = this
            }).add(annotation)
        }


        fun build(): CronetClient {
            return CronetClient(
                cronetEngine,
                callbackExecutorService ?: fixedThreadPool,
                readTimeoutMillis,
                writeTimeoutMillis,
                callTimeoutMillis,
                cookieJar,
                isFollowRedirect,
                interceptors,
                requestFinishedInfoListener,
                networkHandle,
                trafficStatsTag,
                annotationList
            )
        }

        companion object {
            private val fixedThreadPool = Executors.newFixedThreadPool(4)
        }
    }

}