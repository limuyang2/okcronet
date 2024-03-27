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

import okcronet.http.HttpUrl.Companion.toHttpUrl
import okcronet.http.RequestBody.Companion.EMPTY_REQUEST_BODY
import org.chromium.net.UrlRequest
import java.io.IOException
import java.net.URL

/**
 * @author 李沐阳
 * @date 2023/2/23
 * @description 发起请求的参数
 */
class Request private constructor(
    val url: HttpUrl,
    val method: String,
    val headers: Headers,
    val body: RequestBody?,
    val priority: Int,
    val disableCache: Boolean,
    internal val tags: Map<Class<*>, Any>
) {

    val isHttps: Boolean
        get() = url.isHttps

    fun header(name: String): String? = headers[name]

    fun headers(name: String): List<String> = headers.values(name)


    fun tag(): Any? = tag(Any::class.java)

    /**
     * Returns the tag attached with [type] as a key, or null if no tag is attached with that
     * key.
     */
    fun <T> tag(type: Class<out T>): T? = type.cast(tags[type])

    fun newBuilder(): Builder = Builder(this)

    class Builder {
        private var url: HttpUrl? = null
        private var method: String
        private var headers: Headers.Builder
        private var body: RequestBody? = null
        private var priority: Int = UrlRequest.Builder.REQUEST_PRIORITY_MEDIUM
        private  var disableCache: Boolean = false

        /** A mutable map of tags, or an immutable empty map if we don't have any. */
        private var tags: MutableMap<Class<*>, Any> = mutableMapOf()

        constructor() {
            this.method = "GET"
            this.headers = Headers.Builder()
        }

        internal constructor(request: Request) {
            this.url = request.url
            this.method = request.method
            this.body = request.body
            this.tags = if (request.tags.isEmpty()) {
                mutableMapOf()
            } else {
                request.tags.toMutableMap()
            }
            this.headers = request.headers.newBuilder()
        }

        /**
         * 设置请求的优先级
         *
         * @param int 例如：[UrlRequest.Builder.REQUEST_PRIORITY_MEDIUM]
         */
        fun priority(int: Int): Builder {
            this.priority = int
            return this
        }

        /**
         * 关闭 Cache
         */
        fun disableCache(): Builder {
            disableCache = true
            return this
        }

        fun url(url: HttpUrl): Builder = apply {
            this.url = url
        }

        /**
         * Sets the URL target of this request.
         * 设置此请求的 URL 目标。
         *
         * @throws IllegalArgumentException if [url] is not a valid HTTP or HTTPS URL. Avoid this
         *     exception by calling [HttpUrl.parse]; it returns null for invalid URLs.
         */
        fun url(url: String): Builder {
            // Silently replace web socket URLs with HTTP URLs.
            val finalUrl: String = when {
                url.startsWith("ws:", ignoreCase = true) -> {
                    "http:${url.substring(3)}"
                }
                url.startsWith("wss:", ignoreCase = true) -> {
                    "https:${url.substring(4)}"
                }
                else -> url
            }

            return url(finalUrl.toHttpUrl())
        }

        /**
         * Sets the URL target of this request.
         *
         * @throws IllegalArgumentException if the scheme of [url] is not `http` or `https`.
         */
        fun url(url: URL) = url(url.toString().toHttpUrl())

        /**
         * Sets the header named [name] to [value]. If this request already has any headers
         * with that name, they are all replaced.
         */
        fun header(name: String, value: String) = apply {
            headers[name] = value
        }

        /**
         * Adds a header with [name] and [value]. Prefer this method for multiply-valued
         * headers like "Cookie".
         *
         * Note that for some headers including `Content-Length` and `Content-Encoding`,
         * OkHttp may replace [value] with a header derived from the request body.
         */
        fun addHeader(name: String, value: String) = apply {
            headers.add(name, value)
        }

        /** Removes all headers named [name] on this builder. */
        fun removeHeader(name: String) = apply {
            headers.removeAll(name)
        }

        /** Removes all headers on this builder and adds [headers]. */
        fun headers(headers: Headers) = apply {
            this.headers = headers.newBuilder()
        }

        fun get() = method("GET", null)

        fun head() = method("HEAD", null)

        fun post(body: RequestBody) = method("POST", body)


        fun delete(body: RequestBody = EMPTY_REQUEST_BODY) = method("DELETE", body)

        fun put(body: RequestBody) = method("PUT", body)

        fun patch(body: RequestBody) = method("PATCH", body)

        fun method(method: String, body: RequestBody?): Builder = apply {
            require(method.isNotEmpty()) {
                "method.isEmpty() == true"
            }
            if (body == null) {
                require(!requiresRequestBody(method)) {
                    "method $method must have a request body."
                }
            } else {
                require(permitsRequestBody(method)) {
                    "method $method must not have a request body."
                }
            }
            this.method = method
            this.body = body
        }

        /** Attaches [tag] to the request using `Object.class` as a key. */
        fun tag(tag: Any?): Builder = tag(Any::class.java, tag)

        /**
         * Attaches [tag] to the request using [type] as a key. Tags can be read from a
         * request using [Request.tag]. Use null to remove any existing tag assigned for [type].
         *
         * Use this API to attach timing, debugging, or other application data to a request so that
         * you may read it in interceptors, event listeners, or callbacks.
         */
        fun <T> tag(type: Class<in T>, tag: T?) = apply {
            if (tag == null) {
                tags.remove(type)
            } else {
                if (tags.isEmpty()) {
                    tags = mutableMapOf()
                }
                tags[type] =
                    type.cast(tag)!! // Force-unwrap due to lack of contracts on Class#cast()
            }
        }

        fun build(): Request {
            handleHeader()
            return Request(
                checkNotNull(url) { "url == null" },
                method,
                headers.build(),
                body,
                priority,
                disableCache,
                tags,
            )
        }

        private fun handleHeader() {
            if (body != null) {
                val contentLength: Long = try {
                    body!!.length()
                } catch (e: IOException) {
                    throw RuntimeException(e)
                }
                if (contentLength != -1L) {
                    headers["Content-Length"] = contentLength.toString()

                    val contentType: String? = headers["Content-Type"] ?: body!!.contentType()?.toString()

                    if (contentType == null) {
                        // Cronet 始终要求当存在 body 时，也要存在 Content-Type。
                        headers["Content-Type"] = "application/octet-stream"
                    } else {
                        headers["Content-Type"] = contentType
                    }
                }
            }
        }

        private companion object {
            fun requiresRequestBody(method: String): Boolean = (method == "POST" ||
                    method == "PUT" ||
                    method == "PATCH" ||
                    method == "PROPPATCH" || // WebDAV
                    method == "REPORT") // CalDAV/CardDAV (defined in WebDAV Versioning)

            fun permitsRequestBody(method: String): Boolean = !(method == "GET" || method == "HEAD")
        }
    }
}