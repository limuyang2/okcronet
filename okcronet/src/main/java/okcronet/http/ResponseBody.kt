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

import okcronet.http.MediaType.Companion.toMediaTypeOrNull
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import java.io.*
import java.nio.charset.Charset

/**
 * @author 李沐阳
 * @date 2023/2/27
 * @description
 */
abstract class ResponseBody: Closeable {
    /** 多次调用 [charStream] 必须返回同一个实例。 */
    private var reader: Reader? = null

    abstract fun contentType(): MediaType?

    /**
     * 返回 [bytes] 或 [byteStream] 的大小.
     * -1 为未知.
     * <p>
     * Returns the size of [bytes] or [byteStream] .
     * -1 for unknown.
     */
    abstract fun contentLength(): Long

    abstract fun source(): BufferedSource

    fun byteStream(): InputStream = source().inputStream()

    /**
     * 以字节数组的形式返回 body
     *
     * 此方法将整个响应体加载到内存中。如果响应体非常大，则可能会触发[OutOfMemoryError]。
     * 如果可能，请选择流式传输响应主体。
     *
     * Returns the response as a [ByteArray].
     *
     * This method loads entire response body into memory. If the response body is very large this
     * may trigger an [OutOfMemoryError]. Prefer to stream the response body if this is a
     * possibility for your response.
     */
    @Throws(IOException::class)
    fun bytes() = consumeSource(BufferedSource::readByteArray) { it.size }

    /**
     * 以ByteString的形式返回响应。
     * 此方法将整个 body 响应体加载到内存中。如果响应体非常大，这可能会触发[OutOfMemoryError]。
     *
     * Returns the response as a [ByteString].
     *
     * This method loads entire response body into memory. If the response body is very large this
     * may trigger an [OutOfMemoryError]. Prefer to stream the response body if this is a
     * possibility for your response.
     */
    @Throws(IOException::class)
    fun byteString() = consumeSource(BufferedSource::readByteString) { it.size }

    private inline fun <T : Any> consumeSource(
        consumer: (BufferedSource) -> T,
        sizeMapper: (T) -> Int
    ): T {
        val contentLength = contentLength()
        if (contentLength > Int.MAX_VALUE) {
            throw IOException("Cannot buffer entire body for content length: $contentLength")
        }

        val bytes = source().use(consumer)
        val size = sizeMapper(bytes)
        if (contentLength != -1L && contentLength != size.toLong()) {
            throw IOException("Content-Length ($contentLength) and stream length ($size) disagree")
        }
        return bytes
    }

    /**
     * 以字符流的形式返回响应。
     *
     * Returns the response as a character stream.
     *
     * If the response starts with a
     * [Byte Order Mark (BOM)](https://en.wikipedia.org/wiki/Byte_order_mark), it is consumed and
     * used to determine the charset of the response bytes.
     *
     * Otherwise if the response has a `Content-Type` header that specifies a charset, that is used
     * to determine the charset of the response bytes.
     *
     * Otherwise the response bytes are decoded as UTF-8.
     */
    fun charStream(): Reader = reader ?: BomAwareReader(source(), charset()).also {
        reader = it
    }

    /**
     * 以字符串形式返回响应。
     *
     * Returns the response as a string.
     *
     * If the response starts with a
     * [Byte Order Mark (BOM)](https://en.wikipedia.org/wiki/Byte_order_mark), it is consumed and
     * used to determine the charset of the response bytes.
     *
     * Otherwise if the response has a `Content-Type` header that specifies a charset, that is used
     * to determine the charset of the response bytes.
     *
     * Otherwise the response bytes are decoded as UTF-8.
     *
     * This method loads entire response body into memory. If the response body is very large this
     * may trigger an [OutOfMemoryError]. Prefer to stream the response body if this is a
     * possibility for your response.
     */
    @Throws(IOException::class)
    fun string(): String = source().use { source ->
        source.readString(charset = source.readBomAsCharset(charset()))
    }

    private fun charset() = contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8

    override fun close() = source().closeQuietly()

    internal class BomAwareReader(
        private val source: BufferedSource,
        private val charset: Charset
    ) : Reader() {

        private var closed: Boolean = false
        private var delegate: Reader? = null

        @Throws(IOException::class)
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (closed) throw IOException("Stream closed")

            val finalDelegate = delegate ?: InputStreamReader(
                source.inputStream(),
                source.readBomAsCharset(charset)).also {
                delegate = it
            }
            return finalDelegate.read(cbuf, off, len)
        }

        @Throws(IOException::class)
        override fun close() {
            closed = true
            delegate?.close() ?: run { source.close() }
        }
    }

    companion object {
        /**
         * Returns a new response body that transmits this string. If [contentType] is non-null and
         * lacks a charset, this will use UTF-8.
         */
        fun String.toResponseBody(contentType: MediaType? = null): ResponseBody {
            var charset: Charset = Charsets.UTF_8
            var finalContentType: MediaType? = contentType
            if (contentType != null) {
                val resolvedCharset = contentType.charset()
                if (resolvedCharset == null) {
                    charset = Charsets.UTF_8
                    finalContentType = "$contentType; charset=utf-8".toMediaTypeOrNull()
                } else {
                    charset = resolvedCharset
                }
            }
            val buffer = Buffer().writeString(this, charset)
            return buffer.asResponseBody(finalContentType, buffer.size)
        }

        /** Returns a new response body that transmits this byte array. */
        fun ByteArray.toResponseBody(contentType: MediaType? = null): ResponseBody {
            return Buffer()
                .write(this)
                .asResponseBody(contentType, size.toLong())
        }

        /** Returns a new response body that transmits this byte string. */
        fun ByteString.toResponseBody(contentType: MediaType? = null): ResponseBody {
            return Buffer()
                .write(this)
                .asResponseBody(contentType, size.toLong())
        }

        /** Returns a new response body that transmits this source. */
        fun BufferedSource.asResponseBody(
            contentType: MediaType? = null,
            contentLength: Long = -1L
        ): ResponseBody = object : ResponseBody() {
            override fun contentType() = contentType

            override fun contentLength() = contentLength

            override fun source() = this@asResponseBody
        }

    }
}