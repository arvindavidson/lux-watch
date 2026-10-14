# Muse Gadgets — Wire Protocol Specification (for a Wear OS reimplementation)

Source: Meta's public SDK repo `facebookincubator/muse-gadget-sdk` (Apache 2.0),
snapshot cloned 2026-10-03. The Python Linux SDK (`linux/src/musegadget/`) is the
primary reference; the ESP32 C/C++ firmware (`esp32/components/muse/`) is cited
where it documents flows the Python SDK does not implement (voice turns).

**Scope note.** The Python SDK implements: BLE pairing/setup, token management,
Noise session, the `/link-control` device-command channel, and **text-only**
chat (`POST /chat/stream`). The **voice** flows — `POST /api/voice/dictation`,
`POST /chat/subscribe`, the base64-WAV voice note — exist only in the ESP32
firmware (`esp32/components/muse/muse_chat_session.cpp`). A Wear OS voice gadget
needs the ESP32 voice flow grafted onto the Python session architecture. Both
are documented below.

All line citations are approximate (±5 lines) against the cloned snapshot.

---

## 1. End-to-end flow

### 1.1 First-time setup (pairing)

1. Operator puts an SDK token (`mgst_…`, from `gadgets.muse.ai/settings/sdk-tokens`)
   on the device: env var `MUSEGADGET_SDK_TOKEN` or the `sdk_token` file in the
   state dir (`/var/lib/musegadget`, or `$MUSEGADGET_STATE_DIR`). Format:
   `mgst_` + 43 base64url chars; anything else is rejected at startup.
   (`linux/src/musegadget/config.py` ~L30, ~L40–56; `cli.py` ~L80–88 — "gadgets
   without one will stop pairing".)
2. `musegadget pair` generates (or loads) a stable device identity and starts
   BLE advertising for 10 minutes (default; `--timeout`). (`cli.py` ~L67–100)
3. In the Muse phone app: **Settings > Devices > Developer mode ON > Add Device
   (+)**. The gadget appears as `MuseGadgetXXXXXX` (see §6). The app warns it is
   a *community device*; the user continues. (`linux/README.md` "Set it up
   with Muse"; `linux/AGENTS.md` module table)
4. Phone and gadget run **pairing protocol v5** over BLE GATT (ECDH P-256,
   community mode — no manufacturer auth, app-tap confirms; see §6).
5. During pairing the gadget sends its `sdk_token` to the app inside the
   encrypted `pairing_confirmed` status (`pairing.py` `encrypt_status`
   ~L520–530: "Apps read only type and status, so older ones ignore the token").
6. The app sends `provision_v2` with the **account's device token pair**:
   `access_token`, `refresh_token`, `token_type: "device"`, plus `username`,
   `api_url`, `api_url_v2`, `noise_host` (and `ssid`/`password`, which the Linux
   gadget deliberately ignores — it is already online). (`ble_setup.py`
   `_handle_provision` ~L270–310)
7. The gadget verifies the tokens with a `fetch_vms` call; on success it saves
   `pairing.json` = `{access_token, refresh_token, token_type, username,
   api_url, api_url_v2, noise_host, access_token_saved_at}` (0600, atomic
   write). (`cli.py` `_verify_and_save` ~L47–65; `config.py` `save_json`
   ~L70–85)
8. BLE setup shuts down. The device is paired.

### 1.2 Steady-state connection loop (`service.py` ~L85–130)

Each round:
1. Load `pairing.json`. If absent: log "not paired", sleep 30 s, retry.
   (`service.py` ~L88–94, `UNPAIRED_POLL_S` L53)
2. Rotate the device token if due (see §8).
3. `GET /fetch_vms` with the device access token (see §2). On 401: force a
   token refresh and retry. Pick the VM with `default: true`, else the first.
4. Open `wss://{noise_host}/v1/noise?vm_id={vm_id}` with
   `Authorization: Bearer {vm_auth_token}`, run the Noise XX handshake, then
   open the long-lived `POST /link-control` stream and send `link.register`.
   (see §3–4)
5. Serve until the session ends; on end, exponential backoff (2 s → 60 s max;
   a session healthy > 30 s resets it; auth failures set a 15 s floor) and
   reconnect. (`service.py` `Backoff` ~L58–72, ~L120–130)
6. `link.unpaired` / `node.unpaired` event, or 401 on token refresh → delete
   `pairing.json`; the device must be paired again. (`link_client.py` ~L280–290;
   `service.py` ~L190–200)

### 1.3 Device identity

Generated once, kept across upgrades **and** unpairing (`identity.py` ~L56–78):
- `mac`: random 6 bytes, first octet forced to unicast + locally administered
  (`(b0 & 0xFC) | 0x02`). Stored as `identity.json` (`{"mac": "…"}﻿`).
- `node_id` = `"homelink-"` + last 6 hex digits of the MAC (no colons).
- `device_id` = `"hatch-link:"` + full MAC — used as `device_id` in token
  refresh and in `link.register` params (`register_params`, `link_client.py`
  ~L76–88; refresh body `muse_api.py` ~L107–135).
- `ble_name` = `"MuseGadget"` + 6 hex digits **uppercased**, no separator. The
  apps compare the text after `MuseGadget` with the text after `homelink-`.

---

## 2. `fetch_vms` — VM discovery

File: `linux/src/musegadget/muse_api.py` ~L30–104.

- **URL:** `https://api.muse.ai/fetch_vms` (`API_BASE` + `FETCH_PATH`; an
  `api_url_v2` from pairing overrides the base — `api_root()` ~L36–42).
- **Method:** `GET`.
- **Headers:**
  - `Authorization: Bearer <device access_token>` (the token the phone app
    provisioned, **not** the `mgst_…` SDK token)
  - `X-API-Version: 1.0.0`
  - `User-Agent: musegadget/0.1.0 (<platform details>) Python/<ver>` (on Linux
    it also reads `/proc/device-tree/model` when present — `user_agent()`
    ~L45–55; a Wear OS client should send its own UA, e.g.
    `luxwatch/1.0 (Wear OS)`)
- **Timeout:** 15 s.
- **Response JSON:**
  ```json
  {
    "vm_list": [
      {
        "vm_ws_url": "wss://<vm_id>.<noise_host>/…",   // or "vm_url"
        "vm_auth_token": "<per-VM bearer>",
        "vm_name": "…",
        "vm_id": "<vm_id>",
        "default": true
      }
    ]
  }
  ```
  Entries missing `vm_url`/`vm_ws_url` or `vm_auth_token` are skipped.
  Error responses carry `error_title` / `backend_error_code`.
- **Status semantics:** `401` = device token rejected — do not retry the
  fetch; rotate the token instead. `None` (no HTTP response) = transport
  failure — retry. (`fetch_vms_with_status` docstring ~L48–56)
- **Deriving the Noise URL:** `wss://{noise_host}/v1/noise?vm_id={vm_id}`
  (`link_client.py` `noise_url()` ~L131–133; `?vm_id=` is
  encodeURIComponent-escaped, safe chars `-_.!~*'()`). In the Python client,
  `noise_host` comes from the pairing record (`pairing["noise_host"]`,
  default `hatch.metaaivm.com` — `service.py` L45, ~L133–140) and `vm_id`
  from the chosen `vm_list` entry (`vm["vm_id"] or vm["vm_name"]`).
  The ESP32 C client instead parses `vm_id` as the **first DNS label** of the
  `wss://` URL (`esp32/components/muse/muse_account_api.c`, `id_from_url`).
  ⚠️ **Ambiguity:** the code does not prove `noise_host` always equals the
  `vm_ws_url` domain; treat the pairing-supplied `noise_host` as authoritative
  and the URL's first label as the `vm_id`.

---

## 3. Noise session

### 3.1 Handshake — `Noise_XX_25519_AESGCM_SHA256`

File: `linux/src/musegadget/noise/noise_xx.py`.

- **Pattern:** standard Noise **XX** (`PROTOCOL_NAME =
  b"Noise_XX_25519_AESGCM_SHA256"`, L30): `-> e`, `<- e, ee, s, es`,
  `-> s, se`.
- **Initiator = the gadget.** `NoiseXXInitiator`: `initialize()` →
  `write_message1()` (32-byte ephemeral pubkey) → `read_message2()` →
  `write_message3()` → `split()`. Strict phase machine; any violation or
  short message poisons the handshake (`_Phase`, ~L240–260).
- **Static keys are ephemeral:** both `e` and `s` are freshly generated per
  handshake (`_generate_x25519_key_pair()` in `write_message1`/`write_message3`).
  There is **no long-term device static key** in the Noise handshake.
- **Handshake payloads are empty** (`encrypt_and_hash(b"")` in all three
  messages). Device authentication happens *before* Noise, at the WebSocket
  upgrade (`Authorization: Bearer <vm_auth_token>`); the code comments:
  "The bearer already authenticated us at the upgrade; message 3 carries an
  empty payload." (`link_client.py` `_handshake` ~L172–183)
- **Nonce construction:** `build_nonce_iv(n) = b"\x00"*4 + n.to_bytes(8,"big")`
  — the standard 96-bit Noise nonce (32 zero bits + 64-bit LE counter is the
  *spec* layout; this implementation uses **big-endian** counter bytes —
  reimplement exactly as shown, ~L148–153).
- **AEAD:** AES-256-GCM, 16-byte tag; associated data is the running
  handshake hash `h` during the handshake and **empty** (`EMPTY_AD = b""`)
  for all transport messages (`transport.py` L33, `_encrypt_request`
  ~L218–225, `decrypt_frame` ~L183–190).
- **Key schedule:** textbook Noise: `MixHash` = SHA-256 chaining,
  `MixKey` = HKDF-SHA256(ck, ikm) → `(ck, temp_k)`; `Split` = HKDF(ck, zerolen)
  → `(temp_k1, temp_k2)`. (`_SymmetricState` ~L190–240)
- **Hardening (reimplement or match):** peer ephemeral/static pubkeys are
  rejected if they are X25519 low-order points or produce an all-zero DH
  output (`_x25519_dh` ~L165–182); transport nonces poison the CipherState
  past 2⁵³−1 (`MAX_SAFE_NONCE`, `CipherState._next_nonce` ~L120–135).
- `split()` returns `(c1, c2)` where **c1 encrypts initiator→responder** and
  c2 the reverse. (The test-only responder swaps them — `NoiseXXResponder`
  is marked "Test responder for protocol verification", ~L360.)

### 3.2 Handshake on the wire

`link_client.py` `_handshake()` (~L172–183):
1. `await ws.send(initiator.write_message1())` — raw **binary** WebSocket frame,
   32 bytes.
2. `msg2 = await ws.recv()` — must be binary (a text frame is a fatal
   `ConnectionError`); fed to `read_message2()`.
3. `await ws.send(initiator.write_message3())` — binary, 64 bytes
   (48-byte encrypted static key + 16-byte encrypted empty payload;
   verified empirically against the reference implementation).
4. `send, recv = initiator.split()` → `NoiseTransport(send, recv)`.
5. Handshake timeout: 20 s (`HANDSHAKE_TIMEOUT_S`, L60). No extra framing
   around handshake messages.

### 3.3 Transport framing (post-handshake)

Two nested layers (`noise/framing.py`, `noise/envelope.py`,
`noise/transport.py`):

**Layer 1 — chunking** (`framing.py`): each encrypted payload is a
protobuf-encoded `NoiseTransportFrame`:
- field 1 `chunk_id` — int64 varint (random per message)
- field 2 `chunk_index` — uint32 varint
- field 3 `total_chunks` — uint32 varint
- field 4 `payload` — bytes (length-delimited)

Limits: 65 489 bytes payload per chunk (`MAX_CHUNK_PAYLOAD`), ≤256 chunks,
≤16 MiB per reassembly, 60 s assembly TTL (`framing.py` ~L33–37,
`NoiseFrameDecoder` ~L140–236). Encode with `encode_noise_frames()`;
decode/reassemble with `NoiseFrameDecoder` (strict: duplicate index,
inconsistent totals, or oversize → poison the decoder).

**Layer 2 — service envelope** (`envelope.py`), inside the reassembled
`ServiceResponse.payload`:
- `ServiceRequest{ service: varint enum, payload: bytes }` (field 1, field 2)
  wraps outbound frames; inbound arrives as
  `ServiceResponse{ payload: bytes }` (field 1). (`transport.py`
  `_encrypt_request` ~L218–225, `decrypt_frame` ~L183–213)
- `ServiceType`: `0 daemon`, `1 sentinel`, `2 vault`, `3 authd`
  (`envelope.py` ~L30–35; string map in `transport.py` ~L40–45 — the gadget
  uses `"daemon"`).
- `ServiceFrame{ stream_id: int64 varint = field 1, one of:
  field 2 request: ApplicationRequest | field 3 response: ApplicationResponse
  | field 4 body_chunk: BodyChunk | field 5 reset: Reset }`
  (`encode_service_frame`/`decode_service_frame`, `envelope.py` ~L395–470)
- `ApplicationRequest{ 1: verb string, 2: path string, 3: repeated Header,
  4: body bytes, 5: end_body bool }`
- `ApplicationResponse{ 1: status int32, 2: repeated Header, 3: body bytes,
  4: end_body bool }`
- `BodyChunk{ 1: data bytes, 2: end_body bool }`
- `Header{ 1: key string, 2: value string }`
- `Reset{ 1: code int32 enum, 2: reason string }`; codes: 0 unspecified,
  1 cancelled, 2 timeout, 3 protocol_error, 4 refused_stream,
  5 internal_error, 6 service_unavailable (`ResetCode`, ~L37–44).

**Stream multiplexing** (`transport.py`): client-originated `stream_id`s start
at **1** and increment per request (`_next_stream_id`). `encrypt_http_request`
sends a complete request (`end_body=True`); `start_stream_request` opens a
stream with empty body and `end_body=False`; further body goes via
`encrypt_body_chunk(stream_id, …)`; `encrypt_reset` cancels (default code
`CANCELLED`). Inbound frames for an unknown/other stream id are routed to
pending request collectors; a `request`-kind frame arriving **from the
server is a protocol error**. (`transport.py` ~L95–175, `decrypt_frame`
~L183–213)

⚠️ The protobuf codec is hand-rolled (`noise/_proto.py`: varint, int32/int64,
uint32, bool, string/bytes/delimited fields). Field numbers above are the
complete schema a Kotlin implementation needs; no `.proto` file ships.

---

## 4. Request/response: verbs, paths, chat

### 4.1 Control channel — `POST /link-control`

Opened once per session via `start_stream_request("POST", "/link-control")`
(`link_client.py` `_open_control_stream` ~L185–200). Both directions carry
**length-prefixed JSON**: `struct.pack("<I", len) + json.dumps(obj,
separators=(",",":")).encode()`; a zero-length message is a keepalive
(`encode_message`, `MessageDecoder` ~L95–130; max inbound message 4 MiB).

Device → VM:
- `{"type":"req","id":<uuid4>,"method":"link.register","params":{
  "node_id","display_name","platform":"linux","version":"0.1.0",
  "device_family":"homehub","model_id":"linux",
  "is_wakeup_supported":false,"commands_v2":{…}}}` — `commands_v2` is the
  command spec dict the Muse can invoke (Linux SDK: `system.run`,
  `file.read`, `file.write`, … — `executor.py` `COMMAND_SPECS` ~L49).
  A Wear OS client sets its own `platform`/`device_family`/`model_id` and
  its own (possibly empty) `commands_v2`.
- `{"method":"link.result","id":<invoke id>, …result fields}` — reply to each
  `link.invoke`.

VM → device:
- Register reply: same `"id"`, no `"method"`; `"error"` set on rejection.
  Success sets `registered_at`. (`_handle` ~L270–280)
- `{"method":"link.invoke","id":…,"command":"system.run",
  "params":{…},"timeout_ms":…}` — at most 4 concurrent
  (`MAX_CONCURRENT_INVOKES`, L62); each runs via the device's command
  runner and answers with `link.result`.
- `{"event":"link.unpaired"}` or `{"event":"node.unpaired"}` → the Muse
  removed this device: delete local pairing, re-pair required.

### 4.2 Text chat — `POST /chat/stream` (Python SDK)

`LinkSession.send_chat(message, session_id=None)` (`link_client.py`
~L203–240): `encrypt_http_request("POST", "/chat/stream", body, headers=…)`:
- Body JSON: `{"message": <text>, "output_modality": "text",
  "device_id": <node_id>, "session_id": <optional>}`. `session_id` targets a
  side chat (a new id starts one); omitted → main chat.
- Headers: `Content-Type: application/json`, `x-request-id: <uuid4>`,
  `x-app-id: musegadget` (`APP_ID`, L57).
- The reply arrives as that stream's `ApplicationResponse` body (60 s
  timeout, `REQUEST_TIMEOUT_S`; 1 MiB cap, `MAX_RESPONSE_BYTES`).

### 4.3 Voice turns — `POST /api/voice/dictation` + `POST /chat/subscribe`
(ESP32 firmware only)

Reference: `esp32/components/muse/muse_chat_session.cpp`, header comment
~L22–42. The Python SDK does **not** implement this; a Wear OS voice gadget
must.

1. **Dictation.** `POST /api/voice/dictation?sample_rate_hz=24000`
   (cpp ~L1029). No `Content-Type` on the request; `Accept:
   application/x-ndjson`. The mic is streamed **live as 24 kHz PCM16 mono**
   while the button is held (~8 KiB chunks ≈ 170 ms — `DICT_CHUNK_BYTES`),
   then the body is half-closed. The VM replies with NDJSON lines
   (`on_dictation_line`, cpp ~L1233–1256):
   - `{"type":"partial","text":"…"}` — cumulative partial transcript
   - `{"type":"final","text":"…"}` — a committed segment (mid-utterance
     finals accumulate; the final after half-close ends the turn)
   - `{"type":"error",…}`
   Release→final timeout 15 s (`FINAL_TIMEOUT_US`, cpp ~L111).
2. **Chat.** `POST /chat/stream` with the transcript (JSON body like §4.2).
   The ack is `{"result":{"message_id":"…","reply_to_message_id":"…"}}`
   (`on_chat_ack`, cpp ~L1478–1496).
3. **Subscribe.** A long-lived `POST /chat/subscribe` (opened once per
   connection: `Content-Type: application/json`, `Accept:
   application/x-ndjson`, body `{}` — cpp ~L804) streams reply events as
   NDJSON. Line shape (`on_event`, cpp ~L1403–1475):
   ```json
   {"type":"event","seq":123,"event":"delta.text_append",
    "payload":{"message_id":"…","text":"…"}}
   ```
   - First line(s) with `type != "event"` are the subscription ack — ignore.
   - `seq` (int64): drop any line with `seq ≤ last seen`.
   - `agent.status` / `task.status`: `payload.activity_code` / `payload.status`
     → busy indicator (anything other than `online`/`idle`/`completed`/`failed`
     = busy).
   - `delta.message_start` → new assistant message; id from
     `payload.message_id` → `event.message_id` → `payload.id` (`msg_id`,
     cpp ~L1390–1400).
   - `delta.text_append` → `payload.text` chunk.
   - `delta.message_done` → `payload.display_text` (fallback `content`);
     honor `payload.display_text_ready` (if explicitly false, not done).
   - `message.assistant` → full message variant of done.
   - **No explicit end-of-turn event.** A turn settles when every message is
     done and nothing arrives for a few seconds (cpp ~L31–33).
4. **Voice-note alternative** (used by `muse_chat_link.c` on boards without
   PSRAM instead of live dictation): `POST /chat/stream` with JSON body
   `{"message":"","output_modality":"text","items":[{"type":"file",
   "mime_type":"audio/wav","filename":"voice_note.wav",
   "data_base64":"<base64 of WAV>"}]}` (`muse_chat_priv.h` ~L41–46). WAV =
   44-byte header + **16 kHz PCM16 mono**; header uses streaming "unknown"
   lengths (`UINT32_MAX`) for RIFF/data sizes (`muse_chat_text.c`
   `muse_hatch_wav_header` ~L40–62: `RIFF????WAVEfmt `, fmt 16, PCM=1,
   mono, rate, byteRate=rate*2, blockAlign=2, bits=16, `data????`).

⚠️ **Ambiguity:** the exact half-close mechanism for the dictation upload
lives in the ESP32's own Noise stream implementation (`open_stream` with
`end_body`); the Python `encrypt_body_chunk(…, end_body=True)` is the
equivalent primitive, but the end-to-end dictation framing was not
cross-checked between the two stacks.

