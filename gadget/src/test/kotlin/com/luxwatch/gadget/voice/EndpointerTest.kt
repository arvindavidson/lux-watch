package com.luxwatch.gadget.voice

import com.luxwatch.gadget.voice.Endpointer.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointerTest {
    /** Feeds levels every 50 ms; returns the first non-LISTEN decision and when it came. */
    private fun run(level: (Long) -> Float, untilMs: Long = 20_000): Pair<Decision, Long> {
        val ep = Endpointer()
        var t = 0L
        while (t <= untilMs) {
            val d = ep.update(level(t), t)
            if (d != Decision.LISTEN) return d to t
            t += 50
        }
        return Decision.LISTEN to t
    }

    @Test
    fun silenceGivesUp() {
        val (d, t) = run({ 0.01f })
        assertEquals(Decision.GIVE_UP, d)
        assertEquals(6_000L, t)
    }

    @Test
    fun sendsAfterSpeechThenSilence() {
        val (d, t) = run({ if (it in 500..2_500) 0.4f else 0.01f })
        assertEquals(Decision.SEND, d)
        assertTrue("sent at $t", t in 3_600..3_800)
    }

    @Test
    fun pausesMidSentenceDoNotSend() {
        // Two words with a 600 ms gap, then quiet.
        val (d, t) = run({ if (it in 500..1_200 || it in 1_800..3_000) 0.3f else 0.01f })
        assertEquals(Decision.SEND, d)
        assertTrue("sent at $t", t > 4_000)
    }

    @Test
    fun aClickIsNotSpeech() {
        val (d, _) = run({ if (it in 1_000..1_100) 0.9f else 0.01f })
        assertEquals(Decision.GIVE_UP, d)
    }

    @Test
    fun theListenBuzzIsIgnored() {
        val (d, _) = run({ if (it < 250) 0.9f else 0.01f })
        assertEquals(Decision.GIVE_UP, d)
    }

    @Test
    fun noisyRoomStillEnds() {
        // Steady background at 0.12, speech at 0.45, back to background.
        val (d, t) = run({ if (it in 2_000..4_000) 0.45f else 0.12f })
        assertEquals(Decision.SEND, d)
        assertTrue("sent at $t", t in 5_000..5_400)
    }

    @Test
    fun continuousSpeechKeepsListening() {
        val (d, _) = run({ 0.4f }, untilMs = 15_000)
        assertEquals(Decision.LISTEN, d)
    }
}
