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


/**
 * @author 李沐阳
 * @date 2023/2/27
 * @description 用于 Cookie 处理的抽象类
 */
interface CookieJar {
    /**
     * Saves [cookies] from an HTTP response to this store according to this jar's policy.
     *
     * Note that this method may be called a second time for a single HTTP response if the response
     * includes a trailer. For this obscure HTTP feature, [cookies] contains only the trailer's
     * cookies.
     */
    fun save(url: HttpUrl, cookies: List<Cookie>)

    /**
     * Load cookies from the jar for an HTTP request to [url]. This method returns a possibly
     * empty list of cookies for the network request.
     *
     * Simple implementations will return the accepted cookies that have not yet expired and that
     * [match][Cookie.matches] [url].
     */
    fun load(url: HttpUrl): List<Cookie>

    /** Load all cookies */
    fun loadAll(): List<Cookie>

    /**
     * Clear all cookies
     */
    fun clear()

    companion object {
        /** A cookie jar that never accepts any cookies. */
        @JvmField
        val NO_COOKIES: CookieJar = NoCookies()

        private class NoCookies : CookieJar {
            override fun save(url: HttpUrl, cookies: List<Cookie>) {
            }

            override fun load(url: HttpUrl): List<Cookie> {
                return emptyList()
            }

            override fun loadAll(): List<Cookie> {
                return emptyList()
            }

            override fun clear() {
            }
        }
    }
}