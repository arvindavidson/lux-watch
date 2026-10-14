package com.luxwatch.gadget.face

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.luxwatch.gadget.avatar.AvatarArt
import com.luxwatch.gadget.avatar.AvatarPose
import com.luxwatch.gadget.avatar.DrawStyle
import com.luxwatch.gadget.avatar.PixelAvatar
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the Lux watch face: date and time at the top, the avatar below.
 * Ambient mode shows the avatar still and dim and shifts everything a few
 * pixels each minute against burn-in.
 */
class FaceDrawer {
    private val dst = Rect()
    private val condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = condensedBold
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = condensedBold
        letterSpacing = 0.12f
    }
    private val dateFormat = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())
    private val time24 = DateTimeFormatter.ofPattern("HH:mm")
    private val time12 = DateTimeFormatter.ofPattern("h:mm")

    fun draw(
        canvas: Canvas,
        bounds: Rect,
        time: ZonedDateTime,
        avatar: AvatarArt,
        pose: AvatarPose,
        style: DrawStyle,
        use24h: Boolean,
    ) {
        canvas.drawColor(Color.BLACK)
        val ambient = style == DrawStyle.AMBIENT
        val w = bounds.width()
        val h = bounds.height()
        // Burn-in protection: walk a 5x5 grid of small offsets, one step a minute.
        val step = (w / 150f).roundToInt().coerceAtLeast(1)
        val shiftX = if (ambient) (time.minute % 5 - 2) * step else 0
        val shiftY = if (ambient) (time.minute / 5 % 5 - 2) * step else 0
        val cx = bounds.exactCenterX() + shiftX

        datePaint.textSize = h * 0.055f
        datePaint.color = if (ambient) 0xFF9A9A9A.toInt() else avatar.accent(pose.mode)
        canvas.drawText(time.format(dateFormat).uppercase(), cx, bounds.top + h * 0.15f + shiftY, datePaint)

        timePaint.textSize = h * 0.19f
        timePaint.color = if (ambient) 0xFFC8C8C8.toInt() else Color.WHITE
        canvas.drawText(time.format(if (use24h) time24 else time12), cx, bounds.top + h * 0.335f + shiftY, timePaint)

        // A whole multiple of 64 keeps pixel avatars' pixels all the same size.
        val scale = max(1, (w * 0.56f / PixelAvatar.SIZE).roundToInt())
        val side = scale * PixelAvatar.SIZE
        val left = (cx - side / 2f).roundToInt()
        val top = (bounds.top + h * 0.36f).roundToInt() + shiftY
        dst.set(left, top, left + side, top + side)
        avatar.draw(canvas, dst, pose, style)
    }
}