---

## 5. Audio

| Direction | Format | Details |
|---|---|---|
| Mic → dictation | 24 kHz PCM16 **mono**, raw LE bytes | Streamed live in ~8 KiB chunks while talking; `DICT_RATE`/`DICT_CHUNK_BYTES` (cpp ~L80–84) |
| Mic → voice note | 16 kHz PCM16 mono inside a 44-byte WAV (unknown-length header) | Base64'd into the `/chat/stream` JSON (see §4.3.4) |
| Muse → gadget | **Nothing.** | "Muse doesn't speak gadget replies" — TTS is the gadget builder's own responsibility (cpp ~L29–30). The ESP32 decodes MP3 replies from *its own* TTS API via minimp3 and resamples to 16 kHz mono for the speaker (cpp ~L1595–1625). |

For Wear OS: capture with `AudioRecord` (24 kHz for dictation or 16 kHz for
the note path — pick one flow), play replies via `AudioTrack`, synthesize
speech with `android.speech.tts` or a cloud TTS. Text replies arrive over
`/chat/subscribe` regardless; audio is purely a local concern.

---

## 6. BLE pairing & setup

### 6.1 Advertisement (Linux/BlueZ reference — `ble_server.py`)

- Type `peripheral`; **LocalName** = BLE name (`MuseGadgetXXXXXX`); the phone
  also reads the GAP device name (set to the same). (`ble_server.py` ~L80–92,
  ~L246–251)
