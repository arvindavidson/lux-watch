# Your Muse's avatar on the watch

The watch shows a 64x64 pixel-art avatar that animates for each state (boot,
idle, listening, thinking, speaking, error, off, and a happy hop when a reply
arrives). The built-in one is Lux, an original lantern spirit. Your Muse can
draw itself instead, the same way Meta's boards do it
(`esp32/tools/muse/AVATAR_RECIPE.md` in the Muse Gadget SDK). The SDK's
default board avatar is Meta's character, and the Apache License doesn't
grant rights to it, so this app doesn't ship it.

The difference from the boards: the watch can't compile code, so Muse writes
the avatar as a Kotlin file (`MusePixel.kt`) that goes into the next APK.

## 1. Ask Muse

Send your Muse the whole of [`muse-avatar-request.md`](muse-avatar-request.md)
as one message. It holds the prompt plus the two files it refers to
(the avatar API and Lux's renderer, which Muse starts from). Muse replies
with one ```kotlin block, or with `NO AVATAR: …` if it can't find its avatar.

The prompt alone is in [`muse-avatar-prompt.md`](muse-avatar-prompt.md).
After editing it or the renderer, regenerate the request with
`python3 gadget/tools/make_avatar_request.py`.

## 2. Add the file

Either paste Muse's reply into the Claude session, or add it to the repo
yourself as
`gadget/src/main/kotlin/com/luxwatch/gadget/avatar/MusePixel.kt`
(GitHub: Add file → Create new file), and put `MusePixel` first in
`Avatars.all` in `PixelAvatar.kt`:

```kotlin
val all: List<PixelAvatar> = listOf(MusePixel, LuxPixel)
```

## 3. Check it

```sh
./gradlew :gadget:test
```

`AvatarContractTest` checks every avatar: it compiles, draws every mode
with opaque pixels, survives odd poses, takes under 1 ms a frame with no
per-frame allocation, and writes a preview sheet to
`gadget/build/avatar-preview-<name>.png` (one row per mode, the last row the
happy reaction). If a check fails, send the failure back to Muse and ask it
to fix the file, as the boards' `avatar.py` does.

## 4. On the watch

Build and install the APK (see [BUILD.md](../BUILD.md)). Muse's pixel avatar
shows up as **Pixel Lux**: swipe left to Settings and tap **Avatar** to cycle
through Lux (the video avatar below, the default), Pixel Lux and Lantern; the
choice is remembered.

To change the avatar later, send Muse its current `MusePixel.kt` with what to
change ("make the ears bigger and the eyes green") and replace the file with
its reply.

## Muse's video avatar (the default)

Muse also has its avatar as short video clips, one per state. The app's default
avatar, **Lux**, plays those clips (`ClipArt` in `app/.../avatar/AvatarArt.kt`):

| Watch state | Clip |
|---|---|
| idle, connecting, listening, error | `connecting.mp4` (looking around) |
| thinking | `waiting_for_subagents.mp4` / working (typing at the laptop) |
| speaking | `making_something.mp4` (the little dance) |
| paired | `milestone_level_up.mp4` (celebration, once) |

A thin ring in the state's colour goes around the clip; always-on shows a dimmed
still. To update the clips, put Muse's MP4s in a folder and run
`gadget/tools/make_avatar_clips.sh FOLDER`. It turns the white background black
(only the white connected to the frame's edge, so the belly and ears stay),
fades the edges, and writes 320x320, 15 fps animated WebPs to
`app/src/main/res/raw/`. The two pixel avatars (Pixel Lux, Lantern) are still
in Settings → Avatar.

## Anyone's own Muse avatar, without adb or a custom APK

The watch offers Muse an `avatar.set_clips` command: its avatar clips (`idle`
required; `listening`, `thinking`, `speaking`, `happy`, `error` optional) and a
`name`. Each clip is the path of a video file on Muse's computer, which the
watch reads over its own encrypted connection (`GET /fs/raw/…` in ranges, as
Meta's boards do for `display.draw_file`), or an HTTPS link. On the watch: Settings → **Get my avatar**, which asks
Muse to send them, or just tell Muse "put your avatar on my watch".

The watch downloads the clips before answering Muse (so bad links come back
as errors), then prepares them in the background: 15 fps, at most 10 s,
square 320 px, background turned black with the same `BackgroundKey` the build
script uses, stored as JPEG frames in the app's files (`ClipImporter`). When
it's ready the watch says so and the new avatar becomes the default
(`FrameClipArt`); the built-in avatars stay in Settings → Avatar.
