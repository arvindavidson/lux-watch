package com.luxwatch.gadget.tools

import com.luxwatch.gadget.avatar.BackgroundKey
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Keys a folder of PNG frames with [BackgroundKey] (the same code the watch
 * runs on import): `./gradlew :gadget:keyFrames --args="IN_DIR OUT_DIR"`.
 * Used by gadget/tools/make_avatar_clips.sh.
 */
object KeyFramesMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val input = File(args[0])
        val out = File(args[1]).apply { mkdirs() }
        val frames = input.listFiles { _, name -> name.endsWith(".png") }!!.sorted()
        for (f in frames) {
            val img = ImageIO.read(f)
            val w = img.width
            val h = img.height
            val keyed = BackgroundKey.key(img.getRGB(0, 0, w, h, null, 0, w), w, h)
            val result = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            result.setRGB(0, 0, w, h, keyed, 0, w)
            ImageIO.write(result, "png", File(out, f.name))
        }
        println("keyed ${frames.size} frames")
    }
}
