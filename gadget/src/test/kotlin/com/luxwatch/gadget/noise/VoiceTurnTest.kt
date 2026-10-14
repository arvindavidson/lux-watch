package com.luxwatch.gadget.noise

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Records what MuseSession sends and lets the test play the server. */
internal class FakeTransport : MuseTransport {
    class Opened(
        val id: Long,
        val path: String,
        val headers: Map<String, String>,
        val ch: Channel<NoiseTransport.StreamEvent>,
    )
    class Sent(val id: Long, val data: ByteArray, val end: Boolean)

    val opened = mutableListOf<Opened>()
    val sent = mutableListOf<Sent>()
    val resets = mutableListOf<Long>()
    private var next = 1L

    @Synchronized
    override fun openStream(
        verb: String,
        path: String,
        headers: List<Pair<String, String>>,
        service: Framing.ServiceType,
    ): Pair<Long, Channel<NoiseTransport.StreamEvent>> {
        val ch = Channel<NoiseTransport.StreamEvent>(Channel.UNLIMITED)
        val o = Opened(next++, path, headers.toMap(), ch)
        opened += o
        return o.id to ch
    }

    @Synchronized
    override fun sendBodyChunk(streamId: Long, data: ByteArray, endBody: Boolean, service: Framing.ServiceType) {
        sent += Sent(streamId, data, endBody)
    }

    @Synchronized
    override fun sendReset(streamId: Long, code: Int) {
        resets += streamId
    }

    @Synchronized
    fun stream(path: String): Opened? = opened.firstOrNull { it.path == path }

    @Synchronized
    fun body(path: String): ByteArray {
        val id = stream(path)!!.id
        return sent.filter { it.id == id }.fold(ByteArray(0)) { acc, s -> acc + s.data }
    }

    @Synchronized
    fun finished(path: String): Boolean = stream(path)?.let { o -> sent.any { it.id == o.id && it.end } } == true
}

private val FAST = MuseSession.TurnTiming(replyTimeoutMs = 2_000, settleMs = 150, busyHoldMs = 700, turnCapMs = 8_000)

private fun ack(status: Int, body: String) =
    NoiseTransport.StreamEvent.Response(NoiseTransport.AppResponse(status, emptyList(), body.toByteArray(), true))

private fun subscribed() =
    NoiseTransport.StreamEvent.Response(NoiseTransport.AppResponse(200, emptyList(), ByteArray(0), false))

private fun ndjson(vararg lines: String) =
    NoiseTransport.StreamEvent.BodyChunk(lines.joinToString("\n", postfix = "\n").toByteArray(), false)

private fun ev(seq: Int?, event: String, payload: String) =
    """{"type":"event",${if (seq != null) "\"seq\":$seq," else ""}"event":"$event","payload":$payload}"""

/** 1 s of 16 kHz PCM16 silence. */
private val ONE_SECOND = ByteArray(VoiceNote.SAMPLE_RATE * 2)

class VoiceTurnTest {

    private suspend fun FakeTransport.awaitSent() = withTimeout(2_000) {
        while (!finished("/chat/stream") || !finished("/chat/subscribe")) delay(5)
    }

    @Test
    fun sendsTheVoiceNoteLikeTheFirmware() = runBlocking {
        val t = FakeTransport()
        val session = MuseSession(t, "homelink-abc123", timing = FAST)
        val turn = async { session.voiceTurn(ONE_SECOND) }
        t.awaitSent()

        val note = t.stream("/chat/stream")!!
        val sub = t.stream("/chat/subscribe")!!
        for (o in listOf(note, sub)) {
            assertEquals("hatch-web", o.headers["x-app-id"])
            assertEquals("application/json", o.headers["Content-Type"])
            assertTrue(o.headers["x-request-id"]!!.matches(Regex("muse-[0-9a-f]{8}-[0-9a-f]{8}")))
        }
        assertEquals("application/json", note.headers["Accept"])
        assertEquals("application/x-ndjson", sub.headers["Accept"])

        // The subscription body "{}" is half-closed, and before the note's last chunk.
        assertEquals("{}", String(t.body("/chat/subscribe")))
        val subEnd = t.sent.indexOfFirst { it.id == sub.id && it.end }
        val noteEnd = t.sent.indexOfFirst { it.id == note.id && it.end }
        assertTrue("subscribe must be closed before the note ends", subEnd in 0 until noteEnd)

        // Body: the firmware's voice-note JSON around a base64 WAV.
        val body = String(t.body("/chat/stream"))
        assertTrue(body.startsWith("{\"message\":\"\",\"output_modality\":\"text\",\"items\":[{\"type\":\"file\",\"mime_type\":\"audio/wav\",\"filename\":\"voice_note.wav\",\"data_base64\":\""))
        assertTrue(body.endsWith("\"}]}"))
        val wav = Base64.getDecoder().decode(Json.str(body, "data_base64"))
        val h = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, h.getShort(20).toInt())          // PCM
        assertEquals(1, h.getShort(22).toInt())          // mono
        assertEquals(16_000, h.getInt(24))               // rate
        assertEquals(16, h.getShort(34).toInt())         // bits
        assertEquals(ONE_SECOND.size, h.getInt(40))      // data size
        assertEquals(44 + ONE_SECOND.size, wav.size)

