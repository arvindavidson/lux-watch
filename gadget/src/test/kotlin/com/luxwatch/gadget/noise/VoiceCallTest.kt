package com.luxwatch.gadget.noise

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** A whole realtime call against a fake VM, following voice_call.py. Synthetic audio only. */
class VoiceCallTest {
    private fun response(status: Int, end: Boolean = false) =
        NoiseTransport.StreamEvent.Response(NoiseTransport.AppResponse(status, emptyList(), ByteArray(0), end))

    private fun line(json: String) = NoiseTransport.StreamEvent.BodyChunk((json + "\n").toByteArray(), false)

    /** 40 ms of a loud square wave: audible. */
    private val tone = ByteArray(VoiceCall.FRAME_BYTES).also {
        for (i in 0 until VoiceCall.FRAME_SAMPLES) {
            val v = if ((i / 20) % 2 == 0) 8000 else -8000
            it[2 * i] = v.toByte(); it[2 * i + 1] = (v shr 8).toByte()
        }
    }

    private fun FakeTransport.events(path: String): List<JSONObject> =
        String(body(path)).split("\n").filter { it.isNotBlank() }.map { JSONObject(it) }

    @Test
    fun admitsSpeaksPlaysAndEndsOnMusesGoodbye() = runBlocking {
        val t = FakeTransport()
        val played = mutableListOf<ByteArray>()
        val speaker = object : VoiceCall.Speaker {
            override fun write(pcm: ByteArray) { synchronized(played) { played += pcm } }
            override fun flush() {}
        }
        var frames = 0
        val mic = VoiceCall.Mic { delay(40); frames++; if (frames <= 3) tone else null }
        val call = VoiceCall(t, deviceId = "node-1", idleTimeoutMs = 5_000)
        val run = async { call.run(mic, speaker) }

        withTimeout(2_000) { while (t.stream("/voice/realtime?call_id=${call.callId}") == null) delay(2) }
        val media = t.stream("/voice/realtime?call_id=${call.callId}")!!
        assertEquals("application/x-ndjson; charset=utf-8", media.headers["Content-Type"])
        val first = t.events(media.path)
        assertEquals("hatch.voice.call.start", first[0].getString("type"))
        assertEquals("mcu-wearable", first[0].getString("device"))
        assertEquals("node-1", first[0].getString("device_id"))
        assertEquals("audio", first[0].getString("call_media_type"))
        assertEquals(1, first[1].getInt("end_call_stop"))

        media.ch.send(response(200))
        media.ch.send(line("""{"type":"hatch.voice.session","call_id":"${call.callId}","session_id":"s-1","thread_id":"th-1"}"""))
        // The mic goes up once the call is ready.
        withTimeout(2_000) { while (t.events(media.path).none { it.getString("type") == "input_audio_buffer.append" }) delay(5) }
        val append = t.events(media.path).first { it.getString("type") == "input_audio_buffer.append" }
        assertEquals(VoiceCall.FRAME_BYTES, Base64.getDecoder().decode(append.getString("audio")).size)

        // Muse answers; the watch plays it.
        val audio = Base64.getEncoder().encodeToString(tone + tone)
        media.ch.send(line("""{"type":"response.output_audio.delta","delta":"$audio"}"""))
        withTimeout(2_000) { while (synchronized(played) { played.size } < 2) delay(5) }

        // Muse says goodbye: the watch drains, then ends the call itself.
        media.ch.send(line("""{"type":"conversation.item.added","item":{"type":"session_control","name":"stop","call_id":"ctl-1"}}"""))
        withTimeout(3_000) { while (t.stream("/voice/end-call") == null) delay(5) }
        val end = JSONObject(String(t.body("/voice/end-call")))
        assertEquals(call.callId, end.getString("call_id"))
        assertEquals("th-1", end.getString("thread_id"))
        assertTrue(end.getString("call_started_at").endsWith("+00:00"))
        t.stream("/voice/end-call")!!.ch.send(
            NoiseTransport.StreamEvent.Response(
                NoiseTransport.AppResponse(200, emptyList(), """{"ok":true,"result":{"ok":true}}""".toByteArray(), true),
            ),
        )
        media.ch.send(NoiseTransport.StreamEvent.BodyChunk(ByteArray(0), true))
        assertEquals("remote_stop", withTimeout(5_000) { run.await() })
        assertTrue(call.endCallAcknowledged)
        assertEquals("s-1", call.sessionId)
    }

    @Test
    fun readsMusesAudioFormats() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        val cavd = JSONObject("""{"type":"continuous_audio_video.delta","response_id":"r","audio_delta":{"format":{"type":"audio/pcm","rate":24000},"audio_frames":"$b64"}}""")
        org.junit.Assert.assertArrayEquals(pcm, VoiceCall.audioFrom(cavd))
        val wrongRate = JSONObject("""{"type":"continuous_audio_video.delta","audio_delta":{"format":{"rate":16000},"audio_frames":"$b64"}}""")
        assertTrue(runCatching { VoiceCall.audioFrom(wrongRate) }.isFailure)
        // The SDK's fixture: events with no audio, and unknown ones, carry none.
        val fixture = JSONObject(String(javaClass.getResourceAsStream("/realtime_voice.json")!!.readBytes()))
        val events = fixture.getJSONArray("events")
        for (i in 0 until events.length()) assertEquals(null, VoiceCall.audioFrom(events.getJSONObject(i)))
    }
}