- **Service UUIDs:** [`7fdd3d1c-38ea-46cf-8b46-314ecf5f240c`]
- **Manufacturer data:** company id `0xFFFF`, value `[0x00]`
  (`PAIRED_FLAG_COMPANY_ID`, ~L58, ~L85–89)
- Not pairable/bonded (`Pairable=False`, ~L249) — security comes from the
  pairing protocol, not BLE bonding.

### 6.2 GATT layout (`ble_server.py` ~L52–54, ~L251–256)

- Service: `7fdd3d1c-38ea-46cf-8b46-314ecf5f240c`
- **RX** `4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01` — `write`,
  `write-without-response` (phone → gadget)
- **TX** `d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c` — `read`, `notify`
  (gadget → phone)

### 6.3 Packet framing (`ble_framing.py` ~L17–80)

Both directions, on both characteristics:
- A write/notification **not starting with `0xFE`** is a complete message.
- Otherwise: `0xFE | chunk_index (1B) | total_chunks (1B) | fragment`.
- Sender: ≤160 bytes per packet (`MAX_PACKET_BYTES`), fragment size derived
  from the negotiated MTU (`mtu-3`, min 20), 0.05 s stagger between chunks
  (`CHUNK_STAGGER_S`).
- Receiver (`ChunkAssembler`): strictly in-order; index 0 or a changed total
  restarts; out-of-order/oversize (>8192 B, `MAX_MESSAGE_BYTES`) discards.

