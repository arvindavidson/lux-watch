package com.luxwatch.gadget

import com.luxwatch.gadget.noise.MuseSession
import com.luxwatch.gadget.voice.SpeechChunker
import kotlinx.coroutines.CancellationException

/** Where a talk turn is. */
enum class TalkStatus { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Production voice turns: the recording goes to Muse as a voice note over the
 * Noise channel; the text reply is shown and spoken with on-device TTS (Muse
 * sends text replies), sentence by sentence as it streams in, when Settings →
 * Read replies aloud is on. Muse's own voice is for live calls.
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
    /** Settings → Read replies aloud. Off: the reply is only shown, as a chat. */
    private val readAloud: () -> Boolean = { false },
) {
    /** Set by [stopSpeaking]: the rest of this turn's reply is shown, not read out. */
    @Volatile private var muted = false
    @Volatile private var speaking = false

    /** One turn: audio in, reply shown (and read out if asked). */
    suspend fun voiceTurn(audio: ByteArray) = turn { onText -> connection.voiceTurn(audio, onHeard, onText) }

    /** One turn from a typed message (e.g. asking Muse for its avatar). */
    suspend fun textTurn(text: String) = turn { onText -> connection.textTurn(text, onText) }

    /** Tap while Lux speaks: stop now, and don't read out what's still coming. */
    fun stopSpeaking() {
        muted = true
        tts.stop()
    }

    /**
     * The reply streams onto the screen as Muse writes it. Read aloud, Lux
     * starts at the first finished sentence while Muse is still writing the rest.
     */
    private suspend fun turn(ask: suspend (onText: (String) -> Unit) -> String) {
        muted = !readAloud()
        speaking = false
        onStatus(TalkStatus.THINKING)
        val chunker = SpeechChunker()
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
