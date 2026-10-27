package com.luxwatch.gadget.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechChunkerTest {
    /** Feeds [reply] a few characters at a time, as Muse streams it, then finishes. */
    private fun stream(reply: String, step: Int = 3): List<String> {
        val c = SpeechChunker()
        val out = mutableListOf<String>()
        var i = step
        while (i < reply.length) {
            out += c.next(reply.substring(0, i))
            i += step
        }
        out += c.next(reply, final = true)
        return out
    }

    @Test
    fun speaksEachSentenceOnceItEnds() {
        val reply = "Tomorrow looks sunny. Expect a high of 18°! Want the hourly forecast?"
        assertEquals(
            listOf("Tomorrow looks sunny.", "Expect a high of 18°!", "Want the hourly forecast?"),
            stream(reply, step = 1),
        )
    }

    @Test
    fun theFirstSentenceIsReadyBeforeTheRestArrives() {
        val c = SpeechChunker()
        assertEquals(emptyList<String>(), c.next("Sure"))
        assertEquals(emptyList<String>(), c.next("Sure."))      // could still be "Sure.com"
        assertEquals(listOf("Sure."), c.next("Sure. Here"))
        assertEquals(emptyList<String>(), c.next("Sure. Here it is"))
        assertEquals(listOf("Here it is"), c.next("Sure. Here it is", final = true))
    }

    @Test
    fun nothingIsLostOrRepeated() {
        val reply = "Okay — 3.5 km at 7 a.m.\nThen \"rest.\" Done (really). Last bit"
        val pieces = stream(reply, step = 2)
        assertEquals(reply.split(Regex("\\s+")).joinToString(" "), pieces.joinToString(" ").split(Regex("\\s+")).joinToString(" "))
    }

    @Test
    fun aLongSentenceStartsAtAPause() {
        val c = SpeechChunker()
        val long = "The meeting with the design team moved to Thursday afternoon, and the review " +
            "of the new onboarding flow is now on Friday morning before lunch"
        val first = c.next(long)
        assertEquals(listOf("The meeting with the design team moved to Thursday afternoon,"), first)
    }
}