### 6.4 Setup command flow (`ble_setup.py`)

Messages are compact JSON (`{"action":…}`), handled one at a time in arrival
order; all sends under one lock so encrypted record counters stay ordered
(~L17–35).

1. `{"action":"get_device_info"}` (plaintext, always allowed) →
   `{"type":"device_info","node_id":…,"version":…,"device_id":…,"mac":…,
   "model":"hatch_link","pairing_protocol":5,"pairing_auth":"none",
   "pairing_auth_epoch":0,"pairing_policy":"confirm_app",
   "network_ready":<bool>}` (`device_info()` ~L190–200, `pairing.py`
   `device_info()` ~L330–342).
2. `{"action":"pairing_client_hello",…}` (plaintext) → pairing handshake
   (see §6.5). After hello, **all further plaintext is blocked** except the
   listed statuses (`_plaintext_blocked`, ~L215–230).
3. `{"action":"pairing_encrypted", <envelope>}` (plaintext JSON envelope
   wrapping AES-GCM records) → decrypted and dispatched as if written
   directly (`_handle_record` ~L232–240).
4. Sensitive actions require an **encrypted, confirmed** session, else
   `error_encryption_required` / `error_pairing_confirm_required`:
   `provision`, `provision_v2`, `wifi_scan`, `ota`, `device.ota`, `unpair`,
   `set_wifi`, `set_auth` (`SENSITIVE_ACTIONS` ~L33–40).
