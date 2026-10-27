package com.luxwatch.gadget.face

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasComplicationFactory
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.complications.ComplicationSlotBounds
import androidx.wear.watchface.complications.DefaultComplicationDataSourcePolicy
import androidx.wear.watchface.complications.SystemDataSources
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.rendering.CanvasComplicationDrawable
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.TapEvent
import androidx.wear.watchface.TapType
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import com.luxwatch.gadget.LuxRuntime
import com.luxwatch.gadget.LuxStatus
import com.luxwatch.gadget.MainActivity
import com.luxwatch.gadget.avatar.AvatarMode
import com.luxwatch.gadget.avatar.AvatarPose
import com.luxwatch.gadget.avatar.DrawStyle
import com.luxwatch.gadget.avatar.AvatarCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * The Lux watch face: the time, the live avatar and two complications (battery
 * and steps unless changed in the face's Customize screen). Tap the avatar to
 * talk to Lux.
 */
class LuxWatchFaceService : WatchFaceService() {
    companion object {
        const val LEFT_SLOT = 100
        const val RIGHT_SLOT = 101
    }

    override fun createComplicationSlotsManager(
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): ComplicationSlotsManager {
        val factory = CanvasComplicationFactory { watchState, listener ->
            CanvasComplicationDrawable(complicationDrawable(), watchState, listener)
        }
        val types = listOf(
            ComplicationType.RANGED_VALUE,
            ComplicationType.SHORT_TEXT,
            ComplicationType.MONOCHROMATIC_IMAGE,
            ComplicationType.SMALL_IMAGE,
        )
        // Either side of the avatar, at its middle.
        fun slot(id: Int, left: Float, source: Int, type: ComplicationType) =
            ComplicationSlot.createRoundRectComplicationSlotBuilder(
                id,
                factory,
                types,
                DefaultComplicationDataSourcePolicy(source, type),
                ComplicationSlotBounds(RectF(left, 0.55f, left + 0.19f, 0.74f)),
            ).build()
        return ComplicationSlotsManager(
            listOf(
                slot(LEFT_SLOT, 0.05f, SystemDataSources.DATA_SOURCE_WATCH_BATTERY, ComplicationType.RANGED_VALUE),
                slot(RIGHT_SLOT, 0.76f, SystemDataSources.DATA_SOURCE_STEP_COUNT, ComplicationType.SHORT_TEXT),
            ),
            currentUserStyleRepository,
        )
    }

    /** Lux's look for complications: no box, white text, the IDLE accent for progress rings. */
    private fun complicationDrawable() = ComplicationDrawable(this).apply {
        activeStyle.apply {
            backgroundColor = Color.TRANSPARENT
            borderColor = 0x33FFFFFF
            textColor = Color.WHITE
            titleColor = 0xFFB0B0B0.toInt()
            iconColor = Color.WHITE
            rangedValuePrimaryColor = 0xFFA77DFF.toInt()
            rangedValueSecondaryColor = 0x33FFFFFF
        }
        ambientStyle.apply {
            backgroundColor = Color.TRANSPARENT
            borderColor = Color.TRANSPARENT
            textColor = 0xFFB0B0B0.toInt()
            titleColor = 0xFF808080.toInt()
            iconColor = 0xFFB0B0B0.toInt()
            rangedValuePrimaryColor = 0xFF808080.toInt()
            rangedValueSecondaryColor = 0x22FFFFFF
        }
    }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        val renderer = LuxFaceRenderer(
            applicationContext, surfaceHolder, watchState, currentUserStyleRepository, complicationSlotsManager,
        )
        return WatchFace(WatchFaceType.DIGITAL, renderer).setTapListener(renderer)
    }
}

