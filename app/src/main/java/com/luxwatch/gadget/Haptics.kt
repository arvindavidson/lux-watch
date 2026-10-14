package com.luxwatch.gadget

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/** Short wrist cues for the talk flow; can be turned off in Settings. */
class Haptics(context: Context) {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val prefs = context.getSharedPreferences("lux_prefs", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) = prefs.edit().putBoolean("haptics", value).apply()

    /** Listening started. */
    fun listen() = play(VibrationEffect.EFFECT_CLICK)

    /** Recording sent. */
    fun send() = play(VibrationEffect.EFFECT_TICK)

    /** Lux started answering. */
    fun reply() = play(VibrationEffect.EFFECT_DOUBLE_CLICK)

    /** One bezel step (volume). */
    fun tick() = play(VibrationEffect.EFFECT_TICK)

    /** Something went wrong. */
    fun error() = play(VibrationEffect.EFFECT_HEAVY_CLICK)

    private fun play(effect: Int) {
        val v = vibrator ?: return
        if (enabled && v.hasVibrator()) v.vibrate(VibrationEffect.createPredefined(effect))
    }
}