5. `{"action":"pairing_client_finished"}` (first encrypted record) →
   encrypted status `pairing_confirmed` (which also carries the gadget's
   `sdk_token` — `pairing.py` `encrypt_status` ~L520–530).
6. `{"action":"provision_v2","ssid":…,"password":…,"access_token":…,
   "refresh_token":…,"token_type":"device","username":…,"api_url":…,
   "api_url_v2":…,"noise_host":…}` — all of `ssid`, `password`,
   `access_token`, `refresh_token` required and `token_type` must equal
   `"device"`, else `error_missing_credentials`. The Linux gadget ignores
   the Wi-Fi fields (already online). It verifies the tokens with `fetch_vms`
   and persists `pairing.json`, reporting encrypted statuses
   `wifi_connecting` → `wifi_connected` → `auth_ok` (failures:
   `wifi_failed`, `auth_failed`, `error_operation_in_progress`).
   (`_handle_provision` ~L270–310, `_run_provision` ~L312–345)
7. `{"action":"wifi_scan"}` → encrypted `{"type":"wifi_scan_result",
   "networks":[…]}` (single "already online" entry on Linux).
8. Any crypto failure → plaintext `error_pairing_decrypt` (or the other
   `PLAINTEXT_STATUSES` ~L41–46) and disconnect after 0.3 s.

