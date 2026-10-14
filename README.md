# Lux Watch

A Wear OS app that turns a Samsung Galaxy Watch into a Muse voice gadget.

> **Unofficial.** Lux Watch is an independent project, not made, endorsed or
> supported by Meta. It builds on Meta's open-source
> [Muse Gadget SDK](https://github.com/facebookincubator/muse-gadget-sdk).
>
> **Beta.** Talking to Muse, pairing and the watch face run on a Galaxy Watch4
> Classic. Messages from Muse, avatar import, the face's complications and the
> hardware video playback are newer and have had little testing on a watch.

**Download:** the APK is on the [Releases](../../releases) page. Install it with
`adb install -r lux-watch.apk` (see [BUILD.md](BUILD.md) for sideloading and
pairing).

**Status:** running on a Galaxy Watch4 Classic (One UI 8 Watch / Wear OS 6):
pairs with the Muse app over BLE and talks to Muse. The Noise handshake,
framing and BLE pairing key schedule are checked byte-for-byte against Meta's
`muse-gadget-sdk` (`./gradlew :gadget:test`; see
`gadget/src/test/resources/README.md`).

## How it works

1. **Pair**: the watch advertises over BLE; the Muse phone app pairs using
   protocol v5 (P-256 ECDH) and provisions device tokens.
2. **Connect**: the watch fetches its VM list, opens a Noise-secured WebSocket
   (`Noise_XX_25519_AESGCM_SHA256`) and registers over `/link-control`. One
   connection serves the whole app; it warms up when you raise your wrist and,
   with Settings → Messages from Muse on, stays up and reconnects on its own.
3. **Talk**: tap Lux (on the watch face, the tile or the complication), speak,
   and stop: it sends when you stop talking. The 16 kHz recording goes to
   `/chat/stream` as a voice note (as Meta's ESP32 boards send it); the reply
   streams back over `/chat/subscribe` and is shown and spoken with on-device
   TTS (Muse sends no reply audio). From the face, Lux returns to the face after.
4. **Muse can reach the watch**: it offers Muse commands in `link.register`
   (`watch.notify` for reminders and messages, `device.health`,
   `avatar.set_clips`) and answers `link.invoke`.
5. **Avatar**: Lux plays Muse's own avatar clips (background turned black);
   anyone's Muse can send its clips to the watch (Settings → Get my avatar).
   Pixel-art avatars are built in too. See `docs/muse-avatar.md`.
6. **Watch face**: the time, two complications and the avatar, which animates
   when you look and while Lux is busy, and otherwise stays still to save
   battery.

## Layout

- `docs/PROTOCOL.md` — exact protocol spec extracted from Meta's
  `muse-gadget-sdk` Python Linux SDK (every claim cited).
- `gadget/` — pure-Kotlin protocol client (no Android dependencies):
  protobuf codec, Noise XX, chunk/service framing, HTTPS API, WebSocket
  transport, session logic, link commands, end-of-speech detection, pixel
  avatars and the clip background keyer. Tests: `./gradlew :gadget:test`;
  vectors and their generator live in `gadget/src/test/resources/` and
  `gadget/tools/`.
- `app/` — Wear OS app: talk, history and settings pages, watch face, tile,
  complication, BLE pairing server, encrypted storage, the shared connection,
  Muse's commands, avatar clips and import, TTS. Screenshot tests:
  `./gradlew :app:testDebugUnitTest` (images in `app/build/screens/`).
- `BUILD.md` — build, sideload, and pairing instructions.
- `docs/muse-avatar.md` — putting your Muse's own avatar on the watch.

## Verified

`./gradlew :gadget:test` against vectors from Meta's `muse-gadget-sdk`:

```
PASS ble.community_v5 / official_v5 / community_app_v5 (ecdh, transcript, keys, session id, client_finished record)
PASS noise.msg1 (32 bytes) / readMessage2 / msg3 (64 bytes) / transport_ct (32 bytes)
PASS proto.varint(1) / varint(300) / int64field(1,-1)
PASS framing.service_frame (78 bytes) / framing.chunk[0] (92 bytes)
```

## Features

- Talk screen: tap to talk, sends on silence; reply captions (bezel pages
  through long ones, and sets the volume while Lux speaks); haptics.
- History page: recent exchanges and Muse's messages, kept on the watch.
- Settings: Muse connection, SDK token (typed or pasted on the watch), Messages
  from Muse, voice and speed, volume, haptics, avatar, re-pair.
- Lux watch face with complications and a Customize screen; tile; complication.

## Open risks

- The Lux watch face uses the AndroidX watch face library. Google stopped
  allowing installs of such faces in January 2026 (Watch Face Format is
  required); sideloading still works on the Watch4 Classic today.
- Messages from Muse need the link up: the process stays alive while Lux is the
  watch face; with another face, Android may stop it in the background.
- Whether Muse can give HTTPS links to its avatar clips for `avatar.set_clips`
  is untested.
- `noise_host` vs `vm_ws_url` domain: the pairing record is authoritative.

## License

Apache License 2.0: see [LICENSE](LICENSE) and [NOTICE](NOTICE).
