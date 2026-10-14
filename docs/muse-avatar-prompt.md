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