### 6.5 Pairing crypto — protocol v5 (`pairing.py`)

- Suite string: `"p256-hkdf-sha256-aes-gcm-v1"`; community mode only:
  `pairing_auth="none"`, `pairing_policy="confirm_app"`, `auth_epoch=0`
  (no physical button; the app's consent confirms). Constants ~L38–60.
- **Hello** (mobile → device, plaintext JSON): `{"action":
  "pairing_client_hello","version":5,"pairing_auth":"none",
  "pairing_policy":"confirm_app","mobile_pub":<b64url 65-byte uncompressed
  P-256 point, 0x04…>,"mobile_nonce":<b64url 16 bytes>}`. Anything else →
  `error_pairing_invalid_hello`. (`handle_hello` ~L370–440)
- **Ready** (device → mobile, plaintext JSON): `{"type":"pairing_ready",
  "version":5,"device_id":…,"node_id":…,"mac":…,"model":"hatch_link",
  "firmware_version":…,"pairing_auth":"none","pairing_auth_epoch":0,
  "pairing_policy":"confirm_app","device_pub":<b64url>,"device_nonce":
  <b64url>,"transcript_hash":<b64url>,"session_id":<b64url>}`.
- **Transcript** (canonical, SHA-256 → `transcript_hash`), exact lines
  (`build_transcript` ~L300–345):
  ```
  hatch-link-pairing-v5
  version=5
  initiator_role=mobile
  responder_role=link
  device_id=<device_id>
  node_id=<node_id>
  mac=<mac>
  model=hatch_link
  firmware_version=<ver>
  selected_cipher_suite=p256-hkdf-sha256-aes-gcm-v1
  pairing_auth=none
  pairing_auth_epoch=0
  pairing_policy=confirm_app
  confirm_timeout_seconds=0
  mobile_pub=<b64url>
  device_pub=<b64url>
  mobile_nonce=<b64url>
  device_nonce=<b64url>
  ```
- **Key schedule** (`derive_session_keys` ~L348–375):
  - `salt = SHA256(mobile_nonce ‖ device_nonce ‖ transcript_hash)`
  - `session_secret = HKDF-SHA256(salt, info="hatch-link ble setup v1",
    32).derive(ecdh_secret)` (P-256 ECDH)
  - device RX key = `HKDFExpand(info="mobile->device", 32).derive(session_secret)`
  - device TX key = `HKDFExpand(info="device->mobile", 32).derive(session_secret)`
  - `session_id = SHA256(b"hatch-link session id v1" ‖ transcript_hash ‖
    ecdh_secret)[:16]`
- **Records** (AES-256-GCM): nonce = `direction_byte ‖ 0x00 0x00 0x00 ‖
  counter_be64` where direction = `0` mobile→device, `1` device→mobile
  (`record_nonce` ~L378–380); AAD =
  `"hatch-link ble setup v1|<session_id_b64>|m2d|<counter>"` (or `d2m`)
  (`record_aad` ~L383–386). Envelope JSON:
  `{"type":"pairing_encrypted","session_id":<b64url>,"counter":"<decimal>",
  "ciphertext":<b64url>,"tag":<b64url>}`. Counters must be exactly
  sequential starting at 0 — any skip/replay/wrong session/tag failure
  **destroys the session** (`decrypt` ~L443–480).
- **Confirm:** the first decrypted mobile→device record must be exactly
  `{"action":"pairing_client_finished"}` (`handle_client_finished`
  ~L482–510). Device replies with encrypted status `pairing_confirmed`
  (+ `sdk_token`).
- **Timeouts:** hello→finished 60 s, confirmed→provision 120 s,
  provisioning 120 s (`CLIENT_FINISHED_TIMEOUT_S`,
  `CONFIRMED_TIMEOUT_S`, `PROVISIONING_TIMEOUT_S` ~L62–66).
- Security note from the docstring (~L17–27): community pairing protects
  against **passive** observers only; no MITM protection. "Set it up on a
  network you trust." (`linux/README.md`)

---

## 7. Keepalive, timeouts, errors, reconnection

| Item | Value | Source |
|---|---|---|
| WebSocket ping interval / timeout | 20 s / 20 s | `link_client.py` L56–61 (`PING_INTERVAL_S`) |
| Noise handshake timeout | 20 s | `HANDSHAKE_TIMEOUT_S` L60 |
| Chat request timeout | 60 s | `REQUEST_TIMEOUT_S` L58 |
| Max chat response body | 1 MiB | `MAX_RESPONSE_BYTES` L59 |
| Max control-stream message | 4 MiB | `MAX_INBOUND_MESSAGE` L63 |
| Max concurrent `link.invoke` | 4 | `MAX_CONCURRENT_INVOKES` L62 |
| ESP32: server silence incl. pongs | 60 s → reconnect | `muse_chat_session.cpp` `DEAD_US` |
| ESP32: idle connection close | 10 min | `IDLE_CLOSE_US` |
| ESP32: reconnect backoff | 5 s → 120 s | `AUTO_RETRY_MIN/MAX_US` |
| Linux: reconnect backoff | 2 s → 60 s exp; healthy (>30 s) resets; auth failures floor 15 s | `service.py` `Backoff` ~L58–72, ~L120–130 |
| Device token lifetime | ~4 h; rotated at 3 h (`TOKEN_REFRESH_AGE_S`) | `service.py` L54–56 |

**Outcome taxonomy** (`link_client.py` `Outcome`, ~L66–73):
- `CLOSED` — connection ended; reconnect normally.
- `AUTH_REJECTED` — WS upgrade 401 (bad `vm_auth_token`); re-fetch VMs.
- `FORBIDDEN` — upgrade 403 or `/link-control` refused with 403.
- `UNPAIRED` — `link.unpaired`/`node.unpaired` event, or 401 on token
  refresh → delete `pairing.json`, require re-pairing.
- `STOPPED` — local shutdown.
- A `reset` frame on the control stream → treat as `CLOSED`.

---

## 8. Token usage — everything the code reveals

1. **`mgst_…` SDK token** (from `gadgets.muse.ai/settings/sdk-tokens`):
   validated locally (`mgst_` + 43 base64url chars; `config.py` ~L30–56);
   required to *start* pairing (`cli.py` ~L80–88). Sent **outbound only**:
   (a) inside the encrypted `pairing_confirmed` BLE status so the phone app
   can forward/register it (`pairing.py` `encrypt_status`); (b) in the
   `POST /device_token/refresh` JSON body as `sdk_token` — "The SDK token
   reaches Muse only in refresh bodies until apps forward it at mint, so
   each start with a token attempts one refresh to report it."
   (`service.py` ~L80–84, ~L160–190). It is **never** used as an
   `Authorization` bearer anywhere.
2. **Device `access_token`** (provisioned by the phone app, `token_type:
   "device"`): Bearer for `GET /fetch_vms` and *only* there
   (`muse_api.py` ~L58–64). Deliberately **never** sent on refresh
   ("the server can accept it and answer 200 with replacement tokens that
   every endpoint then rejects, which would overwrite working credentials"
   — `refresh_device_token` docstring ~L137–150).
3. **Device `refresh_token`**: `POST /device_token/refresh` with
   `Authorization: Bearer hatch_refresh:<token>` (strip any existing
   `hatch_refresh:` prefix first — take the substring after the last `:`),
   JSON body `{"device_id": <node_id>, "sdk_token": <optional>}`,
   `Content-Type: application/json` (`muse_api.py` `_post_refresh`
   ~L107–135). Response may be wrapped in `{"payload": …}`; needs
   `access_token` + `refresh_token`. 401 → pairing revoked, re-pair.
4. **`vm_auth_token`** (per-VM, from `fetch_vms`): `Authorization: Bearer`
   on the **WebSocket upgrade only** (`link_client.py` `_open` ~L157–170).
   Never sent inside the Noise channel (handshake payloads are empty).
5. Token terms (from the Gadget SDK Terms, referenced in README; also the
   repo README): hosted access not guaranteed; one token ≤ 50 devices;
   no commercial distribution without permission.

---

## 9. Wear OS reimplementation notes (Linux/ESP32 → Android)

- **BLE:** replace BlueZ D-Bus code (`ble_server.py`) with
  `BluetoothLeAdvertiser` (advertise name + service UUID + manufacturer
  data `0xFFFF:[0x00]`) and `BluetoothGattServer` (the two characteristics
  above). `ble_framing.py` / `ble_setup.py` / `pairing.py` are
  transport-agnostic and port almost line-for-line.
- **Crypto:** P-256 ECDH + HKDF-SHA256 + AES-256-GCM are all in Android
  (`javax.crypto`, `java.security`); X25519 is available via BouncyCastle
  (bundled in Android) or a Noise library. **Match the exact nonce/AAD/
  transcript byte layouts** — one wrong byte fails closed.
- **Noise:** no official Noise lib is vendored; the Python `noise/` package
  is a clean-room implementation of standard XX — port it or use an
  existing Noise-Java implementation with protocol name
  `Noise_XX_25519_AESGCM_SHA256`.
- **Protobuf:** hand-rolled in Python (`_proto.py`); field numbers in §3.3
  are the full schema. Write a small Kotlin codec or a `.proto` for
  protobuf-javalite.
- **WebSocket:** OkHttp with the `Authorization` header and
  `?vm_id=` query; 20 s ping interval.
- **Identity/state:** `identity.json`/`pairing.json` → EncryptedSharedPreferences
  or DataStore; keep the 0600-equivalent protection and atomic writes.
- **Audio:** `AudioRecord` 24 kHz PCM16 mono (dictation) or 16 kHz (note
  path); `AudioTrack` for replies; TTS is **your** choice
  (`android.speech.tts` is the zero-dependency option).
- **Background:** the reference is an always-on daemon. On Wear OS, scope
  v1 to **tap-to-talk**: open the Noise session on demand per interaction
  (or hold it while the app is foreground) — no background service needed.
  The `/link-control` channel and `commands_v2` can be minimal or empty for
  a pure voice remote; `link.register` still expects the params object.
- **ESP32-only pieces with no Python equivalent:** the dictation and
  subscribe flows (§4.3), the WAV/note builders, MP3 decode. The ESP32 C++
  Noise stack is `xplat/noise` (not the Python `noise/` package) — same
  protocol per `linux/AGENTS.md` ("speaks the same pairing and control
  protocols").
- **Versions/strings to send:** `version` (e.g. your app version),
  `User-Agent` (your own), `platform`/`device_family`/`model_id` in
  `link.register` (informational; reference uses `linux`/`homehub`/
  `linux` — use `wear-os`/`watch`/your model), `x-app-id: musegadget`
  (consider keeping the literal value; the server may key behavior on it —
  **unverified**).
- **Do not** invent endpoints: every path used is
  `/fetch_vms`, `/device_token/refresh`, `/v1/noise`, `/link-control`,
  `/chat/stream`, `/chat/subscribe`, `/api/voice/dictation`.
