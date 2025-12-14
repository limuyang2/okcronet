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

import okcronet.http.Request
import okcronet.http.Response
import okio.AsyncTimeout
import okio.Timeout
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @author 李沐阳
 * @date 2023/5/11
 * @description 使用 Cronet 发起请求
 */
internal class RealCall(
    private val client: CronetClient,
    /** 应用程序的原始请求，不受重定向或身份验证标头的影响.
     * The application's original request, unaffected by the redirect or authentication headers. */
    private val request: Request
) : Call {

    private var callCronetInterceptor: CallCronetInterceptor? = null

    private val executed = AtomicBoolean(false)

    private val canceled = AtomicBoolean(false)

    private val timeout: AsyncTimeout = object : AsyncTimeout() {
        override fun timedOut() {
            this@RealCall.cancel()
        }
    }

    init {
        timeout.timeout(client.callTimeoutMillis, TimeUnit.MILLISECONDS)
    }

    override fun request(): Request = request

    override fun execute(): Response {
        evaluateExecutionPreconditions()
        try {
            timeout.enter()
            client.dispatcher.add(this)
            return getResponseWithInterceptorChain()
        } catch (e: RuntimeException) {
            throw e
        } catch (e: IOException) {
            throw e
        } finally {
            timeout.exit()
            client.dispatcher.finished(this)
        }
    }

    override fun enqueue(responseCallback: Callback) {
        evaluateExecutionPreconditions()

        client.dispatcher.add(this)

        try {
            client.responseCallbackExecutor.execute {
                timeout.enter()
                try {
                    val response = getResponseWithInterceptorChain()
                    timeout.exit()
                    client.dispatcher.finished(this)
                    responseCallback.onResponse(this, response)
                } catch (e: ExecutionException) {
                    timeout.exit()
                    client.dispatcher.finished(this)
                    responseCallback.onFailure(this, IOException(e.cause ?: e))
                    return@execute
                } catch (e: Throwable) {
                    timeout.exit()
                    client.dispatcher.finished(this)
                    responseCallback.onFailure(this, e as? IOException ?: IOException(e))
                    return@execute
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // 提交线程池失败
            client.dispatcher.finished(this)
            responseCallback.onFailure(this, IOException("Executor rejected task", e))
        }
    }

    override fun cancel() {
        if (!canceled.getAndSet(true)) {
            callCronetInterceptor?.urlRequest?.cancel()
            timeout.exit()
        }
    }

    override val isExecuted: Boolean
        get() = executed.get()

    override val isCanceled: Boolean
        get() = canceled.get()

    override fun timeout(): Timeout = timeout

    override fun clone(): Call = RealCall(client, request)


    /**
     * 链式处理拦截器，从中获取 Response
     */
    private fun getResponseWithInterceptorChain(): Response {
        val interceptors = ArrayList<Interceptor>(client.interceptors.size + 1)
        interceptors += client.interceptors
        callCronetInterceptor = CallCronetInterceptor(client).apply {
            interceptors += this
        }

        val chain = RealInterceptorChain(
            call = this,
            interceptors = interceptors,
            index = 0,
            request = request,
        )

        return chain.proceed(request)
    }

    /**
     * 验证调用是否可以执行，并将调用的状态设置为“正在执行”。
     *
     * @throws IllegalStateException 如果请求已经被执行。
     * @throws IOException 如果请求被取消
     */
    @Throws(IOException::class)
    private fun evaluateExecutionPreconditions() {
        if (canceled.get()) {
            throw IOException("Can't execute canceled requests")
        }
        check(!executed.getAndSet(true)) {
            "Already Executed"
        }
        callCronetInterceptor?.urlRequest?.let {
            check(!it.isDone) {
                "The request was successfully started and is now finished (completed, canceled, or failed)"
            }
        }
    }



}