private class LuxFaceRenderer(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    styleRepository: CurrentUserStyleRepository,
    private val complications: ComplicationSlotsManager,
) : Renderer.CanvasRenderer2<Renderer.SharedAssets>(
    surfaceHolder,
    styleRepository,
    watchState,
    CanvasType.HARDWARE,
    FRAME_MS,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = false,
),
    WatchFace.TapListener {
    private val drawer = FaceDrawer()
    private val prefs = context.getSharedPreferences("lux_prefs", Context.MODE_PRIVATE)
    private val start = SystemClock.elapsedRealtime()
    private var lastMode = AvatarMode.IDLE
    private var modeStart = start
    private var bounds = Rect()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // A message from Muse: draw now, not at the next minute.
        LuxRuntime.onFaceNudge = { invalidate() }
        // Wrist up: if Lux was used lately, connect to Muse now, so it's ready by the time you tap.
        scope.launch {
            watchState.isAmbient.collect { ambient ->
                when (ambient) {
                    false -> {
                        LuxRuntime.warmUpIfRecent(context)
                        wake()
                    }
                    true -> LuxRuntime.coolDown(context)
                    null -> {}
                }
            }
        }
        scope.launch {
            watchState.isVisible.collect { visible ->
                if (visible == true && watchState.isAmbient.value == false) {
                    LuxRuntime.warmUpIfRecent(context)
                    wake()
                }
            }
        }
    }

    /**
     * Battery: the avatar animates only while Lux is busy or a reply or message
     * has just arrived; otherwise it's a still frame and the face redraws once
     * a minute (on the minute) instead of 15 times a second. Raising the wrist
     * doesn't animate it.
     */
    private fun wake() {
        invalidate() // fresh frame (time, complications) as the screen comes on
    }

    override fun onDestroy() {
        LuxRuntime.onFaceNudge = null
        scope.cancel()
        super.onDestroy()
    }

    override suspend fun createSharedAssets() = object : SharedAssets {
        override fun onDestroy() {}
    }

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: SharedAssets) {
        this.bounds = bounds
        val now = SystemClock.elapsedRealtime()
        val mode = LuxStatus.mode()
        if (mode != lastMode) {
            lastMode = mode
            modeStart = now
        }
        // A reply on the talk screen, or a message from Muse.
        val happyAt = maxOf(LuxStatus.happyAt(), LuxRuntime.messageAt)
        val happy = if (happyAt == 0L) 0f else (1f - (now - happyAt) / 1600f).coerceIn(0f, 1f)
        val ambient = renderParameters.drawMode == DrawMode.AMBIENT
        // Lively: busy, or a reply/message in the last few seconds (long enough for a celebration clip).
        val busy = mode == AvatarMode.LISTENING || mode == AvatarMode.THINKING || mode == AvatarMode.SPEAKING
        val recent = happyAt != 0L && now - happyAt < CELEBRATE_MS
        val live = !ambient && (busy || recent)
        interactiveDrawModeUpdateDelayMillis = if (live) FRAME_MS else STILL_FRAME_MS
        drawer.draw(
            canvas,
            bounds,
            zonedDateTime,
            AvatarCatalog.byName(context, prefs.getString("avatar", null)),
            AvatarPose(mode, (now - start) / 1000f, (now - modeStart) / 1000f, happy = happy),
            style = when {
                ambient -> DrawStyle.AMBIENT
                live -> DrawStyle.LIVE
                else -> DrawStyle.STILL
            },
            use24h = DateFormat.is24HourFormat(context),
        )
        for (slot in complications.complicationSlots.values) {
            if (slot.enabled) slot.render(canvas, zonedDateTime, renderParameters)
        }
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: SharedAssets) {
        renderParameters.highlightLayer?.let { canvas.drawColor(it.backgroundTint) }
        for (slot in complications.complicationSlots.values) {
            if (slot.enabled) slot.renderHighlightLayer(canvas, zonedDateTime, renderParameters)
        }
    }

    override fun onTapEvent(tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?) {
        // Only the avatar (lower part of the face) starts a talk, so a stray tap on the time doesn't.
        // A complication tap opens the complication, not Lux.
        if (complicationSlot != null) return
        if (tapType != TapType.UP || tapEvent.yPos < bounds.top + bounds.height() * 0.36f) return
        // One tap: a voice message. Two: a live call. A single tap waits a moment for a second.
        val first = pendingTap
        if (first?.isActive == true) {
            first.cancel()
            pendingTap = null
            openLux(call = true)
            return
        }
        pendingTap = scope.launch {
            delay(DOUBLE_TAP_MS)
            pendingTap = null
            openLux(call = false)
        }
    }

    private var pendingTap: Job? = null

    private fun openLux(call: Boolean) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(if (call) MainActivity.EXTRA_CALL else MainActivity.EXTRA_TALK, true)
                .putExtra(MainActivity.EXTRA_FROM_FACE, true),
        )
    }

    private companion object {
        const val FRAME_MS = 66L // ~15 fps while animating
        const val STILL_FRAME_MS = 60_000L // the clock shows minutes; messages nudge it sooner
        const val CELEBRATE_MS = 8_000L
        /** The system's double-tap window. */
        val DOUBLE_TAP_MS = android.view.ViewConfiguration.getDoubleTapTimeout().toLong()
    }
}
