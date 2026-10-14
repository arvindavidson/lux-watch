package com.luxwatch.gadget.avatar

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns the plain light background of Muse's avatar clips black, so the avatar
 * stands on the watch's black face. Only light pixels connected to the frame's
 * edge go (so a belly highlight, ear insides or a bubble stay), plus any near
 * the bottom (the floor between the feet); then the edges fade to black so
 * cropped clips blend into the round screen.
 *
 * Used on the watch when importing clips, and by gadget/tools/make_avatar_clips.sh.
 */
object BackgroundKey {
    private const val BG_MIN = 226     // background: every channel at least this
    private const val FRINGE_MIN = 196 // anti-aliased rim next to the background
    private const val MAX_TINT = 24    // ...and near-grey (max - min channel)

    /** Returns the keyed frame: opaque ARGB, [w]x[h], background black. */
    fun key(px: IntArray, w: Int, h: Int): IntArray {
        val bg = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        fun visit(i: Int) {
            if (!bg[i] && light(px[i], BG_MIN)) {
                bg[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) { visit(x); visit((h - 1) * w + x) }
        for (y in 0 until h) { visit(y * w); visit(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }
        // The floor is enclosed by the feet and tail: near the bottom, light goes regardless.
        for (y in h * 4 / 5 until h) for (x in 0 until w) {
            if (light(px[y * w + x], BG_MIN)) bg[y * w + x] = true
        }
        val grown = bg.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!bg[i] && light(px[i], FRINGE_MIN) && (bg[i - 1] || bg[i + 1] || bg[i - w] || bg[i + w])) grown[i] = true
        }
        val out = IntArray(w * h)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        for (y in 0 until h) for (x in 0 until w) {
            // Soft edge: the share of background in the 3x3 neighbourhood.
            var n = 0
            var k = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                n++
                if (grown[yy * w + xx]) k++
            }
            var keep = 1f - k.toFloat() / n
            val d = hypot((x - cx) / cx, (y - cy) / cy)
            keep *= 1f - smooth(0.80f, 1.0f, d)
            val c = px[y * w + x]
            val r = (((c shr 16) and 0xFF) * keep).roundToInt()
            val g = (((c shr 8) and 0xFF) * keep).roundToInt()
            val b = ((c and 0xFF) * keep).roundToInt()
            out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /**
     * Which source frames to keep to play at [targetFps] (at most [maxSeconds]):
     * source frame indices in order.
     */
    fun plan(sourceFrames: Int, sourceFps: Double, targetFps: Int = 15, maxSeconds: Int = 10): IntArray {
        if (sourceFrames <= 0 || sourceFps <= 0.0) return IntArray(0)
        val seconds = min(sourceFrames / sourceFps, maxSeconds.toDouble())
        val count = max(1, (seconds * targetFps).toInt())
        return IntArray(count) { i -> min(sourceFrames - 1, (i * sourceFps / targetFps).toInt()) }
    }

    private fun light(c: Int, minLevel: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val lo = min(r, min(g, b))
        val hi = max(r, max(g, b))
        return lo >= minLevel && hi - lo <= MAX_TINT
    }

    private fun smooth(a: Float, b: Float, v: Float): Float {
        val t = ((v - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
