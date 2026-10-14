package com.luxwatch.gadget

import com.luxwatch.gadget.noise.MuseSession
import kotlinx.coroutines.CancellationException

/** Where a talk turn is. */
enum class TalkStatus { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Production voice turns: the recording goes to Muse as a voice note over the
 * Noise channel; the text reply is shown and spoken with on-device TTS (Muse
 * sends text replies; its server TTS is gone, per the SDK).
 * Callbacks may arrive on a background thread.
 */
class MuseLuxBackend(
    private val connection: GadgetConnection,
    private val tts: LuxTts,
    private val onStatus: (TalkStatus) -> Unit = {},
    private val onHeard: (String) -> Unit = {},
    private val onReply: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
) {
    /** One turn: audio in, reply shown and spoken. */
    suspend fun voiceTurn(audio: ByteArray) = turn { connection.voiceTurn(audio, onHeard) }

    /** One turn from a typed message (e.g. asking Muse for its avatar). */
    suspend fun textTurn(text: String) = turn { connection.textTurn(text) }

    private suspend fun turn(ask: suspend () -> String) {
        onStatus(TalkStatus.THINKING)
        try {
            val reply = ask()
            if (reply.isBlank()) {
                onError("Muse had no answer")
                return
            }
            onReply(reply)
            onStatus(TalkStatus.SPEAKING)
            tts.speak(reply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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
