package com.luxwatch.gadget

import com.luxwatch.gadget.noise.MuseSession
import com.luxwatch.gadget.noise.VoiceNote
import com.luxwatch.gadget.voice.SpeechChunker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    @Volatile private var playing: Mp3StreamPlayer? = null

    /** One turn: audio in, reply shown and spoken. */
    suspend fun voiceTurn(audio: ByteArray) = turn { onText, modality, onDone ->
        connection.voiceTurn(audio, onHeard, onText, modality, onDone)
    }

    /** One turn from a typed message (e.g. asking Muse for its avatar). */
    suspend fun textTurn(text: String) = turn { onText, modality, onDone ->
        connection.textTurn(text, onText, modality, onDone)
    }

    /** Tap while Lux speaks: stop now, and don't read out what's still coming. */
    fun stopSpeaking() {
        muted = true
        playing?.stop()
        tts.stop()
    }

    private fun speakingNow() {
        if (!speaking) {
            speaking = true
            onStatus(TalkStatus.SPEAKING)
        }
    }

    @Volatile private var speaking = false

    /**
     * With the watch's voice, Lux starts speaking at the first finished
     * sentence while Muse is still writing the rest. With Muse's voice, each
     * finished message is fetched as speech from Muse and played as it
     * arrives; if Muse has none, the watch's voice reads it.
     */
    private suspend fun turn(
        ask: suspend (
            onText: (String) -> Unit,
            modality: String,
            onMessageDone: (String, String) -> Unit,
        ) -> String,
    ) {
        muted = false
        speaking = false
        onStatus(TalkStatus.THINKING)
        val museVoice = tts.museVoice
        val chunker = SpeechChunker()
        fun speakReady(text: String, final: Boolean) {
            if (muted) return
            for (piece in chunker.next(text, final)) if (tts.say(piece)) speakingNow()
        }
        try {
            coroutineScope {
                val messages = Channel<Pair<String, String>>(Channel.UNLIMITED)
                val voice = if (museVoice) launch { for ((id, text) in messages) speakInMuseVoice(id, text) } else null
                val reply = try {
                    ask(
                        { soFar ->
                            onReply(soFar, false)
                            if (!museVoice) synchronized(chunker) { speakReady(soFar, final = false) }
                        },
                        if (museVoice) VoiceNote.VOICE else VoiceNote.TEXT,
                        { id, text -> if (museVoice) messages.trySend(id to text) },
                    )
                } finally {
                    messages.close()
                }
                if (reply.isBlank()) {
                    onError("Muse had no answer")
                    voice?.cancel()
                    return@coroutineScope
                }
                onReply(reply, true)
                if (museVoice) voice?.join() else synchronized(chunker) { speakReady(reply, final = true) }
                tts.awaitSilence()
            }
        } catch (e: CancellationException) {
            playing?.stop()
            throw e
        } catch (e: Exception) {
            playing?.stop()
            tts.stop()
            onError(friendly(e))
        } finally {
            onStatus(TalkStatus.IDLE)
        }
    }

    /** One finished message in Muse's voice, played while it streams; the watch's voice if Muse has none. */
    private suspend fun speakInMuseVoice(id: String, text: String) {
        if (muted) return
        val player = Mp3StreamPlayer()
        playing = player
        val streamed = try {
            connection.streamTts(id) { chunk ->
                if (!muted) {
                    player.feed(chunk)
                    speakingNow()
                }
            }
        } catch (e: CancellationException) {
            player.stop()
            throw e
        } catch (e: Exception) {
            false
        }
        if (streamed && !muted) {
            player.finish()
            player.await()
        } else {
            player.stop()
            if (!muted && tts.say(text)) {
                speakingNow()
                tts.awaitSilence()
            }
        }
        playing = null
    }

    private fun friendly(e: Exception): String = when (e) {
        is MuseSession.SessionError -> (e.message ?: "Muse didn't answer").replaceFirstChar { it.uppercase() }
        else -> "Can't reach Muse"
    }
}
