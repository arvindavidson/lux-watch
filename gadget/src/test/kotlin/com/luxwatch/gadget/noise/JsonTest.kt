package com.luxwatch.gadget.noise

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {
    @Test
    fun unescapesUnicodeEscapes() {
        assertEquals("café olé", Json.str("""{"text":"caf\u00e9 ol\u00e9"}""", "text"))
        // Surrogate pair → one code point.
        assertEquals("😀", Json.str("""{"text":"\ud83d\ude00"}""", "text"))
    }

    @Test
    fun unescapesSimpleEscapesInOnePass() {
        assertEquals("a\"b\\c/d\ne\tf", Json.unescape("""a\"b\\c\/d\ne\tf"""))
        // An escaped backslash followed by 'n' is a literal backslash + n, not a newline.
        assertEquals("C:\\new", Json.unescape("""C:\\new"""))
    }

    @Test
    fun keepsMalformedEscapesVerbatim() {
        assertEquals("bad\\u12", Json.unescape("""bad\u12"""))
        assertEquals("bad\\u-001", Json.unescape("""bad\u-001"""))
        assertEquals("trailing\\", Json.unescape("trailing\\"))
    }

    @Test
    fun escapeRoundTrips() {
        val s = "say \"hi\"\n\ttab \\ ctrl\u0001"
        assertEquals(s, Json.str("{\"k\":${Json.escape(s)}}", "k"))
    }

    @Test
    fun handlesLongValues() {
        // Long replies / base64 notes used to overflow the regex stack.
        val long = "a\\\"b".repeat(50_000)
        assertEquals("a\"b".repeat(50_000), Json.str("{\"k\":\"$long\",\"n\":1}", "k"))
        assertEquals(null, Json.str("{\"k\":\"unterminated", "k"))
    }
}
