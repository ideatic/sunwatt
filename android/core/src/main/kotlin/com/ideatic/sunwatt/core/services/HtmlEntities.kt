package com.ideatic.sunwatt.core.services

/** Minimal HTML entity decoder. Decodes in a single pass, so `&amp;quot;` becomes `&quot;`. */
object HtmlEntities {
    private val named: Map<String, String> = mapOf(
        "quot" to "\"", "amp" to "&", "lt" to "<", "gt" to ">", "apos" to "'", "nbsp" to " ",
    )

    /** Decodes named (`&quot;`), decimal (`&#39;`) and hex (`&#x27;`) entities. Unknown entities are left untouched. */
    fun unescape(string: String): String {
        if (!string.contains('&')) return string
        val result = StringBuilder(string.length)
        var index = 0
        while (index < string.length) {
            val ch = string[index]
            if (ch == '&') {
                // The ';' must be within the 12 characters that start at the '&'.
                val limit = minOf(index + 12, string.length)
                val semi = string.indexOf(';', index).takeIf { it in index until limit }
                val decoded = semi?.let { decode(string.substring(index + 1, it)) }
                if (semi != null && decoded != null) {
                    result.append(decoded)
                    index = semi + 1
                    continue
                }
            }
            result.append(ch)
            index++
        }
        return result.toString()
    }

    private fun decode(body: String): String? {
        if (!body.startsWith("#")) return named[body]
        val digits = body.drop(1)
        val value = if (digits.startsWith("x") || digits.startsWith("X")) parseUnsigned(digits.drop(1), 16) else parseUnsigned(digits, 10)
        // Surrogates and values above U+10FFFF are not scalars.
        if (value == null || value > 0x10FFFF || value in 0xD800..0xDFFF) return null
        return String(Character.toChars(value))
    }

    /** ASCII digits only, an optional leading "+", no overflow; null otherwise. */
    private fun parseUnsigned(text: String, radix: Int): Int? {
        val digits = text.removePrefix("+")
        if (digits.isEmpty()) return null
        var value = 0L
        for (c in digits) {
            val d = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> return null
            }
            if (d >= radix) return null
            value = value * radix + d
            if (value > UInt.MAX_VALUE.toLong()) return null
        }
        return if (value > Int.MAX_VALUE) null else value.toInt()
    }
}
