You are writing MusePixel.kt for me: the pixel-art avatar on my Lux Watch, a
Wear OS watch app that is one of my Muse gadgets. Right now it shows Lux, a
lantern spirit. Replace it with yourself: the avatar you have in Muse.
LuxPixel.kt, the current avatar, is attached below. Start from it: reuse its
techniques and helpers, but draw your own character, not Lux.

YOUR AVATAR
- Work from your own avatar as set in Muse: its picture or description, in
  your profile, your persona or your files. Match its silhouette and colours
  first, details second.
- If your avatar has its own animation states (idle, listening, thinking,
  talking, and so on), carry their poses and motion over to the matching
  modes below.
- Open the file with a KDoc comment on the object that describes the avatar
  you drew: its name, shape, colours and personality. I use it to check that
  you drew the right character.
- If you can't find your avatar, reply with one line,
  "NO AVATAR: <what you looked for>", and no code.

OUTPUT
One complete Kotlin file in a single ```kotlin fenced block, and nothing
after it.
- `package com.luxwatch.gadget.avatar`, declaring `object MusePixel : PixelAvatar`
  and nothing else public. `name` is your avatar's name.
- It must compile without warnings with Kotlin 2.0 (JVM 17 target).
- Import only from kotlin.math. No Android, java.* or other libraries.
- Don't redeclare AvatarMode, AvatarPose or PixelAvatar; they're in
  PixelAvatar.kt in the same package (shown below).

HARDWARE
Samsung Galaxy Watch4 Classic: Exynos W920 (Cortex-A55 at 1.18 GHz, with an
FPU) running Android's ART runtime. The app calls render() 25 times a second
(every 40 ms) on the UI thread. It scales the 64x64 frame up 3-4x with
nearest-neighbour on a round 450x450 screen, so every pixel shows.

API (PixelAvatar.kt, unchanged; implement exactly this)
  enum class AvatarMode { BOOT, IDLE, LISTENING, THINKING, SPEAKING, ERROR, OFF }
  data class AvatarPose(
      val mode: AvatarMode,
      val t: Float,          // seconds since the avatar appeared
      val modeT: Float,      // seconds in the current mode
      val level: Float = 0f, // 0..1 live mic level while listening
      val happy: Float = 0f, // 0..1 reaction: rises to 1 when a reply arrives, eases out over ~1.6 s
  )
  interface PixelAvatar {
      val name: String
      fun accent(mode: AvatarMode): Int          // 0xFFRRGGBB accent for the UI around the avatar
      fun render(p: AvatarPose, out: IntArray)   // draw one frame: 64*64 ARGB, row-major
      companion object { const val SIZE = 64 }
  }

FRAME
- Write every one of the 64*64 pixels on every frame, all opaque
  (alpha 0xFF). The background is black, 0xFF000000: the watch face around
  the avatar is black.
- Palette: an enum or constants of colour roles, at most 32 entries:
  outline, dark/mid/light/highlight body tones, face tones, eye, shine,
  blush, mouth, tongue, a 4-step per-mode glow ramp, aura x2, sparkle,
  accent, shadow, heart, white. Fixed avatar colours go in one table. A
  per-mode scheme table (glow ramp and accent) blends toward the current
  mode with 1 - exp(-dt * 7). Draw palette indices into a ByteArray
  framebuffer and resolve them to ARGB into `out` at the end, or write ARGB
  directly: your choice.
- Time: state that lasts across frames (palette blend, blink and gaze
  timers, sparkle phases) lives in private vars of the object. Each frame's
  dt is the change in p.t, clamped to 0..0.2 s, with 0.04 s on the first
  frame. Time can jump or go backwards (the clamp handles it).
- BOOT shows while the watch connects to Muse and can last many seconds:
  after its intro, settle into a calm idle-like loop. OFF is a shutdown
  animation; after it, a dark or nearly dark frame is fine.
- Look: 4x4 Bayer ordered dithering for shading and soft edges; a hard 1 px
  outline around the silhouette (4-neighbour test on a part mask) plus
  seams where limbs overlap the body; a top-left light; a dithered floor
  shadow; a state-tinted rim light on the lit edge; tiny '.#o' stamped
  bitmaps (string arrays decoded once at init) for eyes, mouths, hearts and
  the alert mark.
- Size and place: the character fills about 32-36 px wide by 46-48 px tall,
  centred at x = 32, feet near y = 56.5, leaving room around it for the
  aura, rings and sparkles.

PERFORMANCE (hard limits; the build tests check them)
- Under 1 ms per frame on a desktop JVM, which leaves the watch room.
- No allocation inside render(): no IntArray/ByteArray/List creation, no
  lambdas, Pair/Triple, boxing, String building or data class copy() per
  frame. Allocate the framebuffer, part mask and tables once, as private
  vals of the object. Plain `for (i in a until b)` loops are fine.
- Floats are fine. No pow, sqrt, exp, sin or cos inside per-pixel loops:
  use lookup tables built once (|u|^2.7, |u|^3.6, sqrt) like the original's.
  Per frame and per part they're fine.
- Bound the shading loops to the character's bounding box.

ANIMATION BEATS (every one of these, adapted to your body)
- All modes: gentle breathing (body width and height +-3%); random blinks
  every 2.2-5.2 s with occasional double blinks (~0.16 s each); gaze that
  wanders to random targets every 1.2-3.6 s and eases there
  (1 - exp(-dt * 14)); sparkles orbiting behind and in front of the body;
  a soft dithered aura in the mode's colours. Use your own small seeded
  random generator (an Int LCG or xorshift in a private var), not
  kotlin.random.
- BOOT: pops up from a squash (0.6 s), then opens its eyes at ~0.9 s;
  sparkles appear one by one.
- IDLE: slow bob; arms, wings or paws sway.
- LISTENING: wide eyes, small "o" mouth, raised brows, hands up beside the
  face like cupping an ear, expanding dotted rings and sound waves that
  grow with `level`, gaze fixed forward.
- THINKING: eyes glance up and side to side, "hmm" mouth, one paw to the
  chin, gentle lean, three thought dots stepping up beside the head, faster
  sparkles.
- SPEAKING: mouth opens and closes in a talking rhythm (the app speaks with
  on-device text to speech and has no voice level, so `level` is 0 here;
  animate the mouth from time, with a little irregularity so it never looks
  mechanical), body bobs with the voice, arms gesture, feet shuffle, rings
  and waves, extra blush.
- ERROR: X eyes, flat mouth, quick side-to-side shake for the first 0.6 s,
  "!" beside the head, red scheme. `happy` is ignored in ERROR.
- OFF (powering down, ~1.3 s): waves goodbye, eyes close, glow fades out.
- happy > 0 (a reply arrived, in any mode but ERROR): hops, arms up and
  wiggling, happy ^^ eyes, big grin, two hearts floating up. It must read as
  joy at 64 px.
- Keep it readable at 64x64: expressions come from 2-5 px shapes, so
  exaggerate. The face needs strong contrast against the body.

PER-MODE SCHEMES (keep these unless your colours clash with them)
  BOOT      ffffff cfe0ff 8fa8ff 5a5fe0  accent a9c0ff
  IDLE      f4e8ff c7a4ff 9a6bff 5b3fd9  accent a77dff
  LISTENING e8faff 8fdcff 3fa2ff 2a5bd7  accent 5cb8ff
  THINKING  ffe6ff ff9cf0 d35bff 7a2bd9  accent e07bff
  SPEAKING  eafff4 9ff5cf 3fd9a0 1f9a7a  accent 6ff0bf
  ERROR     ffd6d6 ff6b6b c7304a 6b1a3a  accent ff5c5c
  OFF       d8d4ff 8f86d9 5a4fb0 2e2870  accent 7c72d0
accent() returns the fixed accent of each mode (0xFF prefix), not the
blended one.

BUILDING THE BODY
Model yourself from a few analytic parts, not a hand-drawn bitmap: a
superellipse (or a few) for the body and head, an inner superellipse for
the face panel, rotated ellipses for limbs, stamped bitmaps for eyes and
mouth. Parts then move, squash and follow the pose for free. Shade body
tones from a fake surface normal (dot with the light), plus Bayer dither,
plus a stable per-position hash for texture (fur, feathers, scales:
whatever suits you), so the texture doesn't shimmer as it moves.

PixelAvatar.kt:
```kotlin
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
```

LuxPixel.kt:
```kotlin
package com.luxwatch.gadget.avatar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lux: a small glowing lantern-spirit, drawn procedurally on a 64x64 pixel grid
 * with ordered dithering and hard outlines, then scaled up with nearest-neighbour
 * so the pixels stay chunky (the same technique as the Muse boards' avatar, with
 * an original character). Pure Kotlin so it can be rendered and tested on the JVM.
 */
