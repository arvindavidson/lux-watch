package com.luxwatch.gadget.voice

import kotlin.math.max
import kotlin.math.min

/**
 * Decides when the speaker has finished, from the mic level (0..1) sampled
 * every few tens of ms: send after [trailingSilenceMs] of quiet that follows
 * at least [minSpeechMs] of speech; give up if nobody speaks within
 * [noSpeechMs]. Thresholds sit above a slowly tracked background-noise floor,
 * so a noisy room doesn't keep it listening forever.
 */
class Endpointer(
    private val trailingSilenceMs: Long = 1_200,
    private val minSpeechMs: Long = 250,
    private val noSpeechMs: Long = 6_000,
    /** Ignore the start (the listen haptic buzz reaches the mic). */
    private val ignoreMs: Long = 250,
    /** Until then, the quietest level so far sets the noise floor. */
    private val calibrateMs: Long = 600,
) {
    enum class Decision { LISTEN, SEND, GIVE_UP }

    var heardSpeech = false
        private set

    private var floor = -1f
    private var speechMs = 0L
    private var lastLoudAt = 0L
    private var lastT = 0L

    /** [tMs]: time since listening started. */
    fun update(level: Float, tMs: Long): Decision {
        val dt = (tMs - lastT).coerceIn(0L, 200L)
        lastT = tMs
        if (tMs < ignoreMs) return Decision.LISTEN
        if (floor < 0f || tMs < calibrateMs) floor = min(FLOOR_MAX, if (floor < 0f) level else min(floor, level))

        val speechAt = max(SPEECH_MIN, floor + SPEECH_MARGIN)
        val quietAt = max(QUIET_MIN, floor + QUIET_MARGIN)
        // Once talking, anything above the quiet line keeps the turn going.
        val loud = level >= if (heardSpeech) quietAt else speechAt
        if (loud) {
            speechMs += dt
            lastLoudAt = tMs
            if (speechMs >= minSpeechMs) heardSpeech = true
        } else {
            if (!heardSpeech) speechMs = max(0L, speechMs - dt) // a click or a cough doesn't add up
            if (level < speechAt) floor = min(FLOOR_MAX, floor + (level - floor) * 0.05f)
        }

        return when {
            heardSpeech && tMs - lastLoudAt >= trailingSilenceMs -> Decision.SEND
            !heardSpeech && tMs >= noSpeechMs -> Decision.GIVE_UP
            else -> Decision.LISTEN
        }
    }

    private companion object {
        const val SPEECH_MIN = 0.08f
        const val SPEECH_MARGIN = 0.08f
        const val QUIET_MIN = 0.04f
        const val QUIET_MARGIN = 0.04f
        const val FLOOR_MAX = 0.2f
    }
}
