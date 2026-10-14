package com.luxwatch.gadget.noise

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * A voice turn's /chat/stream body: the recording as a base64 WAV "voice note",
 * as the phone app and Meta's ESP32 firmware send it (muse_chat_priv.h
 * MUSE_HATCH_NOTE_HEAD/TAIL; muse_chat_session.cpp VOICE_NOTE: the VM's streaming
 * dictation has no ASR behind it, so the server transcribes the note instead).
 */
object VoiceNote {
    /** Recording rate the firmware uses for notes (MIC_RATE). */
    const val SAMPLE_RATE = 16_000
    /** Shorter than this and the firmware reports "didn't catch that". */
    const val MIN_MS = 300
    /** Muse stops listening to a note after 15 s. */
    const val MAX_SECONDS = 15

    private const val HEAD =
        "{\"message\":\"\",\"output_modality\":\"text\",\"items\":[{\"type\":\"file\"," +
            "\"mime_type\":\"audio/wav\",\"filename\":\"voice_note.wav\",\"data_base64\":\""
    private const val TAIL = "\"}]}"

    fun durationMs(pcm: ByteArray, sampleRate: Int): Long = pcm.size / 2 * 1000L / sampleRate

    /** Trims PCM16 mono to at most [MAX_SECONDS]. */
    fun clip(pcm: ByteArray, sampleRate: Int): ByteArray {
        val max = sampleRate * 2 * MAX_SECONDS
        return if (pcm.size > max) pcm.copyOf(max) else pcm
    }

    /** 44-byte PCM16 mono WAV header + data (real sizes: the whole note is known here). */
    fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun le(v: Long, n: Int) = repeat(n) { out.write(((v ushr (8 * it)) and 0xff).toInt()) }
        out.write("RIFF".toByteArray()); le(36L + pcm.size, 4)
        out.write("WAVEfmt ".toByteArray()); le(16, 4)
        le(1, 2) /* PCM */; le(1, 2) /* mono */
        le(sampleRate.toLong(), 4); le(sampleRate * 2L, 4)
        le(2, 2) /* block align */; le(16, 2) /* bits */
        out.write("data".toByteArray()); le(pcm.size.toLong(), 4)
        out.write(pcm)
        return out.toByteArray()
    }

    /** The full /chat/stream JSON body for a voice note. */
    fun body(pcm: ByteArray, sampleRate: Int): ByteArray =
        (HEAD + Base64.getEncoder().encodeToString(wav(pcm, sampleRate)) + TAIL).toByteArray()

    /** A typed turn's body (same reply path, no attachment). */
    fun textBody(text: String): ByteArray =
        "{\"message\":${Json.escape(text)},\"output_modality\":\"text\"}".toByteArray()
}