object LuxPixel : PixelAvatar {
    const val SIZE = PixelAvatar.SIZE
    override val name = "Lantern"

    private const val BLACK = 0xFF000000.toInt()
    private val OUTLINE = rgb(0x2A1A10)
    private val BODY = intArrayOf(rgb(0x8A4B1C), rgb(0xD48A2F), rgb(0xF6C65E), rgb(0xFFF1BE))
    private val EYE = rgb(0x24142E)
    private val SHINE = rgb(0xFFFFFF)
    private val BLUSH = rgb(0xFF7E8E)
    private val MOUTH = rgb(0x4A1A22)
    private val TONGUE = rgb(0xFF6F7D)
    private val ERROR_EYE = rgb(0x7A0F1C)

    // 4x4 Bayer matrix for ordered dithering.
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    override fun accent(mode: AvatarMode): Int = when (mode) {
        AvatarMode.BOOT -> rgb(0x6C7A96)
        AvatarMode.IDLE -> rgb(0xFFC94D)
        AvatarMode.LISTENING -> rgb(0x3DDCFF)
        AvatarMode.THINKING -> rgb(0xA87BFF)
        AvatarMode.SPEAKING -> rgb(0x4BE38A)
        AvatarMode.ERROR -> rgb(0xFF4D5E)
        AvatarMode.OFF -> rgb(0x3A3A3A)
    }

