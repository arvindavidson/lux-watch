package com.luxwatch.gadget.noise

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * Noise_XX_25519_AESGCM_SHA256, ported from
 * linux/src/musegadget/noise/noise_xx.py. Byte-for-byte compatible,
 * including the big-endian transport nonce quirk.
 */
object Noise {
    val PROTOCOL_NAME = "Noise_XX_25519_AESGCM_SHA256".toByteArray(Charsets.US_ASCII)
    const val DH_KEY_LEN = 32
    const val AEAD_TAG_LEN = 16
    const val MAX_SAFE_NONCE = (1L shl 53) - 1

    class NoiseError(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)

    private val LOW_ORDER_POINTS: List<ByteArray> = listOf(
        "0000000000000000000000000000000000000000000000000000000000000000",
        "0100000000000000000000000000000000000000000000000000000000000000",
        "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
        "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    ).map { hexToBytes(it) }

    private val ALL_ZEROS_32 = ByteArray(32)

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

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

    /** HKDF-extract+expand with SHA-256, exactly as the Python SDK. */
    fun hkdf(chainingKey: ByteArray, ikm: ByteArray, numOutputs: Int): List<ByteArray> {
        require(numOutputs in 2..3)
        val tempKey = hmacSha256(chainingKey, ikm)
        val out1 = hmacSha256(tempKey, byteArrayOf(0x01))
        val out2 = hmacSha256(tempKey, out1 + byteArrayOf(0x02))
        return if (numOutputs == 2) listOf(out1, out2)
        else listOf(out1, out2, hmacSha256(tempKey, out2 + byteArrayOf(0x03)))
    }

    /** The SDK's big-endian nonce quirk: 4 zero bytes + BE64 counter. */
    fun buildNonceIv(nonce: Long): ByteArray {
        require(nonce >= 0) { "nonce outside uint64 range" }
        val iv = ByteArray(12)
        for (i in 0 until 8) iv[4 + i] = (nonce ushr (56 - 8 * i)).toByte()
        return iv
    }

    fun x25519Dh(privateKeyBytes: ByteArray, publicKeyBytes: ByteArray): ByteArray {
        require(publicKeyBytes.size == DH_KEY_LEN) { "invalid public key length" }
        for (low in LOW_ORDER_POINTS) {
            if (MessageDigest.isEqual(publicKeyBytes, low))
                throw NoiseError("x25519: rejected low-order public key")
        }
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKeyBytes, 0))
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(publicKeyBytes, 0), shared, 0)
        if (MessageDigest.isEqual(shared, ALL_ZEROS_32))
            throw NoiseError("x25519: DH produced all-zeros output")
        return shared
    }

    fun generateKeyPair(random: SecureRandom = SecureRandom()): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(32).also { random.nextBytes(it) }
        val pubParams = X25519PrivateKeyParameters(priv, 0).generatePublicKey()
        return priv to pubParams.encoded
    }

    class CipherState {
        private var key: ByteArray? = null
        private var nonce = 0L
        private var poisoned = false

        fun initializeKey(k: ByteArray) {
            require(k.size == 32) { "AES-GCM key must be 32 bytes" }
            key = k.copyOf(); nonce = 0; poisoned = false
        }

        val hasKey: Boolean get() = key != null

        private fun nextNonce(): Long {
            if (poisoned) throw NoiseError("CipherState: poisoned after prior failure")
            if (nonce >= MAX_SAFE_NONCE) {
                poisoned = true
                throw NoiseError("CipherState: nonce exhausted")
            }
            return nonce++
        }

        fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
            val k = key ?: return plaintext.copyOf()
            val n = nextNonce()
            return try {
                aesGcm(k, buildNonceIv(n), ad, plaintext, true)
            } catch (e: Exception) {
                poisoned = true
                throw NoiseError("encrypt failed", e)
            }
        }

        fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
            val k = key ?: return ciphertext.copyOf()
            val n = nextNonce()
            return try {
                aesGcm(k, buildNonceIv(n), ad, ciphertext, false)
            } catch (e: Exception) {
                poisoned = true
                throw NoiseError("CipherState: decrypt failed", e)
            }
        }

        private fun aesGcm(
            key: ByteArray, iv: ByteArray, ad: ByteArray, input: ByteArray, encrypt: Boolean
        ): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(
                if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"), spec
            )
            cipher.updateAAD(ad)
            return cipher.doFinal(input)
        }
    }

    private class SymmetricState {
        private var ck = ByteArray(32)
        private var h = ByteArray(32)
        private var cipher = CipherState()

        fun initialize() {
            val padded = ByteArray(32)
            PROTOCOL_NAME.copyInto(padded)
            h = padded; ck = padded.copyOf()
            mixHash(ByteArray(0))
        }

        fun mixHash(data: ByteArray) {
            h = sha256(h, data)
        }

        fun mixKey(ikm: ByteArray) {
            val (newCk, tempK) = hkdf(ck, ikm, 2)
            ck = newCk
            cipher = CipherState().also { it.initializeKey(tempK) }
        }

        fun encryptAndHash(plaintext: ByteArray): ByteArray {
            val ct = cipher.encryptWithAd(h, plaintext)
            mixHash(ct)
            return ct
        }

        fun decryptAndHash(ciphertext: ByteArray): ByteArray {
            val pt = cipher.decryptWithAd(h, ciphertext)
            mixHash(ciphertext)
            return pt
        }

        fun split(): Pair<CipherState, CipherState> {
            val (k1, k2) = hkdf(ck, ByteArray(0), 2)
            ck = ByteArray(32); h = ByteArray(32)
            return CipherState().also { it.initializeKey(k1) } to
                    CipherState().also { it.initializeKey(k2) }
        }
    }

    /**
     * Noise XX initiator (the gadget side). For tests, pass fixed
     * ephemeral/static private keys to reproduce vectors deterministically.
     */
    class Initiator(
        private val fixedEphemeralPriv: ByteArray? = null,
        private val fixedStaticPriv: ByteArray? = null,
    ) {
        private val ss = SymmetricState()
        private var ePriv: ByteArray? = null
        private var ePub: ByteArray? = null
        private var re: ByteArray? = null
        private var state = 0 // 0 created, 1 init, 2 m1sent, 3 m2read, 4 done

        fun initialize() {
            check(state == 0); ss.initialize(); state = 1
        }

        fun writeMessage1(): ByteArray {
            check(state == 1)
            val (priv, pub) = fixedEphemeralPriv?.let {
                it to X25519PrivateKeyParameters(it, 0).generatePublicKey().encoded
            } ?: generateKeyPair()
            ePriv = priv; ePub = pub
            ss.mixHash(pub)
            ss.encryptAndHash(ByteArray(0)) // cipher unkeyed: hashes only
            state = 2
            return pub
        }

        fun readMessage2(msg: ByteArray) {
            check(state == 2)
            require(msg.size >= DH_KEY_LEN + (DH_KEY_LEN + AEAD_TAG_LEN) + AEAD_TAG_LEN) {
                "message 2 too short"
            }
            var off = 0
            val peerE = msg.copyOfRange(off, off + DH_KEY_LEN); off += DH_KEY_LEN
            ss.mixHash(peerE)
            ss.mixKey(x25519Dh(ePriv!!, peerE))
            val rs = ss.decryptAndHash(msg.copyOfRange(off, off + DH_KEY_LEN + AEAD_TAG_LEN))
            off += DH_KEY_LEN + AEAD_TAG_LEN
            ss.mixKey(x25519Dh(ePriv!!, rs))
            ss.decryptAndHash(msg.copyOfRange(off, msg.size)) // payload (empty)
            re = peerE
            state = 3
        }

        fun writeMessage3(): ByteArray {
            check(state == 3)
            val (priv, pub) = fixedStaticPriv?.let {
                it to X25519PrivateKeyParameters(it, 0).generatePublicKey().encoded
            } ?: generateKeyPair()
            val encS = ss.encryptAndHash(pub)
            ss.mixKey(x25519Dh(priv, re!!))
            val encPayload = ss.encryptAndHash(ByteArray(0))
            state = 4
            return encS + encPayload
        }

        /** Returns (initiator->responder, responder->initiator) ciphers. */
        fun split(): Pair<CipherState, CipherState> {
            check(state == 4)
            state = 5
            return ss.split()
        }
    }
}
