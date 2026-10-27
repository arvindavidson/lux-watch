package com.luxwatch.gadget.noise

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID

/**
 * High-level Muse gadget session: link-control registration and voice turns.
 * Follows link_client.py and esp32/components/muse/muse_chat_session.cpp.
 *
 * After [register], a background reader keeps serving the link-control
 * channel (link.invoke, link.unpaired) until the stream ends or [close].
 */
class MuseSession(
    private val transport: MuseTransport,
    private val nodeId: String,
    private val displayName: String = "Lux Watch",
    /** Called (on a background thread) when Muse removes this device. */
    private val onUnpaired: () -> Unit = {},
    private val timing: TurnTiming = TurnTiming(),
    /** Commands offered to Muse in link.register; [onInvoke] runs them. */
    private val commands: List<CommandSpec> = emptyList(),
    private val onInvoke: CommandHandler? = null,
) {
    /**
     * Voice-turn timeouts from Meta's firmware (muse_chat_session.cpp):
     * REPLY_TIMEOUT_US, SETTLE_US, BUSY_HOLD_US, TURN_CAP_US. Settle is shorter
     * than the firmware's 3 s: the watch speaks the reply as it streams in, so
     * the settle wait only holds the turn open for a quick follow-up message.
     */
    data class TurnTiming(
        val replyTimeoutMs: Long = 60_000,
        val settleMs: Long = 1_000,
        val busyHoldMs: Long = 20_000,
        val turnCapMs: Long = 180_000,
    )

    private val random = SecureRandom()

    class SessionError(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var controlStream: Long = -1

    /** True while the link-control channel is up; false once it ends or the device is unpaired. */
    @Volatile
    var isOpen = false
        private set

    /** Opens POST /link-control and sends link.register. Suspends until acked. */
    suspend fun register() {
        // No headers, like link_client.py and the firmware's control session.
        val (streamId, ch) = transport.openStream("POST", "/link-control")
        controlStream = streamId

        val reqId = UUID.randomUUID().toString()
        val params = """{"node_id":"$nodeId","display_name":"$displayName","platform":"linux","version":"0.1.0","device_family":"homehub","model_id":"linux","is_wakeup_supported":false,"commands_v2":${CommandSpec.catalog(commands)}}"""
        val msg = """{"type":"req","id":"$reqId","method":"link.register","params":$params}"""
        transport.sendBodyChunk(streamId, SessionFraming.encodeMessage(msg.toByteArray()), endBody = false)

        // Wait for the register reply (same id, no method). Anything else that
        // arrives first, or in the same chunk after it, is a normal control message.
        val buf = ByteArrayOutputStream()
        var acked = false
        var ended = false
        withTimeout(20_000) {
            while (!acked) {
                val (data, end) = controlBody(ch.receive())
                buf.write(data)
                for (m in SessionFraming.drainMessages(buf)) {
                    if (!acked && m.contains("\"id\":\"$reqId\"")) {
                        if (m.contains("\"error\"")) {
                            throw SessionError("link.register rejected: ${m.take(300)}")
                        }
                        acked = true
                    } else if (!handleControlMessage(m)) {
                        throw SessionError("device unpaired by Muse; re-pair required")
                    }
                }
                if (end) {
                    if (!acked) throw SessionError("link-control closed before link.register reply")
                    ended = true
                }
            }
        }
        if (ended) return
        isOpen = true
        scope.launch { readControl(ch, buf) }
    }

    /** Serves link-control messages for the rest of the session. */
    private suspend fun readControl(ch: Channel<NoiseTransport.StreamEvent>, buf: ByteArrayOutputStream) {
        try {
            while (true) {
                val (data, end) = controlBody(ch.receive())
                buf.write(data)
                for (m in SessionFraming.drainMessages(buf)) {
                    if (!handleControlMessage(m)) return
                }
                if (end) return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Reset, refusal or transport failure: the session is over (Outcome.CLOSED).
        } finally {
            isOpen = false
        }
    }

    private fun controlBody(event: NoiseTransport.StreamEvent): Pair<ByteArray, Boolean> = when (event) {
        is NoiseTransport.StreamEvent.BodyChunk -> event.data to event.endBody
        is NoiseTransport.StreamEvent.Response -> {
            if (event.response.status >= 400) {
                throw SessionError("link-control refused: HTTP ${event.response.status}")
            }
            event.response.body to event.response.endBody
        }
        is NoiseTransport.StreamEvent.Reset ->
            throw SessionError("link-control reset: ${event.reason}")
    }

    /**
     * Handles one async link-control message (invokes, unpaired events).
     * Returns false once Muse has unpaired the device; the session is then dead.
     */
    private fun handleControlMessage(msg: String): Boolean {
        if (msg.contains("\"event\":\"link.unpaired\"") || msg.contains("\"event\":\"node.unpaired\"")) {
            isOpen = false
            onUnpaired()
            return false
        }
        if (msg.contains("\"method\":\"link.invoke\"")) {
            // Read id and command with params cut out, so a param named "id" can't shadow them.
            val params = Json.obj(msg, "params")
            val outer = if (params.isEmpty()) msg else msg.replaceFirst(params, "{}")
            val id = Json.str(outer, "id") ?: return true
            val command = Json.str(outer, "command") ?: ""
            // Off the reader, so a slow command never holds up the control channel.
            scope.launch { answerInvoke(id, command, params.ifEmpty { "{}" }) }
        }
        return true
    }

    /** Runs an invoked command and sends link.result ({ok, payload} or {ok:false, error}). */
    private suspend fun answerInvoke(id: String, command: String, params: String) {
        val result = when {
            commands.none { it.name == command } || onInvoke == null ->
                CommandResult.Error("unsupported command: $command")
            else -> try {
                onInvoke.invoke(command, params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CommandResult.Error("$command failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        val body = when (result) {
            is CommandResult.Ok -> "\"ok\":true,\"payload\":${result.payloadJson}"
            is CommandResult.Error -> "\"ok\":false,\"error\":${Json.escape(result.message)}"
        }
        val resp = """{"method":"link.result","id":${Json.escape(id)},$body}"""
        try {
            transport.sendBodyChunk(controlStream, SessionFraming.encodeMessage(resp.toByteArray()), endBody = false)
        } catch (_: Exception) {
            // The link went down; Muse times the invoke out.
        }
    }

    companion object {
        /** Body chunk size for /chat/stream, like the firmware's DICT_CHUNK_BYTES. */
        private const val BODY_CHUNK = 8192
    }

    /** Stops the link-control reader. The caller owns (and closes) the transport. */
    fun close() {
        isOpen = false
        scope.cancel()
    }

    /**
     * One voice turn: sends the recording (PCM16 mono) to /chat/stream as a voice
     * note and returns Muse's text reply. [onHeard] gets the server's transcript
     * of the note when it arrives; [onText] gets the reply so far each time
     * more of it streams in. Follows muse_chat_link.c.
     */
    suspend fun voiceTurn(
        pcm: ByteArray,
        sampleRate: Int = VoiceNote.SAMPLE_RATE,
        onHeard: (String) -> Unit = {},
        onText: (String) -> Unit = {},
    ): String {
        if (VoiceNote.durationMs(pcm, sampleRate) < VoiceNote.MIN_MS) {
            throw SessionError("didn't catch that")
        }
        return turn(VoiceNote.body(VoiceNote.clip(pcm, sampleRate), sampleRate), onHeard, onText)
    }

    /** A typed message, with the same reply handling as a voice turn. */
    suspend fun textTurn(text: String, onHeard: (String) -> Unit = {}, onText: (String) -> Unit = {}): String =
        turn(VoiceNote.textBody(text), onHeard, onText)

    private fun requestHeaders(accept: String) = listOf(
        "x-request-id" to "muse-%08x-%08x".format(random.nextInt(), random.nextInt()),
        "x-app-id" to "hatch-web",
        "Content-Type" to "application/json",
        "Accept" to accept,
    )

    private suspend fun turn(
        body: ByteArray,
        onHeard: (String) -> Unit,
        onText: (String) -> Unit,
    ): String = coroutineScope {
        val (noteId, noteCh) = transport.openStream("POST", "/chat/stream", requestHeaders("application/json"))
        val parts = (0 until body.size step BODY_CHUNK).map { body.copyOfRange(it, minOf(it + BODY_CHUNK, body.size)) }
        for (part in parts.dropLast(1)) transport.sendBodyChunk(noteId, part, endBody = false)
        // Subscribe before the note's last chunk so no reply event is missed; the
        // subscribe body "{}" is complete, so half-close it right away.
        val (subId, subCh) = transport.openStream("POST", "/chat/subscribe", requestHeaders("application/x-ndjson"))
        transport.sendBodyChunk(subId, "{}".toByteArray(), endBody = true)
        transport.sendBodyChunk(noteId, parts.lastOrNull() ?: ByteArray(0), endBody = true)
        val sentAt = System.currentTimeMillis()

        // Rows can arrive before the ack; queue them until the ack names the note.
        val lines = Channel<String>(Channel.UNLIMITED)
        val reader = launch {
            try {
                readNdjson(subCh, "subscribe") { lines.trySend(it); false }
                lines.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lines.close(e)
            }
        }
        try {
            val ack = withTimeoutOrNull(timing.replyTimeoutMs) { readAck(noteCh) }
                ?: throw SessionError("no reply from Muse")
            val reply = ReplyAssembler(ack.messageId, ack.parentId)
            withTimeoutOrNull(timing.turnCapMs) {
                while (true) {
                    val wait = when {
                        !reply.replied -> timing.replyTimeoutMs - (System.currentTimeMillis() - sentAt)
                        reply.busy -> timing.busyHoldMs
                        else -> timing.settleMs
                    }
                    if (wait <= 0) throw SessionError("no reply from Muse")
                    val next = withTimeoutOrNull(wait) { lines.receiveCatching() }
                    if (next == null) {
                        // Quiet: a replied turn has settled; otherwise Muse never answered.
                        if (!reply.replied) throw SessionError("no reply from Muse")
                        break
                    }
                    if (next.isClosed) {
                        next.exceptionOrNull()?.let { throw it }
                        if (!reply.replied) throw SessionError("reply stream closed")
                        break
                    }
                    val row = ChatRow.parse(next.getOrThrow()) ?: continue
                    val heardBefore = reply.heard
                    val liveBefore = reply.live
                    reply.accept(row)
                    reply.heard?.let { if (it != heardBefore) onHeard(it) }
                    reply.live.let { if (it != liveBefore && it.isNotEmpty()) onText(it) }
                }
            }
            if (!reply.replied) throw SessionError("no reply from Muse")
            reply.text
        } finally {
            reader.cancel()
            runCatching { transport.sendReset(subId) }
        }
    }

    private data class Ack(val messageId: String, val parentId: String)

    /** Reads the /chat/stream response: 200 and a result.message_id, or the turn fails. */
    private suspend fun readAck(ch: Channel<NoiseTransport.StreamEvent>): Ack {
        var status = 0
        val buf = ByteArrayOutputStream()
        for (event in ch) {
            when (event) {
                is NoiseTransport.StreamEvent.Response -> {
                    status = event.response.status
                    buf.write(event.response.body)
                    if (event.response.endBody) break
                }
                is NoiseTransport.StreamEvent.BodyChunk -> {
                    buf.write(event.data)
                    if (event.endBody) break
                }
                is NoiseTransport.StreamEvent.Reset ->
                    throw SessionError("chat/stream reset: ${event.reason}")
            }
        }
        val body = buf.toString(Charsets.UTF_8.name())
        if (status != 200) throw SessionError("Muse didn't take it (HTTP $status)")
        val result = Json.obj(body, "result").ifEmpty { body }
        val id = Json.str(result, "message_id")?.takeIf { it.isNotEmpty() }
            ?: throw SessionError("Muse didn't take it (no message id)")
        return Ack(id, Json.str(result, "reply_to_message_id") ?: "")
    }

    /**
     * Feeds each NDJSON line of a stream's body to [onLine] until the stream
     * ends or [onLine] returns true. Lines in an ApplicationResponse body are
     * handled like body-chunk lines, and an unterminated last line is flushed
     * when the stream ends.
     */
    private suspend fun readNdjson(
        ch: Channel<NoiseTransport.StreamEvent>,
        what: String,
        onLine: (String) -> Boolean,
    ) {
        val buf = ByteArrayOutputStream()
        for (event in ch) {
            val (data, end) = when (event) {
                is NoiseTransport.StreamEvent.BodyChunk -> event.data to event.endBody
                is NoiseTransport.StreamEvent.Response -> {
                    if (event.response.status >= 400) {
                        throw SessionError(
                            "$what failed: HTTP ${event.response.status} " +
                                event.response.body.toString(Charsets.UTF_8).take(200)
                        )
                    }
                    event.response.body to event.response.endBody
                }
                is NoiseTransport.StreamEvent.Reset ->
                    throw SessionError("$what reset: ${event.reason}")
            }
            buf.write(data)
            for (line in SessionFraming.drainLines(buf, final = end)) {
                if (onLine(line)) return
            }
            if (end) return
        }
    }
}