    override fun render(p: AvatarPose, out: IntArray) {
        require(out.size >= SIZE * SIZE)
        out.fill(BLACK, 0, SIZE * SIZE)
        val t = p.t
        val level = p.level.coerceIn(0f, 1f)
        val happy = p.happy.coerceIn(0f, 1f)
        val accent = accent(p.mode)
        val off = p.mode == AvatarMode.OFF
        val error = p.mode == AvatarMode.ERROR

        // Overall brightness: dim when off, fade in while booting.
        val bright = when (p.mode) {
            AvatarMode.OFF -> 0.35f
            AvatarMode.BOOT -> min(1f, 0.3f + p.modeT * 0.7f)
            else -> 1f
        }

        // Pose: centre, bob and size.
        val bob = when (p.mode) {
            AvatarMode.IDLE -> sin(t * 1.6f) * 0.8f
            AvatarMode.SPEAKING -> sin(t * 9f) * 0.5f
            AvatarMode.THINKING -> sin(t * 2.2f) * 0.5f
            else -> 0f
        } - happy * abs(sin(t * 10f)) * 2f
        val cx = 31.5f
        val cy = 34f + bob
        val r = 16.5f +
            (if (p.mode == AvatarMode.IDLE) sin(t * 1.6f) * 0.4f else 0f) +
            (if (p.mode == AvatarMode.LISTENING) level * 1.4f else 0f)

        // 1. Aura around the body, in the mode's accent colour.
        val auraIntensity = when (p.mode) {
            AvatarMode.IDLE -> 0.35f + 0.1f * sin(t * 1.6f)
            AvatarMode.LISTENING -> 0.45f + level * 0.55f
            AvatarMode.THINKING -> 0.55f
            AvatarMode.SPEAKING -> 0.4f + 0.3f * (0.5f + 0.5f * sin(t * 10f))
            AvatarMode.ERROR -> 0.5f + 0.2f * sin(t * 6f)
            AvatarMode.BOOT -> 0.2f
            AvatarMode.OFF -> 0f
        } + happy * 0.3f
        if (auraIntensity > 0f) {
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d <= r || d > r + 10f) continue
                val k = (d - r) / 10f
                var glow = auraIntensity * (1f - k) * (1f - k)
                if (p.mode == AvatarMode.THINKING) {
                    // Three lobes rotating around the body.
                    val a = atan2(dy, dx)
                    glow *= 0.35f + 0.65f * (0.5f + 0.5f * cos(3f * a - t * 3f))
                }
                val q = floor(glow * 3f + threshold(x, y)) / 3f
                if (q > 0f) out[y * SIZE + x] = scale(accent, min(1f, q))
            }
        }

        // 2. Body: a shaded sphere, warm light tinted toward the accent.
        val ramp = IntArray(4) { i ->
            val base = if (error) mix(BODY[i], rgb(0xE0465A), 0.45f) else BODY[i]
            val tinted = mix(base, accent, 0.18f)
            if (off) mix(tinted, rgb(0x777777), 0.7f) else tinted
        }
        val lx = -0.45f; val ly = -0.6f; val lz = 0.66f
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d > r) continue
            if (d > r - 1.1f) {
                out[y * SIZE + x] = OUTLINE
                continue
            }
            val nx = dx / r; val ny = dy / r
            val nz = sqrt(max(0f, 1f - nx * nx - ny * ny))
            val diff = max(0f, nx * lx + ny * ly + nz * lz)
            val v = 0.12f + 0.88f * diff
            val idx = floor(v * 3f + threshold(x, y) - 0.25f).toInt().coerceIn(0, 3)
            out[y * SIZE + x] = ramp[idx]
        }

        // 3. Spark above the head: Lux's light, twinkling.
        if (!off) {
            val sx = cx.toInt()
            val sy = (cy - r - 5f).toInt()
            val tw = 0.5f + 0.5f * sin(t * (if (p.mode == AvatarMode.THINKING) 6f else 2.5f))
            val arm = if (tw > 0.66f) 3 else if (tw > 0.33f) 2 else 1
            val c = mix(ramp[3], accent, 0.5f)
            for (i in -arm..arm) {
                set(out, sx + i, sy, c)
                set(out, sx, sy + i, c)
            }
            set(out, sx, sy, SHINE)
        }

        // 4. Eyes.
        val eyeY = cy - 2f
        val look = when (p.mode) {
            AvatarMode.THINKING -> Pair(sin(t * 1.3f) * 1.6f, -1.6f)
            AvatarMode.LISTENING -> Pair(0f, -0.4f)
            else -> Pair(0f, 0f)
        }
        val blink = (p.mode == AvatarMode.IDLE || p.mode == AvatarMode.LISTENING || p.mode == AvatarMode.SPEAKING) &&
            ((t + 2f) % 4.3f) < 0.13f // first blink ~2 s in, then every 4.3 s
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * 6.5f + look.first
            val ey = eyeY + look.second
            when {
                error -> {
                    for (i in -2..2) {
                        set(out, (ex + i).toInt(), (ey + i).toInt(), ERROR_EYE)
                        set(out, (ex + i).toInt(), (ey - i).toInt(), ERROR_EYE)
                    }
                }
                off || p.mode == AvatarMode.BOOT && p.modeT < 0.6f -> {
                    for (i in -2..2) set(out, (ex + i).toInt(), ey.toInt(), EYE)
                }
                happy > 0.3f -> {
                    // ^ ^ squint
                    for (i in -2..2) set(out, (ex + i).toInt(), (ey - 1 + abs(i)).toInt(), EYE)
                }
                blink -> for (i in -2..2) set(out, (ex + i).toInt(), ey.toInt(), EYE)
                else -> {
                    val ew = if (p.mode == AvatarMode.LISTENING) 2.7f else 2.2f
                    val eh = if (p.mode == AvatarMode.LISTENING) 3.8f else 3.2f
                    ellipse(out, ex, ey, ew, eh, EYE)
                    set(out, (ex - 0.8f).toInt(), (ey - 1.6f).toInt(), SHINE)
                }
            }
        }

        // 5. Cheeks when happy or chatting.
        if (!off && !error && (happy > 0.2f || p.mode == AvatarMode.IDLE || p.mode == AvatarMode.SPEAKING)) {
            for (side in intArrayOf(-1, 1)) {
                val bx = (cx + side * 10.5f).toInt()
                val by = (cy + 3f).toInt()
                set(out, bx, by, BLUSH)
                set(out, bx + side, by, BLUSH)
            }
        }

        // 6. Mouth.
        val mx = cx
        val my = cy + 6f
        when {
            off || p.mode == AvatarMode.BOOT -> Unit
            error -> for (i in -2..2) set(out, (mx + i).toInt(), (my + abs(i) * 0.5f).toInt() - 1, MOUTH) // frown
            p.mode == AvatarMode.SPEAKING -> {
                val open = 0.6f + (0.5f + 0.5f * sin(t * 14f + sin(t * 5f))) * 2.4f
                ellipse(out, mx, my, 2.6f, open, MOUTH)
                if (open > 1.6f) set(out, mx.toInt(), (my + open - 1f).toInt(), TONGUE)
            }
            p.mode == AvatarMode.LISTENING -> ellipse(out, mx, my, 1.2f, 1.2f + level * 1.5f, MOUTH)
            p.mode == AvatarMode.THINKING -> for (i in 0..2) set(out, (mx + i).toInt(), my.toInt(), MOUTH)
            else -> for (i in -2..2) set(out, (mx + i).toInt(), (my + 1 - abs(i) * 0.5f).toInt(), MOUTH) // smile
        }

        // 7. Thinking: three dots orbiting the body.
        if (p.mode == AvatarMode.THINKING) {
            for (i in 0 until 3) {
                val a = t * 2.4f + i * (2f * PI.toFloat() / 3f)
                val ox = cx + cos(a) * (r + 5.5f)
                val oy = cy + sin(a) * (r + 5.5f) * 0.55f
                set(out, ox.toInt(), oy.toInt(), mix(accent, SHINE, 0.4f))
            }
        }

        if (bright < 1f) for (i in 0 until SIZE * SIZE) out[i] = scale(out[i], bright)
    }

    // ---- helpers ----

    private fun threshold(x: Int, y: Int) = (BAYER[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f

    private fun set(out: IntArray, x: Int, y: Int, c: Int) {
        if (x in 0 until SIZE && y in 0 until SIZE) out[y * SIZE + x] = c
    }

    private fun ellipse(out: IntArray, cx: Float, cy: Float, rx: Float, ry: Float, c: Int) {
        for (y in floor(cy - ry).toInt()..(cy + ry).toInt() + 1) for (x in floor(cx - rx).toInt()..(cx + rx).toInt() + 1) {
            val nx = (x + 0.5f - cx) / rx
            val ny = (y + 0.5f - cy) / ry
            if (nx * nx + ny * ny <= 1f) set(out, x, y, c)
        }
    }

    private fun rgb(v: Int) = (0xFF shl 24) or v

    private fun scale(c: Int, k: Float): Int {
        val r = ((c shr 16 and 0xFF) * k).toInt()
        val g = ((c shr 8 and 0xFF) * k).toInt()
        val b = ((c and 0xFF) * k).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun mix(a: Int, b: Int, k: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - k) + ((b shr s) and 0xFF) * k).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
```
