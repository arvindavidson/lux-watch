package com.luxwatch.gadget.avatar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lux: a small glowing lantern-spirit, drawn procedurally on a 64x64 pixel grid
 * with ordered dithering and hard outlines, then scaled up with nearest-neighbour
 * so the pixels stay chunky (the same technique as the Muse boards' avatar, with
 * an original character). Pure Kotlin so it can be rendered and tested on the JVM.
 */
object LuxPixel : PixelAvatar {
    const val SIZE = PixelAvatar.SIZE
    override val name = "Lantern"

    private const val BLACK = 0xFF000000.toInt()
    private val OUTLINE = rgb(0x2A1A10)
    private val BODY = intArrayOf(rgb(0x8A4B1C), rgb(0xD48A2F), rgb(0xF6C65E), rgb(0xFFF1BE))
    private val EYE = rgb(0x24142E)
    private val SHINE = rgb(0xFFFFFF)
    private val BLUSH = rgb(0xFF7E8E)
    private val MOUTH = rgb(0x4A1A22)
    private val TONGUE = rgb(0xFF6F7D)
    private val ERROR_EYE = rgb(0x7A0F1C)

    // 4x4 Bayer matrix for ordered dithering.
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    override fun accent(mode: AvatarMode): Int = when (mode) {
        AvatarMode.BOOT -> rgb(0x6C7A96)
        AvatarMode.IDLE -> rgb(0xFFC94D)
        AvatarMode.LISTENING -> rgb(0x3DDCFF)
        AvatarMode.THINKING -> rgb(0xA87BFF)
        AvatarMode.SPEAKING -> rgb(0x4BE38A)
        AvatarMode.ERROR -> rgb(0xFF4D5E)
        AvatarMode.OFF -> rgb(0x3A3A3A)
    }

