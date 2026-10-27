package com.luxwatch.gadget.noise

import com.luxwatch.gadget.voice.ReplyGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import kotlin.math.sqrt

/**
 * One realtime voice call with Muse, a port of the SDK's voice_call.py
 * (experimental there too): the mic goes up live as 24 kHz PCM16 and Muse
 * answers in its own voice as it thinks. Half duplex: the mic is gated while
 * Muse speaks. Runs on a Noise session opened for voice (app_id on the
 * upgrade, no link.register).
 *
 * The call is admitted as an `mcu-wearable` audio call with
 * `hatch.voice.call.start`, offers to end on Muse's goodbye, and is always
 * ended explicitly with `POST /voice/end-call`.
 */
class VoiceCall(
    private val transport: MuseTransport,
    private val deviceId: String,
    private var threadId: String? = null,
    private val halfDuplex: Boolean = true,
    private val micThreshold: Int = 0,
    private val idleTimeoutMs: Long = 45_000,
    private val overallTimeoutMs: Long = 300_000,
    private val readyTimeoutMs: Long = 35_000,
    private val cleanupTimeoutMs: Long = 10_000,
    private val listener: Listener = object : Listener {},
) {
    /** The microphone: the next frame of up to [FRAME_SAMPLES] PCM16LE samples at 24 kHz, null at its end. */
    fun interface Mic {
        suspend fun read(): ByteArray?
    }

    /** The speaker: nonblocking writes of 24 kHz PCM16LE, and a flush that drops what's queued. */
    interface Speaker {
        fun write(pcm: ByteArray)
        fun flush()
    }

    /** What the call is doing, for the screen. Called on the call's threads. */
    interface Listener {
        fun onReady() {}
        fun onHeld(held: Boolean) {}
        fun onSpeaking(speaking: Boolean) {}
        /** A transcript line, if the VM sends them: the caller's ([you]) or Muse's so far. */
        fun onCaption(you: Boolean, text: String) {}
    }

    class CallError(message: String) : Exception(message)

    val callId: String = UUID.randomUUID().toString()
    private val startedAt = Instant.now()

    /** Learned from this call's readiness. */
    var sessionId: String? = null
        private set
    var endCallAcknowledged = false
        private set

    @Volatile private var ready = false
    @Volatile private var held = false
    @Volatile private var micEpoch = 0
    @Volatile private var remoteEnded = false
    @Volatile private var reason = ""
    @Volatile private var activity = now()
    @Volatile private var playingUntil = 0L
    @Volatile private var speakingShown = false

    private val gate = ReplyGate()
    private val lock = Any()
    private val waitingPcm = ByteArrayOutputStream()
    private val playback = ArrayDeque<Pair<ByteArray, Boolean>>()
    private var playbackBytes = 0
    @Volatile private var audibleFrames = 0
    private var receivedAudio = false
    private val activeTurns = LinkedHashMap<String, String>()
    private val settledTurns = HashMap<String, String>()
    private var streamId = -1L
    private var admissionSent = false
    private var used = false
    private val captionMuse = StringBuilder()

    /** Runs the call until Muse ends it, it idles out, or the caller cancels. Returns why it ended. */
    suspend fun run(mic: Mic, speaker: Speaker): String {
        check(!used) { "a VoiceCall can only run once" }
        used = true
        val started = now()
        try {
            coroutineScope {
                val receive = async { receive(speaker) }
                val input = async { input(mic) }
                val play = async { play(speaker) }
                val watch = async { watchdog(started) }
                // The first to finish ends the call; a failure wins over a plain finish.
                select<Unit> {
                    receive.onAwait { }
                    input.onAwait { }
                    play.onAwait { }
                    watch.onAwait { }
                }
                listOf(receive, input, play, watch).filter { it.isCompleted }.forEach { it.await() }
                coroutineContext.cancelChildren()
            }
            return reason.ifEmpty { "remote_end" }
        } finally {
            remoteEnded = true
            withContext(NonCancellable) {
                runCatching { flush(speaker) }
                cleanup()
            }
        }
    }

    private fun kotlin.coroutines.CoroutineContext.cancelChildren() =
        this[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }

    /** Tap while Muse speaks: drop its audio until the caller's next turn, and cancel its reply. */
    fun bargeIn(speaker: Speaker) {
        val response = synchronized(lock) {
            val r = gate.openResponse
            gate.interrupt()
            applyReplyFlush(speaker)
            r
        }
        if (response != null && !remoteEnded) {
            runCatching { event(JSONObject().put("type", "response.cancel").put("response_id", response)) }
        }
    }

    // ---- sending ----

    private fun event(event: JSONObject) {
        transport.sendBodyChunk(streamId, (event.toString() + "\n").toByteArray(), endBody = false)
    }

    private fun start() {
        val (id, ch) = transport.openStream(
            "POST", "/voice/realtime?call_id=$callId",
            listOf("Content-Type" to "application/x-ndjson; charset=utf-8"),
        )
        streamId = id
        responses = ch
        val start = JSONObject()
            .put("type", "hatch.voice.call.start").put("call_id", callId)
            .put("device", "mcu-wearable").put("device_id", deviceId)
            .put("call_media_type", "audio").put("proactive_greeting_enabled", false)
        threadId?.let { start.put("thread_id", it) }
        // Even a failed send may have admitted the call: cleanup ends it by our own id.
        admissionSent = true
        event(start)
        // Muse may hang up with a session_control stop when the caller says goodbye.
        event(JSONObject().put("type", "hatch.voice.capability.offer").put("end_call_stop", 1))
    }

    private var responses: Channel<NoiseTransport.StreamEvent>? = null

    // ---- receiving ----

    private suspend fun receive(speaker: Speaker) {
        start()
        val ch = responses!!
        val line = ByteArrayOutputStream()
        var status = 0
        suspend fun feed(data: ByteArray): Boolean {
            for (b in data) {
                if (b == '\n'.code.toByte()) {
                    val text = line.toString(Charsets.UTF_8.name()).trim()
                    line.reset()
                    if (text.isNotEmpty() && onLine(text, speaker)) return true
                } else {
                    line.write(b.toInt())
                    if (line.size() > MAX_LINE_BYTES) throw CallError("voice event too large")
                }
            }
            return false
        }
        for (ev in ch) {
            val (data, end) = when (ev) {
                is NoiseTransport.StreamEvent.Response -> {
                    status = ev.response.status
                    if (status !in 200..299) throw CallError("Muse refused the call (HTTP $status)")
                    ev.response.body to ev.response.endBody
                }
                is NoiseTransport.StreamEvent.BodyChunk -> ev.data to ev.endBody
                is NoiseTransport.StreamEvent.Reset -> throw CallError("the call stream was reset")
            }
            if (feed(data)) return // remote stop, handled
            if (end) break
        }
        if (!ready) throw CallError("the call ended before it was ready")
        drainPlayback()
        reason = "remote_end"
    }

    /** One NDJSON event; true once Muse has stopped the call (drained, ready to end). */
    private suspend fun onLine(text: String, speaker: Speaker): Boolean {
        val event = try {
            JSONObject(text)
        } catch (e: Exception) {
            return false
        }
        val kind = event.opt("type") as? String ?: throw CallError("voice event has no type")
        when (kind) {
            "error" -> {
                val error = event.optJSONObject("error") ?: throw CallError("invalid voice error event")
                if (!ready || error.optString("code") == "session_dead") throw CallError("voice session failed")
                return false // advisory; never log its text
            }
            "hatch.voice.session" -> confirm(event)
            "hatch.voice.switch_input_hold" -> {
                val h = event.opt("held") as? Boolean ?: throw CallError("invalid input hold event")
                if (held != h) micEpoch++
                held = h
                listener.onHeld(h)
            }
            "input_audio_buffer.speech_started" -> activity = now()
            "conversation.item.added" -> {
                val item = event.optJSONObject("item")
                val control = item?.opt("call_id") as? String
                if (item != null && item.optString("type") == "session_control" && control != null &&
                    control.length in 1..128 && item.optString("name").trim().lowercase() == "stop"
                ) {
                    if (!ready) throw CallError("stop received before the call was ready")
                    // call_id here is a control id, not ours. Drain, then end the call.
                    drainPlayback()
                    reason = "remote_stop"
                    return true
                }
            }
            "hatch.voice.turn_started", "hatch.voice.turn_settled" -> if (!trackTurn(event)) return false
            "response.created", "response.done", "response.completed" -> activity = now()
            // Captions, when the VM sends transcripts (OpenAI realtime names).
            "conversation.item.input_audio_transcription.completed" ->
                (event.opt("transcript") as? String)?.takeIf { it.isNotBlank() }?.let {
                    synchronized(captionMuse) { captionMuse.setLength(0) }
                    listener.onCaption(you = true, it.trim())
                }
            "response.output_audio_transcript.delta" ->
                (event.opt("delta") as? String)?.let {
                    val soFar = synchronized(captionMuse) { captionMuse.append(it).toString() }
                    listener.onCaption(you = false, soFar)
                }
        }
        synchronized(lock) {
            val wasWaiting = gate.waiting
            gate.event(event)
            applyReplyFlush(speaker)
            if (wasWaiting && !gate.waiting) {
                if (gate.playing && waitingPcm.size() > 0) queuePcm(waitingPcm.toByteArray())
                waitingPcm.reset()
            }
        }
        val pcm = audioFrom(event) ?: return false
        if (pcm.isEmpty()) return false
        synchronized(lock) {
            // Zero keepalives before any audio: don't count them as speech.
            if (pcm.all { it.toInt() == 0 } && !receivedAudio && waitingPcm.size() == 0) {
                if (ready) {
                    gate.audio(event)
                    applyReplyFlush(speaker)
                }
                return false
            }
            if (!ready) throw CallError("audio before the call was ready")
            val verdict = gate.audio(event)
            applyReplyFlush(speaker)
            when (verdict) {
                ReplyGate.Verdict.DROP -> Unit
                ReplyGate.Verdict.HOLD -> {
                    if (waitingPcm.size() + pcm.size > MAX_PLAYBACK_BYTES) throw CallError("reply buffer full")
                    waitingPcm.write(pcm)
                }
                ReplyGate.Verdict.PLAY -> queuePcm(pcm)
            }
        }
        return false
    }

    private fun confirm(event: JSONObject) {
        if (ready || event.opt("call_id") != callId) throw CallError("duplicate or mismatched call identity")
        val session = event.opt("session_id") as? String
        if (session.isNullOrBlank()) throw CallError("the call's readiness has no session")
        val thread = event.opt("thread_id")
        if (threadId != null && threadId != thread) throw CallError("call thread mismatch")
        if (thread != null && thread != JSONObject.NULL && (thread !is String || thread.isBlank())) {
            throw CallError("invalid call thread")
        }
        sessionId = session
        threadId = thread as? String
        activity = now()
        ready = true
        listener.onReady()
    }

    private fun trackTurn(event: JSONObject): Boolean = synchronized(lock) {
        if (event.optString("type") == "hatch.voice.turn_started") {
            val message = ReplyGate.identifier(event.opt("submission_message_id"))
            val item = ReplyGate.identifier(event.opt("item_id"))
            if (message == null || item == null) throw CallError("invalid voice turn identity")
            val previous = activeTurns[message] ?: settledTurns[message]
            if (previous != null) {
                if (previous != item) throw CallError("conflicting voice turn identity")
                return true
            }
            if (activeTurns.size >= MAX_TRACKED_TURNS) throw CallError("too many active voice turns")
            activeTurns[message] = item
            activity = now()
            return true
        }
        val message = ReplyGate.identifier(event.opt("response_message_id")) ?: return false
        val item = activeTurns[message] ?: return false
        // A delayed response aliases the original input item: settle that item only.
        val completed = mutableListOf<String>()
        for ((key, it) in activeTurns) {
            if (it == item) completed += key
            if (key == message) break
        }
        if (settledTurns.size + completed.size > MAX_SETTLED_TURNS) throw CallError("too many settled voice turns")
        for (key in completed) settledTurns[key] = activeTurns.remove(key)!!
        activity = now()
        return true
    }

    // ---- playback ----

    private fun queuePcm(pcm: ByteArray) {
        receivedAudio = true
        if (playbackBytes + pcm.size > MAX_PLAYBACK_BYTES) throw CallError("playback buffer full")
        var offset = 0
        while (offset < pcm.size) {
            val part = pcm.copyOfRange(offset, minOf(offset + FRAME_BYTES, pcm.size))
            val audible = rms(part) >= AUDIBLE_RMS
            playback.addLast(part to audible)
            if (audible) {
                audibleFrames++
                if (halfDuplex) micEpoch++
                activity = now()
            }
            offset += FRAME_BYTES
        }
        playbackBytes += pcm.size
    }

    private fun applyReplyFlush(speaker: Speaker) {
        val (playing, waiting) = gate.takeFlush()
        if (playing) flush(speaker) else if (waiting) waitingPcm.reset()
    }

    private fun flush(speaker: Speaker) {
        synchronized(lock) {
            waitingPcm.reset()
            playback.clear()
            playbackBytes = 0
            audibleFrames = 0
            receivedAudio = false
            playingUntil = 0
        }
        speaker.flush()
        showSpeaking()
    }

    private suspend fun play(speaker: Speaker) {
        while (true) {
            val next = synchronized(lock) {
                playback.removeFirstOrNull()?.also { (pcm, audible) ->
                    playbackBytes -= pcm.size
                    if (audible) audibleFrames--
                }
            }
            if (next == null) {
                showSpeaking()
                delay(10)
                continue
            }
            val (pcm, audible) = next
            speaker.write(pcm)
            val durationMs = pcm.size * 1000L / (SAMPLE_RATE * 2)
            if (audible) {
                playingUntil = now() + durationMs + SETTLE_MS
                activity = now()
            }
            showSpeaking()
            delay(durationMs)
        }
    }

    private fun showSpeaking() {
        val speaking = audibleFrames > 0 || now() < playingUntil
        if (speaking != speakingShown) {
            speakingShown = speaking
            listener.onSpeaking(speaking)
        }
    }

    private suspend fun drainPlayback() {
        remoteEnded = true
        // Keep the last words, but a stalled speaker can't hold the hangup forever.
        val deadline = now() + MAX_PLAYBACK_BYTES * 1000L / (SAMPLE_RATE * 2) + SETTLE_MS
        while ((synchronized(lock) { playback.isNotEmpty() } || now() < playingUntil) && now() < deadline) {
            delay(FRAME_MS)
        }
    }

    // ---- microphone ----

    private fun gated(): Boolean =
        remoteEnded || held || (halfDuplex && (audibleFrames > 0 || now() < playingUntil))

    private suspend fun input(mic: Mic) {
        while (!ready) delay(20)
        var lastSpeech = 0L
        var nextSend = now()
        var eof = false
        while (true) {
            var pcm: ByteArray? = if (eof) null else mic.read()
            if (pcm == null) {
                // Muse's endpointing needs a microphone that stays on, if quiet.
                eof = true
                pcm = ByteArray(FRAME_BYTES)
            }
            if (pcm.isEmpty() || pcm.size > FRAME_BYTES || pcm.size % 2 != 0) {
                throw CallError("a mic frame must hold 1 to $FRAME_SAMPLES samples")
            }
            // Live mic: drop what's captured while gated rather than send it late.
            if (!eof && gated()) continue
            while (gated()) delay(FRAME_MS)
            val epoch = micEpoch
            val t = now()
            val level = rms(pcm)
            if (level >= maxOf(AUDIBLE_RMS.toInt(), micThreshold)) {
                activity = t
                lastSpeech = t
            }
            val muted = eof || (micThreshold > 0 && level < micThreshold && t - lastSpeech > 400)
            if (muted) pcm = ByteArray(pcm.size)
            delay(maxOf(0, nextSend - t))
            if (epoch != micEpoch) continue
            if (gated()) {
                if (!eof) continue
                while (gated()) delay(FRAME_MS)
            }
            if (gated() || epoch != micEpoch) continue
            event(
                JSONObject()
                    .put("type", "input_audio_buffer.append")
                    .put("audio", Base64.getEncoder().encodeToString(pcm))
                    .put("send_timestamp_ms", System.currentTimeMillis()),
            )
            nextSend = now() + pcm.size * 1000L / (SAMPLE_RATE * 2)
        }
    }

    // ---- deadlines and the end ----

    private suspend fun watchdog(started: Long) {
        while (true) {
            val t = now()
            if (t - started >= overallTimeoutMs) {
                reason = "overall_timeout"
                return
            }
            if (!ready && t - started >= minOf(readyTimeoutMs, overallTimeoutMs)) {
                throw CallError("Muse didn't pick up")
            }
            val idle = synchronized(lock) { activeTurns.isEmpty() }
            if (ready && idle && t - activity >= idleTimeoutMs) {
                reason = "idle_timeout"
                return
            }
            delay(100)
        }
    }

    /** Ends the call explicitly (POST /voice/end-call), then closes the media stream. */
    private suspend fun cleanup() {
        try {
            withTimeout(cleanupTimeoutMs) {
                if (admissionSent) {
                    val ended = maxOf(Instant.now(), startedAt.plusMillis(1))
                    val body = JSONObject()
                        .put("call_id", callId)
                        .put("call_started_at", ISO.format(startedAt))
                        .put("call_ended_at", ISO.format(ended))
                    threadId?.let { body.put("thread_id", it) }
                    val (status, reply) = request("/voice/end-call", body.toString().toByteArray())
                    val json = runCatching { JSONObject(reply) }.getOrNull()
                    val ok = status in 200..299 && json?.opt("ok") == true &&
                        json.optJSONObject("result")?.opt("ok") == true
                    if (!ok) throw CallError("the end of the call wasn't acknowledged")
                    endCallAcknowledged = true
                }
                if (streamId >= 0) {
                    transport.sendBodyChunk(streamId, ByteArray(0), endBody = true)
                    responses?.let { ch -> for (ev in ch) if (ev.ends()) break }
                }
            }
        } catch (e: CancellationException) {
            // Timed out draining: reset below.
        } catch (e: Exception) {
            if (!endCallAcknowledged) {
                if (streamId >= 0) runCatching { transport.sendReset(streamId) }
                throw e
            }
        }
        if (streamId >= 0) runCatching { transport.sendReset(streamId) }
    }

    private fun NoiseTransport.StreamEvent.ends() = when (this) {
        is NoiseTransport.StreamEvent.Response -> response.endBody
        is NoiseTransport.StreamEvent.BodyChunk -> endBody
        is NoiseTransport.StreamEvent.Reset -> true
    }

    private suspend fun request(path: String, body: ByteArray): Pair<Int, String> {
        val (id, ch) = transport.openStream("POST", path, listOf("Content-Type" to "application/json"))
        transport.sendBodyChunk(id, body, endBody = true)
        var status = 0
        val buf = ByteArrayOutputStream()
        for (ev in ch) {
            when (ev) {
                is NoiseTransport.StreamEvent.Response -> {
                    status = ev.response.status
                    buf.write(ev.response.body)
                }
                is NoiseTransport.StreamEvent.BodyChunk -> buf.write(ev.data)
                is NoiseTransport.StreamEvent.Reset -> throw CallError("end-call was reset")
            }
            if (ev.ends()) break
        }
        return status to buf.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        const val FRAME_SAMPLES = 960
        const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val FRAME_MS = FRAME_SAMPLES * 1000L / SAMPLE_RATE
        private const val MAX_AUDIO_BYTES = SAMPLE_RATE * 2 * 4
        private const val MAX_PLAYBACK_BYTES = SAMPLE_RATE * 2 * 5
        private const val SETTLE_MS = 200L
        private const val MAX_TRACKED_TURNS = 64
        private const val MAX_SETTLED_TURNS = 1024
        private const val MAX_LINE_BYTES = 1 shl 20
        private const val AUDIBLE_RMS = 300.0
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx").withZone(ZoneOffset.UTC)

        private fun now() = System.nanoTime() / 1_000_000

        fun rms(pcm: ByteArray): Double {
            val n = pcm.size / 2
            if (n == 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) {
                val s = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toDouble()
                sum += s * s
            }
            return sqrt(sum / n)
        }

        /** The PCM in an audio event, null if it carries none (voice_call.py audio_from_event). */
        fun audioFrom(event: JSONObject): ByteArray? {
            val encoded = when (event.optString("type")) {
                "continuous_audio_video.delta" -> {
                    val audio = event.opt("audio_delta") ?: return null
                    if (audio == JSONObject.NULL) return null
                    if (audio !is JSONObject) throw CallError("invalid audio delta")
                    val format = audio.opt("format")
                    if (format != null && format != JSONObject.NULL) {
                        if (format !is JSONObject || format.optString("type", "") !in setOf("", "audio/pcm") ||
                            format.opt("rate") != SAMPLE_RATE
                        ) throw CallError("unsupported audio format; expected PCM16LE mono 24 kHz")
                    }
                    if (audio.optInt("num_channels", 1) != 1) throw CallError("voice audio must be mono")
                    audio.opt("audio_frames")
                }
                "response.output_audio.delta", "response.audio.delta" -> event.opt("delta")
                else -> return null
            }
            if (encoded !is String || encoded.length > ((MAX_AUDIO_BYTES + 2) / 3) * 4) {
                throw CallError("invalid or oversized audio delta")
            }
            val pcm = try {
                Base64.getDecoder().decode(encoded)
            } catch (e: IllegalArgumentException) {
                throw CallError("invalid audio base64")
            }
            if (pcm.size % 2 != 0 || pcm.size > MAX_AUDIO_BYTES) throw CallError("invalid PCM16 audio length")
            return pcm
        }
    }
}
