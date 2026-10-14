package com.luxwatch.gadget.noise

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Body framings used on Muse streams: length-prefixed JSON (link-control) and NDJSON. */
internal object SessionFraming {
    private const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024

    /** `<u32 LE length><json>`; a zero-length message is a keepalive. */
    fun encodeMessage(json: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(4 + json.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(json.size)
        bb.put(json)
        return bb.array()
    }

    /** Drains complete length-prefixed messages from buf; keeps the remainder. */
    fun drainMessages(buf: ByteArrayOutputStream): List<String> {
        val bytes = buf.toByteArray()
        val out = mutableListOf<String>()
        var off = 0
        while (off + 4 <= bytes.size) {
            val len = ByteBuffer.wrap(bytes, off, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (len < 0 || len > MAX_MESSAGE_BYTES) break // corrupt; wait for more
            if (off + 4 + len > bytes.size) break
            val msg = bytes.copyOfRange(off + 4, off + 4 + len)
            if (msg.isNotEmpty()) out.add(msg.toString(Charsets.UTF_8)) // zero-len = keepalive
            off += 4 + len
        }
        buf.reset()
        buf.write(bytes, off, bytes.size - off)
        return out
    }

    /**
     * Drains complete newline-terminated lines from buf; keeps the remainder.
     * With [final] set (the stream has ended), an unterminated last line is
     * returned too and the buffer is left empty.
     */
    fun drainLines(buf: ByteArrayOutputStream, final: Boolean = false): List<String> {
        val bytes = buf.toByteArray()
        val out = mutableListOf<String>()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == '\n'.code.toByte()) {
                val line = bytes.copyOfRange(start, i).toString(Charsets.UTF_8).trim()
                if (line.isNotEmpty()) out.add(line)
                start = i + 1
            }
        }
        if (final && start < bytes.size) {
            val line = bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8).trim()
            if (line.isNotEmpty()) out.add(line)
            start = bytes.size
        }
        buf.reset()
        buf.write(bytes, start, bytes.size - start)
        return out
    }
}
