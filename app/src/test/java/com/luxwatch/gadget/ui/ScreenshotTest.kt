package com.luxwatch.gadget.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.wear.compose.material.MaterialTheme
import com.luxwatch.gadget.avatar.AvatarCatalog
import com.luxwatch.gadget.avatar.AvatarMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the watch screens as PNGs in app/build/screens (Galaxy Watch4 Classic
 * 46 mm: 450 px round, xhdpi -> 225 dp) so the UI can be reviewed off-device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w225dp-h225dp-round-xhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.activity.actionBar?.hide() // the app's theme has no action bar
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black)) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(700)
        // Draw the window directly: captureToImage waits for idle, which an
        // always-animating avatar never reaches.
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screens/$name.png")
        file.parentFile!!.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun talk(ui: TalkUi, level: Float = 0f, happy: Boolean = false) =
        TalkPage(
            ui,
            level = { level },
            happyAt = { if (happy) android.os.SystemClock.elapsedRealtime() else 0L },
            onTap = {},
            avatar = AvatarCatalog.all(compose.activity).first(),
            video = false, // Robolectric has no GPU for the TextureView
        )

    @Test fun idle() = shoot("1-idle") { talk(TalkUi(AvatarMode.IDLE, "Tap to talk", null)) }

    @Test fun listening() = shoot("2-listening") {
        talk(TalkUi(AvatarMode.LISTENING, "Listening…", null), level = 0.6f)
    }

    @Test fun thinking() = shoot("3-thinking") {
        talk(TalkUi(AvatarMode.THINKING, "“what's the weather tomorrow”", null))
    }

    @Test fun speaking() = shoot("4-speaking") {
        talk(TalkUi(AvatarMode.SPEAKING, "", "Tomorrow looks mostly sunny with a high of 18° and a light breeze in the afternoon."))
    }

    @Test fun error() = shoot("5-error") { talk(TalkUi(AvatarMode.ERROR, "No reply from Muse", null, isError = true)) }

    @Test fun unpaired() = shoot("6-unpaired") {
        talk(TalkUi(AvatarMode.IDLE, "Not paired yet", null, notes = listOf("Add me in the Muse app", "SDK token set")))
    }

    @Test fun volume() = shoot("9-volume") {
        TalkPage(
            TalkUi(AvatarMode.IDLE, "Tap to talk", null),
            level = { 0f },
            happyAt = { 0L },
            onTap = {},
            avatar = AvatarCatalog.all(compose.activity).first(),
            volume = 9,
            maxVolume = 15,
            volumeShownAt = 1L,
        )
    }

    @Test fun history() = shoot("8-history") {
        val now = System.currentTimeMillis()
        HistoryPage(
            listOf(
                com.luxwatch.gadget.ConversationLog.Entry(now - 60_000, null, "Time to stand up and stretch.", "Reminder"),
                com.luxwatch.gadget.ConversationLog.Entry(now - 600_000, "what's the weather tomorrow", "Mostly sunny, a high of 18°."),
            ),
            onClear = {},
        )
    }

    @Test fun settings() = shoot("7-settings") {
        SettingsPage(
            SettingsUi(
                paired = true, link = "Connected", tokenSet = true, haptics = true, version = "0.1.0",
                avatarName = "Lux", canChangeAvatar = true,
            ),
            SettingsActions({}, {}, {}, {}, {}),
        )
    }
}
