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
import okio.Timeout
import java.io.IOException


/**
 * @author 李沐阳
 * @date 2023/5/11
 * @description
 */
interface Call : Cloneable {

    /** Returns the original request that initiated this call. */
    fun request(): Request

    /**
     * Invokes the request immediately, and blocks until the response can be processed or is in error.
     *
     * To avoid leaking resources callers should close the [okcronet.http.Response] which in turn will close the
     * underlying [okcronet.http.ResponseBody].
     *
     * ```
     * // ensure the response (and underlying response body) is closed
     * try (Response response = client.newCall(request).execute()) {
     *   ...
     * }
     * ```
     *
     * The caller may read the response body with the response's [okcronet.http.Response.body] method. To avoid
     * leaking resources callers must [close the response body][okcronet.http.ResponseBody] or the response.
     *
     * Note that transport-layer success (receiving a HTTP response code, headers and body) does not
     * necessarily indicate application-layer success: `response` may still indicate an unhappy HTTP
     * response code like 404 or 500.
     *
     * @throws IOException if the request could not be executed due to cancellation, a connectivity
     *     problem or timeout. Because networks can fail during an exchange, it is possible that the
     *     remote server accepted the request before the failure.
     * @throws IllegalStateException when the call has already been executed.
     */
    @Throws(IOException::class)
    fun execute(): Response

    /**
     * Schedules the request to be executed at some point in the future.
     *
     * @throws IllegalStateException when the call has already been executed.
     */
    fun enqueue(responseCallback: Callback)

    /** Cancels the request, if possible. Requests that are already complete cannot be canceled. */
    fun cancel()

    /**
     * Returns true if this call has been either [executed][execute] or [enqueued][enqueue]. It is an
     * error to execute a call more than once.
     *
     * 如果此网络请求已执行或已排队，则返回true。多次执行调用是错误的。
     */
    val isExecuted: Boolean

    /**
     * 是否已经取消请求
     */
    val isCanceled: Boolean

    /**
     * Returns a timeout that spans the entire call: resolving DNS, connecting, writing the request
     * body, server processing, and reading the response body. If the call requires redirects or
     * retries all must complete within one timeout period.
     *
     */
    fun timeout(): Timeout

    /**
     * Create a new, identical call to this one which can be enqueued or executed even if this call
     * has already been.
     */
    public override fun clone(): Call

    fun interface Factory {
        fun newCall(request: Request): Call
    }

}