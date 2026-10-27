package com.luxwatch.gadget

import com.luxwatch.gadget.noise.MuseSession
import com.luxwatch.gadget.voice.SpeechChunker
import kotlinx.coroutines.CancellationException

/** Where a talk turn is. */
enum class TalkStatus { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Production voice turns: the recording goes to Muse as a voice note over the
 * Noise channel; the text reply is shown and spoken with on-device TTS (Muse
 * sends text replies; its server TTS is gone, per the SDK), sentence by
 * sentence as it streams in.
 * Callbacks may arrive on a background thread.
 */
class MuseLuxBackend(
    private val connection: GadgetConnection,
    private val tts: LuxTts,
    private val onStatus: (TalkStatus) -> Unit = {},
    private val onHeard: (String) -> Unit = {},
    /** The reply so far while it streams in, then the whole reply with final = true. */
    private val onReply: (text: String, final: Boolean) -> Unit = { _, _ -> },
    private val onError: (String) -> Unit = {},
) {
    /** Set by [stopSpeaking]: the rest of this turn's reply is shown, not read out. */
    @Volatile private var muted = false

    /** One turn: audio in, reply shown and spoken. */
    suspend fun voiceTurn(audio: ByteArray) = turn { onText -> connection.voiceTurn(audio, onHeard, onText) }

    /** One turn from a typed message (e.g. asking Muse for its avatar). */
    suspend fun textTurn(text: String) = turn { onText -> connection.textTurn(text, onText) }

    /** Tap while Lux speaks: stop now, and don't read out what's still coming. */
    fun stopSpeaking() {
        muted = true
        tts.stop()
    }

    /**
     * Lux starts speaking at the first finished sentence while Muse is still
     * writing the rest, instead of waiting for the whole reply.
     */
    private suspend fun turn(ask: suspend (onText: (String) -> Unit) -> String) {
        muted = false
        onStatus(TalkStatus.THINKING)
        val chunker = SpeechChunker()
        var speaking = false
        fun speakReady(text: String, final: Boolean) {
            if (muted) return
            for (piece in chunker.next(text, final)) {
                if (tts.say(piece) && !speaking) {
                    speaking = true
                    onStatus(TalkStatus.SPEAKING)
                }
            }
        }
        try {
            val reply = ask { soFar ->
                onReply(soFar, false)
                synchronized(chunker) { speakReady(soFar, final = false) }
            }
            if (reply.isBlank()) {
                onError("Muse had no answer")
                return
            }
            onReply(reply, true)
            synchronized(chunker) { speakReady(reply, final = true) }
            tts.awaitSilence()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tts.stop()
            onError(friendly(e))
        } finally {
            onStatus(TalkStatus.IDLE)
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is MuseSession.SessionError -> (e.message ?: "Muse didn't answer").replaceFirstChar { it.uppercase() }
        else -> "Can't reach Muse"
    }
}
