// Writes gadget/src/test/resources/token_transfer.json: a token sealed by the
// web page's own code (docs/token/token-crypto.js) for a known watch key, so
// TokenTransferTest checks the watch can open what browsers send.
//   node gadget/tools/token_vector.mjs
import { writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { sealToken, chunks } from "../../docs/token/token-crypto.js";

const subtle = globalThis.crypto.subtle;
const watch = await subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
const watchPub = new Uint8Array(await subtle.exportKey("raw", watch.publicKey));
const watchPriv = Buffer.from((await subtle.exportKey("jwk", watch.privateKey)).d, "base64url");
// Synthetic token: right format, not a real one.
const token = "mgst_" + "Lux-Watch_test-vector_0123456789abcdefghijk".slice(0, 42) + "w";
const message = await sealToken(watchPub, token);
const hex = (b) => Buffer.from(b).toString("hex");
const vector = {
  note: "Made by gadget/tools/token_vector.mjs with docs/token/token-crypto.js (Node WebCrypto)",
  watch_priv: hex(watchPriv),
  watch_pub: hex(watchPub),
  token,
  message: hex(message),
  chunks: chunks(message).map(hex),
};
const out = fileURLToPath(new URL("../src/test/resources/token_transfer.json", import.meta.url));
writeFileSync(out, JSON.stringify(vector, null, 2) + "\n");
console.log(`wrote ${out}`);
