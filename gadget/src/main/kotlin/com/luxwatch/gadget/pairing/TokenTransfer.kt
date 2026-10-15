package com.luxwatch.gadget.pairing

import com.luxwatch.gadget.noise.SdkToken
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sending the SDK token from a phone's browser to the watch over Bluetooth
 * (Web Bluetooth; the page is docs/token/ in this repo).
 *
 * The watch offers a GATT service while Settings → SDK token is open:
 * - KEY (read): the watch's P-256 public key, new each time the screen opens.
 * - TOKEN (write): `index (1B) | total (1B) | up to 18 bytes`, so every write
 *   fits the default 23-byte MTU. The joined message is
 *   `phone public key (65) | iv (12) | AES-256-GCM(token) + tag (16)`.
 * - STATUS (read): ASCII, see [Status].
 *
 * The AES key is HKDF-SHA256(salt = watch key | phone key, ECDH secret,
 * info = [LABEL]), and [LABEL] is also the AAD. Like the Muse app's own BLE
 * pairing this keeps the token from passive listeners; the watch shows the
 * token's last characters and asks before saving it.
 * Must match docs/token/token-crypto.js (checked by TokenTransferTest).
 */
object TokenTransfer {
    const val SERVICE_UUID = "5c34f7da-8494-4989-baa2-035c96f1b5f0"
    const val KEY_UUID = "10f79740-f47b-468e-ac9f-f76e601f9118"
    const val TOKEN_UUID = "44eb37c2-6c5d-403e-807e-1e37c00e4079"
    const val STATUS_UUID = "8a46a765-eca6-45d9-af0b-7f768b571959"

    const val LABEL = "lux-watch token v1"
    const val CHUNK_DATA_BYTES = 18
    private const val PUB_BYTES = 65
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val MAX_MESSAGE_BYTES = 512

    /** What the page reads from STATUS. */
    enum class Status(val wire: String) {
        /** Waiting for a token. */
        READY("ready"),
        /** Got one; waiting for Save on the watch. */
        CONFIRM("confirm"),
        SAVED("saved"),
        CANCELLED("cancelled"),
        /** Couldn't decrypt it, or it isn't an SDK token. */
        INVALID("invalid"),
    }

    class TransferError(message: String) : Exception(message)

    /** One screen's worth of receiving: a fresh key pair. */
    class Receiver(random: SecureRandom = SecureRandom()) {
        private val keys = PairingCrypto.generateP256KeyPair(random)
        val publicKey: ByteArray get() = keys.second.copyOf()

        /** Decrypts a joined TOKEN message; returns the token or throws [TransferError]. */
        fun open(message: ByteArray): String = TokenTransfer.open(keys.first, keys.second, message)
    }

    fun open(watchPriv: ByteArray, watchPub: ByteArray, message: ByteArray): String {
        if (message.size <= PUB_BYTES + IV_BYTES + TAG_BYTES) throw TransferError("too short")
        val phonePub = message.copyOfRange(0, PUB_BYTES)
        if (phonePub[0] != 0x04.toByte()) throw TransferError("bad phone key")
        val iv = message.copyOfRange(PUB_BYTES, PUB_BYTES + IV_BYTES)
        val sealed = message.copyOfRange(PUB_BYTES + IV_BYTES, message.size)
        val plain = try {
            val secret = PairingCrypto.ecdhP256(watchPriv, phonePub)
            val key = PairingCrypto.hkdf(watchPub + phonePub, secret, LABEL.toByteArray(), 32)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, iv))
            c.updateAAD(LABEL.toByteArray())
            c.doFinal(sealed)
        } catch (e: Exception) {
            throw TransferError("couldn't decrypt")
        }
        val token = plain.toString(Charsets.UTF_8)
        if (!SdkToken.isValid(token)) throw TransferError("not an SDK token")
        return token
    }

    /** Joins TOKEN writes; a write with index 0 starts over. */
    class Assembler {
        private val buf = ByteArrayOutputStream()
        private var next = 0
        private var total = -1

        /** Returns the whole message once the last chunk is in, else null. */
        fun feed(chunk: ByteArray): ByteArray? {
            if (chunk.size < 3) return null
            val index = chunk[0].toInt() and 0xFF
            val count = chunk[1].toInt() and 0xFF
            if (index == 0) {
                buf.reset(); next = 0; total = count
            }
            if (count == 0 || count != total || index != next ||
                buf.size() + chunk.size - 2 > MAX_MESSAGE_BYTES
            ) {
                reset()
                return null
            }
            buf.write(chunk, 2, chunk.size - 2)
            next++
            if (next < total) return null
            return buf.toByteArray().also { reset() }
        }

        fun reset() {
            buf.reset(); next = 0; total = -1
        }
    }
}
