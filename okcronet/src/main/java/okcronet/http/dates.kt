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

import java.text.DateFormat
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal val UTC = TimeZone.getTimeZone("GMT")!!

/** The last four-digit year: "Fri, 31 Dec 9999 23:59:59 GMT". */
internal const val MAX_DATE = 253402300799999L

/**
 * Most websites serve cookies in the blessed format. Eagerly create the parser to ensure such
 * cookies are on the fast path.
 */
private val STANDARD_DATE_FORMAT = object : ThreadLocal<DateFormat>() {
    override fun initialValue(): DateFormat {
        // Date format specified by RFC 7231 section 7.1.1.1.
        return SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            isLenient = false
            timeZone = UTC
        }
    }
}

/** If we fail to parse a date in a non-standard format, try each of these formats in sequence. */
private val BROWSER_COMPATIBLE_DATE_FORMAT_STRINGS = arrayOf(
    // HTTP formats required by RFC2616 but with any timezone.
    "EEE, dd MMM yyyy HH:mm:ss zzz", // RFC 822, updated by RFC 1123 with any TZ
    "EEEE, dd-MMM-yy HH:mm:ss zzz", // RFC 850, obsoleted by RFC 1036 with any TZ.
    "EEE MMM d HH:mm:ss yyyy", // ANSI C's asctime() format
    // Alternative formats.
    "EEE, dd-MMM-yyyy HH:mm:ss z",
    "EEE, dd-MMM-yyyy HH-mm-ss z",
    "EEE, dd MMM yy HH:mm:ss z",
    "EEE dd-MMM-yyyy HH:mm:ss z",
    "EEE dd MMM yyyy HH:mm:ss z",
    "EEE dd-MMM-yyyy HH-mm-ss z",
    "EEE dd-MMM-yy HH:mm:ss z",
    "EEE dd MMM yy HH:mm:ss z",
    "EEE,dd-MMM-yy HH:mm:ss z",
    "EEE,dd-MMM-yyyy HH:mm:ss z",
    "EEE, dd-MM-yyyy HH:mm:ss z",

    /* RI bug 6641315 claims a cookie of this format was once served by www.yahoo.com */
    "EEE MMM d yyyy HH:mm:ss z"
)

private val BROWSER_COMPATIBLE_DATE_FORMATS =
    arrayOfNulls<DateFormat>(BROWSER_COMPATIBLE_DATE_FORMAT_STRINGS.size)

/** Returns the date for this string, or null if the value couldn't be parsed. */
internal fun String.toHttpDateOrNull(): Date? {
    if (isEmpty()) return null

    val position = ParsePosition(0)
    var result = STANDARD_DATE_FORMAT.get()?.parse(this, position)
    if (position.index == length) {
        // STANDARD_DATE_FORMAT must match exactly; all text must be consumed, e.g. no ignored
        // non-standard trailing "+01:00". Those cases are covered below.
        return result
    }
    synchronized(BROWSER_COMPATIBLE_DATE_FORMAT_STRINGS) {
        for (i in BROWSER_COMPATIBLE_DATE_FORMAT_STRINGS.indices) {
            var format: DateFormat? = BROWSER_COMPATIBLE_DATE_FORMATS[i]
            if (format == null) {
                format = SimpleDateFormat(BROWSER_COMPATIBLE_DATE_FORMAT_STRINGS[i], Locale.US).apply {
                    // Set the timezone to use when interpreting formats that don't have a timezone. GMT is
                    // specified by RFC 7231.
                    timeZone = UTC
                }
                BROWSER_COMPATIBLE_DATE_FORMATS[i] = format
            }
            position.index = 0
            result = format.parse(this, position)
            if (position.index != 0) {
                // Something was parsed. It's possible the entire string was not consumed but we ignore
                // that. If any of the BROWSER_COMPATIBLE_DATE_FORMAT_STRINGS ended in "'GMT'" we'd have
                // to also check that position.getIndex() == value.length() otherwise parsing might have
                // terminated early, ignoring things like "+01:00". Leaving this as != 0 means that any
                // trailing junk is ignored.
                return result
            }
        }
    }
    return null
}

/** Returns the string for this date. */
internal fun Date.toHttpDateString(): String = STANDARD_DATE_FORMAT.get()!!.format(this)