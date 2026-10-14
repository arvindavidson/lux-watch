package com.luxwatch.gadget.pairing

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Device side of Muse BLE pairing protocol v5 (community mode): transcript,
 * P-256 ECDH, HKDF key schedule and the AES-256-GCM record layer.
 * Ports linux/src/musegadget/pairing.py; pure JVM so it is tested against the
 * SDK's official vectors (link_pairing_v5.json). BlePairingServer owns the
 * BLE transport and session state machine around it.
 */
object PairingCrypto {
    const val VERSION = 5
    const val MODEL = "hatch_link"
    const val CIPHER_SUITE = "p256-hkdf-sha256-aes-gcm-v1"
    const val RECORD_LABEL = "hatch-link ble setup v1"
    const val NONCE_BYTES = 16
    const val TAG_BYTES = 16
    const val DIR_TO_DEVICE = 0
    const val DIR_FROM_DEVICE = 1
    private val SESSION_ID_LABEL = "hatch-link session id v1".toByteArray()
    private const val SESSION_ID_BYTES = 16
    private const val KEY_BYTES = 32

    /** The device fields that go into the pairing transcript. */
    class Identity(
        val deviceId: String,
        val nodeId: String,
        val mac: String,
        val firmwareVersion: String,
        val model: String = MODEL,
    )

    /** Keys derived for one pairing session (pairing.py `derive_session_keys`). */
    class SessionKeys(
        val sessionSecret: ByteArray,
        /** Mobile → device: the device decrypts with this. */
        val mobileTxKey: ByteArray,
        /** Device → mobile: the device encrypts with this. */
        val mobileRxKey: ByteArray,
        val sessionId: ByteArray,
    ) {
        val sessionIdB64: String get() = b64urlEncode(sessionId)
    }

    /** What the device computes on pairing_client_hello. */
    class DeviceSession(
        val transcript: String,
        val transcriptHash: ByteArray,
        val keys: SessionKeys,
    )

    // ---- handshake ----

    /** Newline-joined pairing transcript (pairing.py `build_transcript`, pairing_auth=none). */
    fun transcript(
        identity: Identity,
        pairingPolicy: String,
        confirmTimeoutSeconds: Int,
        mobilePub: ByteArray,
        devicePub: ByteArray,
        mobileNonce: ByteArray,
        deviceNonce: ByteArray,
    ): String = listOf(
        "hatch-link-pairing-v$VERSION",
        "version=$VERSION",
        "initiator_role=mobile",
        "responder_role=link",
        "device_id=${identity.deviceId}",
        "node_id=${identity.nodeId}",
        "mac=${identity.mac}",
        "model=${identity.model}",
        "firmware_version=${identity.firmwareVersion}",
        "selected_cipher_suite=$CIPHER_SUITE",
        "pairing_auth=none",
        "pairing_auth_epoch=0",
        "pairing_policy=$pairingPolicy",
        "confirm_timeout_seconds=$confirmTimeoutSeconds",
        "mobile_pub=${b64urlEncode(mobilePub)}",
        "device_pub=${b64urlEncode(devicePub)}",
        "mobile_nonce=${b64urlEncode(mobileNonce)}",
        "device_nonce=${b64urlEncode(deviceNonce)}",
    ).joinToString("\n")

    fun deriveSessionKeys(
        ecdhSecret: ByteArray,
        mobileNonce: ByteArray,
        deviceNonce: ByteArray,
        transcriptHash: ByteArray,
    ): SessionKeys {
        val salt = sha256(mobileNonce, deviceNonce, transcriptHash)
        val sessionSecret = hkdf(salt, ecdhSecret, RECORD_LABEL.toByteArray(), KEY_BYTES)
        return SessionKeys(
            sessionSecret = sessionSecret,
            mobileTxKey = hkdfExpand(sessionSecret, "mobile->device".toByteArray(), KEY_BYTES),
            mobileRxKey = hkdfExpand(sessionSecret, "device->mobile".toByteArray(), KEY_BYTES),
            sessionId = sha256(SESSION_ID_LABEL, transcriptHash, ecdhSecret).copyOf(SESSION_ID_BYTES),
        )
    }

