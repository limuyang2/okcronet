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

import android.os.ParcelFileDescriptor
import okcronet.http.MediaType.Companion.toMediaTypeOrNull
import okio.*
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/**
 * @author 李沐阳
 * @date 2023/2/21
 * @description
 */
abstract class RequestBody {

    abstract fun contentType(): MediaType?

    @Throws(IOException::class)
    abstract fun length(): Long

    @Throws(IOException::class)
    abstract fun writeTo(sink: BufferedSink)

    companion object {

        val EMPTY_REQUEST_BODY = ByteArray(0).toRequestBody()

        fun File.toRequestBody(contentType: MediaType? = null): RequestBody {
            return FileRequestBody(this, contentType)
        }

        fun ParcelFileDescriptor.toRequestBody(contentType: MediaType? = null): RequestBody {
            val inputStream = ParcelFileDescriptor.AutoCloseInputStream(this)

            return object : RequestBody() {

                override fun contentType() = contentType

                override fun length() = this@toRequestBody.statSize

                override fun writeTo(sink: BufferedSink) {
                    sink.writeAll(inputStream.source())
                }
            }
        }

        fun String.toRequestBody(contentType: MediaType? = null): RequestBody {
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
            val bytes = toByteArray(charset)
            return bytes.toRequestBody(finalContentType, 0, bytes.size)
        }

        fun ByteString.toRequestBody(contentType: MediaType? = null): RequestBody {
            return object : RequestBody() {
                override fun contentType() = contentType

                override fun length() = size.toLong()

                override fun writeTo(sink: BufferedSink) {
                    sink.write(this@toRequestBody)
                }
            }
        }

        fun ByteArray.toRequestBody(
            contentType: MediaType? = null,
            offset: Int = 0,
            byteCount: Int = size
        ): RequestBody {
            checkOffsetAndCount(size.toLong(), offset.toLong(), byteCount.toLong())
            return object : RequestBody() {
                override fun contentType() = contentType

                override fun length() = byteCount.toLong()

                override fun writeTo(sink: BufferedSink) {
                    sink.write(this@toRequestBody, offset, byteCount)
                }
            }
        }

    }
}

