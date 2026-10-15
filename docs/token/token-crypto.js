// Seals the SDK token for the watch (Lux Watch, Settings → SDK token).
// Must match gadget/.../pairing/TokenTransfer.kt; TokenTransferTest checks a
// vector made with this file (gadget/tools/token_vector.mjs).

export const SERVICE_UUID = "5c34f7da-8494-4989-baa2-035c96f1b5f0";
export const KEY_UUID = "10f79740-f47b-468e-ac9f-f76e601f9118";
export const TOKEN_UUID = "44eb37c2-6c5d-403e-807e-1e37c00e4079";
export const STATUS_UUID = "8a46a765-eca6-45d9-af0b-7f768b571959";

const LABEL = new TextEncoder().encode("lux-watch token v1");
const CHUNK_DATA_BYTES = 18;

/** mgst_ plus 43 canonical base64url characters. */
export const TOKEN_PATTERN = /mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]/;

/** The one token in pasted text, or null. */
export function findToken(text) {
  const all = text.match(new RegExp(`(?<![A-Za-z0-9_-])${TOKEN_PATTERN.source}(?![A-Za-z0-9_-])`, "g")) || [];
  return all.length === 1 ? all[0] : null;
}

/** phone public key (65) | iv (12) | AES-256-GCM(token) + tag, for the watch's 65-byte key. */
export async function sealToken(watchPub, token, subtle = globalThis.crypto.subtle) {
  const watchKey = await subtle.importKey("raw", watchPub, { name: "ECDH", namedCurve: "P-256" }, false, []);
  const phone = await subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
  const phonePub = new Uint8Array(await subtle.exportKey("raw", phone.publicKey));
  const secret = await subtle.deriveBits({ name: "ECDH", public: watchKey }, phone.privateKey, 256);
  const hkdf = await subtle.importKey("raw", secret, "HKDF", false, ["deriveKey"]);
  const aes = await subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt: concat(watchPub, phonePub), info: LABEL },
    hkdf, { name: "AES-GCM", length: 256 }, false, ["encrypt"],
  );
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
  const sealed = new Uint8Array(await subtle.encrypt(
    { name: "AES-GCM", iv, additionalData: LABEL }, aes, new TextEncoder().encode(token),
  ));
  return concat(phonePub, iv, sealed);
}

/** Splits a message into TOKEN writes: index | total | up to 18 bytes. */
export function chunks(message) {
  const total = Math.ceil(message.length / CHUNK_DATA_BYTES);
  const out = [];
  for (let i = 0; i < total; i++) {
    const part = message.subarray(i * CHUNK_DATA_BYTES, (i + 1) * CHUNK_DATA_BYTES);
    out.push(concat(Uint8Array.of(i, total), part));
  }
  return out;
}

function concat(...parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}
