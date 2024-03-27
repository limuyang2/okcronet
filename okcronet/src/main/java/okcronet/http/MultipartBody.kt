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

import okcronet.http.MediaType.Companion.toMediaType
import okio.Buffer
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.*

/**
 * @author 李沐阳
 * @date 2023/2/23
 * @description
 */
class MultipartBody internal constructor(
    private val boundaryByteString: ByteString,
    val type: MediaType,
    val parts: List<Part>
) : RequestBody() {

    private val boundary: String
        get() = boundaryByteString.utf8()

    private val contentType: MediaType =
        "$type; boundary=$boundary".toMediaType()

    private var contentLength = -1L

    override fun contentType(): MediaType = contentType

    override fun length(): Long {
        if (contentLength == -1L) {
            contentLength = writeOrCountBytes(null)
        }
        return contentLength
    }

    override fun writeTo(sink: BufferedSink) {
        writeOrCountBytes(sink)
    }

    @Throws(IOException::class)
    private fun writeOrCountBytes(bufferedSink: BufferedSink?): Long {
        val countBytes = bufferedSink == null

        var sink = bufferedSink
        var byteCount = 0L

        var byteCountBuffer: Buffer? = null
        if (countBytes) {
            byteCountBuffer = Buffer()
            sink = byteCountBuffer
        }

        for (element in parts) {
            val headers = element.headers
            val body = element.body

            sink!!.write(DASHDASH)
            sink.write(boundaryByteString)
            sink.write(CRLF)

            if (headers != null) {
                for (h in 0 until headers.size) {
                    sink.writeUtf8(headers.name(h))
                        .write(COLONSPACE)
                        .writeUtf8(headers.value(h))
                        .write(CRLF)
                }
            }

            val contentType = body.contentType()
            if (contentType != null) {
                sink.writeUtf8("Content-Type: ")
                    .writeUtf8(contentType.toString())
                    .write(CRLF)
            }

            val contentLength = body.length()
            if (contentLength != -1L) {
                sink.writeUtf8("Content-Length: ")
                    .writeDecimalLong(contentLength)
                    .write(CRLF)
            } else if (countBytes) {
                // We can't measure the body's size without the sizes of its components.
                byteCountBuffer!!.clear()
                return -1L
            }

            sink.write(CRLF)

            if (countBytes) {
                byteCount += contentLength
            } else {
                body.writeTo(sink)
            }

            sink.write(CRLF)
        }

        sink!!.write(DASHDASH)
        sink.write(boundaryByteString)
        sink.write(DASHDASH)
        sink.write(CRLF)

        if (countBytes) {
            byteCount += byteCountBuffer!!.size
            byteCountBuffer.clear()
        }

        return byteCount
    }

    class Builder @JvmOverloads constructor(boundary: String = UUID.randomUUID().toString()) {
        private val boundary: ByteString = boundary.encodeUtf8()
        private var type = MIXED
        private val parts = mutableListOf<Part>()

        /**
         * Set the MIME type. Expected values for `type` are [MIXED] (the default), [ALTERNATIVE],
         * [DIGEST], [PARALLEL] and [FORM].
         */
        fun setType(type: MediaType) = apply {
            require(type.type == "multipart") { "multipart != $type" }
            this.type = type
        }

        /** Add a part to the body. */
        fun addPart(headers: Headers, body: RequestBody) = apply {
            addPart(Part.create(headers, body))
        }

        /** Add a form data part to the body. */
        fun addFormDataPart(name: String, value: String) = apply {
            addPart(Part.createFormData(name, value))
        }

        /** Add a form data part to the body. */
        fun addFormDataPart(name: String, filename: String?, body: RequestBody) = apply {
            addPart(Part.createFormData(name, filename, body))
        }

        /** Add a part to the body. */
        fun addPart(part: Part) = apply {
            parts += part
        }

        /** Assemble the specified parts into a request body. */
        fun build(): MultipartBody {
            check(parts.isNotEmpty()) { "Multipart body must have at least one part." }
            return MultipartBody(boundary, type, parts)
        }
    }

    class Part private constructor(
        val headers: Headers?,
        val body: RequestBody
    ) {

        companion object {

            @JvmStatic
            fun create(headers: Headers, body: RequestBody): Part {
                require(headers["Content-Type"] == null) { "Unexpected header: Content-Type" }
                require(headers["Content-Length"] == null) { "Unexpected header: Content-Length" }
                return Part(headers, body)
            }

            @JvmStatic
            fun createFormData(name: String, value: String): Part =
                createFormData(name, null, value.toRequestBody())

            @JvmStatic
            fun createFormData(name: String, filename: String?, body: RequestBody): Part {
                val disposition = buildString {
                    append("form-data; name=")
                    appendQuotedString(name)

                    if (filename != null) {
                        append("; filename=")
                        appendQuotedString(filename)
                    }
                }

                val headers = Headers.Builder()
                    .addUnsafeNonAscii("Content-Disposition", disposition)
                    .build()

                return create(headers, body)
            }
        }
    }

    companion object {
        /**
         * The "mixed" subtype of "multipart" is intended for use when the body parts are independent
         * and need to be bundled in a particular order. Any "multipart" subtypes that an implementation
         * does not recognize must be treated as being of subtype "mixed".
         */
        @JvmField
        val MIXED = "multipart/mixed".toMediaType()

        /**
         * The "multipart/alternative" type is syntactically identical to "multipart/mixed", but the
         * semantics are different. In particular, each of the body parts is an "alternative" version of
         * the same information.
         */
        @JvmField
        val ALTERNATIVE = "multipart/alternative".toMediaType()

        /**
         * This type is syntactically identical to "multipart/mixed", but the semantics are different.
         * In particular, in a digest, the default `Content-Type` value for a body part is changed from
         * "text/plain" to "message/rfc822".
         */
        @JvmField
        val DIGEST = "multipart/digest".toMediaType()

        /**
         * This type is syntactically identical to "multipart/mixed", but the semantics are different.
         * In particular, in a parallel entity, the order of body parts is not significant.
         */
        @JvmField
        val PARALLEL = "multipart/parallel".toMediaType()

        /**
         * The media-type multipart/form-data follows the rules of all multipart MIME data streams as
         * outlined in RFC 2046. In forms, there are a series of fields to be supplied by the user who
         * fills out the form. Each field has a name. Within a given form, the names are unique.
         */
        @JvmField
        val FORM = "multipart/form-data".toMediaType()

        private val COLONSPACE = byteArrayOf(':'.code.toByte(), ' '.code.toByte())
        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        private val DASHDASH = byteArrayOf('-'.code.toByte(), '-'.code.toByte())

        /**
         * Appends a quoted-string to a StringBuilder.
         *
         * RFC 2388 is rather vague about how one should escape special characters in form-data
         * parameters, and as it turns out Firefox and Chrome actually do rather different things, and
         * both say in their comments that they're not really sure what the right approach is. We go
         * with Chrome's behavior (which also experimentally seems to match what IE does), but if you
         * actually want to have a good chance of things working, please avoid double-quotes, newlines,
         * percent signs, and the like in your field names.
         */
        internal fun StringBuilder.appendQuotedString(key: String) {
            append('"')
            for (element in key) {
                when (element) {
                    '\n' -> append("%0A")
                    '\r' -> append("%0D")
                    '"' -> append("%22")
                    else -> append(element)
                }
            }
            append('"')
        }
    }
}