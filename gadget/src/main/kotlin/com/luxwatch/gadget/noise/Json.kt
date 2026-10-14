package com.luxwatch.gadget.noise

/**
 * Minimal regex-based JSON field extraction for flat protocol messages.
 * Not a general parser: it returns the first match of [key] anywhere in the text.
 */
internal object Json {
    fun str(json: String, key: String): String? {
        // Find the key with a short regex, then scan the value by hand: a regex over
        // the whole value recurses per character and overflows on long strings.
        val m = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"").find(json) ?: return null
        var i = m.range.last + 1
        val start = i
        while (i < json.length) {
            when (json[i]) {
                '\\' -> i += 2
                '"' -> return unescape(json.substring(start, i))
                else -> i++
            }
        }
        return null
    }

    fun long(json: String, key: String): Long? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()

    /** Returns the `{…}` object value of [key], or "" if absent. */
    fun obj(json: String, key: String): String {
        val m = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\\{").find(json) ?: return ""
        var d = 0; var k = m.range.last
        do {
            when (json[k]) { '{' -> d++; '}' -> d-- }
            k++
        } while (d > 0 && k < json.length)
        return json.substring(m.range.last, k)
    }

    /**
     * Decodes the escapes in a JSON string body (without the quotes), including
     * \uXXXX; surrogate pairs come out as two chars, which is a valid UTF-16 pair.
     * Malformed escapes are kept verbatim.
     */
    fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                sb.append(c); i++; continue
            }
            when (val e = s[i + 1]) {
                '"', '\\', '/' -> { sb.append(e); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'u' -> {
                    val hex = if (i + 6 <= s.length) s.substring(i + 2, i + 6) else ""
                    val code = if (hex.length == 4 && hex.all { it.isHexDigit() }) hex.toInt(16) else null
                    if (code != null) {
                        sb.append(code.toChar()); i += 6
                    } else {
                        sb.append(c); i++
                    }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    fun escape(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }
}
