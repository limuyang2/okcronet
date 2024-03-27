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

import okio.Buffer
import java.net.*
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * @author 李沐阳
 * @date 2023/2/25
 * @description
 */
class HttpUrl internal constructor(
    /** Either "http" or "https". */
    val scheme: String,

    /**
     * The decoded username, or an empty string if none is present.
     *
     * | URL                              | `username()` |
     * | :------------------------------- | :----------- |
     * | `http://host/`                   | `""`         |
     * | `http://username@host/`          | `"username"` |
     * | `http://username:password@host/` | `"username"` |
     * | `http://a%20b:c%20d@host/`       | `"a b"`      |
     */
    val username: String,

    /**
     * Returns the decoded password, or an empty string if none is present.
     *
     * | URL                              | `password()` |
     * | :------------------------------- | :----------- |
     * | `http://host/`                   | `""`         |
     * | `http://username@host/`          | `""`         |
     * | `http://username:password@host/` | `"password"` |
     * | `http://a%20b:c%20d@host/`       | `"c d"`      |
     */
    val password: String,

    /**
     * The host address suitable for use with [InetAddress.getAllByName]. May be:
     *
     *  * A regular host name, like `android.com`.
     *
     *  * An IPv4 address, like `127.0.0.1`.
     *
     *  * An IPv6 address, like `::1`. Note that there are no square braces.
     *
     *  * An encoded IDN, like `xn--n3h.net`.
     *
     * | URL                   | `host()`        |
     * | :-------------------- | :-------------- |
     * | `http://android.com/` | `"android.com"` |
     * | `http://127.0.0.1/`   | `"127.0.0.1"`   |
     * | `http://[::1]/`       | `"::1"`         |
     * | `http://xn--n3h.net/` | `"xn--n3h.net"` |
     */
    val host: String,

    /**
     * The explicitly-specified port if one was provided, or the default port for this URL's scheme.
     * For example, this returns 8443 for `https://square.com:8443/` and 443 for
     * `https://square.com/`. The result is in `[1..65535]`.
     *
     * | URL                 | `port()` |
     * | :------------------ | :------- |
     * | `http://host/`      | `80`     |
     * | `http://host:8000/` | `8000`   |
     * | `https://host/`     | `443`    |
     */
    val port: Int,

    /**
     * A list of path segments like `["a", "b", "c"]` for the URL `http://host/a/b/c`. This list is
     * never empty though it may contain a single empty string.
     *
     * | URL                      | `pathSegments()`    |
     * | :----------------------- | :------------------ |
     * | `http://host/`           | `[""]`              |
     * | `http://host/a/b/c"`     | `["a", "b", "c"]`   |
     * | `http://host/a/b%20c/d"` | `["a", "b c", "d"]` |
     */
    val pathSegments: List<String>,

    /**
     * Alternating, decoded query names and values, or null for no query. Names may be empty or
     * non-empty, but never null. Values are null if the name has no corresponding '=' separator, or
     * empty, or non-empty.
     */
    private val queryNamesAndValues: List<String?>?,

    /**
     * This URL's fragment, like `"abc"` for `http://host/#abc`. This is null if the URL has no
     * fragment.
     *
     * | URL                    | `fragment()` |
     * | :--------------------- | :----------- |
     * | `http://host/`         | null         |
     * | `http://host/#`        | `""`         |
     * | `http://host/#abc`     | `"abc"`      |
     * | `http://host/#abc|def` | `"abc|def"`  |
     */
    val fragment: String?,

    /** Canonical URL. */
    private val url: String
) {
    val isHttps: Boolean = scheme == "https"

    /** Returns this URL as a [java.net.URL][URL]. */
    fun toUrl(): URL {
        try {
            return URL(url)
        } catch (e: MalformedURLException) {
            throw RuntimeException(e) // Unexpected!
        }
    }

    /**
     * Returns this URL as a [java.net.URI][URI]. Because `URI` is more strict than this class, the
     * returned URI may be semantically different from this URL:
     *
     *  * Characters forbidden by URI like `[` and `|` will be escaped.
     *
     *  * Invalid percent-encoded sequences like `%xx` will be encoded like `%25xx`.
     *
     *  * Whitespace and control characters in the fragment will be stripped.
     *
     * These differences may have a significant consequence when the URI is interpreted by a
     * web server. For this reason the [URI class][URI] and this method should be avoided.
     */
    fun toUri(): URI {
        val uri = newBuilder().reencodeForUri().toString()
        return try {
            URI(uri)
        } catch (e: URISyntaxException) {
            // Unlikely edge case: the URI has a forbidden character in the fragment. Strip it & retry.
            try {
                val stripped = uri.replace(Regex("[\\u0000-\\u001F\\u007F-\\u009F\\p{javaWhitespace}]"), "")
                URI.create(stripped)
            } catch (e1: Exception) {
                throw RuntimeException(e) // Unexpected!
            }
        }
    }

    /**
     * The username, or an empty string if none is set.
     *
     * | URL                              | `encodedUsername()` |
     * | :------------------------------- | :------------------ |
     * | `http://host/`                   | `""`                |
     * | `http://username@host/`          | `"username"`        |
     * | `http://username:password@host/` | `"username"`        |
     * | `http://a%20b:c%20d@host/`       | `"a%20b"`           |
     */
    val encodedUsername: String
        get() {
            if (username.isEmpty()) return ""
            val usernameStart = scheme.length + 3 // "://".length() == 3.
            val usernameEnd = url.delimiterOffset(":@", usernameStart, url.length)
            return url.substring(usernameStart, usernameEnd)
        }

    /**
     * The password, or an empty string if none is set.
     *
     * | URL                              | `encodedPassword()` |
     * | :--------------------------------| :------------------ |
     * | `http://host/`                   | `""`                |
     * | `http://username@host/`          | `""`                |
     * | `http://username:password@host/` | `"password"`        |
     * | `http://a%20b:c%20d@host/`       | `"c%20d"`           |
     */
    val encodedPassword: String
        get() {
            if (password.isEmpty()) return ""
            val passwordStart = url.indexOf(':', scheme.length + 3) + 1
            val passwordEnd = url.indexOf('@')
            return url.substring(passwordStart, passwordEnd)
        }

    /**
     * The number of segments in this URL's path. This is also the number of slashes in this URL's
     * path, like 3 in `http://host/a/b/c`. This is always at least 1.
     *
     * | URL                  | `pathSize()` |
     * | :------------------- | :----------- |
     * | `http://host/`       | `1`          |
     * | `http://host/a/b/c`  | `3`          |
     * | `http://host/a/b/c/` | `4`          |
     */
    val pathSize: Int get() = pathSegments.size

    /**
     * The entire path of this URL encoded for use in HTTP resource resolution. The returned path will
     * start with `"/"`.
     *
     * | URL                     | `encodedPath()` |
     * | :---------------------- | :-------------- |
     * | `http://host/`          | `"/"`           |
     * | `http://host/a/b/c`     | `"/a/b/c"`      |
     * | `http://host/a/b%20c/d` | `"/a/b%20c/d"`  |
     */
    val encodedPath: String
        get() {
            val pathStart = url.indexOf('/', scheme.length + 3) // "://".length() == 3.
            val pathEnd = url.delimiterOffset("?#", pathStart, url.length)
            return url.substring(pathStart, pathEnd)
        }

    /**
     * A list of encoded path segments like `["a", "b", "c"]` for the URL `http://host/a/b/c`. This
     * list is never empty though it may contain a single empty string.
     *
     * | URL                     | `encodedPathSegments()` |
     * | :---------------------- | :---------------------- |
     * | `http://host/`          | `[""]`                  |
     * | `http://host/a/b/c`     | `["a", "b", "c"]`       |
     * | `http://host/a/b%20c/d` | `["a", "b%20c", "d"]`   |
     */
    val encodedPathSegments: List<String>
        get() {
            val pathStart = url.indexOf('/', scheme.length + 3)
            val pathEnd = url.delimiterOffset("?#", pathStart, url.length)
            val result = mutableListOf<String>()
            var i = pathStart
            while (i < pathEnd) {
                i++ // Skip the '/'.
                val segmentEnd = url.delimiterOffset('/', i, pathEnd)
                result.add(url.substring(i, segmentEnd))
                i = segmentEnd
            }
            return result
        }

    /**
     * The query of this URL, encoded for use in HTTP resource resolution. This string may be null
     * (for URLs with no query), empty (for URLs with an empty query) or non-empty (all other URLs).
     *
     * | URL                               | `encodedQuery()`       |
     * | :-------------------------------- | :--------------------- |
     * | `http://host/`                    | null                   |
     * | `http://host/?`                   | `""`                   |
     * | `http://host/?a=apple&k=key+lime` | `"a=apple&k=key+lime"` |
     * | `http://host/?a=apple&a=apricot`  | `"a=apple&a=apricot"`  |
     * | `http://host/?a=apple&b`          | `"a=apple&b"`          |
     */
    val encodedQuery: String?
        get() {
            if (queryNamesAndValues == null) return null // No query.
            val queryStart = url.indexOf('?') + 1
            val queryEnd = url.delimiterOffset('#', queryStart, url.length)
            return url.substring(queryStart, queryEnd)
        }

    /**
     * This URL's query, like `"abc"` for `http://host/?abc`. Most callers should prefer
     * [queryParameterName] and [queryParameterValue] because these methods offer direct access to
     * individual query parameters.
     *
     * | URL                               | `query()`              |
     * | :-------------------------------- | :--------------------- |
     * | `http://host/`                    | null                   |
     * | `http://host/?`                   | `""`                   |
     * | `http://host/?a=apple&k=key+lime` | `"a=apple&k=key lime"` |
     * | `http://host/?a=apple&a=apricot`  | `"a=apple&a=apricot"`  |
     * | `http://host/?a=apple&b`          | `"a=apple&b"`          |
     */
    val query: String?
        get() {
            if (queryNamesAndValues == null) return null // No query.
            val result = StringBuilder()
            queryNamesAndValues.toQueryString(result)
            return result.toString()
        }

    /**
     * The number of query parameters in this URL, like 2 for `http://host/?a=apple&b=banana`. If this
     * URL has no query this is 0. Otherwise it is one more than the number of `"&"` separators in the
     * query.
     *
     * | URL                               | `querySize()` |
     * | :-------------------------------- | :------------ |
     * | `http://host/`                    | `0`           |
     * | `http://host/?`                   | `1`           |
     * | `http://host/?a=apple&k=key+lime` | `2`           |
     * | `http://host/?a=apple&a=apricot`  | `2`           |
     * | `http://host/?a=apple&b`          | `2`           |
     */
    val querySize: Int
        get() {
            return if (queryNamesAndValues != null) queryNamesAndValues.size / 2 else 0
        }

    /**
     * The first query parameter named `name` decoded using UTF-8, or null if there is no such query
     * parameter.
     *
     * | URL                               | `queryParameter("a")` |
     * | :-------------------------------- | :-------------------- |
     * | `http://host/`                    | null                  |
     * | `http://host/?`                   | null                  |
     * | `http://host/?a=apple&k=key+lime` | `"apple"`             |
     * | `http://host/?a=apple&a=apricot`  | `"apple"`             |
     * | `http://host/?a=apple&b`          | `"apple"`             |
     */
    fun queryParameter(name: String): String? {
        if (queryNamesAndValues == null) return null
        for (i in queryNamesAndValues.indices step 2) {
            if (name == queryNamesAndValues[i]) {
                return queryNamesAndValues[i + 1]
            }
        }
        return null
    }

    /**
     * The distinct query parameter names in this URL, like `["a", "b"]` for
     * `http://host/?a=apple&b=banana`. If this URL has no query this is the empty set.
     *
     * | URL                               | `queryParameterNames()` |
     * | :-------------------------------- | :---------------------- |
     * | `http://host/`                    | `[]`                    |
     * | `http://host/?`                   | `[""]`                  |
     * | `http://host/?a=apple&k=key+lime` | `["a", "k"]`            |
     * | `http://host/?a=apple&a=apricot`  | `["a"]`                 |
     * | `http://host/?a=apple&b`          | `["a", "b"]`            |
     */
    val queryParameterNames: Set<String>
        get() {
            if (queryNamesAndValues == null) return emptySet()
            val result = LinkedHashSet<String>()
            for (i in queryNamesAndValues.indices step 2) {
                result.add(queryNamesAndValues[i]!!)
            }
            return Collections.unmodifiableSet(result)
        }

    /**
     * Returns all values for the query parameter `name` ordered by their appearance in this
     * URL. For example this returns `["banana"]` for `queryParameterValue("b")` on
     * `http://host/?a=apple&b=banana`.
     *
     * | URL                               | `queryParameterValues("a")` | `queryParameterValues("b")` |
     * | :-------------------------------- | :-------------------------- | :-------------------------- |
     * | `http://host/`                    | `[]`                        | `[]`                        |
     * | `http://host/?`                   | `[]`                        | `[]`                        |
     * | `http://host/?a=apple&k=key+lime` | `["apple"]`                 | `[]`                        |
     * | `http://host/?a=apple&a=apricot`  | `["apple", "apricot"]`      | `[]`                        |
     * | `http://host/?a=apple&b`          | `["apple"]`                 | `[null]`                    |
     */
    fun queryParameterValues(name: String): List<String?> {
        if (queryNamesAndValues == null) return emptyList()
        val result = mutableListOf<String?>()
        for (i in queryNamesAndValues.indices step 2) {
            if (name == queryNamesAndValues[i]) {
                result.add(queryNamesAndValues[i + 1])
            }
        }
        return Collections.unmodifiableList(result)
    }

    /**
     * Returns the name of the query parameter at `index`. For example this returns `"a"`
     * for `queryParameterName(0)` on `http://host/?a=apple&b=banana`. This throws if
     * `index` is not less than the [query size][querySize].
     *
     * | URL                               | `queryParameterName(0)` | `queryParameterName(1)` |
     * | :-------------------------------- | :---------------------- | :---------------------- |
     * | `http://host/`                    | exception               | exception               |
     * | `http://host/?`                   | `""`                    | exception               |
     * | `http://host/?a=apple&k=key+lime` | `"a"`                   | `"k"`                   |
     * | `http://host/?a=apple&a=apricot`  | `"a"`                   | `"a"`                   |
     * | `http://host/?a=apple&b`          | `"a"`                   | `"b"`                   |
     */
    fun queryParameterName(index: Int): String {
        if (queryNamesAndValues == null) throw IndexOutOfBoundsException()
        return queryNamesAndValues[index * 2]!!
    }

    /**
     * Returns the value of the query parameter at `index`. For example this returns `"apple"` for
     * `queryParameterName(0)` on `http://host/?a=apple&b=banana`. This throws if `index` is not less
     * than the [query size][querySize].
     *
     * | URL                               | `queryParameterValue(0)` | `queryParameterValue(1)` |
     * | :-------------------------------- | :----------------------- | :----------------------- |
     * | `http://host/`                    | exception                | exception                |
     * | `http://host/?`                   | null                     | exception                |
     * | `http://host/?a=apple&k=key+lime` | `"apple"`                | `"key lime"`             |
     * | `http://host/?a=apple&a=apricot`  | `"apple"`                | `"apricot"`              |
     * | `http://host/?a=apple&b`          | `"apple"`                | null                     |
     */
    fun queryParameterValue(index: Int): String? {
        if (queryNamesAndValues == null) throw IndexOutOfBoundsException()
        return queryNamesAndValues[index * 2 + 1]
    }

    /**
     * This URL's encoded fragment, like `"abc"` for `http://host/#abc`. This is null if the URL has
     * no fragment.
     *
     * | URL                    | `encodedFragment()` |
     * | :--------------------- | :------------------ |
     * | `http://host/`         | null                |
     * | `http://host/#`        | `""`                |
     * | `http://host/#abc`     | `"abc"`             |
     * | `http://host/#abc|def` | `"abc|def"`         |
     */
    val encodedFragment: String?
        get() {
            if (fragment == null) return null
            val fragmentStart = url.indexOf('#') + 1
            return url.substring(fragmentStart)
        }

    /**
     * Returns a string with containing this URL with its username, password, query, and fragment
     * stripped, and its path replaced with `/...`. For example, redacting
     * `http://username:password@example.com/path` returns `http://example.com/...`.
     */
    fun redact(): String {
        return newBuilder("/...")!!
            .username("")
            .password("")
            .build()
            .toString()
    }

    /**
     * Returns the URL that would be retrieved by following `link` from this URL, or null if the
     * resulting URL is not well-formed.
     */
    fun resolve(link: String): HttpUrl? = newBuilder(link)?.build()

    /**
     * Returns a builder based on this URL.
     */
    fun newBuilder(): Builder {
        val result = Builder()
        result.scheme = scheme
        result.encodedUsername = encodedUsername
        result.encodedPassword = encodedPassword
        result.host = host
        // If we're set to a default port, unset it in case of a scheme change.
        result.port = if (port != defaultPort(scheme)) port else -1
        result.encodedPathSegments.clear()
        result.encodedPathSegments.addAll(encodedPathSegments)
        result.encodedQuery(encodedQuery)
        result.encodedFragment = encodedFragment
        return result
    }

    /**
     * Returns a builder for the URL that would be retrieved by following `link` from this URL,
     * or null if the resulting URL is not well-formed.
     */
    fun newBuilder(link: String): Builder? {
        return try {
            Builder().parse(this, link)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is HttpUrl && other.url == url
    }

    override fun hashCode(): Int = url.hashCode()

    override fun toString(): String = url



    class Builder {
        internal var scheme: String? = null
        internal var encodedUsername = ""
        internal var encodedPassword = ""
        internal var host: String? = null
        internal var port = -1
        internal val encodedPathSegments = mutableListOf<String>()
        private var encodedQueryNamesAndValues: MutableList<String?>? = null
        internal var encodedFragment: String? = null

        init {
            encodedPathSegments.add("") // The default path is '/' which needs a trailing space.
        }

        /**
         * @param scheme either "http" or "https".
         */
        fun scheme(scheme: String) = apply {
            when {
                scheme.equals("http", ignoreCase = true) -> this.scheme = "http"
                scheme.equals("https", ignoreCase = true) -> this.scheme = "https"
                else -> throw IllegalArgumentException("unexpected scheme: $scheme")
            }
        }

        fun username(username: String) = apply {
            this.encodedUsername = username.canonicalize(encodeSet = USERNAME_ENCODE_SET)
        }

        fun encodedUsername(encodedUsername: String) = apply {
            this.encodedUsername = encodedUsername.canonicalize(
                encodeSet = USERNAME_ENCODE_SET,
                alreadyEncoded = true
            )
        }

        fun password(password: String) = apply {
            this.encodedPassword = password.canonicalize(encodeSet = PASSWORD_ENCODE_SET)
        }

        fun encodedPassword(encodedPassword: String) = apply {
            this.encodedPassword = encodedPassword.canonicalize(
                encodeSet = PASSWORD_ENCODE_SET,
                alreadyEncoded = true
            )
        }

        /**
         * @param host either a regular hostname, International Domain Name, IPv4 address, or IPv6
         * address.
         */
        fun host(host: String) = apply {
            val encoded = host.percentDecode().toCanonicalHost() ?: throw IllegalArgumentException(
                "unexpected host: $host")
            this.host = encoded
        }

        fun port(port: Int) = apply {
            require(port in 1..65535) { "unexpected port: $port" }
            this.port = port
        }

        private fun effectivePort(): Int {
            return if (port != -1) port else defaultPort(scheme!!)
        }

        fun addPathSegment(pathSegment: String) = apply {
            push(pathSegment, 0, pathSegment.length, addTrailingSlash = false, alreadyEncoded = false)
        }

        /**
         * Adds a set of path segments separated by a slash (either `\` or `/`). If `pathSegments`
         * starts with a slash, the resulting URL will have empty path segment.
         */
        fun addPathSegments(pathSegments: String): Builder = addPathSegments(pathSegments, false)

        fun addEncodedPathSegment(encodedPathSegment: String) = apply {
            push(encodedPathSegment, 0, encodedPathSegment.length, addTrailingSlash = false,
                alreadyEncoded = true)
        }

        /**
         * Adds a set of encoded path segments separated by a slash (either `\` or `/`). If
         * `encodedPathSegments` starts with a slash, the resulting URL will have empty path segment.
         */
        fun addEncodedPathSegments(encodedPathSegments: String): Builder =
            addPathSegments(encodedPathSegments, true)

        private fun addPathSegments(pathSegments: String, alreadyEncoded: Boolean) = apply {
            var offset = 0
            do {
                val segmentEnd = pathSegments.delimiterOffset("/\\", offset, pathSegments.length)
                val addTrailingSlash = segmentEnd < pathSegments.length
                push(pathSegments, offset, segmentEnd, addTrailingSlash, alreadyEncoded)
                offset = segmentEnd + 1
            } while (offset <= pathSegments.length)
        }

        fun setPathSegment(index: Int, pathSegment: String) = apply {
            val canonicalPathSegment = pathSegment.canonicalize(encodeSet = PATH_SEGMENT_ENCODE_SET)
            require(!isDot(canonicalPathSegment) && !isDotDot(canonicalPathSegment)) {
                "unexpected path segment: $pathSegment"
            }
            encodedPathSegments[index] = canonicalPathSegment
        }

        fun setEncodedPathSegment(index: Int, encodedPathSegment: String) = apply {
            val canonicalPathSegment = encodedPathSegment.canonicalize(
                encodeSet = PATH_SEGMENT_ENCODE_SET,
                alreadyEncoded = true
            )
            encodedPathSegments[index] = canonicalPathSegment
            require(!isDot(canonicalPathSegment) && !isDotDot(canonicalPathSegment)) {
                "unexpected path segment: $encodedPathSegment"
            }
        }

        fun removePathSegment(index: Int) = apply {
            encodedPathSegments.removeAt(index)
            if (encodedPathSegments.isEmpty()) {
                encodedPathSegments.add("") // Always leave at least one '/'.
            }
        }

        fun encodedPath(encodedPath: String) = apply {
            require(encodedPath.startsWith("/")) { "unexpected encodedPath: $encodedPath" }
            resolvePath(encodedPath, 0, encodedPath.length)
        }

        fun query(query: String?) = apply {
            this.encodedQueryNamesAndValues = query?.canonicalize(
                encodeSet = QUERY_ENCODE_SET,
                plusIsSpace = true
            )?.toQueryNamesAndValues()
        }

        fun encodedQuery(encodedQuery: String?) = apply {
            this.encodedQueryNamesAndValues = encodedQuery?.canonicalize(
                encodeSet = QUERY_ENCODE_SET,
                alreadyEncoded = true,
                plusIsSpace = true
            )?.toQueryNamesAndValues()
        }

        /** Encodes the query parameter using UTF-8 and adds it to this URL's query string. */
        fun addQueryParameter(name: String, value: String?) = apply {
            if (encodedQueryNamesAndValues == null) encodedQueryNamesAndValues = mutableListOf()
            encodedQueryNamesAndValues!!.add(name.canonicalize(
                encodeSet = QUERY_COMPONENT_ENCODE_SET,
                plusIsSpace = true
            ))
            encodedQueryNamesAndValues!!.add(value?.canonicalize(
                encodeSet = QUERY_COMPONENT_ENCODE_SET,
                plusIsSpace = true
            ))
        }

        /** Adds the pre-encoded query parameter to this URL's query string. */
        fun addEncodedQueryParameter(encodedName: String, encodedValue: String?) = apply {
            if (encodedQueryNamesAndValues == null) encodedQueryNamesAndValues = mutableListOf()
            encodedQueryNamesAndValues!!.add(encodedName.canonicalize(
                encodeSet = QUERY_COMPONENT_REENCODE_SET,
                alreadyEncoded = true,
                plusIsSpace = true
            ))
            encodedQueryNamesAndValues!!.add(encodedValue?.canonicalize(
                encodeSet = QUERY_COMPONENT_REENCODE_SET,
                alreadyEncoded = true,
                plusIsSpace = true
            ))
        }

        fun setQueryParameter(name: String, value: String?) = apply {
            removeAllQueryParameters(name)
            addQueryParameter(name, value)
        }

        fun setEncodedQueryParameter(encodedName: String, encodedValue: String?) = apply {
            removeAllEncodedQueryParameters(encodedName)
            addEncodedQueryParameter(encodedName, encodedValue)
        }

        fun removeAllQueryParameters(name: String) = apply {
            if (encodedQueryNamesAndValues == null) return this
            val nameToRemove = name.canonicalize(
                encodeSet = QUERY_COMPONENT_ENCODE_SET,
                plusIsSpace = true
            )
            removeAllCanonicalQueryParameters(nameToRemove)
        }

        fun removeAllEncodedQueryParameters(encodedName: String) = apply {
            if (encodedQueryNamesAndValues == null) return this
            removeAllCanonicalQueryParameters(encodedName.canonicalize(
                encodeSet = QUERY_COMPONENT_REENCODE_SET,
                alreadyEncoded = true,
                plusIsSpace = true
            ))
        }

        private fun removeAllCanonicalQueryParameters(canonicalName: String) {
            for (i in encodedQueryNamesAndValues!!.size - 2 downTo 0 step 2) {
                if (canonicalName == encodedQueryNamesAndValues!![i]) {
                    encodedQueryNamesAndValues!!.removeAt(i + 1)
                    encodedQueryNamesAndValues!!.removeAt(i)
                    if (encodedQueryNamesAndValues!!.isEmpty()) {
                        encodedQueryNamesAndValues = null
                        return
                    }
                }
            }
        }

        fun fragment(fragment: String?) = apply {
            this.encodedFragment = fragment?.canonicalize(
                encodeSet = FRAGMENT_ENCODE_SET,
                unicodeAllowed = true
            )
        }

        fun encodedFragment(encodedFragment: String?) = apply {
            this.encodedFragment = encodedFragment?.canonicalize(
                encodeSet = FRAGMENT_ENCODE_SET,
                alreadyEncoded = true,
                unicodeAllowed = true
            )
        }

        /**
         * Re-encodes the components of this URL so that it satisfies (obsolete) RFC 2396, which is
         * particularly strict for certain components.
         */
        internal fun reencodeForUri() = apply {
            host = host?.replace(Regex("[\"<>^`{|}]"), "")

            for (i in 0 until encodedPathSegments.size) {
                encodedPathSegments[i] = encodedPathSegments[i].canonicalize(
                    encodeSet = PATH_SEGMENT_ENCODE_SET_URI,
                    alreadyEncoded = true,
                    strict = true
                )
            }

            val encodedQueryNamesAndValues = this.encodedQueryNamesAndValues
            if (encodedQueryNamesAndValues != null) {
                for (i in 0 until encodedQueryNamesAndValues.size) {
                    encodedQueryNamesAndValues[i] = encodedQueryNamesAndValues[i]?.canonicalize(
                        encodeSet = QUERY_COMPONENT_ENCODE_SET_URI,
                        alreadyEncoded = true,
                        strict = true,
                        plusIsSpace = true
                    )
                }
            }

            encodedFragment = encodedFragment?.canonicalize(
                encodeSet = FRAGMENT_ENCODE_SET_URI,
                alreadyEncoded = true,
                strict = true,
                unicodeAllowed = true
            )
        }

        fun build(): HttpUrl {
            return HttpUrl(
                scheme = scheme ?: throw IllegalStateException("scheme == null"),
                username = encodedUsername.percentDecode(),
                password = encodedPassword.percentDecode(),
                host = host ?: throw IllegalStateException("host == null"),
                port = effectivePort(),
                pathSegments = encodedPathSegments.map { it.percentDecode() },
                queryNamesAndValues = encodedQueryNamesAndValues?.map { it?.percentDecode(plusIsSpace = true) },
                fragment = encodedFragment?.percentDecode(),
                url = toString()
            )
        }

        override fun toString(): String {
            return buildString {
                if (scheme != null) {
                    append(scheme)
                    append("://")
                } else {
                    append("//")
                }

                if (encodedUsername.isNotEmpty() || encodedPassword.isNotEmpty()) {
                    append(encodedUsername)
                    if (encodedPassword.isNotEmpty()) {
                        append(':')
                        append(encodedPassword)
                    }
                    append('@')
                }

                if (host != null) {
                    if (':' in host!!) {
                        // Host is an IPv6 address.
                        append('[')
                        append(host)
                        append(']')
                    } else {
                        append(host)
                    }
                }

                if (port != -1 || scheme != null) {
                    val effectivePort = effectivePort()
                    if (scheme == null || effectivePort != defaultPort(scheme!!)) {
                        append(':')
                        append(effectivePort)
                    }
                }

                encodedPathSegments.toPathString(this)

                if (encodedQueryNamesAndValues != null) {
                    append('?')
                    encodedQueryNamesAndValues!!.toQueryString(this)
                }

                if (encodedFragment != null) {
                    append('#')
                    append(encodedFragment)
                }
            }
        }

        internal fun parse(base: HttpUrl?, input: String): Builder {
            var pos = input.indexOfFirstNonAsciiWhitespace()
            val limit = input.indexOfLastNonAsciiWhitespace(pos)

            // Scheme.
            val schemeDelimiterOffset = schemeDelimiterOffset(input, pos, limit)
            if (schemeDelimiterOffset != -1) {
                when {
                    input.startsWith("https:", ignoreCase = true, startIndex = pos) -> {
                        this.scheme = "https"
                        pos += "https:".length
                    }
                    input.startsWith("http:", ignoreCase = true, startIndex = pos) -> {
                        this.scheme = "http"
                        pos += "http:".length
                    }
                    else -> throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but was '" +
                            input.substring(0, schemeDelimiterOffset) + "'")
                }
            } else if (base != null) {
                this.scheme = base.scheme
            } else {
                val truncated = if (input.length > 6) input.take(6) + "..." else input
                throw IllegalArgumentException(
                    "Expected URL scheme 'http' or 'https' but no scheme was found for $truncated")
            }

            // Authority.
            var hasUsername = false
            var hasPassword = false
            val slashCount = input.slashCount(pos, limit)
            if (slashCount >= 2 || base == null || base.scheme != this.scheme) {
                // Read an authority if either:
                //  * The input starts with 2 or more slashes. These follow the scheme if it exists.
                //  * The input scheme exists and is different from the base URL's scheme.
                //
                // The structure of an authority is:
                //   username:password@host:port
                //
                // Username, password and port are optional.
                //   [username[:password]@]host[:port]
                pos += slashCount
                authority@ while (true) {
                    val componentDelimiterOffset = input.delimiterOffset("@/\\?#", pos, limit)
                    val c = if (componentDelimiterOffset != limit) {
                        input[componentDelimiterOffset].code
                    } else {
                        -1
                    }
                    when (c) {
                        '@'.code -> {
                            // User info precedes.
                            if (!hasPassword) {
                                val passwordColonOffset = input.delimiterOffset(':', pos, componentDelimiterOffset)
                                val canonicalUsername = input.canonicalize(
                                    pos = pos,
                                    limit = passwordColonOffset,
                                    encodeSet = USERNAME_ENCODE_SET,
                                    alreadyEncoded = true
                                )
                                this.encodedUsername = if (hasUsername) {
                                    this.encodedUsername + "%40" + canonicalUsername
                                } else {
                                    canonicalUsername
                                }
                                if (passwordColonOffset != componentDelimiterOffset) {
                                    hasPassword = true
                                    this.encodedPassword = input.canonicalize(
                                        pos = passwordColonOffset + 1,
                                        limit = componentDelimiterOffset,
                                        encodeSet = PASSWORD_ENCODE_SET,
                                        alreadyEncoded = true
                                    )
                                }
                                hasUsername = true
                            } else {
                                this.encodedPassword = this.encodedPassword + "%40" + input.canonicalize(
                                    pos = pos,
                                    limit = componentDelimiterOffset,
                                    encodeSet = PASSWORD_ENCODE_SET,
                                    alreadyEncoded = true
                                )
                            }
                            pos = componentDelimiterOffset + 1
                        }

                        -1, '/'.code, '\\'.code, '?'.code, '#'.code -> {
                            // Host info precedes.
                            val portColonOffset = portColonOffset(input, pos, componentDelimiterOffset)
                            if (portColonOffset + 1 < componentDelimiterOffset) {
                                host = input.percentDecode(pos = pos, limit = portColonOffset).toCanonicalHost()
                                port = parsePort(input, portColonOffset + 1, componentDelimiterOffset)
                                require(port != -1) {
                                    "Invalid URL port: \"${input.substring(portColonOffset + 1,
                                        componentDelimiterOffset)}\""
                                }
                            } else {
                                host = input.percentDecode(pos = pos, limit = portColonOffset).toCanonicalHost()
                                port = defaultPort(scheme!!)
                            }
                            require(host != null) {
                                "$INVALID_HOST: \"${input.substring(pos, portColonOffset)}\""
                            }
                            pos = componentDelimiterOffset
                            break@authority
                        }
                    }
                }
            } else {
                // This is a relative link. Copy over all authority components. Also maybe the path & query.
                this.encodedUsername = base.encodedUsername
                this.encodedPassword = base.encodedPassword
                this.host = base.host
                this.port = base.port
                this.encodedPathSegments.clear()
                this.encodedPathSegments.addAll(base.encodedPathSegments)
                if (pos == limit || input[pos] == '#') {
                    encodedQuery(base.encodedQuery)
                }
            }

            // Resolve the relative path.
            val pathDelimiterOffset = input.delimiterOffset("?#", pos, limit)
            resolvePath(input, pos, pathDelimiterOffset)
            pos = pathDelimiterOffset

            // Query.
            if (pos < limit && input[pos] == '?') {
                val queryDelimiterOffset = input.delimiterOffset('#', pos, limit)
                this.encodedQueryNamesAndValues = input.canonicalize(
                    pos = pos + 1,
                    limit = queryDelimiterOffset,
                    encodeSet = QUERY_ENCODE_SET,
                    alreadyEncoded = true,
                    plusIsSpace = true
                ).toQueryNamesAndValues()
                pos = queryDelimiterOffset
            }

            // Fragment.
            if (pos < limit && input[pos] == '#') {
                this.encodedFragment = input.canonicalize(
                    pos = pos + 1,
                    limit = limit,
                    encodeSet = FRAGMENT_ENCODE_SET,
                    alreadyEncoded = true,
                    unicodeAllowed = true
                )
            }

            return this
        }

        private fun resolvePath(input: String, startPos: Int, limit: Int) {
            var pos = startPos
            // Read a delimiter.
            if (pos == limit) {
                // Empty path: keep the base path as-is.
                return
            }
            val c = input[pos]
            if (c == '/' || c == '\\') {
                // Absolute path: reset to the default "/".
                encodedPathSegments.clear()
                encodedPathSegments.add("")
                pos++
            } else {
                // Relative path: clear everything after the last '/'.
                encodedPathSegments[encodedPathSegments.size - 1] = ""
            }

            // Read path segments.
            var i = pos
            while (i < limit) {
                val pathSegmentDelimiterOffset = input.delimiterOffset("/\\", i, limit)
                val segmentHasTrailingSlash = pathSegmentDelimiterOffset < limit
                push(input, i, pathSegmentDelimiterOffset, segmentHasTrailingSlash, true)
                i = pathSegmentDelimiterOffset
                if (segmentHasTrailingSlash) i++
            }
        }

        /** Adds a path segment. If the input is ".." or equivalent, this pops a path segment. */
        private fun push(
            input: String,
            pos: Int,
            limit: Int,
            addTrailingSlash: Boolean,
            alreadyEncoded: Boolean
        ) {
            val segment = input.canonicalize(
                pos = pos,
                limit = limit,
                encodeSet = PATH_SEGMENT_ENCODE_SET,
                alreadyEncoded = alreadyEncoded
            )
            if (isDot(segment)) {
                return // Skip '.' path segments.
            }
            if (isDotDot(segment)) {
                pop()
                return
            }
            if (encodedPathSegments[encodedPathSegments.size - 1].isEmpty()) {
                encodedPathSegments[encodedPathSegments.size - 1] = segment
            } else {
                encodedPathSegments.add(segment)
            }
            if (addTrailingSlash) {
                encodedPathSegments.add("")
            }
        }

        private fun isDot(input: String): Boolean {
            return input == "." || input.equals("%2e", ignoreCase = true)
        }

        private fun isDotDot(input: String): Boolean {
            return input == ".." ||
                    input.equals("%2e.", ignoreCase = true) ||
                    input.equals(".%2e", ignoreCase = true) ||
                    input.equals("%2e%2e", ignoreCase = true)
        }

        /**
         * Removes a path segment. When this method returns the last segment is always "", which means
         * the encoded path will have a trailing '/'.
         *
         * Popping "/a/b/c/" yields "/a/b/". In this case the list of path segments goes from ["a",
         * "b", "c", ""] to ["a", "b", ""].
         *
         * Popping "/a/b/c" also yields "/a/b/". The list of path segments goes from ["a", "b", "c"]
         * to ["a", "b", ""].
         */
        private fun pop() {
            val removed = encodedPathSegments.removeAt(encodedPathSegments.size - 1)

            // Make sure the path ends with a '/' by either adding an empty string or clearing a segment.
            if (removed.isEmpty() && encodedPathSegments.isNotEmpty()) {
                encodedPathSegments[encodedPathSegments.size - 1] = ""
            } else {
                encodedPathSegments.add("")
            }
        }

        companion object {
            internal const val INVALID_HOST = "Invalid URL host"

            /**
             * Returns the index of the ':' in `input` that is after scheme characters. Returns -1 if
             * `input` does not have a scheme that starts at `pos`.
             */
            private fun schemeDelimiterOffset(input: String, pos: Int, limit: Int): Int {
                if (limit - pos < 2) return -1

                val c0 = input[pos]
                if ((c0 < 'a' || c0 > 'z') && (c0 < 'A' || c0 > 'Z')) return -1 // Not a scheme start char.

                characters@ for (i in pos + 1 until limit) {
                    return when (input[i]) {
                        // Scheme character. Keep going.
                        in 'a'..'z', in 'A'..'Z', in '0'..'9', '+', '-', '.' -> continue@characters

                        // Scheme prefix!
                        ':' -> i

                        // Non-scheme character before the first ':'.
                        else -> -1
                    }
                }

                return -1 // No ':'; doesn't start with a scheme.
            }

            /** Returns the number of '/' and '\' slashes in this, starting at `pos`. */
            private fun String.slashCount(pos: Int, limit: Int): Int {
                var slashCount = 0
                for (i in pos until limit) {
                    val c = this[i]
                    if (c == '\\' || c == '/') {
                        slashCount++
                    } else {
                        break
                    }
                }
                return slashCount
            }

            /** Finds the first ':' in `input`, skipping characters between square braces "[...]". */
            private fun portColonOffset(input: String, pos: Int, limit: Int): Int {
                var i = pos
                while (i < limit) {
                    when (input[i]) {
                        '[' -> {
                            while (++i < limit) {
                                if (input[i] == ']') break
                            }
                        }
                        ':' -> return i
                    }
                    i++
                }
                return limit // No colon.
            }

            private fun parsePort(input: String, pos: Int, limit: Int): Int {
                return try {
                    // Canonicalize the port string to skip '\n' etc.
                    val portString = input.canonicalize(pos = pos, limit = limit, encodeSet = "")
                    val i = portString.toInt()
                    if (i in 1..65535) i else -1
                } catch (_: NumberFormatException) {
                    -1 // Invalid port.
                }
            }
        }
    }

    companion object {
        private val HEX_DIGITS =
            charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
        internal const val USERNAME_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#"
        internal const val PASSWORD_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#"
        internal const val PATH_SEGMENT_ENCODE_SET = " \"<>^`{}|/\\?#"
        internal const val PATH_SEGMENT_ENCODE_SET_URI = "[]"
        internal const val QUERY_ENCODE_SET = " \"'<>#"
        internal const val QUERY_COMPONENT_REENCODE_SET = " \"'<>#&="
        internal const val QUERY_COMPONENT_ENCODE_SET = " !\"#$&'(),/:;<=>?@[]\\^`{|}~"
        internal const val QUERY_COMPONENT_ENCODE_SET_URI = "\\^`{|}"
        internal const val FORM_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#&!$(),~"
        internal const val FRAGMENT_ENCODE_SET = ""
        internal const val FRAGMENT_ENCODE_SET_URI = " \"#<>\\^`{|}"

        /** Returns 80 if `scheme.equals("http")`, 443 if `scheme.equals("https")` and -1 otherwise. */
        fun defaultPort(scheme: String): Int {
            return when (scheme) {
                "http" -> 80
                "https" -> 443
                else -> -1
            }
        }

        /** Returns a path string for this list of path segments. */
        internal fun List<String>.toPathString(out: StringBuilder) {
            for (element in this) {
                out.append('/')
                out.append(element)
            }
        }

        /** Returns a string for this list of query names and values. */
        internal fun List<String?>.toQueryString(out: StringBuilder) {
            for (i in indices step 2) {
                val name = this[i]
                val value = this[i + 1]
                if (i > 0) out.append('&')
                out.append(name)
                if (value != null) {
                    out.append('=')
                    out.append(value)
                }
            }
        }

        /**
         * Cuts this string up into alternating parameter names and values. This divides a query string
         * like `subject=math&easy&problem=5-2=3` into the list `["subject", "math", "easy", null,
         * "problem", "5-2=3"]`. Note that values may be null and may contain '=' characters.
         */
        internal fun String.toQueryNamesAndValues(): MutableList<String?> {
            val result = mutableListOf<String?>()
            var pos = 0
            while (pos <= length) {
                var ampersandOffset = indexOf('&', pos)
                if (ampersandOffset == -1) ampersandOffset = length

                val equalsOffset = indexOf('=', pos)
                if (equalsOffset == -1 || equalsOffset > ampersandOffset) {
                    result.add(substring(pos, ampersandOffset))
                    result.add(null) // No value for this name.
                } else {
                    result.add(substring(pos, equalsOffset))
                    result.add(substring(equalsOffset + 1, ampersandOffset))
                }
                pos = ampersandOffset + 1
            }
            return result
        }

        /**
         * Returns a new [HttpUrl] representing this.
         *
         * @throws IllegalArgumentException If this is not a well-formed HTTP or HTTPS URL.
         */
        fun String.toHttpUrl(): HttpUrl = Builder().parse(null, this).build()

        /**
         * Returns a new `HttpUrl` representing `url` if it is a well-formed HTTP or HTTPS URL, or null
         * if it isn't.
         */
        fun String.toHttpUrlOrNull(): HttpUrl? {
            return try {
                toHttpUrl()
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /**
         * Returns an [HttpUrl] for this if its protocol is `http` or `https`, or null if it has any
         * other protocol.
         */
        fun URL.toHttpUrlOrNull(): HttpUrl? = toString().toHttpUrlOrNull()

        fun URI.toHttpUrlOrNull(): HttpUrl? = toString().toHttpUrlOrNull()


        internal fun String.percentDecode(
            pos: Int = 0,
            limit: Int = length,
            plusIsSpace: Boolean = false
        ): String {
            for (i in pos until limit) {
                val c = this[i]
                if (c == '%' || c == '+' && plusIsSpace) {
                    // Slow path: the character at i requires decoding!
                    val out = Buffer()
                    out.writeUtf8(this, pos, i)
                    out.writePercentDecoded(this, pos = i, limit = limit, plusIsSpace = plusIsSpace)
                    return out.readUtf8()
                }
            }

            // Fast path: no characters in [pos..limit) required decoding.
            return substring(pos, limit)
        }

        private fun Buffer.writePercentDecoded(
            encoded: String,
            pos: Int,
            limit: Int,
            plusIsSpace: Boolean
        ) {
            var codePoint: Int
            var i = pos
            while (i < limit) {
                codePoint = encoded.codePointAt(i)
                if (codePoint == '%'.code && i + 2 < limit) {
                    val d1 = encoded[i + 1].parseHexDigit()
                    val d2 = encoded[i + 2].parseHexDigit()
                    if (d1 != -1 && d2 != -1) {
                        writeByte((d1 shl 4) + d2)
                        i += 2
                        i += Character.charCount(codePoint)
                        continue
                    }
                } else if (codePoint == '+'.code && plusIsSpace) {
                    writeByte(' '.code)
                    i++
                    continue
                }
                writeUtf8CodePoint(codePoint)
                i += Character.charCount(codePoint)
            }
        }

        private fun String.isPercentEncoded(pos: Int, limit: Int): Boolean {
            return pos + 2 < limit &&
                    this[pos] == '%' &&
                    this[pos + 1].parseHexDigit() != -1 &&
                    this[pos + 2].parseHexDigit() != -1
        }

        /**
         * Returns a substring of `input` on the range `[pos..limit)` with the following
         * transformations:
         *
         *  * Tabs, newlines, form feeds and carriage returns are skipped.
         *
         *  * In queries, ' ' is encoded to '+' and '+' is encoded to "%2B".
         *
         *  * Characters in `encodeSet` are percent-encoded.
         *
         *  * Control characters and non-ASCII characters are percent-encoded.
         *
         *  * All other characters are copied without transformation.
         *
         * @param alreadyEncoded true to leave '%' as-is; false to convert it to '%25'.
         * @param strict true to encode '%' if it is not the prefix of a valid percent encoding.
         * @param plusIsSpace true to encode '+' as "%2B" if it is not already encoded.
         * @param unicodeAllowed true to leave non-ASCII codepoint unencoded.
         * @param charset which charset to use, null equals UTF-8.
         */
        internal fun String.canonicalize(
            pos: Int = 0,
            limit: Int = length,
            encodeSet: String,
            alreadyEncoded: Boolean = false,
            strict: Boolean = false,
            plusIsSpace: Boolean = false,
            unicodeAllowed: Boolean = false,
            charset: Charset? = null
        ): String {
            var codePoint: Int
            var i = pos
            while (i < limit) {
                codePoint = codePointAt(i)
                if (codePoint < 0x20 ||
                    codePoint == 0x7f ||
                    codePoint >= 0x80 && !unicodeAllowed ||
                    codePoint.toChar() in encodeSet ||
                    codePoint == '%'.code &&
                    (!alreadyEncoded || strict && !isPercentEncoded(i, limit)) ||
                    codePoint == '+'.code && plusIsSpace) {
                    // Slow path: the character at i requires encoding!
                    val out = Buffer()
                    out.writeUtf8(this, pos, i)
                    out.writeCanonicalized(
                        input = this,
                        pos = i,
                        limit = limit,
                        encodeSet = encodeSet,
                        alreadyEncoded = alreadyEncoded,
                        strict = strict,
                        plusIsSpace = plusIsSpace,
                        unicodeAllowed = unicodeAllowed,
                        charset = charset
                    )
                    return out.readUtf8()
                }
                i += Character.charCount(codePoint)
            }

            // Fast path: no characters in [pos..limit) required encoding.
            return substring(pos, limit)
        }

        private fun Buffer.writeCanonicalized(
            input: String,
            pos: Int,
            limit: Int,
            encodeSet: String,
            alreadyEncoded: Boolean,
            strict: Boolean,
            plusIsSpace: Boolean,
            unicodeAllowed: Boolean,
            charset: Charset?
        ) {
            var encodedCharBuffer: Buffer? = null // Lazily allocated.
            var codePoint: Int
            var i = pos
            while (i < limit) {
                codePoint = input.codePointAt(i)
                if (alreadyEncoded && (codePoint == '\t'.code || codePoint == '\n'.code ||
                            codePoint == '\u000c'.code || codePoint == '\r'.code)) {
                    // Skip this character.
                } else if (codePoint == '+'.code && plusIsSpace) {
                    // Encode '+' as '%2B' since we permit ' ' to be encoded as either '+' or '%20'.
                    writeUtf8(if (alreadyEncoded) "+" else "%2B")
                } else if (codePoint < 0x20 ||
                    codePoint == 0x7f ||
                    codePoint >= 0x80 && !unicodeAllowed ||
                    codePoint.toChar() in encodeSet ||
                    codePoint == '%'.code &&
                    (!alreadyEncoded || strict && !input.isPercentEncoded(i, limit))) {
                    // Percent encode this character.
                    if (encodedCharBuffer == null) {
                        encodedCharBuffer = Buffer()
                    }

                    if (charset == null || charset == StandardCharsets.UTF_8) {
                        encodedCharBuffer.writeUtf8CodePoint(codePoint)
                    } else {
                        encodedCharBuffer.writeString(input, i, i + Character.charCount(codePoint), charset)
                    }

                    while (!encodedCharBuffer.exhausted()) {
                        val b = encodedCharBuffer.readByte().toInt() and 0xff
                        writeByte('%'.code)
                        writeByte(HEX_DIGITS[b shr 4 and 0xf].code)
                        writeByte(HEX_DIGITS[b and 0xf].code)
                    }
                } else {
                    // This character doesn't need encoding. Just copy it over.
                    writeUtf8CodePoint(codePoint)
                }
                i += Character.charCount(codePoint)
            }
        }
    }
}
