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

import java.nio.charset.Charset
import java.util.Locale
import java.util.regex.Pattern


class MediaType private constructor(
    private val mediaType: String,

    /**
     * Returns the high-level media type, such as "text", "image", "audio", "video", or "application".
     */
    val type: String,

    /**
     * Returns a specific media subtype, such as "plain" or "png", "mpeg", "mp4" or "xml".
     */
    val subtype: String,

    /** Alternating parameter names with their values, like `["charset", "utf-8"]`. */
    private val parameterNamesAndValues: Array<String>
) {

    /**
     * Returns the charset of this media type, or [defaultValue] if either this media type doesn't
     * specify a charset, of it its charset is unsupported by the current runtime.
     */
    @JvmOverloads
    fun charset(defaultValue: Charset? = null): Charset? {
        val charset = parameter("charset") ?: return defaultValue
        return try {
            Charset.forName(charset)
        } catch (_: IllegalArgumentException) {
            defaultValue // This charset is invalid or unsupported. Give up.
        }
    }

    /**
     * Returns the parameter [name] of this media type, or null if this media type does not define
     * such a parameter.
     */
    fun parameter(name: String): String? {
        for (i in parameterNamesAndValues.indices step 2) {
            if (parameterNamesAndValues[i].equals(name, ignoreCase = true)) {
                return parameterNamesAndValues[i + 1]
            }
        }
        return null
    }

    /**
     * Returns the encoded media type, like "text/plain; charset=utf-8", appropriate for use in a
     * Content-Type header.
     */
    override fun toString() = mediaType

    override fun equals(other: Any?) = other is MediaType && other.mediaType == mediaType

    override fun hashCode() = mediaType.hashCode()

    companion object {
        private const val TOKEN = "([a-zA-Z0-9-!#$%&'*+.^_`{|}~]+)"
        private const val QUOTED = "\"([^\"]*)\""
        private val TYPE_SUBTYPE = Pattern.compile("$TOKEN/$TOKEN")
        private val PARAMETER = Pattern.compile(";\\s*(?:$TOKEN=(?:$TOKEN|$QUOTED))?")

        /**
         * Returns a media type for this string.
         *
         * @throws IllegalArgumentException if this is not a well-formed media type.
         */
        @JvmStatic
        fun String.toMediaType(): MediaType {
            val typeSubtype = TYPE_SUBTYPE.matcher(this)
            require(typeSubtype.lookingAt()) { "No subtype found for: \"$this\"" }
            val type = typeSubtype.group(1)?.lowercase(Locale.US) ?: throw IllegalArgumentException()
            val subtype = typeSubtype.group(2)?.lowercase(Locale.US) ?: throw IllegalArgumentException()

            val parameterNamesAndValues = mutableListOf<String>()
            val parameter = PARAMETER.matcher(this)
            var s = typeSubtype.end()
            while (s < length) {
                parameter.region(s, length)
                require(parameter.lookingAt()) {
                    "Parameter is not formatted correctly: \"${substring(s)}\" for: \"$this\""
                }

                val name = parameter.group(1)
                if (name == null) {
                    s = parameter.end()
                    continue
                }

                val token = parameter.group(2)
                val value = when {
                    token == null -> {
                        // Value is "double-quoted". That's valid and our regex group already strips the quotes.
                        parameter.group(3)
                    }
                    token.startsWith("'") && token.endsWith("'") && token.length > 2 -> {
                        // If the token is 'single-quoted' it's invalid! But we're lenient and strip the quotes.
                        token.substring(1, token.length - 1)
                    }
                    else -> token
                }

                parameterNamesAndValues += name
                parameterNamesAndValues += value
                s = parameter.end()
            }

            return MediaType(this, type, subtype, parameterNamesAndValues.toTypedArray())
        }

        /** Returns a media type for this, or null if this is not a well-formed media type. */
        @JvmStatic
        fun String.toMediaTypeOrNull(): MediaType? {
            return try {
                toMediaType()
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    }
}
