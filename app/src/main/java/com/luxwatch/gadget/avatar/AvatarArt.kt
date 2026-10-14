package com.luxwatch.gadget.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.math.min
import com.luxwatch.gadget.R
import kotlin.math.max

/** How an avatar is drawn. */
enum class DrawStyle {
    /** Animated. */
    LIVE,

    /** A still frame at full brightness: the watch face while nothing is happening (saves battery). */
    STILL,

    /** Always-on: still and dim. */
    AMBIENT,
}

/** An avatar as the app draws it, on the talk screen and the watch face. */
interface AvatarArt {
    val name: String

    /** Accent colour of a mode (0xFFRRGGBB) for the UI around the avatar. */
    fun accent(mode: AvatarMode): Int

    /** Draws the avatar for [pose] into the square [dst]. */
    fun draw(canvas: Canvas, dst: Rect, pose: AvatarPose, style: DrawStyle)
}

/**
 * Every avatar the app offers; the first is the default: the one Muse sent
 * (avatar.set_clips) if any, then the built-in clips and the pixel avatars.
 */
object AvatarCatalog {
    @Volatile private var cached: List<AvatarArt>? = null

    fun all(context: Context): List<AvatarArt> = cached ?: synchronized(this) {
        cached ?: build(context.applicationContext).also { cached = it }
    }

    /** Re-reads the avatars (after Muse sends new clips). */
    fun refresh() {
        cached = null
    }

    private fun build(context: Context): List<AvatarArt> {
        val imported = FrameClipArt.load(context)
        val bundled = ClipArt(context).takeIf { it.name != imported?.name }
        return listOfNotNull<AvatarArt>(imported, bundled) + Avatars.all.map(::PixelArt)
    }

    fun byName(context: Context, name: String?): AvatarArt =
        all(context).firstOrNull { it.name == name } ?: all(context).first()
}

