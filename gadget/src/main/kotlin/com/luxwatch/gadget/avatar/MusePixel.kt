package com.luxwatch.gadget.avatar

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Muse: Lux's own pixel avatar — a small, soft gray rat standing upright,
 * drawn procedurally on a 64x64 pixel grid with ordered dithering and hard
 * 1 px outlines, then scaled up with nearest-neighbour so the pixels stay
 * chunky.
 *
 * Shape: big round ears with pink inners, a round head with large glossy
 * black eyes (white shine), a pink nose with thin whiskers, a pear-shaped
 * gray body with a pale belly patch, pink paws and feet, and a long pink
 * tail curling off to the right.
 *
 * Colours: warm mid-grays for the fur (dark 6B6672, mid 8D8894, light
 * ABA5B3, highlight CFC9D8), pale gray belly (9A949C..F0EBF2), soft pinks
 * for ears/nose/paws/tail (C96F82..FFD3DA), near-black eyes (18121A) with
 * white shine, and a per-mode glow ramp/aura taken from the shared scheme
 * table.
 *
 * Personality: bright, curious, a little playful. Ears perk up when
 * listening, one paw goes to the chin while thinking, and a happy reply
 * gets a hop, ^^ eyes, a big grin and two hearts floating up.
 *
 * Pure Kotlin so it renders and tests on the JVM. render() allocates
 * nothing and stays well under 1 ms per frame on a desktop JVM, leaving
 * the watch's UI thread plenty of room at 25 fps.
 */
object MusePixel : PixelAvatar {
    const val SIZE = PixelAvatar.SIZE
    override val name = "Pixel Lux"

    // ---------- palette ----------
    private const val BLACK = 0xFF000000.toInt()
    private val OUTLINE = rgb(0x241A20)
    private val FUR = intArrayOf(rgb(0x6B6672), rgb(0x8D8894), rgb(0xABA5B3), rgb(0xCFC9D8))
    private val BELLYT = intArrayOf(rgb(0x9A949C), rgb(0xBCB6BE), rgb(0xD8D3DA), rgb(0xF0EBF2))
    private val PINKT = intArrayOf(rgb(0xC96F82), rgb(0xE88B9C), rgb(0xF7AEB9), rgb(0xFFD3DA))
    private val EYE = rgb(0x18121A)
    private val SHINE = rgb(0xFFFFFF)
    private val WHITE = rgb(0xFFFFFF)
    private val BLUSH = rgb(0xE87E90)
    private val MOUTH = rgb(0x5A2A34)
    private val TONGUE = rgb(0xE86A7C)
    private val ERREYE = rgb(0x8A1626)
    private val SHADOWC = rgb(0x14141C)
    private val HEARTC = rgb(0xFF7E96)
    private val WHISK = rgb(0xD9D5DE)

    // Per-mode glow ramps (light -> deep) and fixed accents.
    private val GLOW = arrayOf(
        intArrayOf(rgb(0xFFFFFF), rgb(0xCFE0FF), rgb(0x8FA8FF), rgb(0x5A5FE0)), // BOOT
        intArrayOf(rgb(0xF4E8FF), rgb(0xC7A4FF), rgb(0x9A6BFF), rgb(0x5B3FD9)), // IDLE
        intArrayOf(rgb(0xE8FAFF), rgb(0x8FDCFF), rgb(0x3FA2FF), rgb(0x2A5BD7)), // LISTENING
        intArrayOf(rgb(0xFFE6FF), rgb(0xFF9CF0), rgb(0xD35BFF), rgb(0x7A2BD9)), // THINKING
        intArrayOf(rgb(0xEAFFF4), rgb(0x9FF5CF), rgb(0x3FD9A0), rgb(0x1F9A7A)), // SPEAKING
        intArrayOf(rgb(0xFFD6D6), rgb(0xFF6B6B), rgb(0xC7304A), rgb(0x6B1A3A)), // ERROR
        intArrayOf(rgb(0xD8D4FF), rgb(0x8F86D9), rgb(0x5A4FB0), rgb(0x2E2870)), // OFF
    )
    private val ACCENTS = intArrayOf(
        rgb(0xA9C0FF), rgb(0xA77DFF), rgb(0x5CB8FF), rgb(0xE07BFF),
        rgb(0x6FF0BF), rgb(0xFF5C5C), rgb(0x7C72D0),
    )

