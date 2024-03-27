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

import okcronet.http.HttpUrl.Companion.canonicalize
import okcronet.http.MediaType.Companion.toMediaType
import okio.Buffer
import okio.BufferedSink
import java.nio.charset.Charset


/**
 * @author 李沐阳
 * @date 2023/2/25
 * @description
 */
class FormBody internal constructor(
    private val encodedNames: List<String>,
    private val encodedValues: List<String>
) : RequestBody() {

    /** The number of key-value pairs in this form-encoded body. */
    val size: Int
        get() = encodedNames.size

    private var contentLength = -1L

    fun encodedName(index: Int) = encodedNames[index]

    fun name(index: Int) = encodedName(index)

    fun encodedValue(index: Int) = encodedValues[index]

    fun value(index: Int) = encodedValue(index)

    override fun contentType(): MediaType = CONTENT_TYPE

    override fun length(): Long {
        if (contentLength == -1L) {
            contentLength = writeOrCountBytes(null)
        }
        return contentLength
    }

    override fun writeTo(sink: BufferedSink) {
        writeOrCountBytes(sink)
    }

    private fun writeOrCountBytes(sink: BufferedSink?): Long {
        val countBytes = sink == null

        var byteCount = 0L
        val buffer: Buffer = if (countBytes) Buffer() else sink!!.buffer

        for (i in encodedNames.indices) {
            if (i > 0) buffer.writeByte('&'.code)
            buffer.writeUtf8(encodedNames[i])
            buffer.writeByte('='.code)
            buffer.writeUtf8(encodedValues[i])
        }

        if (countBytes) {
            byteCount = buffer.size
            buffer.clear()
        }

        return byteCount
    }

    class Builder @JvmOverloads constructor(private val charset: Charset? = null) {
        private val names = mutableListOf<String>()
        private val values = mutableListOf<String>()

        fun add(name: String, value: String) = apply {
            names += name.canonicalize(
                encodeSet = HttpUrl.FORM_ENCODE_SET,
                plusIsSpace = true,
                charset = charset
            )
            values += value.canonicalize(
                encodeSet = HttpUrl.FORM_ENCODE_SET,
                plusIsSpace = true,
                charset = charset
            )
        }

        fun addEncoded(name: String, value: String) = apply {
            names += name.canonicalize(
                encodeSet = HttpUrl.FORM_ENCODE_SET,
                alreadyEncoded = true,
                plusIsSpace = true,
                charset = charset
            )
            values += value.canonicalize(
                encodeSet = HttpUrl.FORM_ENCODE_SET,
                alreadyEncoded = true,
                plusIsSpace = true,
                charset = charset
            )
        }

        fun build(): FormBody = FormBody(names, values)
    }

    companion object {
        private val CONTENT_TYPE: MediaType = "application/x-www-form-urlencoded".toMediaType()
    }
}