    /** Everything the device derives from a hello, given its key pair and nonce. */
    fun deviceSession(
        identity: Identity,
        pairingPolicy: String,
        confirmTimeoutSeconds: Int,
        mobilePub: ByteArray,
        mobileNonce: ByteArray,
        devicePriv: ByteArray,
        devicePub: ByteArray,
        deviceNonce: ByteArray,
    ): DeviceSession {
        val transcript = transcript(
            identity, pairingPolicy, confirmTimeoutSeconds,
            mobilePub, devicePub, mobileNonce, deviceNonce,
        )
        val transcriptHash = sha256(transcript.toByteArray())
        val keys = deriveSessionKeys(ecdhP256(devicePriv, mobilePub), mobileNonce, deviceNonce, transcriptHash)
        return DeviceSession(transcript, transcriptHash, keys)
    }

    // ---- record layer ----

    /** `direction (1B) | 0 0 0 | counter (u64 BE)` — pairing.py `record_nonce`. */
    fun recordNonce(direction: Int, counter: Long): ByteArray {
        val n = ByteArray(12)
        n[0] = direction.toByte()
        for (i in 0 until 8) n[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        return n
    }

    /** `label|session_id|m2d or d2m|counter` — pairing.py `record_aad`. */
    fun recordAad(sessionIdB64: String, direction: Int, counter: Long): ByteArray {
        val arrow = if (direction == DIR_TO_DEVICE) "m2d" else "d2m"
        return "$RECORD_LABEL|$sessionIdB64|$arrow|$counter".toByteArray()
    }

    class Sealed(val ciphertext: ByteArray, val tag: ByteArray)

    fun seal(key: ByteArray, sessionIdB64: String, direction: Int, counter: Long, plaintext: ByteArray): Sealed {
        val sealed = aesGcm(Cipher.ENCRYPT_MODE, key, sessionIdB64, direction, counter, plaintext)
        return Sealed(
            ciphertext = sealed.copyOf(sealed.size - TAG_BYTES),
            tag = sealed.copyOfRange(sealed.size - TAG_BYTES, sealed.size),
        )
    }

    /** Decrypts one record; throws (AEADBadTagException etc.) if it doesn't authenticate. */
    fun open(
        key: ByteArray, sessionIdB64: String, direction: Int, counter: Long,
        ciphertext: ByteArray, tag: ByteArray,
    ): ByteArray {
        require(tag.size == TAG_BYTES) { "bad tag length" }
        return aesGcm(Cipher.DECRYPT_MODE, key, sessionIdB64, direction, counter, ciphertext + tag)
    }

    private fun aesGcm(
        mode: Int, key: ByteArray, sessionIdB64: String, direction: Int, counter: Long, input: ByteArray,
    ): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, recordNonce(direction, counter)))
        c.updateAAD(recordAad(sessionIdB64, direction, counter))
        return c.doFinal(input)
    }

    // ---- P-256 ----

    private val p256: ECDomainParameters by lazy {
        val x9 = SECNamedCurves.getByName("secp256r1")
        ECDomainParameters(x9.curve, x9.g, x9.n, x9.h)
    }

    /** Uncompressed (0x04 | X | Y, 65 bytes) public key for a 32-byte private scalar. */
    fun p256PublicKey(priv: ByteArray): ByteArray =
        p256.g.multiply(BigInteger(1, priv)).normalize().getEncoded(false)

    /** Returns (32-byte private scalar, 65-byte uncompressed public key). */
    fun generateP256KeyPair(random: SecureRandom): Pair<ByteArray, ByteArray> {
        var d: BigInteger
        do {
            d = BigInteger(256, random)
        } while (d.signum() == 0 || d >= p256.n)
        val priv = fixed32(d)
        return priv to p256PublicKey(priv)
    }

    /** P-256 ECDH; returns the 32-byte X coordinate of the shared point. */
    fun ecdhP256(priv: ByteArray, peerPubUncompressed: ByteArray): ByteArray {
        val agreement = ECDHBasicAgreement()
        agreement.init(ECPrivateKeyParameters(BigInteger(1, priv), p256))
        val peer = ECPublicKeyParameters(p256.curve.decodePoint(peerPubUncompressed), p256)
        return fixed32(agreement.calculateAgreement(peer))
    }

    /** Big-endian, left-padded to 32 bytes (drops BigInteger's sign byte). */
    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        return ByteArray(32).also { src.copyInto(it, 32 - src.size) }
    }

    // ---- primitives ----

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 extract+expand (matches cryptography's HKDF). */
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray =
        hkdfExpand(hmacSha256(salt, ikm), info, length)

    /** HKDF-SHA256 expand-only (matches cryptography's HKDFExpand). */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun b64urlEncode(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** Accepts padded or unpadded base64url. */
    fun b64urlDecode(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
}
