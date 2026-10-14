package com.luxwatch.gadget.noise

import java.io.ByteArrayOutputStream

/** Sanity checks for length-prefixed control messages and NDJSON line splitting. */
object SessionFramingCheck {
    private fun encodeMessage(json: ByteArray) = SessionFraming.encodeMessage(json)
    private fun drainMessages(buf: ByteArrayOutputStream) = SessionFraming.drainMessages(buf)
    private fun drainLines(buf: ByteArrayOutputStream, final: Boolean = false) =
        SessionFraming.drainLines(buf, final)

    @JvmStatic
    fun main(args: Array<String>) {
        // Length-prefixed: two messages, second split across writes.
        val m1 = encodeMessage("""{"type":"req","id":"a"}""".toByteArray())
        val m2 = encodeMessage("""{"type":"req","id":"b"}""".toByteArray())
        val buf = ByteArrayOutputStream()
        buf.write(m1)
        buf.write(m2, 0, 7) // partial
        var msgs = drainMessages(buf)
        check(msgs == listOf("""{"type":"req","id":"a"}""")) { "msg1: $msgs" }
        buf.write(m2, 7, m2.size - 7)
        msgs = drainMessages(buf)
        check(msgs == listOf("""{"type":"req","id":"b"}""")) { "msg2: $msgs" }
        // Keepalive (zero-length) is swallowed.
        buf.write(encodeMessage(ByteArray(0)))
        msgs = drainMessages(buf)
        check(msgs.isEmpty()) { "keepalive: $msgs" }
        println("PASS control-message framing")

        // NDJSON lines, split mid-line.
        val lb = ByteArrayOutputStream()
        lb.write("""{"type":"partial","text":"hel""".toByteArray())
        var lines = drainLines(lb)
        check(lines.isEmpty()) { "partial line should wait: $lines" }
        lb.write(("lo\"}\n{\"type\":\"final\",\"text\":\"hello\"}\n{\"type\":\"par").toByteArray())
        lines = drainLines(lb)
        check(lines.size == 2) { "lines: $lines" }
        check(lines[0] == """{"type":"partial","text":"hello"}""") { "line0: ${lines[0]}" }
        check(lines[1] == """{"type":"final","text":"hello"}""") { "line1: ${lines[1]}" }
        println("PASS ndjson line splitting")

        // End of stream: an unterminated last line is flushed, not lost.
        lb.write("""tial","text":"bye"}""".toByteArray())
        lines = drainLines(lb)
        check(lines.isEmpty()) { "unterminated line should wait mid-stream: $lines" }
        lines = drainLines(lb, final = true)
        check(lines == listOf("""{"type":"partial","text":"bye"}""")) { "final flush: $lines" }
        check(lb.size() == 0) { "buffer not emptied: ${lb.size()}" }
        println("PASS ndjson final-line flush")
        println("ALL SESSION FRAMING CHECKS PASS")
    }
}
