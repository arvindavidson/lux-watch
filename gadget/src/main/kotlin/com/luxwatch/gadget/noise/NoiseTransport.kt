package com.luxwatch.gadget.noise

import com.luxwatch.gadget.proto.Proto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Noise-secured multiplexed transport over a WebSocket.
 * Follows linux/src/musegadget/noise/transport.py and link_client.py.
 *
 * After [connect], use [request] for single-shot calls, or [openStream]
 * + [sendBodyChunk] for streaming (dictation, subscribe).
 */
class NoiseTransport(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived streams
        // Keeps middleboxes from dropping an idle session and detects dead ones.
        // 60 s like the ESP32 boards' always-on link (noise_control.cpp
        // KEEPALIVE_INTERVAL_MS); the Linux SDK uses 20 s, which on a watch
        // wakes the radio three times as often.
        .pingInterval(60, TimeUnit.SECONDS)
        .build(),
) : MuseTransport {
    data class AppResponse(
        val status: Int,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
        val endBody: Boolean,
    )

    /** A body chunk or terminal frame arriving on a stream. */
    sealed interface StreamEvent {
        data class Response(val response: AppResponse) : StreamEvent
        data class BodyChunk(val data: ByteArray, val endBody: Boolean) : StreamEvent
        data class Reset(val code: Int, val reason: String) : StreamEvent
    }

    class TransportError(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)

    private val random = SecureRandom()
    private val nextStreamId = AtomicLong(1)
    private val streams = mutableMapOf<Long, Channel<StreamEvent>>()
    private val lock = Any()

    private lateinit var ws: WebSocket
    private lateinit var sendCipher: Noise.CipherState
    private lateinit var recvCipher: Noise.CipherState
    private var failure: Throwable? = null

    // Chunk reassembly state per chunk_id.
    private data class Assembly(
        val total: Int,
        val parts: MutableMap<Int, ByteArray> = mutableMapOf(),
    )
    private val assemblies = mutableMapOf<Long, Assembly>()

    /**
     * Opens wss://{noiseHost}/v1/noise?vm_id={vmId} with the VM auth token
     * and runs the Noise XX handshake. Suspends until ready.
     */
    suspend fun connect(noiseHost: String, vmId: String, vmAuthToken: String) {
        val url = "wss://$noiseHost/v1/noise?vm_id=" + encode(vmId)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $vmAuthToken")
            .header("User-Agent", "luxwatch/0.1.0 (Wear OS)")
            .build()

        val handshakeDone = CompletableDeferred<Pair<Noise.CipherState, Noise.CipherState>>()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            private var handshakeCiphers: Pair<Noise.CipherState, Noise.CipherState>? = null
            private val initiator = Noise.Initiator().also { it.initialize() }
            private var phase = 0 // 0: sent m1, 1: handshake done

            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    webSocket.send(ByteString.of(*initiator.writeMessage1()))
                    phase = 0
                } catch (e: Exception) {
                    fail(e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    if (phase == 0) {
                        initiator.readMessage2(bytes.toByteArray())
                        webSocket.send(ByteString.of(*initiator.writeMessage3()))
                        val ciphers = initiator.split()
                        handshakeCiphers = ciphers
                        phase = 1
                        handshakeDone.complete(ciphers)
                    } else {
                        onEncryptedFrame(bytes.toByteArray())
                    }
                } catch (e: Exception) {
                    fail(e)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                fail(TransportError("unexpected text frame during Noise session"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                fail(TransportError("websocket closed: $code $reason"))
            }
        })

        val (c1, c2) = try {
            handshakeDone.await()
        } catch (e: Exception) {
            throw TransportError("Noise handshake failed", e)
        }
        sendCipher = c1
        recvCipher = c2
        // Inbound frames are handled in onMessage (OkHttp's own thread);
        // nothing further needed here.
    }

    private fun fail(e: Throwable) {
        failure = e
        synchronized(lock) {
            streams.values.forEach { it.close(e) }
            streams.clear()
        }
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---- outbound ----

    /** Single complete request; returns when the full response arrives. */
    suspend fun request(
        verb: String,
        path: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray = ByteArray(0),
        service: Framing.ServiceType = Framing.ServiceType.DAEMON,
    ): AppResponse {
        val streamId = nextStreamId.getAndIncrement()
        val ch = Channel<StreamEvent>(Channel.UNLIMITED)
        synchronized(lock) { streams[streamId] = ch }

        val frame = Framing.encodeServiceFrame(
            streamId,
            Framing.AppRequest(verb, path, headers, body, endBody = true),
        )
        sendFrame(frame, service)

        val bodyOut = java.io.ByteArrayOutputStream()
        var status = 0
        var respHeaders: List<Pair<String, String>> = emptyList()
        try {
            for (event in ch) {
                when (event) {
                    is StreamEvent.Response -> {
                        status = event.response.status
                        respHeaders = event.response.headers
                        bodyOut.write(event.response.body)
                        if (event.response.endBody) break
                    }
                    is StreamEvent.BodyChunk -> {
                        bodyOut.write(event.data)
                        if (event.endBody) break
                    }
                    is StreamEvent.Reset ->
                        throw TransportError("stream reset: ${event.code} ${event.reason}")
                }
            }
        } finally {
            synchronized(lock) { streams.remove(streamId) }
        }
        return AppResponse(status, respHeaders, bodyOut.toByteArray(), true)
    }

    /** Opens a streaming request (empty body, end_body=false). Returns the stream id. */
    override fun openStream(
        verb: String,
        path: String,
        headers: List<Pair<String, String>>,
        service: Framing.ServiceType,
    ): Pair<Long, Channel<StreamEvent>> {
        val streamId = nextStreamId.getAndIncrement()
        val ch = Channel<StreamEvent>(Channel.UNLIMITED)
        synchronized(lock) { streams[streamId] = ch }
        val frame = Framing.encodeServiceFrame(
            streamId,
            Framing.AppRequest(verb, path, headers, ByteArray(0), endBody = false),
        )
        sendFrame(frame, service)
        return streamId to ch
    }

    /** Sends a body chunk on an open stream. */
    override fun sendBodyChunk(
        streamId: Long,
        data: ByteArray,
        endBody: Boolean,
        service: Framing.ServiceType,
    ) {
        val body = encodeBodyChunkFrame(streamId, data, endBody)
        sendFrame(Framing.encodeServiceRequest(service, body), raw = true)
    }

    /** Cancels a stream. */
    override fun sendReset(streamId: Long, code: Int) {
        val out = java.io.ByteArrayOutputStream()
        out.write(Proto.int64Field(1, streamId))
        // field 5 = reset, Reset{ code=1, reason=2 }
        val reset = java.io.ByteArrayOutputStream()
        reset.write(Proto.varintField(1, code.toLong()))
        out.write(Proto.bytesField(5, reset.toByteArray()))
        sendFrame(Framing.encodeServiceRequest(Framing.ServiceType.DAEMON, out.toByteArray()), raw = true)
    }

    private fun encodeBodyChunkFrame(streamId: Long, data: ByteArray, endBody: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(Proto.int64Field(1, streamId))
        val chunk = java.io.ByteArrayOutputStream()
        if (data.isNotEmpty()) chunk.write(Proto.bytesField(1, data))
        if (endBody) chunk.write(Proto.boolField(2, true))
        out.write(Proto.bytesField(4, chunk.toByteArray()))
        return out.toByteArray()
    }

    private fun sendFrame(serviceFrame: ByteArray, service: Framing.ServiceType) {
        val wrapped = Framing.encodeServiceRequest(service, serviceFrame)
        sendFrame(wrapped, raw = true)
    }

    private fun sendFrame(wrapped: ByteArray, raw: Boolean) {
        val f = failure
        if (f != null) throw TransportError("transport dead", f)
        val chunkId = random.nextLong()
        val frames = Framing.encodeNoiseFrames(wrapped, chunkId)
        synchronized(sendCipher) {
            for (frame in frames) {
                val ct = sendCipher.encryptWithAd(ByteArray(0), frame)
                ws.send(ByteString.of(*ct))
            }
        }
    }

    // ---- inbound ----

    private fun onEncryptedFrame(ct: ByteArray) {
        val pt = synchronized(recvCipher) {
            recvCipher.decryptWithAd(ByteArray(0), ct)
        }
        val payload = reassemble(pt) ?: return
        routePayload(payload)
    }

    /** Reassembles chunked frames; returns the full payload when complete. */
    private fun reassemble(frameBytes: ByteArray): ByteArray? {
        val r = Proto.Reader(frameBytes)
        var chunkId = 0L
        var index = 0
        var total = 1
        var payload = ByteArray(0)
        while (r.remaining > 0) {
            val key = r.readVarint()
            when ((key shr 3).toInt()) {
                1 -> chunkId = r.readVarint()
                2 -> index = r.readVarint().toInt()
                3 -> total = r.readVarint().toInt()
                4 -> payload = r.readBytes()
                else -> throw TransportError("unknown NoiseTransportFrame field")
            }
        }
        if (total == 1) return payload
        val asm = synchronized(lock) {
            assemblies.getOrPut(chunkId) { Assembly(total) }
        }
        synchronized(asm) {
            if (asm.total != total) throw TransportError("inconsistent total_chunks")
            if (!asm.parts.containsKey(index)) asm.parts[index] = payload
            if (asm.parts.size < total) return null
            val out = java.io.ByteArrayOutputStream()
            for (i in 0 until total) out.write(asm.parts[i] ?: throw TransportError("missing chunk $i"))
            synchronized(lock) { assemblies.remove(chunkId) }
            return out.toByteArray()
        }
    }

    private fun routePayload(payload: ByteArray) {
        // ServiceResponse{ payload: bytes } (field 1)
        val r = Proto.Reader(payload)
        var inner = ByteArray(0)
        while (r.remaining > 0) {
            val key = r.readVarint()
            if ((key shr 3).toInt() == 1) inner = r.readBytes()
            else skipField(r, key)
        }
        routeServiceFrame(inner)
    }

    private fun routeServiceFrame(frameBytes: ByteArray) {
        val r = Proto.Reader(frameBytes)
        var streamId = 0L
        var kind = 0 // 2=request, 3=response, 4=body_chunk, 5=reset
        var kindBytes = ByteArray(0)
        while (r.remaining > 0) {
            val key = r.readVarint()
            when ((key shr 3).toInt()) {
                1 -> streamId = r.readVarint()
                2, 3, 4, 5 -> {
                    kind = (key shr 3).toInt()
                    kindBytes = r.readBytes()
                }
                else -> skipField(r, key)
            }
        }
        if (kind == 2) throw TransportError("unexpected request frame from server")
        val ch = synchronized(lock) { streams[streamId] }
            ?: return // unknown stream; drop (matches Python routing)
        val event: StreamEvent = when (kind) {
            3 -> StreamEvent.Response(decodeAppResponse(kindBytes))
            4 -> {
                val (data, end) = decodeBodyChunk(kindBytes)
                StreamEvent.BodyChunk(data, end)
            }
            5 -> {
                val (code, reason) = decodeReset(kindBytes)
                StreamEvent.Reset(code, reason)
            }
            else -> throw TransportError("unknown ServiceFrame kind $kind")
        }
        if (!ch.trySend(event).isSuccess) {
            // Channel closed; drop.
        }
        if (event is StreamEvent.BodyChunk && event.endBody) {
            synchronized(lock) { streams.remove(streamId) }
            ch.close()
        }
    }

    private fun decodeAppResponse(b: ByteArray): AppResponse {
        val r = Proto.Reader(b)
        var status = 0
        val headers = mutableListOf<Pair<String, String>>()
        var body = ByteArray(0)
        var endBody = false
        while (r.remaining > 0) {
            when ((r.readVarint() shr 3).toInt()) {
                1 -> status = r.readVarint().toInt()
                2 -> {
                    val h = Proto.Reader(r.readBytes())
                    var k = ""; var v = ""
                    while (h.remaining > 0) {
                        when ((h.readVarint() shr 3).toInt()) {
                            1 -> k = h.readString()
                            2 -> v = h.readString()
                            else -> {}
                        }
                    }
                    headers.add(k to v)
                }
                3 -> body = r.readBytes()
                4 -> endBody = r.readVarint() != 0L
                else -> {}
            }
        }
        return AppResponse(status, headers, body, endBody)
    }

    private fun decodeBodyChunk(b: ByteArray): Pair<ByteArray, Boolean> {
        val r = Proto.Reader(b)
        var data = ByteArray(0)
        var end = false
        while (r.remaining > 0) {
            when ((r.readVarint() shr 3).toInt()) {
                1 -> data = r.readBytes()
                2 -> end = r.readVarint() != 0L
                else -> {}
            }
        }
        return data to end
    }

    private fun decodeReset(b: ByteArray): Pair<Int, String> {
        val r = Proto.Reader(b)
        var code = 0
        var reason = ""
        while (r.remaining > 0) {
            when ((r.readVarint() shr 3).toInt()) {
                1 -> code = r.readVarint().toInt()
                2 -> reason = r.readString()
                else -> {}
            }
        }
        return code to reason
    }

    private fun skipField(r: Proto.Reader, key: Long) {
        when ((key and 7).toInt()) {
            0 -> r.readVarint()
            2 -> r.readBytes()
            else -> throw TransportError("unsupported wire type")
        }
    }

    fun close() {
        try {
            ws.close(1000, "done")
        } catch (_: Exception) {
        }
    }
}
