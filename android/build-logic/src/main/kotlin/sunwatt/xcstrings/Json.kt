package sunwatt.xcstrings

/**
 * Minimal JSON reader (objects, arrays, strings, numbers, booleans, null).
 * Enough for `.xcstrings` files, so the build logic needs no third-party dependency.
 * Objects keep insertion order.
 */
internal object Json {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue()
        parser.skipWhitespace()
        parser.expectEnd()
        return value
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun expectEnd() {
            if (i != s.length) fail("trailing characters")
        }

        fun readValue(): Any? {
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("unexpected '$c'")
            }
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++
            skipWhitespace()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected a string key")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') fail("expected ':'")
                i++
                skipWhitespace()
                map[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            i++
            skipWhitespace()
            if (peek() == ']') { i++; return list }
            while (true) {
                skipWhitespace()
                list.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            val sb = StringBuilder()
            i++ // opening quote
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad \\u escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("unexpected token")
            i += word.length
            return value
        }

        private fun peek(): Char = if (i < s.length) s[i] else fail("unexpected end")

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("Invalid JSON at offset $i: $message")
    }
}
