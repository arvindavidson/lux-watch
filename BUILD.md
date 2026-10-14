# Lux Watch — Build & Install Guide

Turns a Samsung Galaxy Watch (Wear OS 3+, e.g. Galaxy Watch 4) into a
Muse voice gadget: hold-to-talk → dictation → Muse reply → spoken aloud.

## What you need

- A PC with **Android Studio** (Hedgehog or newer)
- Your Galaxy Watch 4 (or newer) with **Wear OS 3.0+**, charged
- Your phone with the **Muse app** installed and signed in
- A gadget token from **gadgets.muse.ai/settings/sdk-tokens** (starts with `mgst_…`)

## 1. Open the project

1. Copy the entire `lux-watch` folder to your PC.
2. In Android Studio: **File → Open** → select the `lux-watch` folder.
3. Let Gradle sync (first sync downloads dependencies — needs internet).

> The project was developed with `compileSdk 34`, `minSdk 30`, Kotlin 2.0.20
> and Compose for Wear OS 1.4.0. If Gradle complains about the Android SDK
> location, set it in **File → Settings → Appearance → System Settings →
> Android SDK**.

## 2. Build the APK

- For the watch, build the optimized APK (R8, not debuggable; much faster than
  a debug build): `./gradlew :app:assembleOptimized`. It lands at
  `app/build/outputs/apk/optimized/app-optimized.apk`, with the same package
  and debug key as the debug build, so either installs over the other.
- For debugging: `./gradlew :app:assembleDebug`
  (`app/build/outputs/apk/debug/app-debug.apk`), or Android Studio's
  **Build → Build APK(s)**.
- Protocol tests (pure JVM, no device needed): `./gradlew :gadget:test`.

## 3. Enable sideloading on the watch

1. On the watch: **Settings → About watch → Software** → tap **Software version**
   7 times until "Developer mode enabled".
2. **Settings → Developer options** → enable **ADB debugging** and
   **Debug over Wi-Fi**.
3. Connect the watch to the same Wi-Fi as your PC. Note the IP address shown
   under Debug over Wi-Fi (e.g. `192.168.1.42`).

## 4. Install

```bash
# from the platform-tools folder, or anywhere with adb on PATH
adb connect 192.168.1.42:5555
# accept the pairing prompt on the watch
adb install -r lux-watch.apk   # from Releases, or your own build:
# adb install -r app/build/outputs/apk/optimized/app-optimized.apk
```

The release APK and your own builds are each signed with the debug key of the
machine that built them. Android won't install one over the other: uninstall
first (`adb uninstall com.luxwatch.gadget.debug`) and pair again.

## 5. Pair with the Muse app

1. Open **Lux Watch** on the watch and allow **Nearby devices** (Bluetooth) when
   asked — pairing can't start without it. The **microphone** permission is
   requested the first time you hold the talk button.
2. The watch starts BLE advertising as `MuseGadgetXXXXXX` and shows
   "Not paired — add in Muse app".
3. On your phone, open the **Muse app** → gadgets/pairing → scan.
   Select the `MuseGadgetXXXXXX` whose suffix matches the watch screen.
4. Approve the pairing in the app. The watch receives its device tokens
   over the encrypted BLE channel and stores them in encrypted storage.
5. The watch confirms with "Paired. Tap to talk to Lux."

## 6. Use it

Swipe between three pages: **Talk**, **History** and **Settings**.

- **Talk**: tap Lux, speak, then stop: it sends after about a second of
  quiet (or tap to send sooner; it gives up after 6 s of silence). Listening →
  Thinking → Speaking: the reply shows on screen and plays on the watch speaker.
  Tap while it speaks to stop it. Turn the bezel to page through a long reply,
  or to change the volume while Lux speaks.
- **Lux watch face**: long-press the current face, swipe to the end, tap **+**
  and pick **Lux** (or Galaxy Wearable app → Watch faces). Tap the avatar to
  talk: Lux opens already listening and goes back to the face after the
  reply. Long-press → **Customize** picks the two complications (battery and
  steps by default). The avatar animates when you raise your wrist and while
  Lux is busy, and is a still frame otherwise; in always-on mode it's dim.
  This face uses the AndroidX watch face library, which Google stopped allowing
  installs of in January 2026 in favour of Watch Face Format. If Lux isn't in
  the face list after installing the APK, the watch is refusing it.
- **Tile / complication** on another face: one tap opens Lux listening.
- **Messages from Muse**: ask Muse to "remind me at 5 on my watch" or "send
  this to my watch". The watch buzzes and shows it (and reads it out if Lux is
  open). Needs Settings → Messages from Muse on. It's off by default: it keeps
  the watch connected all day (a keep-alive every minute), which costs battery.