    override fun render(p: AvatarPose, out: IntArray) {
        require(out.size >= SIZE * SIZE)
        out.fill(BLACK, 0, SIZE * SIZE)
        val t = p.t
        val level = p.level.coerceIn(0f, 1f)
        val happy = p.happy.coerceIn(0f, 1f)
        val accent = accent(p.mode)
        val off = p.mode == AvatarMode.OFF
        val error = p.mode == AvatarMode.ERROR

        // Overall brightness: dim when off, fade in while booting.
        val bright = when (p.mode) {
            AvatarMode.OFF -> 0.35f
            AvatarMode.BOOT -> min(1f, 0.3f + p.modeT * 0.7f)
            else -> 1f
        }

        // Pose: centre, bob and size.
        val bob = when (p.mode) {
            AvatarMode.IDLE -> sin(t * 1.6f) * 0.8f
            AvatarMode.SPEAKING -> sin(t * 9f) * 0.5f
            AvatarMode.THINKING -> sin(t * 2.2f) * 0.5f
            else -> 0f
        } - happy * abs(sin(t * 10f)) * 2f
        val cx = 31.5f
        val cy = 34f + bob
        val r = 16.5f +
            (if (p.mode == AvatarMode.IDLE) sin(t * 1.6f) * 0.4f else 0f) +
            (if (p.mode == AvatarMode.LISTENING) level * 1.4f else 0f)

        // 1. Aura around the body, in the mode's accent colour.
        val auraIntensity = when (p.mode) {
            AvatarMode.IDLE -> 0.35f + 0.1f * sin(t * 1.6f)
            AvatarMode.LISTENING -> 0.45f + level * 0.55f
            AvatarMode.THINKING -> 0.55f
            AvatarMode.SPEAKING -> 0.4f + 0.3f * (0.5f + 0.5f * sin(t * 10f))
            AvatarMode.ERROR -> 0.5f + 0.2f * sin(t * 6f)
            AvatarMode.BOOT -> 0.2f
            AvatarMode.OFF -> 0f
        } + happy * 0.3f
        if (auraIntensity > 0f) {
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d <= r || d > r + 10f) continue
                val k = (d - r) / 10f
                var glow = auraIntensity * (1f - k) * (1f - k)
                if (p.mode == AvatarMode.THINKING) {
                    // Three lobes rotating around the body.
                    val a = atan2(dy, dx)
                    glow *= 0.35f + 0.65f * (0.5f + 0.5f * cos(3f * a - t * 3f))
                }
                val q = floor(glow * 3f + threshold(x, y)) / 3f
                if (q > 0f) out[y * SIZE + x] = scale(accent, min(1f, q))
            }
        }

        // 2. Body: a shaded sphere, warm light tinted toward the accent.
        val ramp = IntArray(4) { i ->
            val base = if (error) mix(BODY[i], rgb(0xE0465A), 0.45f) else BODY[i]
            val tinted = mix(base, accent, 0.18f)
            if (off) mix(tinted, rgb(0x777777), 0.7f) else tinted
        }
        val lx = -0.45f; val ly = -0.6f; val lz = 0.66f
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d > r) continue
            if (d > r - 1.1f) {
                out[y * SIZE + x] = OUTLINE
                continue
            }
            val nx = dx / r; val ny = dy / r
            val nz = sqrt(max(0f, 1f - nx * nx - ny * ny))
            val diff = max(0f, nx * lx + ny * ly + nz * lz)
            val v = 0.12f + 0.88f * diff
            val idx = floor(v * 3f + threshold(x, y) - 0.25f).toInt().coerceIn(0, 3)
            out[y * SIZE + x] = ramp[idx]
        }

        // 3. Spark above the head: Lux's light, twinkling.
        if (!off) {
            val sx = cx.toInt()
            val sy = (cy - r - 5f).toInt()
            val tw = 0.5f + 0.5f * sin(t * (if (p.mode == AvatarMode.THINKING) 6f else 2.5f))
            val arm = if (tw > 0.66f) 3 else if (tw > 0.33f) 2 else 1
            val c = mix(ramp[3], accent, 0.5f)
            for (i in -arm..arm) {
                set(out, sx + i, sy, c)
                set(out, sx, sy + i, c)
            }
            set(out, sx, sy, SHINE)
        }

        // 4. Eyes.
        val eyeY = cy - 2f
        val look = when (p.mode) {
            AvatarMode.THINKING -> Pair(sin(t * 1.3f) * 1.6f, -1.6f)
            AvatarMode.LISTENING -> Pair(0f, -0.4f)
            else -> Pair(0f, 0f)
        }
        val blink = (p.mode == AvatarMode.IDLE || p.mode == AvatarMode.LISTENING || p.mode == AvatarMode.SPEAKING) &&
            ((t + 2f) % 4.3f) < 0.13f // first blink ~2 s in, then every 4.3 s
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * 6.5f + look.first
            val ey = eyeY + look.second
            when {
                error -> {
                    for (i in -2..2) {
                        set(out, (ex + i).toInt(), (ey + i).toInt(), ERROR_EYE)
                        set(out, (ex + i).toInt(), (ey - i).toInt(), ERROR_EYE)
                    }
                }
                off || p.mode == AvatarMode.BOOT && p.modeT < 0.6f -> {
                    for (i in -2..2) set(out, (ex + i).toInt(), ey.toInt(), EYE)
                }
                happy > 0.3f -> {
                    // ^ ^ squint
                    for (i in -2..2) set(out, (ex + i).toInt(), (ey - 1 + abs(i)).toInt(), EYE)
                }
                blink -> for (i in -2..2) set(out, (ex + i).toInt(), ey.toInt(), EYE)
                else -> {
                    val ew = if (p.mode == AvatarMode.LISTENING) 2.7f else 2.2f
                    val eh = if (p.mode == AvatarMode.LISTENING) 3.8f else 3.2f
                    ellipse(out, ex, ey, ew, eh, EYE)
                    set(out, (ex - 0.8f).toInt(), (ey - 1.6f).toInt(), SHINE)
                }
            }
        }

        // 5. Cheeks when happy or chatting.
        if (!off && !error && (happy > 0.2f || p.mode == AvatarMode.IDLE || p.mode == AvatarMode.SPEAKING)) {
            for (side in intArrayOf(-1, 1)) {
                val bx = (cx + side * 10.5f).toInt()
                val by = (cy + 3f).toInt()
                set(out, bx, by, BLUSH)
                set(out, bx + side, by, BLUSH)
            }
        }

        // 6. Mouth.
        val mx = cx
        val my = cy + 6f
        when {
            off || p.mode == AvatarMode.BOOT -> Unit
            error -> for (i in -2..2) set(out, (mx + i).toInt(), (my + abs(i) * 0.5f).toInt() - 1, MOUTH) // frown
            p.mode == AvatarMode.SPEAKING -> {
                val open = 0.6f + (0.5f + 0.5f * sin(t * 14f + sin(t * 5f))) * 2.4f
                ellipse(out, mx, my, 2.6f, open, MOUTH)
                if (open > 1.6f) set(out, mx.toInt(), (my + open - 1f).toInt(), TONGUE)
            }
            p.mode == AvatarMode.LISTENING -> ellipse(out, mx, my, 1.2f, 1.2f + level * 1.5f, MOUTH)
            p.mode == AvatarMode.THINKING -> for (i in 0..2) set(out, (mx + i).toInt(), my.toInt(), MOUTH)
            else -> for (i in -2..2) set(out, (mx + i).toInt(), (my + 1 - abs(i) * 0.5f).toInt(), MOUTH) // smile
        }

        // 7. Thinking: three dots orbiting the body.
        if (p.mode == AvatarMode.THINKING) {
            for (i in 0 until 3) {
                val a = t * 2.4f + i * (2f * PI.toFloat() / 3f)
                val ox = cx + cos(a) * (r + 5.5f)
                val oy = cy + sin(a) * (r + 5.5f) * 0.55f
                set(out, ox.toInt(), oy.toInt(), mix(accent, SHINE, 0.4f))
            }
        }

        if (bright < 1f) for (i in 0 until SIZE * SIZE) out[i] = scale(out[i], bright)
    }

    // ---- helpers ----

    private fun threshold(x: Int, y: Int) = (BAYER[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f

    private fun set(out: IntArray, x: Int, y: Int, c: Int) {
        if (x in 0 until SIZE && y in 0 until SIZE) out[y * SIZE + x] = c
    }

    private fun ellipse(out: IntArray, cx: Float, cy: Float, rx: Float, ry: Float, c: Int) {
        for (y in floor(cy - ry).toInt()..(cy + ry).toInt() + 1) for (x in floor(cx - rx).toInt()..(cx + rx).toInt() + 1) {
            val nx = (x + 0.5f - cx) / rx
            val ny = (y + 0.5f - cy) / ry
            if (nx * nx + ny * ny <= 1f) set(out, x, y, c)
        }
    }

    private fun rgb(v: Int) = (0xFF shl 24) or v

    private fun scale(c: Int, k: Float): Int {
        val r = ((c shr 16 and 0xFF) * k).toInt()
        val g = ((c shr 8 and 0xFF) * k).toInt()
        val b = ((c and 0xFF) * k).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun mix(a: Int, b: Int, k: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - k) + ((b shr s) and 0xFF) * k).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
