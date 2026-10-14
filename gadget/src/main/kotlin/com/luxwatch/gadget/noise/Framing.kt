package com.luxwatch.gadget.noise

import com.luxwatch.gadget.proto.Proto
import java.io.ByteArrayOutputStream

/**
 * Transport framing, ported from linux/src/musegadget/noise/framing.py
 * and envelope.py. Field numbers match PROTOCOL.md §3.3 exactly.
 */
object Framing {
    const val MAX_CHUNK_PAYLOAD = 65489

    enum class ServiceType(val id: Int) { DAEMON(0), SENTINEL(1), VAULT(2), AUTH(3) }

    data class AppRequest(
        val verb: String,
        val path: String,
        val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray = ByteArray(0),
        val endBody: Boolean = false,
    )

    private fun encodeHeader(key: String, value: String): ByteArray {
        val out = ByteArrayOutputStream()
        if (key.isNotEmpty()) out.write(Proto.stringField(1, key))
        if (value.isNotEmpty()) out.write(Proto.stringField(2, value))
        return out.toByteArray()
    }

    private fun encodeAppRequest(r: AppRequest): ByteArray {
        val out = ByteArrayOutputStream()
        if (r.verb.isNotEmpty()) out.write(Proto.stringField(1, r.verb))
        if (r.path.isNotEmpty()) out.write(Proto.stringField(2, r.path))
        for ((k, v) in r.headers) out.write(Proto.bytesField(3, encodeHeader(k, v)))
        if (r.body.isNotEmpty()) out.write(Proto.bytesField(4, r.body))
        if (r.endBody) out.write(Proto.boolField(5, true))
        return out.toByteArray()
    }

    /** ServiceFrame{ stream_id=1, request=2 }. */
    fun encodeServiceFrame(streamId: Long, request: AppRequest): ByteArray {
        val out = ByteArrayOutputStream()
        if (streamId != 0L) out.write(Proto.int64Field(1, streamId))
        out.write(Proto.bytesField(2, encodeAppRequest(request)))
        return out.toByteArray()
    }

    /** ServiceRequest{ service=1, payload=2 } wrapper (outbound).
     * Field 1 is omitted for the default DAEMON service, matching the SDK. */
    fun encodeServiceRequest(service: ServiceType, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        if (service != ServiceType.DAEMON) out.write(Proto.varintField(1, service.id.toLong()))
        out.write(Proto.bytesField(2, payload))
        return out.toByteArray()
    }

    /** NoiseTransportFrame{ chunk_id=1, chunk_index=2, total_chunks=3, payload=4 }.
     * Zero-valued fields are omitted, matching the SDK's protobuf default
     * omission. */
    fun encodeNoiseFrames(data: ByteArray, chunkId: Long): List<ByteArray> {
        val total = maxOf(1, (data.size + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD)
        require(total <= 256) { "payload too large" }
        return (0 until total).map { i ->
            val start = i * MAX_CHUNK_PAYLOAD
            val end = minOf(start + MAX_CHUNK_PAYLOAD, data.size)
            val out = ByteArrayOutputStream()
            if (chunkId != 0L) out.write(Proto.int64Field(1, chunkId))
            if (i != 0) out.write(Proto.uint32Field(2, i.toLong()))
            if (total != 0) out.write(Proto.uint32Field(3, total.toLong()))
            val payload = data.copyOfRange(start, end)
            if (payload.isNotEmpty()) out.write(Proto.bytesField(4, payload))
            out.toByteArray()
        }
    }
}