    // ---------- lookup tables (built once) ----------
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    private val SQLUT = FloatArray(8193) { i -> sqrt(i.toFloat()) }
    private val POW27 = FloatArray(257) { i -> (i / 256.0).pow(2.7).toFloat() }

    // ---------- stamped bitmaps (decoded once) ----------
    private val HEART_W = 7
    private val HEART_H = 6
    private val HEART_M = decodeStamp(
        arrayOf(
            ".##.##.",
            "#######",
            "#######",
            ".#####.",
            "..###..",
            "...#...",
        ), HEART_W, HEART_H
    )
    private val BANG_W = 5
    private val BANG_H = 9
    private val BANG_M = decodeStamp(
        arrayOf(
            "..#..",
            "..#..",
            "..#..",
            "..#..",
            "..#..",
            "..#..",
            ".....",
            "..#..",
            "..#..",
        ), BANG_W, BANG_H
    )

    // ---------- cross-frame state ----------
    private var lastT = -1f
    private var seed = 0x1F2E3D4C
    private var blinkT = 2.5f
    private var blinkDur = 0f
    private var blinkGap = 0f
    private var wantDouble = false
    private var gazeT = 1.5f
    private var gazeX = 0f
    private var gazeY = 0f
    private var gazeTx = 0f
    private var gazeTy = 0f
    private var glowInit = false
    private val glow = IntArray(4)
    private val furT = IntArray(4)
    private val sparkA = FloatArray(8)
    private val sparkR = FloatArray(8)
    private val sparkS = FloatArray(8)
    private val sparkP = FloatArray(8)

    private const val LX = -0.45f
    private const val LY = -0.6f
    private const val LZ = 0.66f

    init {
        for (i in 0 until 8) {
            sparkA[i] = rnd() * 6.2831853f
            sparkR[i] = 20f + rnd() * 10f
            sparkS[i] = (0.4f + rnd() * 0.8f) * (if (i % 2 == 0) 1f else -1f)
            sparkP[i] = rnd() * 6.2831853f
        }
    }

    override fun accent(mode: AvatarMode): Int = ACCENTS[mode.ordinal]

