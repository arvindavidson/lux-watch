package com.luxwatch.gadget.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.luxwatch.gadget.avatar.AvatarMode
import com.luxwatch.gadget.avatar.AvatarPose
import com.luxwatch.gadget.avatar.DrawStyle
import com.luxwatch.gadget.avatar.AvatarCatalog
import org.robolectric.RuntimeEnvironment
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Renders the watch face at the Watch4 Classic's 450x450 to build/screens/.
 * face-<avatar>.png is also the face picker's preview (res/drawable-nodpi/face_preview.png).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FaceScreenshotTest {
    private val time = ZonedDateTime.of(2026, 10, 14, 10, 8, 0, 0, ZoneId.of("UTC"))

    private fun shoot(name: String, ambient: Boolean, mode: AvatarMode = AvatarMode.IDLE, happy: Float = 0f) {
        for (avatar in AvatarCatalog.all(RuntimeEnvironment.getApplication())) {
            val bitmap = Bitmap.createBitmap(450, 450, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val drawer = FaceDrawer()
            // Run a second of frames first so the avatar's glow settles on the mode.
            var t = 0f
            while (t < 1.2f) {
                drawer.draw(canvas, Rect(0, 0, 450, 450), time, avatar, AvatarPose(mode, 30f + t, t, happy = happy), if (ambient) DrawStyle.AMBIENT else DrawStyle.LIVE, use24h = false)
                t += 0.04f
            }
            val slug = avatar.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
            val file = File("build/screens/$name-$slug.png")
            file.parentFile!!.mkdirs()
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun face() = shoot("face", ambient = false)

    @Test fun faceAmbient() = shoot("face-ambient", ambient = true)

    @Test fun faceHappy() = shoot("face-happy", ambient = false, happy = 1f)
}
