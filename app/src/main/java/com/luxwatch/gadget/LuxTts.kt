package com.luxwatch.gadget

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Speaks Lux's replies on-device. Muse does not send reply audio,
 * so the gadget performs its own TTS (per PROTOCOL.md §4).
 */
class LuxTts(context: Context) {
    @Volatile
    private var ready = false
    private var tts: TextToSpeech? = null
    private val prefs = context.applicationContext.getSharedPreferences("lux_prefs", Context.MODE_PRIVATE)

    /** Called on the main thread once the engine is up (voices are known then). */
    var onReady: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.language = Locale.getDefault()
                voices().firstOrNull { it.name == prefs.getString(PREF_VOICE, null) }?.let { tts?.voice = it }
                tts?.setSpeechRate(rate)
                onReady?.invoke()
            }
        }
    }

    /** Voices that work offline in the watch's language, in a stable order. */
    private fun voices(): List<Voice> {
        val lang = Locale.getDefault().language
        return runCatching { tts?.voices.orEmpty() }.getOrDefault(emptySet())
            .filter {
                !it.isNetworkConnectionRequired && it.locale.language == lang &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }
            .sortedBy { it.name }
    }

    /** "2 of 5", or "Default" before the engine is ready or with a single voice. */
    val voiceLabel: String
        get() {
            val all = voices()
            val i = all.indexOfFirst { it.name == tts?.voice?.name }
            return if (all.size < 2 || i < 0) "Default" else "${i + 1} of ${all.size}"
        }

    /** Switches to the next voice and remembers it. */
    fun nextVoice() {
        val all = voices()
        if (all.isEmpty()) return
        val i = all.indexOfFirst { it.name == tts?.voice?.name }
        val next = all[(i + 1).mod(all.size)]
        tts?.voice = next
        prefs.edit().putString(PREF_VOICE, next.name).apply()
    }

    /** Speech rate, 1.0 = normal; kept between 0.6 and 2.0. */
    var rate: Float
        get() = prefs.getFloat(PREF_RATE, 1f)
        set(value) {
            val r = (Math.round(value.coerceIn(0.6f, 2f) * 10) / 10f)
            prefs.edit().putFloat(PREF_RATE, r).apply()
            tts?.setSpeechRate(r)
        }

    private companion object {
        const val PREF_VOICE = "tts_voice"
        const val PREF_RATE = "tts_rate"
    }

    /** Speaks [text], suspending until the utterance finishes. No-op if TTS isn't ready. */
    suspend fun speak(text: String) {
        val engine = tts ?: return
        if (!ready || text.isBlank()) return
        suspendCancellableCoroutine { cont ->
            val utteranceId = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Legacy callback; kept for older engines")
                override fun onError(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(Unit)
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && cont.isActive) cont.resume(Unit)
                }

                // stop() (tap to interrupt) ends an utterance with onStop, not onDone.
                override fun onStop(id: String?, interrupted: Boolean) {
                    if (id == utteranceId && cont.isActive) cont.resume(Unit)
                }
            })
            cont.invokeOnCancellation { engine.stop() }
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) ==
                TextToSpeech.ERROR && cont.isActive
            ) {
                cont.resume(Unit)
            }
        }
    }

    /** Stops speaking now; a pending [speak] returns. */
    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
