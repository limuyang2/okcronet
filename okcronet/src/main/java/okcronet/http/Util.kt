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

import okcronet.DirectExecutor
import okcronet.UploadDataHelper
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.Options
import org.chromium.net.UploadDataProvider
import org.chromium.net.UrlRequest
import java.io.Closeable
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.*


private val VERIFY_AS_IP_ADDRESS: Regex
    get() = "([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)".toRegex()

/** Byte order marks. */
private val UNICODE_BOMS = Options.of(
    "efbbbf".decodeHex(), // UTF-8
    "feff".decodeHex(), // UTF-16BE
    "fffe".decodeHex(), // UTF-16LE
    "0000ffff".decodeHex(), // UTF-32BE
    "ffff0000".decodeHex() // UTF-32LE
)

internal fun checkOffsetAndCount(arrayLength: Long, offset: Long, count: Long) {
    if (offset or count < 0L || offset > arrayLength || arrayLength - offset < count) {
        throw ArrayIndexOutOfBoundsException()
    }
}

/**
 * Returns the index of the first character in this string that contains a character in
 * [delimiters]. Returns endIndex if there is no such character.
 */
internal fun String.delimiterOffset(delimiters: String, startIndex: Int = 0, endIndex: Int = length): Int {
    for (i in startIndex until endIndex) {
        if (this[i] in delimiters) return i
    }
    return endIndex
}


/**
 * Returns the index of the first character in this string that is [delimiter]. Returns [endIndex]
 * if there is no such character.
 */
internal fun String.delimiterOffset(delimiter: Char, startIndex: Int = 0, endIndex: Int = length): Int {
    for (i in startIndex until endIndex) {
        if (this[i] == delimiter) return i
    }
    return endIndex
}

internal fun String.indexOfControlOrNonAscii(): Int {
    for (i in indices) {
        val c = this[i]
        if (c <= '\u001f' || c >= '\u007f') {
            return i
        }
    }
    return -1
}

/** Returns true if this string is not a host name and might be an IP address. */
internal fun String.canParseAsIpAddress(): Boolean {
    return VERIFY_AS_IP_ADDRESS.matches(this)
}


/**
 * Increments [startIndex] until this string is not ASCII whitespace. Stops at [endIndex].
 */
internal fun String.indexOfFirstNonAsciiWhitespace(startIndex: Int = 0, endIndex: Int = length): Int {
    for (i in startIndex until endIndex) {
        when (this[i]) {
            '\t', '\n', '\u000C', '\r', ' ' -> Unit
            else -> return i
        }
    }
    return endIndex
}

/**
 * Decrements [endIndex] until `input[endIndex - 1]` is not ASCII whitespace. Stops at [startIndex].
 */
internal fun String.indexOfLastNonAsciiWhitespace(startIndex: Int = 0, endIndex: Int = length): Int {
    for (i in endIndex - 1 downTo startIndex) {
        when (this[i]) {
            '\t', '\n', '\u000C', '\r', ' ' -> Unit
            else -> return i + 1
        }
    }
    return startIndex
}

internal fun Char.parseHexDigit(): Int = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    in 'A'..'F' -> this - 'A' + 10
    else -> -1
}

internal infix fun Byte.and(mask: Int): Int = toInt() and mask
internal infix fun Short.and(mask: Int): Int = toInt() and mask
internal infix fun Int.and(mask: Long): Long = toLong() and mask


/** Equivalent to `string.substring(startIndex, endIndex).trim()`. */
internal fun String.trimSubstring(startIndex: Int = 0, endIndex: Int = length): String {
    val start = indexOfFirstNonAsciiWhitespace(startIndex, endIndex)
    val end = indexOfLastNonAsciiWhitespace(start, endIndex)
    return substring(start, end)
}


internal fun isSensitiveHeader(name: String): Boolean {
    return name.equals("Authorization", ignoreCase = true) ||
            name.equals("Cookie", ignoreCase = true) ||
            name.equals("Proxy-Authorization", ignoreCase = true) ||
            name.equals("Set-Cookie", ignoreCase = true)
}

internal fun format(format: String, vararg args: Any): String {
    return String.format(Locale.US, format, *args)
}

@Throws(IOException::class)
internal fun BufferedSource.readBomAsCharset(default: Charset): Charset {
    return when (select(UNICODE_BOMS)) {
        0 -> StandardCharsets.UTF_8
        1 -> StandardCharsets.UTF_16BE
        2 -> StandardCharsets.UTF_16LE
        3 -> Charsets.UTF_32BE
        4 -> Charsets.UTF_32LE
        -1 -> default
        else -> throw AssertionError()
    }
}

/** Closes this, ignoring any checked exceptions. */
internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (rethrown: RuntimeException) {
        throw rethrown
    } catch (_: Exception) {
    }
}


fun UrlRequest.Builder.addCookie(cookies: List<Cookie>) : UrlRequest.Builder {
    // 设置 cookie
    if (cookies.isNotEmpty()) {
        val stringBuilder = StringBuilder()
        for (cookie in cookies) {
            stringBuilder.append(cookie.toString()).append(";")
        }
        if (stringBuilder.isNotEmpty()) {
            this.addHeader("Cookie", stringBuilder.toString())
        }
    }
    return this
}

fun UrlRequest.Builder.setRequestBody(body: RequestBody, writeTimeoutMillis: Long = 20_000): UrlRequest.Builder {
    return setUploadDataProvider(body.toUploadDataProvider(writeTimeoutMillis), DirectExecutor.INSTANCE)
}


fun RequestBody.toUploadDataProvider(writeTimeoutMillis: Long = 20_000) : UploadDataProvider {
    return UploadDataHelper.getUploadDataProvider(this, writeTimeoutMillis)
}