/** A [PixelAvatar] (64x64, procedural) scaled up with nearest-neighbour. */
class PixelArt(private val avatar: PixelAvatar) : AvatarArt {
    override val name: String get() = avatar.name
    private val size = PixelAvatar.SIZE
    private val pixels = IntArray(size * size)
    private val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val src = Rect(0, 0, size, size)
    private val paint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }

    override fun accent(mode: AvatarMode) = avatar.accent(mode)

    override fun draw(canvas: Canvas, dst: Rect, pose: AvatarPose, style: DrawStyle) {
        // Rendering costs a fraction of a millisecond, so STILL just draws the pose.
        if (style == DrawStyle.AMBIENT) {
            // Asleep: the OFF pose's last frame, brightened so it reads, no glow.
            avatar.render(AvatarPose(AvatarMode.OFF, pose.t, 10f), pixels)
            brighten(pixels)
        } else {
            avatar.render(pose, pixels)
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        canvas.drawBitmap(bitmap, src, dst, paint)
    }

    private fun brighten(px: IntArray) {
        var peak = 1
        for (c in px) peak = max(peak, max((c shr 16) and 0xFF, max((c shr 8) and 0xFF, c and 0xFF)))
        val k = (160f / peak).coerceIn(1f, 6f)
        for (i in px.indices) {
            val c = px[i]
            val r = (((c shr 16) and 0xFF) * k).toInt().coerceAtMost(255)
            val g = (((c shr 8) and 0xFF) * k).toInt().coerceAtMost(255)
            val b = ((c and 0xFF) * k).toInt().coerceAtMost(255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

/**
 * Lux as Muse draws it: Muse's own avatar clips (res/raw, made from the MP4s by
 * gadget/tools/make_avatar_clips.sh), one per state, cross-faded on change, with
 * a thin state ring around them. Ambient shows a dimmed still.
 *
 * Clips: idle ("connecting": looking around) for idle, connecting, listening and
 * errors; working (typing at the laptop) while thinking; making (the little dance)
 * while speaking; milestone (proud celebration, plays once) when pairing succeeds.
 */
class ClipArt(private val context: Context) : AvatarArt {
    override val name = "Lux"

    private class Clip(val res: Int, val loop: Boolean) {
        var drawable: AnimatedImageDrawable? = null
        var failed = false
    }

    private val idle = Clip(R.raw.lux_idle, loop = true)
    private val working = Clip(R.raw.lux_working, loop = true)
    private val making = Clip(R.raw.lux_making, loop = true)
    private val milestone = Clip(R.raw.lux_milestone, loop = false)

    private var current: Clip? = null
    private var previous: Clip? = null
    private var switchedAt = 0L
    private var milestoneUntil = 0L
    private var lastHappy = 0f

    private val still: Bitmap? by lazy {
        runCatching { BitmapFactory.decodeResource(context.resources, R.raw.lux_still) }.getOrNull()
    }
    private val stillPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ambientPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix().apply {
                setSaturation(0.5f)
                postConcat(ColorMatrix().apply { setScale(0.5f, 0.5f, 0.55f, 1f) })
            },
        )
    }

    override fun accent(mode: AvatarMode): Int = accentOf(mode)

    /**
     * The H.264 clip for [mode] (res/raw *_video.mp4), for the hardware decoder
     * on the talk screen, and whether it loops. [celebrating]: a happy moment
     * while calm plays the milestone clip once.
     */
    fun videoFor(mode: AvatarMode, celebrating: Boolean): Pair<Int, Boolean> = when {
        celebrating && (mode == AvatarMode.IDLE || mode == AvatarMode.BOOT) -> R.raw.lux_milestone_video to false
        mode == AvatarMode.THINKING -> R.raw.lux_working_video to true
        mode == AvatarMode.SPEAKING -> R.raw.lux_making_video to true
        else -> R.raw.lux_idle_video to true
    }

    override fun draw(canvas: Canvas, dst: Rect, pose: AvatarPose, style: DrawStyle) {
        if (style != DrawStyle.LIVE) {
            stopAll()
            still?.let { canvas.drawBitmap(it, null, dst, if (style == DrawStyle.AMBIENT) ambientPaint else stillPaint.apply { alpha = 255 }) }
            return
        }
        val now = SystemClock.uptimeMillis()
        // A happy moment while idle (pairing done) plays the celebration once.
        val calm = pose.mode == AvatarMode.IDLE || pose.mode == AvatarMode.BOOT
        if (pose.happy > 0.5f && lastHappy <= 0.5f && calm) milestoneUntil = now + MILESTONE_MS
        lastHappy = pose.happy
        val want = when {
            now < milestoneUntil && calm -> milestone
            pose.mode == AvatarMode.THINKING -> working
            pose.mode == AvatarMode.SPEAKING -> making
            else -> idle
        }
        if (want !== current) {
            previous?.drawable?.stop()
            previous = current
            current = want
            // Fade in from the last clip; with nothing to fade from, show it at once.
            switchedAt = if (previous == null) now - FADE_MS else now
            load(want)?.let {
                if (!want.loop) it.stop()
                it.start()
            }
        }
        val fade = ((now - switchedAt) / FADE_MS.toFloat()).coerceIn(0f, 1f)
        val prev = previous
        if (prev != null) {
            if (fade < 1f) {
                drawClip(canvas, dst, prev, 255)
            } else {
                prev.drawable?.stop()
                previous = null
            }
        }
        drawClip(canvas, dst, want, (fade * 255).toInt())
        StateRing.draw(canvas, dst, pose, accent(pose.mode), now)
    }

    private fun drawClip(canvas: Canvas, dst: Rect, clip: Clip, alpha: Int) {
        // Clips animate on the GPU; a software canvas (the face picker's preview,
        // screenshots) gets the still instead of a blank.
        val d = if (canvas.isHardwareAccelerated) load(clip) else null
        if (d != null) {
            try {
                d.bounds = dst
                d.alpha = alpha
                d.draw(canvas)
                return
            } catch (e: RuntimeException) {
                Log.w(TAG, "clip draw failed; using the still", e)
                clip.failed = true
                clip.drawable = null
            }
        }
        still?.let {
            stillPaint.alpha = alpha
            canvas.drawBitmap(it, null, dst, stillPaint)
        }
    }

    private fun load(clip: Clip): AnimatedImageDrawable? {
        if (clip.failed) return null
        clip.drawable?.let { return it }
        return try {
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, clip.res))
            (d as? AnimatedImageDrawable)?.also {
                it.repeatCount = if (clip.loop) AnimatedImageDrawable.REPEAT_INFINITE else 0
                clip.drawable = it
            } ?: run {
                clip.failed = true
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "can't decode clip ${clip.res}; using the still", e)
            clip.failed = true
            null
        }
    }

    private fun stopAll() {
        for (c in listOf(idle, working, making, milestone)) c.drawable?.stop()
        current = null
        previous = null
    }

    companion object {
        private const val TAG = "ClipArt"

        fun accentOf(mode: AvatarMode): Int = ACCENTS[mode.ordinal]
        private const val FADE_MS = 350L
        private const val MILESTONE_MS = 8_000L

        // Per-mode accents (the Muse boards' scheme).
        private val ACCENTS = intArrayOf(
            0xFFA9C0FF.toInt(), // BOOT
            0xFFA77DFF.toInt(), // IDLE
            0xFF5CB8FF.toInt(), // LISTENING
            0xFFE07BFF.toInt(), // THINKING
            0xFF6FF0BF.toInt(), // SPEAKING
            0xFFFF5C5C.toInt(), // ERROR
            0xFF7C72D0.toInt(), // OFF
        )
    }
}

/** The thin ring in the state's colour around a clip avatar: pulses with the mic, spins while thinking. */
internal object StateRing {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    fun draw(canvas: Canvas, dst: Rect, pose: AvatarPose, color: Int, now: Long) {
        val w = dst.width().toFloat()
        val inset = w * 0.03f
        rect.set(dst.left + inset, dst.top + inset, dst.right - inset, dst.bottom - inset)
        paint.strokeWidth = w * 0.018f
        paint.color = color
        val pulse = 0.5f + 0.5f * kotlin.math.sin(now / 300f)
        when (pose.mode) {
            AvatarMode.LISTENING -> {
                paint.alpha = (110 + 145 * pose.level.coerceIn(0f, 1f)).toInt()
                canvas.drawOval(rect, paint)
            }
            AvatarMode.THINKING, AvatarMode.BOOT -> {
                paint.alpha = 220
                canvas.drawArc(rect, (now / 4 % 360).toFloat(), 80f, false, paint)
            }
            AvatarMode.SPEAKING -> {
                paint.alpha = (90 + 110 * pulse).toInt()
                canvas.drawOval(rect, paint)
            }
            AvatarMode.ERROR -> {
                paint.alpha = 230
                canvas.drawOval(rect, paint)
            }
            else -> {}
        }
    }
}

