package com.luxwatch.gadget.noise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkTokenTest {
    // Synthetic: 32 zero bytes base64url-encoded (canonical final char 'A').
    private val valid = "mgst_" + "A".repeat(43)

    @Test
    fun acceptsCanonicalToken() {
        assertTrue(SdkToken.isValid(valid))
        assertTrue(SdkToken.isValid("mgst_" + "-_09azAZ".repeat(5) + "abw"))
    }

    @Test
    fun rejectsMalformedTokens() {
        assertFalse(SdkToken.isValid(valid.dropLast(1)))          // too short
        assertFalse(SdkToken.isValid(valid + "A"))                // too long
        assertFalse(SdkToken.isValid(valid.dropLast(1) + "B"))    // non-canonical last char
        assertFalse(SdkToken.isValid("mgsx_" + "A".repeat(43)))   // wrong prefix
        assertFalse(SdkToken.isValid(" $valid"))                  // stray whitespace
        assertFalse(SdkToken.isValid(valid.replaceRange(10, 11, "+"))) // not base64url
    }

    @Test
    fun findsTheTokenInPastedText() {
        assertEquals(valid, SdkToken.find(valid))
        assertEquals(valid, SdkToken.find("  $valid \n"))
        assertEquals(valid, SdkToken.find("Your token: $valid (keep it secret)"))
    }

    @Test
    fun findRejectsNoneTwoOrPartial() {
        assertNull(SdkToken.find("no token here"))
        assertNull(SdkToken.find("$valid $valid"))           // ambiguous
        assertNull(SdkToken.find(valid + "A"))               // too long, not a prefix match
        assertNull(SdkToken.find(valid.dropLast(1) + "B"))   // non-canonical
    }
}