- **Your Muse's avatar**: Settings → **Get my avatar** asks Muse to send its
  avatar clips; the watch prepares them and switches to them. Settings →
  **Avatar** switches between avatars.
- **Voice**: Settings → Voice and Speed.

## Entering the gadget token (mgst_…)

Get a token at **gadgets.muse.ai/settings/sdk-tokens**. Then either:

- **On the watch**: Settings → **SDK token**. The watch's text input opens; on
  a Galaxy watch you can choose to type on the phone, and paste the token
  there. The watch picks the token out of whatever you paste.
- **With adb** (debug builds only; watch connected, before pairing):

  ```bash
  adb shell am start -S -n com.luxwatch.gadget.debug/com.luxwatch.gadget.MainActivity \
    --es sdk_token mgst_YOUR_TOKEN
  ```

  `-S` stops the app first. Without it, if Lux Watch is already open, Android
  only brings it to the front and the token is **not** delivered. (For a
  release build drop the `.debug` from the package name.)

The watch checks the format, stores it encrypted, and shows **SDK token
saved**. Like Meta's SDK it is sent inside the encrypted `pairing_confirmed`
BLE status and in one token refresh per app start; it is never used as a
Bearer token and never logged. It survives unpairing. Keep the token private:
don't commit it or paste it into chats.

## Troubleshooting

| Problem | Fix |
|---|---|
| Watch doesn't appear in the Muse app scan | Make sure the Lux Watch app is open (advertising runs while the app is in foreground in v1). Check Bluetooth is on. |
| "Not paired yet" when tapping Lux | Pairing didn't complete — reopen the app and re-scan from the Muse app. |
| Reply never comes / timeout | Check the watch has Wi-Fi or LTE data. The Noise session needs internet. |
| TTS silent | Check watch volume; some Watch 4 builds need the "Google TTS" voice data downloaded (Settings → General → Text-to-speech). |
| `adb connect` refused | Re-enable "Debug over Wi-Fi" (it turns off when the watch sleeps); re-accept the RSA prompt. |

## Project layout

```
lux-watch/
  docs/PROTOCOL.md        # exact gadget protocol spec (from Meta's Python SDK)
  gadget/src/main/kotlin/com/luxwatch/gadget/
    proto/Proto.kt        # hand-rolled protobuf codec
    noise/Noise.kt        # Noise_XX_25519_AESGCM_SHA256 (verified vs Python)
    noise/Framing.kt      # chunk + service-envelope framing
    noise/MuseApi.kt      # fetch_vms + token refresh (HTTPS)
    noise/NoiseTransport.kt # WebSocket + Noise handshake + stream mux
    noise/MuseSession.kt  # link.register + link-control reader (invokes) + voice/text turns
    noise/LinkCommands.kt # commands offered to Muse (commands_v2) and their results
    noise/SessionFraming.kt # length-prefixed JSON + NDJSON stream bodies
    noise/Json.kt         # minimal JSON field extraction / escaping
    pairing/PairingCrypto.kt # BLE pairing v5 transcript, P-256 ECDH, keys, records
    voice/Endpointer.kt   # sends when you stop talking
    avatar/               # pixel avatars (LuxPixel, MusePixel), BackgroundKey
  gadget/src/test/        # JUnit: ./gradlew :gadget:test
  app/src/main/java/com/luxwatch/gadget/
    MainActivity.kt       # talk, history and settings pages (ui/), tap to talk
    LuxRuntime.kt         # the shared Muse connection, keep-alive, messages
    LuxCommands.kt        # watch.notify, device.health, avatar.set_clips
    ConversationLog.kt    # history, kept on the watch
    LuxStatus.kt          # what Lux is doing, for the watch face
    avatar/               # AvatarArt: clip avatars, import (ClipImporter), pixel art
    face/                 # Lux watch face, its complications and Customize screen
    LuxTileService.kt     # Wear OS Tile
    LuxComplicationProvider.kt # watch-face complication
    BlePairingServer.kt   # BLE GATT pairing (protocol v5): transport + state machine
    GadgetStorage.kt      # encrypted identity + pairing storage
    GadgetConnection.kt   # connection lifecycle
    MuseLuxBackend.kt     # LuxBackend wiring
    AudioCapture.kt       # mic capture
    LuxTts.kt             # on-device TTS
```

## Security notes

- Device tokens live in `EncryptedSharedPreferences` (AES-256-GCM).
- The `mgst_…` SDK token is never used as a Bearer token and never logged.
- BLE pairing (v5) uses P-256 ECDH + HKDF-SHA256 + AES-256-GCM; it protects
  against passive observers only — pair on a network you trust.
- Deleting the app (or "unpair" from the Muse app) wipes the pairing.
