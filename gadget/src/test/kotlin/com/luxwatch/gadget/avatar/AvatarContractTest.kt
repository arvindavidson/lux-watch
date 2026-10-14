package com.luxwatch.gadget.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.lang.management.ManagementFactory
import javax.imageio.ImageIO

/**
 * What every avatar in [Avatars.all] must do to run on the watch. A new avatar
 * (such as Muse's MusePixel) passes these before it goes in the APK.
 */
class AvatarContractTest {
    private val n = PixelAvatar.SIZE * PixelAvatar.SIZE

    @Test
    fun namesAreSetAndUnique() {
        assertTrue(Avatars.all.isNotEmpty())
        assertTrue(Avatars.all.all { it.name.isNotBlank() })
        assertEquals(Avatars.all.size, Avatars.all.map { it.name }.toSet().size)
        assertEquals(Avatars.all.first(), Avatars.byName("no such avatar"))
    }

    @Test
    fun accentsAreOpaque() = forEachAvatar { a ->
        for (mode in AvatarMode.entries) {
            assertEquals("${a.name} $mode accent alpha", 0xFF, a.accent(mode) ushr 24)
        }
    }

    @Test
    fun everyModeDrawsTheCharacterOpaque() = forEachAvatar { a ->
        val out = IntArray(n)
        for (mode in AvatarMode.entries) {
            for (frame in 0 until 75) {
                val t = frame * 0.04f
                a.render(AvatarPose(mode, 10f + t, t, level = (frame % 10) / 10f, happy = if (frame > 60) 1f else 0f), out)
                assertTrue("${a.name} $mode frame $frame has transparent pixels", out.all { it ushr 24 == 0xFF })
            }
            val lit = out.count { it and 0xFFFFFF != 0 }
            // OFF may end dark, as on Meta's boards; every other mode must show the character.
            if (mode != AvatarMode.OFF) assertTrue("${a.name} $mode drew only $lit pixels", lit > 400)
        }
    }

    @Test
    fun survivesOddPoses() = forEachAvatar { a ->
        val out = IntArray(n)
        val odd = listOf(
            AvatarPose(AvatarMode.BOOT, 0f, 0f),
            AvatarPose(AvatarMode.IDLE, 100_000f, 100_000f),
            AvatarPose(AvatarMode.LISTENING, 5f, 0f, level = -1f),
            AvatarPose(AvatarMode.SPEAKING, 5f, 0f, level = 3f),
            AvatarPose(AvatarMode.ERROR, 5f, 0.1f, happy = 1f),
            AvatarPose(AvatarMode.THINKING, 2f, 1f), // time going backwards
            AvatarPose(AvatarMode.OFF, 2f, 99f),
        )
        for (p in odd) a.render(p, out)
    }

    /** The watch draws at 25 fps on a Cortex-A55, several times slower than a desktop JVM; leave it room. */
    @Test
    fun fastAndAllocationFree() = forEachAvatar { a ->
        val out = IntArray(n)
        val poses = (0 until 700).map { i ->
            val mode = AvatarMode.entries[(i / 100) % AvatarMode.entries.size]
            AvatarPose(mode, i * 0.04f, (i % 100) * 0.04f, level = (i % 7) / 7f, happy = if (i % 100 > 80) 1f else 0f)
        }
        repeat(3) { for (p in poses) a.render(p, out) } // warm up the JIT

        val mx = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        val bytesBefore = mx?.getThreadAllocatedBytes(tid) ?: 0L
        val start = System.nanoTime()
        for (p in poses) a.render(p, out)
        val msPerFrame = (System.nanoTime() - start) / 1e6 / poses.size
        val bytesPerFrame = ((mx?.getThreadAllocatedBytes(tid) ?: 0L) - bytesBefore) / poses.size
        println("${a.name}: %.3f ms/frame, %d bytes allocated/frame".format(msPerFrame, bytesPerFrame))
        assertTrue("${a.name} takes %.2f ms per frame here (limit 1)".format(msPerFrame), msPerFrame < 1.0)
        assertTrue("${a.name} allocates $bytesPerFrame bytes per frame (limit 256)", bytesPerFrame < 256)
    }

    /** Writes build/avatar-preview-<name>.png: 6 frames of each mode (last row: happy), 4x. */
    @Test
    fun writesPreviewSheets() = forEachAvatar { a ->
        val scale = 4
        val frames = 6
        val modes = AvatarMode.entries + listOf(AvatarMode.IDLE)
        val sheet = BufferedImage(frames * 64 * scale, modes.size * 64 * scale, BufferedImage.TYPE_INT_ARGB)
        val out = IntArray(n)
        modes.forEachIndexed { row, mode ->
            val happy = if (row == modes.size - 1) 1f else 0f
            var s = 0f
            for (f in 0 until frames) {
                val t = 0.3f + f * 0.37f
                // Draw the frames in between too, for avatars that keep state across frames.
                while (s < t) {
                    a.render(AvatarPose(mode, 20f + s, s, level = 0.3f + 0.12f * f, happy = happy), out)
                    s += 0.04f
                }
                a.render(AvatarPose(mode, 20f + t, t, level = 0.3f + 0.12f * f, happy = happy), out)
                for (y in 0 until 64 * scale) for (x in 0 until 64 * scale) {
                    sheet.setRGB(f * 64 * scale + x, row * 64 * scale + y, out[(y / scale) * 64 + x / scale])
                }
            }
        }
        val slug = a.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val file = File("build/avatar-preview-$slug.png")
        file.parentFile.mkdirs()
        ImageIO.write(sheet, "png", file)
        assertTrue(file.length() > 0)
    }

    private fun forEachAvatar(check: (PixelAvatar) -> Unit) {
        for (a in Avatars.all) check(a)
    }
}
