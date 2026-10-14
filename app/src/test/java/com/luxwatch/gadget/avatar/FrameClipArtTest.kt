package com.luxwatch.gadget.avatar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The avatar Muse sends (avatar.set_clips) as stored by ClipImporter, played by FrameClipArt. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FrameClipArtTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dir = File(context.filesDir, ClipImporter.DIR)

    /** Writes [frames] solid-colour JPEGs for [state]. */
    private fun clip(state: String, frames: Int, color: Int) {
        File(dir, state).mkdirs()
        val bmp = Bitmap.createBitmap(ClipImporter.SIZE, ClipImporter.SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        repeat(frames) { i -> File(dir, "$state/%04d.jpg".format(i)).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
    }

    private fun meta(name: String, vararg counts: Pair<String, Int>) {
        File(dir, ClipImporter.META).writeText("name=$name\n" + counts.joinToString("\n") { "${it.first}=${it.second}" })
    }

    private fun centre(art: AvatarArt, mode: AvatarMode): Int {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        art.draw(canvas, Rect(0, 0, 200, 200), AvatarPose(mode, 1f, 1f), DrawStyle.LIVE)
        android.os.SystemClock.sleep(400) // let a change of clip finish fading in (Robolectric's clock)
        art.draw(canvas, Rect(0, 0, 200, 200), AvatarPose(mode, 1.4f, 1.4f), DrawStyle.LIVE)
        return bmp.getPixel(100, 100)
    }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
        AvatarCatalog.refresh()
    }

    @Test
    fun noImportMeansNoImportedAvatar() {
        AvatarCatalog.refresh()
        assertEquals(null, FrameClipArt.load(context))
        assertEquals("Lux", AvatarCatalog.all(context).first().name)
    }

    @Test
    fun importedAvatarIsTheDefaultAndPlaysEachState() {
        clip("idle", 3, Color.rgb(200, 40, 40))
        clip("thinking", 2, Color.rgb(40, 40, 200))
        meta("Pip", "idle" to 3, "thinking" to 2)
        AvatarCatalog.refresh()
        val all = AvatarCatalog.all(context)
        assertEquals("Pip", all.first().name)
        assertTrue(all.any { it.name == "Lux" }) // the built-in clips stay available
        val art = all.first()
        assertTrue(Color.red(centre(art, AvatarMode.IDLE)) > 150)
        // A state with its own clip switches to it…
        assertTrue(Color.blue(centre(art, AvatarMode.THINKING)) > 100)
        // …and one without falls back to idle.
        assertTrue(Color.red(centre(art, AvatarMode.SPEAKING)) > 100)
    }

    @Test
    fun sameNameReplacesTheBuiltInClips() {
        clip("idle", 1, Color.GRAY)
        meta("Lux", "idle" to 1)
        AvatarCatalog.refresh()
        assertEquals(1, AvatarCatalog.all(context).count { it.name == "Lux" })
        assertTrue(AvatarCatalog.all(context).first() is FrameClipArt)
    }
}
