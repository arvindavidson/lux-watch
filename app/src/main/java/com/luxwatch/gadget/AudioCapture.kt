package com.luxwatch.gadget

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * Records 16 kHz PCM16 mono (the voice-note rate Meta's firmware uses) while active.
 * Caller must hold RECORD_AUDIO before calling [start].
 */
class AudioCapture {
    companion object {
        const val SAMPLE_RATE = com.luxwatch.gadget.noise.VoiceNote.SAMPLE_RATE
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile
    private var running = false

    /** Live input level, 0..1 (RMS of the last read), for the avatar. */
    @Volatile
    var level = 0f
        private set

    /** Recorded length so far, in milliseconds. */
    val recordedMs: Long get() = synchronized(sink) { sink.size().toLong() / 2 * 1000 / SAMPLE_RATE }
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val sink = ByteArrayOutputStream()

    /** Starts capture on a background thread. Returns false if the mic is unavailable. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return false
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, minBuf * 4
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        synchronized(sink) { sink.reset() }
        running = true
        record = rec
        thread = Thread({
            val buf = ByteArray(minBuf)
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    synchronized(sink) { sink.write(buf, 0, n) }
                    level = rms(buf, n)
                } else if (n < 0) {
                    break
                }
            }
        }, "LuxAudioCapture").also { it.start() }
        return true
    }

    private fun rms(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < n) {
            val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            sum += s.toDouble() * s
            count++
            i += 2
        }
        if (count == 0) return 0f
        // Speech RMS is roughly 500..8000; map that onto 0..1.
        val r = kotlin.math.sqrt(sum / count)
        return ((r - 300) / 5000).toFloat().coerceIn(0f, 1f)
    }

    /** Stops capture and returns the recorded PCM bytes (may be empty). */
    fun stop(): ByteArray {
        running = false
        level = 0f
        try {
            thread?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
            // Was never recording; nothing to stop.
        }
        record?.release()
        record = null
        synchronized(sink) {
            return sink.toByteArray()
        }
    }
}