    override fun render(p: AvatarPose, out: IntArray) {
        require(out.size >= SIZE * SIZE)
        val dt = if (lastT < 0f) 0.04f else (p.t - lastT).coerceIn(0f, 0.2f)
        lastT = p.t
        val t = p.t
        val mode = p.mode
        val mi = mode.ordinal
        val modeT = p.modeT
        val level = p.level.coerceIn(0f, 1f)
        val happy = p.happy.coerceIn(0f, 1f)
        val error = mode == AvatarMode.ERROR
        val off = mode == AvatarMode.OFF

        // Blend the glow ramp toward the current mode.
        val kBlend = 1f - exp(-dt * 7f)
        val scheme = GLOW[mi]
        if (!glowInit) {
            for (i in 0..3) glow[i] = scheme[i]
            glowInit = true
        } else {
            for (i in 0..3) glow[i] = mixC(glow[i], scheme[i], kBlend)
        }

        var bright = 1f
        if (off) bright = max(0.12f, 1f - modeT / 1.3f)
        else if (mode == AvatarMode.BOOT) bright = min(1f, 0.35f + modeT * 0.65f)

        // Blinks: every 2.2-5.2 s, ~0.16 s each, sometimes a double.
        blinkT -= dt
        if (blinkDur > 0f) {
            blinkDur -= dt
            if (blinkDur <= 0f && wantDouble) {
                wantDouble = false
                blinkGap = 0.12f
            }
        } else if (blinkGap > 0f) {
            blinkGap -= dt
            if (blinkGap <= 0f) blinkDur = 0.16f
        } else if (blinkT <= 0f) {
            blinkDur = 0.16f
            blinkT = 2.2f + rnd() * 3f
            wantDouble = rnd() < 0.3f
        }
        val eyesShut = blinkDur > 0f || off || (mode == AvatarMode.BOOT && modeT < 0.9f)

        // Gaze wanders to random targets and eases there.
        gazeT -= dt
        if (gazeT <= 0f) {
            gazeT = 1.2f + rnd() * 2.4f
            gazeTx = rnd() * 3f - 1.5f
            gazeTy = rnd() * 2f - 1f
        }
        val gk = 1f - exp(-dt * 14f)
        gazeX += (gazeTx - gazeX) * gk
        gazeY += (gazeTy - gazeY) * gk

        // Fur tinted toward the mode (red in ERROR).
        if (error) {
            for (i in 0..3) furT[i] = mixC(FUR[i], rgb(0xE0465A), 0.45f)
        } else {
            for (i in 0..3) furT[i] = mixC(FUR[i], glow[3], 0.12f)
        }

        // ---- pose geometry ----
        val breath = sin(t * 1.8f) * 0.03f
        var bob = 0f
        when (mode) {
            AvatarMode.IDLE -> bob = sin(t * 1.6f) * 0.9f
            AvatarMode.BOOT -> bob = if (modeT > 0.6f) sin(t * 1.6f) * 0.9f else 0f
            AvatarMode.SPEAKING -> bob = sin(t * 9f) * 0.6f
            AvatarMode.THINKING -> bob = sin(t * 2.2f) * 0.5f
            else -> { /* steady */ }
        }
        bob -= happy * abs(sin(t * 10f)) * 2.5f
        val shakeX = if (error && modeT < 0.6f) sin(modeT * 40f) * 2f * (1f - modeT / 0.6f) else 0f

        // BOOT squash-and-stretch around the feet anchor (0.6 s).
        var bsx = 1f
        var bsy = 1f
        if (mode == AvatarMode.BOOT && modeT < 0.6f) {
            val q = modeT / 0.6f
            bsy = 0.5f + 0.5f * q
            bsx = 1.25f - 0.25f * q
        }

        val bodyCx = squashX(32f, bsx) + shakeX
        val bodyCy = squashY(43f, bsy) + bob
        val bodyRx = 12f * (1f + breath) * bsx
        val bodyRy = 15f * (1f - breath) * bsy
        val headCx = squashX(32f, bsx) + shakeX + (if (mode == AvatarMode.THINKING) 1.5f else 0f)
        val headCy = squashY(21f, bsy) + bob * 0.6f
        val headR = 11.5f * (bsx + bsy) * 0.5f

        val earPerk = if (mode == AvatarMode.LISTENING) -2.2f else 0f
        val earSpread = if (mode == AvatarMode.LISTENING) 1f else 0f
        val earLx = squashX(21.5f, bsx) + shakeX - earSpread
        val earRx = squashX(42.5f, bsx) + shakeX + earSpread
        val earY = squashY(10.5f, bsy) + bob * 0.6f + earPerk
        val earR = 6f * (bsx + bsy) * 0.5f

        val bellyCx = squashX(32f, bsx) + shakeX
        val bellyCy = squashY(45f, bsy) + bob
        val bellyRx = 7.5f * (1f + breath) * bsx
        val bellyRy = 11f * (1f - breath) * bsy

        var pawLx = 22f
        var pawLy = 46.5f
        var pawRx = 42f
        var pawRy = 46.5f
        when (mode) {
            AvatarMode.LISTENING -> {
                pawLx = 24f; pawLy = 31.5f
                pawRx = 40f; pawRy = 31.5f
            }
            AvatarMode.THINKING -> {
                pawRx = 37.5f; pawRy = 32f
            }
            AvatarMode.SPEAKING -> {
                pawLx = 22f + sin(t * 7f) * 2f
                pawLy = 46.5f + cos(t * 9f) * 1.5f
                pawRx = 42f - sin(t * 7f + 1f) * 2f
                pawRy = 46.5f - cos(t * 9f) * 1.5f
            }
            AvatarMode.OFF -> {
                pawRx = 41f + sin(t * 12f) * 2.5f
                pawRy = 29f
            }
            else -> { /* base pose */ }
        }
        if (happy > 0.3f && !error && !off) {
            pawLx = 22.5f
            pawLy = 30.5f + sin(t * 14f) * 1.5f
            pawRx = 41.5f
            pawRy = 30.5f - sin(t * 14f) * 1.5f
        }
        pawLx = squashX(pawLx, bsx) + shakeX
        pawLy = squashY(pawLy, bsy) + bob
        pawRx = squashX(pawRx, bsx) + shakeX
        pawRy = squashY(pawRy, bsy) + bob

        val footY = squashY(54f, bsy) + bob * 0.5f
        var footLx = squashX(26.5f, bsx) + shakeX
        var footRx = squashX(37.5f, bsx) + shakeX
        if (mode == AvatarMode.SPEAKING) {
            footLx += sin(t * 9f) * 1f
            footRx -= sin(t * 9f) * 1f
        }

        val lookX: Float
        val lookY: Float
        when (mode) {
            AvatarMode.THINKING -> {
                lookX = sin(t * 1.3f) * 1.5f
                lookY = -1.5f
            }
            AvatarMode.LISTENING -> {
                lookX = 0f
                lookY = -0.3f
            }
            else -> {
                lookX = gazeX
                lookY = gazeY
            }
        }
        val noseX = headCx
        val noseY = headCy + 6.5f
        val eyeY = headCy + lookY
        val eyeLx = headCx - 5.5f + lookX
        val eyeRx = headCx + 5.5f + lookX
        val eyeR = if (mode == AvatarMode.LISTENING) 3.7f else 3.1f
        val mouthX = headCx
        val mouthY = headCy + 10.5f

        // ---- draw ----
        out.fill(BLACK, 0, SIZE * SIZE)

        // Aura in the mode's colours.
        val auraI = when (mode) {
            AvatarMode.IDLE -> 0.35f + 0.1f * sin(t * 1.6f)
            AvatarMode.LISTENING -> 0.45f + level * 0.55f
            AvatarMode.THINKING -> 0.55f
            AvatarMode.SPEAKING -> 0.4f + 0.3f * (0.5f + 0.5f * sin(t * 10f))
            AvatarMode.ERROR -> 0.5f + 0.2f * sin(t * 6f)
            AvatarMode.BOOT -> 0.2f
            AvatarMode.OFF -> 0f
        } + happy * 0.3f
        if (auraI > 0.02f) {
            val ar = 21f
            for (y in 0 until SIZE) {
                for (x in 0 until SIZE) {
                    val dx = x + 0.5f - bodyCx
                    val dy = y + 0.5f - bodyCy
                    val d2 = dx * dx + dy * dy
                    if (d2 < ar * ar) continue
                    val di = min(8192, d2.toInt())
                    if (di > 1089) continue
                    val d = SQLUT[di]
                    val kk = (d - ar) / 12f
                    val g = auraI * (1f - kk) * (1f - kk) + (bayer(x, y) - 0.5f) * 0.12f
                    if (g <= 0.02f) continue
                    var qi = (g * 4f).toInt()
                    if (qi > 3) qi = 3
                    out[y * SIZE + x] = glow[3 - qi] // brightest next to the body, deepest where it fades
                }
            }
        }

        // Dithered floor shadow.
        run {
            val scx = 32f + shakeX
            val scy = 58.5f
            val srx = 14f
            val sry = 2.6f
            val x0 = max(0, (scx - srx).toInt())
            val x1 = min(SIZE - 1, (scx + srx).toInt())
            val y0 = max(0, (scy - sry).toInt())
            val y1 = min(SIZE - 1, (scy + sry).toInt())
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val nx = (x + 0.5f - scx) / srx
                    val ny = (y + 0.5f - scy) / sry
                    val q = nx * nx + ny * ny
                    if (q < 1f && bayer(x, y) < 0.75f * (1f - q) + 0.1f) out[y * SIZE + x] = SHADOWC
                }
            }
        }

        // Sparkles behind the body.
        for (i in 0 until 4) drawSparkle(out, t, i, bodyCx, bodyCy, mode, modeT, glow[1])

        // Tail: tapering bezier curling right, wagging at the tip.
        run {
            val wag = sin(t * 3f)
            val p0x = bodyCx + 10f
            val p0y = bodyCy + 8f
            val p1x = bodyCx + 22f
            val p1y = bodyCy + 14f + wag * 2f
            val p2x = bodyCx + 26f
            val p2y = bodyCy + 6f + wag * 3f
            var s = 0f
            while (s <= 1f) {
                val u = 1f - s
                val px = u * u * p0x + 2f * u * s * p1x + s * s * p2x
                val py = u * u * p0y + 2f * u * s * p1y + s * s * p2y
                val r = 2.1f * u + 0.7f
                drawFlat(out, px, py, r, r * 0.85f, PINKT[1])
                s += 1f / 12f
            }
        }

        // Ears (outer fur, pink inner).
        drawShaded(out, earLx, earY, earR, earR, furT, false)
        drawShaded(out, earRx, earY, earR, earR, furT, false)
        drawShaded(out, earLx - 0.6f, earY - 0.8f, earR * 0.55f, earR * 0.62f, PINKT, false)
        drawShaded(out, earRx + 0.6f, earY - 0.8f, earR * 0.55f, earR * 0.62f, PINKT, false)

        // Body, belly, feet, arms, paws.
        drawShaded(out, bodyCx, bodyCy, bodyRx, bodyRy, furT, true)
        drawShaded(out, bellyCx, bellyCy, bellyRx, bellyRy, BELLYT, false)
        drawShaded(out, footLx, footY, 3.6f, 2.4f, PINKT, false)
        drawShaded(out, footRx, footY, 3.6f, 2.4f, PINKT, false)
        drawArm(out, 24.5f, 40f, pawLx, pawLy, bob, shakeX, bsx, bsy)
        drawArm(out, 39.5f, 40f, pawRx, pawRy, bob, shakeX, bsx, bsy)
        drawShaded(out, pawLx, pawLy, 2.7f, 2.7f, PINKT, false)
        drawShaded(out, pawRx, pawRy, 2.7f, 2.7f, PINKT, false)

        // Head.
        drawShaded(out, headCx, headCy, headR, headR, furT, false)

        // Sparkles in front of the body.
        for (i in 4 until 8) drawSparkle(out, t, i, bodyCx, bodyCy, mode, modeT, glow[1])

        // Nose.
        drawFlat(out, noseX, noseY, 2.3f, 1.8f, PINKT[2])
        setP(out, (noseX - 0.8f).toInt(), (noseY - 0.7f).toInt(), SHINE)

        // Eyes.
        if (error) {
            drawX(out, eyeLx, eyeY, ERREYE)
            drawX(out, eyeRx, eyeY, ERREYE)
        } else if (eyesShut) {
            eyeLine(out, eyeLx, eyeY)
            eyeLine(out, eyeRx, eyeY)
        } else if (happy > 0.3f) {
            happyEyes(out, eyeLx, eyeY)
            happyEyes(out, eyeRx, eyeY)
        } else {
            drawEye(out, eyeLx, eyeY, eyeR)
            drawEye(out, eyeRx, eyeY, eyeR)
            if (mode == AvatarMode.LISTENING) {
                brow(out, eyeLx - 1f, eyeY - eyeR - 2.2f, 1)
                brow(out, eyeRx + 1f, eyeY - eyeR - 2.2f, -1)
            }
        }

        // Mouth.
        when {
            off -> mouthLine(out, mouthX, mouthY, 2)
            mode == AvatarMode.BOOT -> { /* none yet */ }
            error -> mouthLine(out, mouthX, mouthY, 2)
            mode == AvatarMode.LISTENING -> drawFlat(out, mouthX, mouthY, 1.4f, 1.6f + level * 1.5f, MOUTH)
            mode == AvatarMode.THINKING -> {
                setP(out, mouthX.toInt(), mouthY.toInt(), MOUTH)
                setP(out, mouthX.toInt() + 1, mouthY.toInt(), MOUTH)
                setP(out, mouthX.toInt() + 2, (mouthY - 0.5f).toInt(), MOUTH)
            }
            mode == AvatarMode.SPEAKING -> {
                val open = 1f + (0.5f + 0.5f * sin(t * 13f + sin(t * 4.7f) * 1.8f)) * 2.8f
                drawFlat(out, mouthX, mouthY, 2.6f, open, MOUTH)
                if (open > 2f) drawFlat(out, mouthX, mouthY + open - 1.2f, 1.4f, 1f, TONGUE)
            }
            happy > 0.3f -> {
                for (i in -4..4) setP(out, (mouthX + i).toInt(), (mouthY + 1.5f - abs(i) * 0.45f).toInt(), MOUTH)
                drawFlat(out, mouthX, mouthY + 1.5f, 2f, 1.2f, TONGUE)
            }
            else -> {
                for (i in -3..3) setP(out, (mouthX + i).toInt(), (mouthY + 1.5f - abs(i) * 0.5f).toInt(), MOUTH)
            }
        }

        // Blush.
        if (!error && !off && (happy > 0.2f || mode == AvatarMode.IDLE || mode == AvatarMode.SPEAKING)) {
            blush(out, headCx - 8.5f, headCy + 6f)
            blush(out, headCx + 8.5f, headCy + 6f)
        }

        // Whiskers.
        whisker(out, noseX - 2f, noseY + 1f, noseX - 11f, noseY - 1f)
        whisker(out, noseX - 2f, noseY + 1.5f, noseX - 11f, noseY + 1.5f)
        whisker(out, noseX - 2f, noseY + 2f, noseX - 11f, noseY + 4f)
        whisker(out, noseX + 2f, noseY + 1f, noseX + 11f, noseY - 1f)
        whisker(out, noseX + 2f, noseY + 1.5f, noseX + 11f, noseY + 1.5f)
        whisker(out, noseX + 2f, noseY + 2f, noseX + 11f, noseY + 4f)

        // Expanding dotted rings while listening / speaking.
        if (mode == AvatarMode.LISTENING || mode == AvatarMode.SPEAKING) {
            val maxR = if (mode == AvatarMode.LISTENING) 12f + level * 8f else 16f
            for (ring in 0..1) {
                val rr = 5f + (modeT * 14f + ring * 10f) % (maxR - 5f)
                var n = 0
                var a = 0f
                while (n < 36) {
                    if (n % 2 == 0) {
                        setP(
                            out,
                            (headCx + cos(a) * rr).toInt(),
                            (headCy + sin(a) * rr * 0.92f).toInt(),
                            glow[1]
                        )
                    }
                    a += 0.1745f
                    n++
                }
            }
        }

        // Thought dots stepping up beside the head.
        if (mode == AvatarMode.THINKING) {
            thoughtDot(out, headCx - 13f, headCy - 8f, modeT > 0.2f, 1)
            thoughtDot(out, headCx - 17f, headCy - 13f, modeT > 0.55f, 1)
            thoughtDot(out, headCx - 21f, headCy - 18f, modeT > 0.9f, 2)
        }

        // Alert mark in ERROR.
        if (error) stamp(out, 47, 12, BANG_M, BANG_W, BANG_H, rgb(0xFF5C5C))

        // Hearts float up when happy.
        if (happy > 0.25f && !error) {
            val hy1 = 30f - (t * 10f) % 24f
            val hy2 = 28f - (t * 10f + 12f) % 24f
            stamp(out, (20f + sin(t * 3f) * 2f).toInt() - 3, hy1.toInt() - 3, HEART_M, HEART_W, HEART_H, HEARTC)
            stamp(out, (44f - sin(t * 3f + 1f) * 2f).toInt() - 3, hy2.toInt() - 3, HEART_M, HEART_W, HEART_H, HEARTC)
        }

        if (bright < 1f) {
            for (i in 0 until SIZE * SIZE) out[i] = scaleC(out[i], bright)
        }
    }

    // ---------- helpers (no allocation, safe to call per frame) ----------

    private fun rgb(v: Int): Int = (0xFF shl 24) or v

    private fun mixC(a: Int, b: Int, k: Float): Int {
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar * (1f - k) + br * k).toInt()
        val g = (ag * (1f - k) + bg * k).toInt()
        val bb2 = (ab * (1f - k) + bb * k).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bb2
    }

    private fun scaleC(c: Int, k: Float): Int {
        val r = (((c shr 16) and 0xFF) * k).toInt()
        val g = (((c shr 8) and 0xFF) * k).toInt()
        val b = ((c and 0xFF) * k).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun bayer(x: Int, y: Int): Float = (BAYER[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f

    private fun hash01(x: Int, y: Int): Float {
        var h = x * 73856093 xor y * 19349663
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return (h and 0x7fffffff).toFloat() / 2147483647f
    }

    private fun rnd(): Float {
        seed = seed * 1664525 + 1013904223
        return ((seed ushr 9) and 0x7FFFFF).toFloat() / 8388607f
    }

    private fun setP(out: IntArray, x: Int, y: Int, c: Int) {
        if (x in 0 until SIZE && y in 0 until SIZE) out[y * SIZE + x] = c
    }

    private fun squashX(bx: Float, bsx: Float): Float = 32f + (bx - 32f) * bsx

    private fun squashY(by: Float, bsy: Float): Float = 56.5f + (by - 56.5f) * bsy

    private fun shapeQ(dx: Float, dy: Float, rx: Float, ry: Float, se: Boolean): Float {
        val ax = abs(dx) / rx
        val ay = abs(dy) / ry
        return if (se) {
            POW27[min(256, (ax * 256f).toInt())] + POW27[min(256, (ay * 256f).toInt())]
        } else {
            ax * ax + ay * ay
        }
    }

    private fun ellQ(px: Float, py: Float, rx: Float, ry: Float): Float =
        px * px / (rx * rx) + py * py / (ry * ry)

    private fun drawShaded(
        out: IntArray, cx: Float, cy: Float, rx: Float, ry: Float, tones: IntArray, se: Boolean
    ) {
        val x0 = max(0, floor(cx - rx - 1f).toInt())
        val x1 = min(SIZE - 1, (cx + rx + 1f).toInt())
        val y0 = max(0, floor(cy - ry - 1f).toInt())
        val y1 = min(SIZE - 1, (cy + ry + 1f).toInt())
        for (y in y0..y1) {
            for (x in x0..x1) {
                val px = x + 0.5f - cx
                val py = y + 0.5f - cy
                if (shapeQ(px, py, rx, ry, se) > 1f) continue
                if (shapeQ(px + 1f, py, rx, ry, se) > 1f ||
                    shapeQ(px - 1f, py, rx, ry, se) > 1f ||
                    shapeQ(px, py + 1f, rx, ry, se) > 1f ||
                    shapeQ(px, py - 1f, rx, ry, se) > 1f
                ) {
                    out[y * SIZE + x] = OUTLINE
                    continue
                }
                val nx = px / rx
                val ny = py / ry
                val qe = (nx * nx + ny * ny).coerceIn(0f, 1f)
                val nz = SQLUT[((1f - qe) * 8192f).toInt()] * (1f / 90.50967f) // sqrt(8192): back to 0..1
                val diff = max(0f, nx * LX + ny * LY + nz * LZ)
                var idx = ((0.15f + 0.85f * diff) * 3f + bayer(x, y) - 0.25f).toInt()
                if (idx < 0) idx = 0 else if (idx > 3) idx = 3
                if (hash01(x, y) > 0.82f && idx > 0) idx--
                out[y * SIZE + x] = tones[idx]
            }
        }
    }

    private fun drawFlat(out: IntArray, cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        val x0 = max(0, floor(cx - rx - 1f).toInt())
        val x1 = min(SIZE - 1, (cx + rx + 1f).toInt())
        val y0 = max(0, floor(cy - ry - 1f).toInt())
        val y1 = min(SIZE - 1, (cy + ry + 1f).toInt())
        for (y in y0..y1) {
            for (x in x0..x1) {
                val px = x + 0.5f - cx
                val py = y + 0.5f - cy
                if (ellQ(px, py, rx, ry) > 1f) continue
                if (ellQ(px + 1f, py, rx, ry) > 1f || ellQ(px - 1f, py, rx, ry) > 1f ||
                    ellQ(px, py + 1f, rx, ry) > 1f || ellQ(px, py - 1f, rx, ry) > 1f
                ) {
                    out[y * SIZE + x] = OUTLINE
                    continue
                }
                out[y * SIZE + x] = color
            }
        }
    }

    private fun drawArm(
        out: IntArray, sx: Float, sy: Float, px: Float, py: Float,
        bob: Float, shakeX: Float, bsx: Float, bsy: Float
    ) {
        val csx = squashX(sx, bsx) + shakeX
        val csy = squashY(sy, bsy) + bob
        val mx = (csx + px) * 0.5f
        val my = (csy + py) * 0.5f
        val dx = px - csx
        val dy = py - csy
        val len = SQLUT[min(8192, (dx * dx + dy * dy).toInt())]
        drawShaded(out, mx, my, 3f, len * 0.5f + 2.5f, furT, false)
    }

    private fun drawEye(out: IntArray, ex: Float, ey: Float, r: Float) {
        drawFlat(out, ex, ey, r, r * 1.15f, EYE)
        setP(out, (ex - r * 0.35f).toInt(), (ey - r * 0.5f).toInt(), SHINE)
        setP(out, (ex - r * 0.35f).toInt() + 1, (ey - r * 0.5f).toInt(), SHINE)
        setP(out, (ex + r * 0.3f).toInt(), (ey + r * 0.45f).toInt(), SHINE)
    }

    private fun drawX(out: IntArray, ex: Float, ey: Float, c: Int) {
        val xi = ex.toInt()
        val yi = ey.toInt()
        for (i in -2..2) {
            setP(out, xi + i, yi + i, c)
            setP(out, xi + i, yi - i, c)
        }
    }

    private fun eyeLine(out: IntArray, ex: Float, ey: Float) {
        val xi = ex.toInt()
        val yi = ey.toInt()
        for (i in -2..2) setP(out, xi + i, yi, EYE)
    }

    private fun happyEyes(out: IntArray, ex: Float, ey: Float) {
        val xi = ex.toInt()
        val yi = ey.toInt()
        setP(out, xi - 2, yi, EYE)
        setP(out, xi + 2, yi, EYE)
        setP(out, xi - 1, yi - 1, EYE)
        setP(out, xi + 1, yi - 1, EYE)
        setP(out, xi, yi - 2, EYE)
    }

    private fun brow(out: IntArray, ex: Float, ey: Float, dir: Int) {
        val xi = ex.toInt()
        val yi = ey.toInt()
        for (i in 0..2) setP(out, xi + i * dir, yi - i / 2, furT[3])
    }

    private fun mouthLine(out: IntArray, mx: Float, my: Float, w: Int) {
        val xi = mx.toInt()
        val yi = my.toInt()
        for (i in -w..w) setP(out, xi + i, yi, MOUTH)
    }

    private fun blush(out: IntArray, bx: Float, by: Float) {
        val xi = bx.toInt()
        val yi = by.toInt()
        setP(out, xi, yi, BLUSH)
        setP(out, xi + 1, yi, BLUSH)
        setP(out, xi, yi + 1, BLUSH)
        setP(out, xi + 1, yi + 1, BLUSH)
    }

    private fun whisker(out: IntArray, x0: Float, y0: Float, x1: Float, y1: Float) {
        var i = 0
        while (i <= 12) {
            val f = i / 12f
            setP(out, (x0 + (x1 - x0) * f).toInt(), (y0 + (y1 - y0) * f).toInt(), WHISK)
            i++
        }
    }

    private fun stamp(out: IntArray, x: Int, y: Int, mask: ByteArray, w: Int, h: Int, color: Int) {
        for (dy in 0 until h) {
            for (dx in 0 until w) {
                if (mask[dy * w + dx] != 0.toByte()) setP(out, x + dx, y + dy, color)
            }
        }
    }

    private fun drawSparkle(
        out: IntArray, t: Float, i: Int, cx: Float, cy: Float,
        mode: AvatarMode, modeT: Float, color: Int
    ) {
        if (mode == AvatarMode.BOOT && modeT < 0.2f + i * 0.12f) return
        val spd = if (mode == AvatarMode.THINKING) 2f else 1f
        val a = sparkA[i] + t * sparkS[i] * spd
        val xi = (cx + cos(a) * sparkR[i]).toInt()
        val yi = (cy + sin(a) * sparkR[i] * 0.8f).toInt()
        if (xi < 2 || xi > SIZE - 3 || yi < 2 || yi > SIZE - 3) return
        val tw = 0.5f + 0.5f * sin(t * 5f + sparkP[i])
        val r = if (tw > 0.66f) 2 else 1
        for (d in -r..r) {
            setP(out, xi + d, yi, color)
            setP(out, xi, yi + d, color)
        }
        setP(out, xi, yi, WHITE)
    }

    private fun thoughtDot(out: IntArray, x: Float, y: Float, on: Boolean, r: Int) {
        if (!on) return
        val xi = x.toInt()
        val yi = y.toInt()
        for (dy in -r..r) {
            for (dx in -r..r) {
                if (dx * dx + dy * dy <= r * r) setP(out, xi + dx, yi + dy, glow[2])
            }
        }
    }

    private fun decodeStamp(rows: Array<String>, w: Int, h: Int): ByteArray {
        val m = ByteArray(w * h)
        for (y in 0 until h) {
            val row = rows[y]
            for (x in 0 until w) m[y * w + x] = if (row[x] == '#') 1 else 0
        }
        return m
    }
}
