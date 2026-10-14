package com.luxwatch.gadget.avatar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundKeyTest {
    private val n = 100
    private val white = 0xFFFAFAFA.toInt()
    private val grey = 0xFF8A8A90.toInt()

    /** White frame; a grey disc with a white "belly" inside; a white floor patch enclosed at the bottom. */
    private fun scene(): IntArray = IntArray(n * n) { i ->
        val x = i % n
        val y = i / n
        val dx = x - 50
        val dy = y - 45
        when {
            dx * dx + dy * dy < 8 * 8 -> white                       // belly highlight, inside the body
            dx * dx + dy * dy < 30 * 30 -> grey                      // body
            y in 85..95 && x in 40..60 -> white                      // floor, fenced in below
            y in 82..97 && x in 37..63 -> grey                       // feet and tail around it
            else -> white
        }
    }

    private fun rgb(c: Int) = c and 0xFFFFFF

    @Test
    fun removesTheBackgroundButNotWhiteInsideTheBody() {
        val out = BackgroundKey.key(scene(), n, n)
        assertEquals(0, rgb(out[5 * n + 50]))                        // background near the top
        assertEquals(0, rgb(out[50 * n + 3]))                        // background at the side
        assertEquals(white and 0xFFFFFF, rgb(out[45 * n + 50]))      // belly stays white
        assertEquals(grey and 0xFFFFFF, rgb(out[45 * n + 30]))       // body untouched
        assertEquals(0, rgb(out[90 * n + 50]))                       // enclosed floor near the bottom: gone
        assertTrue(out.all { it ushr 24 == 0xFF })                   // opaque
    }

    @Test
    fun edgesFadeToBlack() {
        val solid = IntArray(n * n) { grey }
        val out = BackgroundKey.key(solid, n, n)
        assertEquals(0, rgb(out[0]))                                  // corner: fully faded
        assertEquals(grey and 0xFFFFFF, rgb(out[50 * n + 50]))        // centre: untouched
    }

    @Test
    fun planSamplesAt15FpsAndCapsLength() {
        // 8 s at 24 fps: 120 frames, every 1.6 source frames.
        val p = BackgroundKey.plan(192, 24.0)
        assertEquals(120, p.size)
        assertArrayEquals(intArrayOf(0, 1, 3, 4, 6), p.copyOf(5))
        assertTrue(p.last() < 192)
        // 30 s clip: capped at 10 s.
        assertEquals(150, BackgroundKey.plan(900, 30.0).size)
        // Nothing to read.
        assertEquals(0, BackgroundKey.plan(0, 24.0).size)
    }
}