        note.ch.send(ack(200, """{"result":{"message_id":"m1","reply_to_message_id":"p0"}}"""))
        sub.ch.send(subscribed())
        sub.ch.send(ndjson(ev(1, "delta.message_done", """{"message_id":"a1","reply_to_message_id":"m1","display_text":"Hi."}""")))
        assertEquals("Hi.", turn.await())
        assertTrue("subscription is cancelled after the turn", sub.id in t.resets)
    }

    @Test
    fun buildsTheReplyFromDeltasAndReportsTheTranscript() = runBlocking {
        val t = FakeTransport()
        val heard = mutableListOf<String>()
        val turn = async { MuseSession(t, "n", timing = FAST).voiceTurn(ONE_SECOND) { heard += it } }
        t.awaitSent()
        val sub = t.stream("/chat/subscribe")!!
        sub.ch.send(subscribed())
        // Events may arrive before the ack; they must be kept.
        sub.ch.send(ndjson(
            """{"type":"subscribed"}""",
            ev(1, "message.user", """{"message_id":"m1","content":"[Voice note]"}"""),
            ev(2, "message.user", """{"message_id":"m1","content":"what time is it\n[file:audio/wav voice_note.wav]"}"""),
            ev(3, "delta.message_start", """{"message_id":"a1","reply_to_message_id":"m1"}"""),
        ))
        t.stream("/chat/stream")!!.ch.send(ack(200, """{"result":{"message_id":"m1","reply_to_message_id":"p0"}}"""))
        sub.ch.send(ndjson(
            ev(4, "delta.text_append", """{"message_id":"a1","text":"It's "}"""),
            ev(5, "delta.text_append", """{"message_id":"a1","text":"noon."}"""),
            ev(6, "message.assistant", """{"message_id":"zz","reply_to_message_id":"other","content":"not ours"}"""),
            ev(7, "delta.message_done", """{"message_id":"a1"}"""),
            ev(7, "delta.message_done", """{"message_id":"dup","reply_to_message_id":"m1","display_text":"duplicate seq"}"""),
        ))
        assertEquals("It's noon.", turn.await())
        assertEquals(listOf("what time is it"), heard)
    }

    @Test
    fun waitsForFollowUpMessagesWhileMuseIsBusy() = runBlocking {
        val t = FakeTransport()
        val turn = async { MuseSession(t, "n", timing = FAST).voiceTurn(ONE_SECOND) }
        t.awaitSent()
        val sub = t.stream("/chat/subscribe")!!
        t.stream("/chat/stream")!!.ch.send(ack(200, """{"message_id":"m1"}"""))
        sub.ch.send(subscribed())
        sub.ch.send(ndjson(
            ev(1, "delta.message_done", """{"message_id":"a1","reply_to_message_id":"m1","display_text":"Let me check."}"""),
            ev(2, "agent.status", """{"activity_code":"searching"}"""),
        ))
        delay(400) // longer than settle (150 ms), shorter than the busy hold (700 ms)
        sub.ch.send(ndjson(
            ev(3, "delta.message_done", """{"message_id":"a2","reply_to_message_id":"m1","display_text":"It's sunny."}"""),
            ev(4, "agent.status", """{"activity_code":"idle"}"""),
        ))
        assertEquals("Let me check. It's sunny.", turn.await())
    }

    @Test
    fun aRejectedNoteFailsTheTurn() = runBlocking {
        for ((status, body) in listOf(500 to "oops", 200 to """{"result":{}}""")) {
            val t = FakeTransport()
            val turn = async { runCatching { MuseSession(t, "n", timing = FAST).voiceTurn(ONE_SECOND) } }
            t.awaitSent()
            t.stream("/chat/stream")!!.ch.send(ack(status, body))
            val e = turn.await().exceptionOrNull()
            assertTrue("HTTP $status: $e", e is MuseSession.SessionError && "didn't take it" in e.message!!)
        }
    }

    @Test
    fun noReplyTimesOut() = runBlocking {
        val t = FakeTransport()
        val turn = async { runCatching { MuseSession(t, "n", timing = FAST).voiceTurn(ONE_SECOND) } }
        t.awaitSent()
        t.stream("/chat/stream")!!.ch.send(ack(200, """{"message_id":"m1"}"""))
        t.stream("/chat/subscribe")!!.ch.send(subscribed())
        val e = turn.await().exceptionOrNull()
        assertTrue("$e", e is MuseSession.SessionError && "no reply" in e.message!!)
    }

    @Test
    fun aSubscriptionErrorFailsTheTurn() = runBlocking {
        val t = FakeTransport()
        val turn = async { runCatching { MuseSession(t, "n", timing = FAST).voiceTurn(ONE_SECOND) } }
        t.awaitSent()
        t.stream("/chat/stream")!!.ch.send(ack(200, """{"message_id":"m1"}"""))
        t.stream("/chat/subscribe")!!.ch.send(ack(403, "denied"))
        val e = turn.await().exceptionOrNull()
        assertTrue("$e", e is MuseSession.SessionError && "403" in e.message!!)
    }

    @Test
    fun tooShortANoteIsNotSent() = runBlocking {
        val t = FakeTransport()
        try {
            MuseSession(t, "n", timing = FAST).voiceTurn(ByteArray(VoiceNote.SAMPLE_RATE * 2 / 10)) // 100 ms
            fail("expected SessionError")
        } catch (e: MuseSession.SessionError) {
            assertTrue(t.opened.isEmpty())
        }
    }

    @Test
    fun longNotesAreClippedToFifteenSeconds() {
        val pcm = ByteArray(VoiceNote.SAMPLE_RATE * 2 * 20)
        assertEquals(VoiceNote.SAMPLE_RATE * 2 * 15, VoiceNote.clip(pcm, VoiceNote.SAMPLE_RATE).size)
    }
}

