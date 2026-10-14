package com.luxwatch.gadget.ui

import android.graphics.Rect
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.luxwatch.gadget.avatar.AvatarArt
import com.luxwatch.gadget.avatar.AvatarMode
import com.luxwatch.gadget.avatar.AvatarPose
import com.luxwatch.gadget.avatar.ClipArt
import com.luxwatch.gadget.avatar.DrawStyle
import com.luxwatch.gadget.avatar.StateRing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Draws an avatar. Battery first:
 * - [animate] false: one still frame, no redraws at all.
 * - Lux's own clips: played by the hardware video decoder ([ClipVideoPlayer]),
 *   with the state ring drawn on top.
 * - Other avatars: drawn at 15 fps while frames are being drawn (screen on).
 *
 * @param level live mic level 0..1, read every frame.
 * @param happyAt elapsedRealtime of the last happy moment (0 = none).
 */
@Composable
fun AvatarView(
    avatar: AvatarArt,
    mode: AvatarMode,
    level: () -> Float,
    happyAt: () -> Long,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    /** Play Lux's clips with the hardware decoder (off only where there's no GPU, e.g. tests). */
    video: Boolean = true,
) {
    if (animate && video && avatar is ClipArt) {
        ClipVideo(avatar, mode, level, happyAt, modifier)
        return
    }
    val start = remember { SystemClock.elapsedRealtime() }
    val modeStart = remember(mode) { SystemClock.elapsedRealtime() }
    val dst = remember { Rect() }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(animate) {
        // Still: draw once (the Canvas below) and stop.
        while (animate && isActive) {
            withFrameNanos { } // pauses while nothing is drawn (screen off / app hidden)
            frame++
            delay(FRAME_MS)
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE")
        val redraw = frame // read so each new frame redraws
        val now = SystemClock.elapsedRealtime()
        val pose = AvatarPose(
            mode = mode,
            t = (now - start) / 1000f,
            modeT = (now - modeStart) / 1000f,
            level = level(),
            happy = happyOf(happyAt(), now),
        )
        val side = minOf(size.width, size.height).toInt()
        val left = ((size.width - side) / 2).toInt()
        val top = ((size.height - side) / 2).toInt()
        dst.set(left, top, left + side, top + side)
        drawIntoCanvas { avatar.draw(it.nativeCanvas, dst, pose, if (animate) DrawStyle.LIVE else DrawStyle.STILL) }
    }
}

/** Lux's clips through the hardware video decoder, with the state ring on top. */
@Composable
private fun ClipVideo(
    avatar: ClipArt,
    mode: AvatarMode,
    level: () -> Float,
    happyAt: () -> Long,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val player = remember { ClipVideoPlayer(context) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val celebrating = happyAt().let { it != 0L && SystemClock.elapsedRealtime() - it < CELEBRATE_MS }
    val (clip, loop) = avatar.videoFor(mode, celebrating)
    LaunchedEffect(clip) { player.play(clip, loop) }

    val dst = remember { Rect() }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        // Only the ring animates here (pulse / spin): cheap vector drawing.
        while (isActive) {
            withFrameNanos { }
            frame++
            delay(FRAME_MS)
        }
    }
    Box(modifier) {
        AndroidView(
            factory = { TextureView(it).also(player::attach) },
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE")
            val redraw = frame
            val side = minOf(size.width, size.height).toInt()
            val left = ((size.width - side) / 2).toInt()
            val top = ((size.height - side) / 2).toInt()
            dst.set(left, top, left + side, top + side)
            val pose = AvatarPose(mode, 0f, 0f, level = level())
            drawIntoCanvas {
                StateRing.draw(it.nativeCanvas, dst, pose, avatar.accent(mode), SystemClock.uptimeMillis())
            }
        }
    }
}

private fun happyOf(happyAt: Long, now: Long): Float =
    if (happyAt == 0L) 0f else (1f - (now - happyAt) / 1600f).coerceIn(0f, 1f)

/** 15 fps: every frame of Muse's clips, 40% fewer redraws than 25 fps. */
private const val FRAME_MS = 66L

/** How long the milestone clip plays after a happy moment. */
private const val CELEBRATE_MS = 8_000L
