package com.luxwatch.gadget.noise

import kotlinx.coroutines.channels.Channel

/** The stream operations MuseSession needs from the Noise transport (fakeable in tests). */
interface MuseTransport {
    /** Opens a streaming request (empty body, end_body=false). */
    fun openStream(
        verb: String,
        path: String,
        headers: List<Pair<String, String>> = emptyList(),
        service: Framing.ServiceType = Framing.ServiceType.DAEMON,
    ): Pair<Long, Channel<NoiseTransport.StreamEvent>>

    /** Sends a body chunk on an open stream. */
    fun sendBodyChunk(
        streamId: Long,
        data: ByteArray,
        endBody: Boolean,
        service: Framing.ServiceType = Framing.ServiceType.DAEMON,
    )

    /** Cancels a stream. */
    fun sendReset(streamId: Long, code: Int = 1 /* CANCELLED */)
}
