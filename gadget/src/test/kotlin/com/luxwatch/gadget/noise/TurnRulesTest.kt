package com.luxwatch.gadget.noise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Test

/** The turn rules ported from the SDK's muse_turn.py and muse_chat_link.c (Oct 2026). */
class TurnRulesTest {
    private var seq = 0
    private fun row(event: String, payload: String) =
        ChatRow.parse("""{"type":"event","seq":${++seq},"event":"$event","payload":$payload}""")!!

    private fun ReplyAssembler.feed(event: String, payload: String) = accept(row(event, payload))

    @Test
    fun aFinishedTaskCompletesTheTurnAtOnce() {
        val r = ReplyAssembler("m1", "p0")
        r.feed("agent.status", """{"activity_code":"thinking","reply_to_message_id":"m1"}""")
        assertTrue(r.busy)
        r.feed("delta.message_start", """{"message_id":"a1","reply_to_message_id":"m1"}""")
        r.feed("delta.text_append", """{"message_id":"a1","text":"Noon."}""")
        r.feed("delta.message_done", """{"message_id":"a1"}""")
        assertFalse(r.complete) // still "thinking", no task end yet
        r.feed("task.status", """{"status":"completed","reply_to_message_id":"m1"}""")
        assertTrue(r.complete)
        assertFalse(r.busy)
        assertEquals("Noon.", r.text)
    }

    @Test
    fun statusesForAnotherRequestDontHoldTheTurn() {
        val r = ReplyAssembler("m1", "p0")
        r.feed("delta.message_done", """{"message_id":"a1","reply_to_message_id":"m1","display_text":"Hi."}""")
        r.feed("agent.status", """{"activity_code":"researching","reply_to_message_id":"other"}""")
        assertFalse(r.busy)
        r.feed("task.status", """{"status":"running","reply_to_message_id":"other"}""")
        assertFalse(r.busy)
        // A parentless status still counts (older servers send them).
        r.feed("agent.status", """{"activity_code":"working"}""")
        assertTrue(r.busy)
    }

    @Test
    fun aReplyToSomethingElseStaysOutEvenWithoutAParentLater() {
        val r = ReplyAssembler("m1", "p0")
        r.feed("delta.message_start", """{"message_id":"x9","reply_to_message_id":"someone-else"}""")
        r.feed("delta.text_append", """{"message_id":"x9","text":"not ours"}""")
        r.feed("delta.message_done", """{"message_id":"x9"}""")
        assertFalse(r.replied)
        assertEquals("", r.live)
    }

    @Test
    fun aMessageStillStreamingKeepsTheTurnOpen() {
        val r = ReplyAssembler("m1", "p0")
        r.feed("delta.message_done", """{"message_id":"a1","reply_to_message_id":"m1","display_text":"Checking."}""")
        r.feed("delta.message_start", """{"message_id":"a2","reply_to_message_id":"a1"}""") // a follow-up to our reply
        r.feed("delta.text_append", """{"message_id":"a2","text":"It's"}""")
        r.feed("task.status", """{"status":"completed","reply_to_message_id":"m1"}""")
        assertFalse(r.allDone)
        assertFalse(r.complete)
        assertEquals("Checking. It's", r.live)
        r.feed("delta.message_done", """{"message_id":"a2","display_text":"It's sunny."}""")
        assertTrue(r.complete)
        assertEquals("Checking. It's sunny.", r.text)
    }
}

class RegisterReplyTest {
    private val session = MuseSession(FakeTransport(), "n")

    @Test
    fun anErrorIsARejectionButNullOrFalseIsNot() {
        assertTrue(session.registerRejected("""{"id":"r","error":{"message":"bad commands"}}"""))
        assertTrue(session.registerRejected("""{"id":"r","error":"forbidden"}"""))
        assertFalse(session.registerRejected("""{"id":"r","result":{"ok":true},"error":null}"""))
        assertFalse(session.registerRejected("""{"id":"r","result":{},"error": false}"""))
        assertFalse(session.registerRejected("""{"id":"r","result":{}}"""))
    }
}

class VmFileTest {
    /** Serves [file] from a fake VM: 206 ranges, 416 past the end. */
    private fun CoroutineScope.serve(t: FakeTransport, file: ByteArray, ignoreRange: Boolean = false) =
        launch {
            var served = 0
            while (true) {
                val o = synchronized(t) { t.opened.getOrNull(served) }
                if (o == null) {
                    delay(2)
                    continue
                }
                served++
                val (a, b) = Regex("bytes=(\\d+)-(\\d+)").find(o.headers["Range"]!!)!!.destructured
                val from = a.toInt()
                val to = minOf(b.toInt(), file.size - 1)
                val response = when {
                    ignoreRange -> NoiseTransport.AppResponse(200, emptyList(), file, true)
                    from >= file.size -> NoiseTransport.AppResponse(416, emptyList(), ByteArray(0), true)
                    else -> NoiseTransport.AppResponse(206, emptyList(), file.copyOfRange(from, to + 1), true)
                }
                o.ch.send(NoiseTransport.StreamEvent.Response(response))
            }
        }

