package com.luxwatch.gadget.avatar

/** The avatar's states; the same set Meta's board UI animates. */
enum class AvatarMode { BOOT, IDLE, LISTENING, THINKING, SPEAKING, ERROR, OFF }

/**
 * One frame's inputs.
 * @param t seconds since start, @param modeT seconds in the current mode,
 * @param level 0..1 live audio level (mic while listening),
 * @param happy 0..1 reaction (rises on a reply, eases out over ~1.6 s).
 */
data class AvatarPose(
    val mode: AvatarMode,
    val t: Float,
    val modeT: Float,
    val level: Float = 0f,
    val happy: Float = 0f,
)

/**
 * A pixel-art avatar: draws a 64x64 frame that the app scales up with
 * nearest-neighbour. Implementations are pure Kotlin (no Android) so they run
 * and are tested on the JVM. render() is called ~25 times a second on the UI
 * thread, one frame at a time.
 */
interface PixelAvatar {
    /** The character's name, shown in settings. */
    val name: String

    /** Accent colour of a mode (0xFFRRGGBB), also used by the UI around the avatar. */
    fun accent(mode: AvatarMode): Int

    /** Renders one frame into [out] (SIZE*SIZE opaque ARGB pixels, row-major, black background). */
    fun render(p: AvatarPose, out: IntArray)

    companion object {
        const val SIZE = 64
    }
}

/** Every avatar the app can show; the first is the default. */
object Avatars {
    // Muse's own avatar (MusePixel.kt, see docs/muse-avatar.md) goes first in this list.
    val all: List<PixelAvatar> = listOf(MusePixel, LuxPixel)

    fun byName(name: String?): PixelAvatar = all.firstOrNull { it.name == name } ?: all.first()
}