/**
 * The avatar Muse sent (avatar.set_clips): JPEG frame sequences made by
 * [ClipImporter], one per state, played at 15 fps. States without a clip use
 * idle; "happy" plays once when something good happens while calm.
 */
class FrameClipArt private constructor(
    private val dir: File,
    override val name: String,
    private val frames: Map<String, Int>,
) : AvatarArt {
    companion object {
        private const val FRAME_MS = 1000L / 15
        private const val FADE_MS = 350L

        /** The imported avatar, or null if Muse hasn't sent one. */
        fun load(context: Context): FrameClipArt? {
            val dir = File(context.filesDir, ClipImporter.DIR)
            val meta = File(dir, ClipImporter.META)
            if (!meta.isFile) return null
            val p = java.util.Properties().apply { meta.inputStream().use { load(it) } }
            val frames = ClipImporter.STATES.mapNotNull { s -> p.getProperty(s)?.toIntOrNull()?.let { s to it } }.toMap()
            if ((frames["idle"] ?: 0) <= 0) return null
            return FrameClipArt(dir, p.getProperty("name")?.takeIf { it.isNotBlank() } ?: "My Muse", frames)
        }
    }

    private val size = ClipImporter.SIZE
    private var cur = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    private var prev = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    private var still: Bitmap? = null
    private var shownState: String? = null
    private var shownFrame = -1
    private var hasPrev = false
    private var stateStart = 0L
    private var happyUntil = 0L
    private var lastHappy = 0f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val stillPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ambientPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix().apply {
                setSaturation(0.5f)
                postConcat(ColorMatrix().apply { setScale(0.5f, 0.5f, 0.55f, 1f) })
            },
        )
    }

    override fun accent(mode: AvatarMode): Int = ClipArt.accentOf(mode)

    override fun draw(canvas: Canvas, dst: Rect, pose: AvatarPose, style: DrawStyle) {
        if (style != DrawStyle.LIVE) {
            val s = still ?: decode("idle", 0, null)?.also { still = it }
            s?.let { canvas.drawBitmap(it, null, dst, if (style == DrawStyle.AMBIENT) ambientPaint else stillPaint) }
            shownState = null
            return
        }
        val now = SystemClock.uptimeMillis()
        val calm = pose.mode == AvatarMode.IDLE || pose.mode == AvatarMode.BOOT
        val happyFrames = frames["happy"] ?: 0
        if (happyFrames > 0 && pose.happy > 0.5f && lastHappy <= 0.5f && calm) {
            happyUntil = now + happyFrames * FRAME_MS
        }
        lastHappy = pose.happy
        val want = when {
            now < happyUntil && calm -> "happy"
            pose.mode == AvatarMode.LISTENING -> "listening"
            pose.mode == AvatarMode.THINKING -> "thinking"
            pose.mode == AvatarMode.SPEAKING -> "speaking"
            pose.mode == AvatarMode.ERROR -> "error"
            else -> "idle"
        }.let { if ((frames[it] ?: 0) > 0) it else "idle" }

        if (want != shownState) {
            // Keep the last frame to fade from; start the new clip from its first frame.
            hasPrev = shownState != null
            val t = prev; prev = cur; cur = t
            stateStart = if (hasPrev) now else now - FADE_MS
            shownState = want
            shownFrame = -1
        }
        val count = frames.getValue(want)
        val elapsed = ((now - stateStart) / FRAME_MS).toInt()
        val index = if (want == "happy") min(elapsed, count - 1) else elapsed % count
        if (index != shownFrame) {
            decode(want, index, cur)?.let { cur = it }
            shownFrame = index
        }
        val fade = ((now - stateStart) / FADE_MS.toFloat()).coerceIn(0f, 1f)
        if (hasPrev && fade < 1f) {
            paint.alpha = 255
            canvas.drawBitmap(prev, null, dst, paint)
        }
        paint.alpha = (fade * 255).toInt()
        canvas.drawBitmap(cur, null, dst, paint)
        StateRing.draw(canvas, dst, pose, accent(pose.mode), now)
    }

    private fun decode(state: String, index: Int, into: Bitmap?): Bitmap? {
        val file = File(dir, "$state/%04d.jpg".format(index))
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inMutable = true
            inBitmap = into
        }
        return try {
            BitmapFactory.decodeFile(file.path, opts)
        } catch (e: IllegalArgumentException) {
            BitmapFactory.decodeFile(file.path) // couldn't reuse the bitmap
        }
    }
}
