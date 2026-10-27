package com.luxwatch.gadget.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** After the caller interrupts, old audio is dropped and the new reply waits for its turn (voice_gate.py). */
class ReplyGateTest {
    private fun ev(json: String) = JSONObject(json)
    private fun audio(response: String) =
        ev("""{"type":"continuous_audio_video.delta","response_id":"$response","audio_delta":{"audio_frames":""}}""")

    @Test
    fun interruptedAudioIsDroppedAndTheNextReplyWaitsForItsTurn() {
        val g = ReplyGate()
        assertEquals(ReplyGate.Verdict.PLAY, g.audio(audio("old")))
        g.event(ev("""{"type":"input_audio_buffer.speech_started","item_id":"in-2"}"""))
        assertEquals(ReplyGate.Verdict.DROP, g.audio(audio("old")))      // the interrupted reply
        assertEquals(ReplyGate.Verdict.HOLD, g.audio(audio("new")))      // unconfirmed: held
        g.event(ev("""{"type":"hatch.voice.turn_started","item_id":"in-2","submission_message_id":"m-2"}"""))
        assertEquals(ReplyGate.Verdict.PLAY, g.audio(audio("new")))      // the caller's turn: plays
    }

    @Test
    fun aReplyToSomeoneElsesTurnStaysOut() {
        val g = ReplyGate()
        g.event(ev("""{"type":"input_audio_buffer.speech_started","item_id":"in-2"}"""))
        assertEquals(ReplyGate.Verdict.HOLD, g.audio(audio("r")))
        g.event(ev("""{"type":"hatch.voice.turn_started","item_id":"other","submission_message_id":"m-9"}"""))
        assertEquals(ReplyGate.Verdict.DROP, g.audio(audio("r")))
    }
}
