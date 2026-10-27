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

    /**
     * Replies in Muse's own voice (a spoken reply streamed from Muse) instead
     * of the watch's. Lux then starts speaking once each message is finished
     * rather than at its first sentence.
     */
    var museVoice: Boolean
        get() = prefs.getBoolean(PREF_MUSE_VOICE, false)
        private set(value) {
            prefs.edit().putBoolean(PREF_MUSE_VOICE, value).apply()
        }

    /** "Muse's voice", "Watch voice 2 of 5", or "Watch voice" before the engine is ready or with one voice. */
    val voiceLabel: String
        get() {
            if (museVoice) return "Muse's voice"
            val all = voices()
            val i = all.indexOfFirst { it.name == tts?.voice?.name }
            return if (all.size < 2 || i < 0) "Watch voice" else "Watch voice ${i + 1} of ${all.size}"
        }

    /** Muse's voice, then each of the watch's voices, then Muse's again; remembered. */
    fun nextVoice() {
        val all = voices()
        val i = all.indexOfFirst { it.name == tts?.voice?.name }
        when {
            museVoice -> {
                museVoice = false
                all.firstOrNull()?.let { tts?.voice = it; prefs.edit().putString(PREF_VOICE, it.name).apply() }
            }
            all.isEmpty() || i == all.lastIndex -> museVoice = true
            else -> {
                val next = all[i + 1]
                tts?.voice = next
                prefs.edit().putString(PREF_VOICE, next.name).apply()
            }
        }
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
        const val PREF_MUSE_VOICE = "muse_voice"
    }

    /** Utterances queued or playing. */
    private val pending = java.util.Collections.synchronizedSet(HashSet<String>())
    private val silenceWaiters = java.util.Collections.synchronizedList(ArrayList<() -> Unit>())

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(id: String?) = finished(id)

        @Deprecated("Legacy callback; kept for older engines")
        override fun onError(id: String?) = finished(id)
        override fun onError(id: String?, errorCode: Int) = finished(id)

        // stop() (tap to interrupt) and flushed utterances end with onStop, not onDone.
        override fun onStop(id: String?, interrupted: Boolean) = finished(id)
    }

    private fun finished(id: String?) {
        pending.remove(id)
        if (pending.isEmpty()) wakeWaiters()
    }

    private fun wakeWaiters() {
        val waiters = synchronized(silenceWaiters) { silenceWaiters.toList().also { silenceWaiters.clear() } }
        waiters.forEach { it() }
    }

    /**
     * Queues [text] after whatever is already being said and returns at once,
     * so a reply can be spoken sentence by sentence while it streams in.
     * Returns false if TTS isn't ready.
     */
    fun say(text: String, flush: Boolean = false): Boolean {
        val engine = tts ?: return false
        if (!ready || text.isBlank()) return false
        engine.setOnUtteranceProgressListener(progress)
        val id = UUID.randomUUID().toString()
        if (flush) pending.clear()
        pending += id
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (engine.speak(text, mode, null, id) == TextToSpeech.ERROR) {
            finished(id)
            return false
        }
        return true
    }

    /** Suspends until everything queued has been said (or stopped). Cancelling stops the speech. */
    suspend fun awaitSilence() {
        if (pending.isEmpty()) return
        suspendCancellableCoroutine { cont ->
            val wake = { if (cont.isActive) cont.resume(Unit) }
            silenceWaiters += wake
            if (pending.isEmpty()) wake() // finished meanwhile
            cont.invokeOnCancellation {
                silenceWaiters -= wake
                stop()
            }
        }
    }

    /** Speaks [text] now (replacing anything queued), suspending until it finishes. No-op if TTS isn't ready. */
    suspend fun speak(text: String) {
        if (say(text, flush = true)) awaitSilence()
    }

    /** Stops speaking now; a pending [speak] returns. */
    fun stop() {
        tts?.stop()
        pending.clear()
        wakeWaiters()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
