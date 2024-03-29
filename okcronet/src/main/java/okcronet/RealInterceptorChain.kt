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

/**
 * @author 李沐阳
 * @date 2024/1/23
 * @description
 */
internal class RealInterceptorChain(
    private val call: RealCall,
    private val interceptors: List<Interceptor>,
    private val index: Int,
    private val request: Request
): Interceptor.Chain {

    override fun request(): Request = request

    override fun proceed(request: Request): Response {
        check(index < interceptors.size)

        // Call the next interceptor in the chain.
        val next = copy(index = index + 1, request = request)
        val interceptor = interceptors[index]

        @Suppress("USELESS_ELVIS")
        val response = interceptor.intercept(next) ?: throw NullPointerException(
            "interceptor $interceptor returned null")

        check(response.body != null) { "interceptor $interceptor returned a response with no body" }

        return response
    }

    override fun call(): Call = call


    private fun copy(
        index: Int = this.index,
        request: Request = this.request,
    ) = RealInterceptorChain(call, interceptors, index, request)
}