    private val file = ByteArray(10_000) { (it * 7).toByte() }

    @Test
    fun readsAFileInRanges() = kotlinx.coroutines.runBlocking {
        val t = FakeTransport()
        val server = serve(t, file)
        val got = MuseSession(t, "n").readFile("/home/muse/avatar/idle.mp4", maxBytes = 1_000_000, chunk = 4096)
        server.cancel()
        org.junit.Assert.assertArrayEquals(file, got)
        assertEquals("/fs/raw/home/muse/avatar/idle.mp4", t.opened[0].path)
        assertEquals(listOf("bytes=0-4095", "bytes=4096-8191", "bytes=8192-12287"), t.opened.map { it.headers["Range"] })
    }

    @Test
    fun aWholeFileAnswerIsFineAndTheSizeIsCapped() = kotlinx.coroutines.runBlocking {
        val t = FakeTransport()
        val server = serve(t, file, ignoreRange = true)
        org.junit.Assert.assertArrayEquals(file, MuseSession(t, "n").readFile("a.png", 1_000_000, chunk = 4096))
        val t2 = FakeTransport()
        val server2 = serve(t2, file)
        val error = runCatching { MuseSession(t2, "n").readFile("a.png", maxBytes = 5_000, chunk = 4096) }.exceptionOrNull()
        server.cancel(); server2.cancel()
        assertTrue(error is MuseSession.SessionError)
    }

    @Test
    fun pathsArePercentEncodedButForSlashes() {
        assertEquals("/fs/raw/My%20Clips/l%C3%BCx.mp4", MuseSession.vmFilePath("//My Clips/lüx.mp4"))
    }
}

class TtsStreamTest {
    private fun response(status: Int, body: String = "", end: Boolean = true) =
        NoiseTransport.StreamEvent.Response(NoiseTransport.AppResponse(status, emptyList(), body.toByteArray(), end))

    @Test
    fun streamsMuseVoiceAndFallsBackToTheOldPath() = kotlinx.coroutines.runBlocking {
        val t = FakeTransport()
        val got = StringBuilder()
        val call = async { MuseSession(t, "n").streamTts("msg 1") { got.append(String(it)) } }
        while (t.opened.size < 1) delay(2)
        assertEquals("/api/voice/tts-stream?message_id=msg%201", t.opened[0].path)
        assertEquals("audio/mpeg", t.opened[0].headers["Accept"])
        t.opened[0].ch.send(response(404))
        while (t.opened.size < 2) delay(2)
        assertEquals("/voice/tts-stream?message_id=msg%201", t.opened[1].path)
        t.opened[1].ch.send(response(200, "ID3", end = false))
        t.opened[1].ch.send(NoiseTransport.StreamEvent.BodyChunk("mp3".toByteArray(), true))
        assertTrue(call.await())
        assertEquals("ID3mp3", got.toString())
    }

    @Test
    fun noSpeechAnywhereIsFalse() = kotlinx.coroutines.runBlocking {
        val t = FakeTransport()
        val call = async { MuseSession(t, "n").streamTts("m") { } }
        while (t.opened.size < 1) delay(2)
        t.opened[0].ch.send(response(404))
        while (t.opened.size < 2) delay(2)
        t.opened[1].ch.send(response(404))
        assertFalse(call.await())
    }
}

class SideChatTest {
    @Test
    fun aSideChatGoesOnTheNoteAndTheSubscription() = kotlinx.coroutines.runBlocking {
        val t = FakeTransport()
        val session = MuseSession(t, "node-1").apply { chatSessionId = "lux-watch-node-1" }
        val turn = async { runCatching { session.textTurn("hi", modality = VoiceNote.VOICE) } }
        while (t.opened.size < 2 || t.sent.count { it.end } < 2) delay(2)
        assertEquals("""{"session_id":"lux-watch-node-1"}""", String(t.body("/chat/subscribe")))
        assertEquals(
            """{"message":"hi","output_modality":"voice","device_id":"node-1","session_id":"lux-watch-node-1"}""",
            String(t.body("/chat/stream")),
        )
        turn.cancel()
    }
}