class ReplyAssemblerTest {
    private fun row(line: String) = ChatRow.parse(line)!!

    @Test
    fun parsesFirmwareFieldVariants() {
        val r = row("""{"type":"event","seq":9,"event_name":"message.assistant","payload":{"id":"a1","parent_message_id":"m1","text":"t","content":"c","display_text":"d","display_text_ready":false}}""")
        assertEquals("message.assistant", r.event)
        assertEquals(9, r.seq)
        assertEquals("a1", r.msg)
        assertEquals("m1", r.replyTo)
        assertEquals("d", r.text)
        assertFalse(r.ready)
        assertEquals(null, ChatRow.parse("""{"type":"subscribed","seq":1}"""))
    }

    @Test
    fun displayTextReadyOnlyGatesThePersistedMessage() {
        val a = ReplyAssembler("m1", "")
        // A persisted message whose display text isn't ready is skipped…
        a.accept(row(ev(1, "message.assistant", """{"message_id":"a1","reply_to_message_id":"m1","content":"draft","display_text_ready":false}""")))
        assertFalse(a.replied)
        // …but message_done always completes the message.
        a.accept(row(ev(2, "delta.message_done", """{"message_id":"a1","reply_to_message_id":"m1","display_text":"final","display_text_ready":false}""")))
        assertEquals("final", a.text)
        // The persisted copy of the same message doesn't duplicate it.
        a.accept(row(ev(3, "message.assistant", """{"message_id":"a1","reply_to_message_id":"m1","content":"final"}""")))
        assertEquals("final", a.text)
    }

    @Test
    fun rowsWithoutSeqAreKept() {
        val a = ReplyAssembler("m1", "")
        a.accept(row(ev(5, "delta.message_done", """{"message_id":"a1","reply_to_message_id":"m1","display_text":"one"}""")))
        a.accept(row(ev(null, "delta.message_done", """{"message_id":"a2","reply_to_message_id":"m1","display_text":"two"}""")))
        a.accept(row(ev(4, "delta.message_done", """{"message_id":"a3","reply_to_message_id":"m1","display_text":"stale"}""")))
        assertEquals("one two", a.text)
    }

    @Test
    fun repliesToTheParentAndUnparentedRepliesCount() {
        val a = ReplyAssembler("m1", "p0")
        a.accept(row(ev(1, "delta.message_done", """{"message_id":"a1","reply_to_message_id":"p0","display_text":"x"}""")))
        a.accept(row(ev(2, "delta.message_done", """{"message_id":"a2","display_text":"y"}""")))
        a.accept(row(ev(3, "delta.message_done", """{"message_id":"a3","reply_to_message_id":"elsewhere","display_text":"z"}""")))
        assertEquals("x y", a.text)
    }

    @Test
    fun busyFollowsAgentAndTaskStatus() {
        val a = ReplyAssembler("m1", "")
        a.accept(row(ev(1, "agent.status", """{"activity_code":"thinking"}""")))
        assertTrue(a.busy)
        a.accept(row(ev(2, "agent.status", """{"activity_code":"online"}""")))
        assertFalse(a.busy)
        a.accept(row(ev(3, "task.status", """{"status":"running"}""")))
        assertTrue(a.busy)
        a.accept(row(ev(4, "task.status", """{"status":"completed"}""")))
        assertFalse(a.busy)
    }
}
