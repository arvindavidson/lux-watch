package com.luxwatch.gadget.noise

import com.luxwatch.gadget.pairing.PairingCrypto
import org.json.JSONObject

/**
 * Checks PairingCrypto — the code BlePairingServer runs on the watch — against
 * the official BLE pairing v5 vectors shipped in Meta's muse-gadget-sdk
 * (linux/tests/vectors/link_pairing_v5.json).
 */
object BleVectorCheck {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = Noise.hexToBytes(s)
    private fun b64u(b: ByteArray) = PairingCrypto.b64urlEncode(b)
    private fun unb64u(s: String) = PairingCrypto.b64urlDecode(s)

    private fun expect(name: String, actual: String, expected: String) {
        check(actual == expected) { "$name mismatch:\n got $actual\n exp $expected" }
    }

    private fun checkVector(v: JSONObject) {
        val name = v.getString("name")
        val mobilePub = unb64u(v.getString("mobile_pub"))
        val mobileNonce = unb64u(v.getString("mobile_nonce"))
        val devicePriv = unhex(v.getString("device_private_scalar_hex"))
        val devicePub = unb64u(v.getString("device_pub"))
        val deviceNonce = unb64u(v.getString("device_nonce"))

        // P-256: public keys and ECDH from the published scalars, both directions.
        expect("$name.device_pub", b64u(PairingCrypto.p256PublicKey(devicePriv)), v.getString("device_pub"))
        expect("$name.mobile_pub", b64u(PairingCrypto.p256PublicKey(unhex(v.getString("mobile_private_scalar_hex")))), v.getString("mobile_pub"))
        val ecdh = PairingCrypto.ecdhP256(devicePriv, mobilePub)
        expect("$name.ecdh(device)", hex(ecdh), v.getString("ecdh_secret_hex"))
        expect("$name.ecdh(mobile)", hex(PairingCrypto.ecdhP256(unhex(v.getString("mobile_private_scalar_hex")), devicePub)), v.getString("ecdh_secret_hex"))

        val keys: PairingCrypto.SessionKeys
        if (v.getString("pairing_auth") == "none") {
            // Community mode — what the watch implements: the full device-side derivation.
            check(v.getString("model") == PairingCrypto.MODEL) { "$name: unexpected model" }
            check(v.getString("selected_cipher_suite") == PairingCrypto.CIPHER_SUITE) { "$name: unexpected suite" }
            val identity = PairingCrypto.Identity(
                deviceId = v.getString("device_id"),
                nodeId = v.getString("node_id"),
                mac = v.getString("mac"),
                firmwareVersion = v.getString("firmware_version"),
            )
            val session = PairingCrypto.deviceSession(
                identity, v.getString("pairing_policy"), v.getInt("confirm_timeout_seconds"),
                mobilePub, mobileNonce, devicePriv, devicePub, deviceNonce,
            )
            expect("$name.transcript", session.transcript, v.getString("transcript"))
            expect("$name.transcript_hash", b64u(session.transcriptHash), v.getString("transcript_hash"))
            keys = session.keys
        } else {
            // Fleet-auth transcript isn't built by the watch; still check the key schedule.
            val thash = PairingCrypto.sha256(v.getString("transcript").toByteArray())
            expect("$name.transcript_hash", b64u(thash), v.getString("transcript_hash"))
            keys = PairingCrypto.deriveSessionKeys(ecdh, mobileNonce, deviceNonce, thash)
        }
        expect("$name.session_secret", hex(keys.sessionSecret), v.getString("session_secret_hex"))
        expect("$name.mobile_tx_key", hex(keys.mobileTxKey), v.getString("mobile_tx_key_hex"))
        expect("$name.mobile_rx_key", hex(keys.mobileRxKey), v.getString("mobile_rx_key_hex"))
        expect("$name.session_id", keys.sessionIdB64, v.getString("session_id"))

        // Record layer: the device opens the phone's first record (client_finished, counter 0)…
        val sid = keys.sessionIdB64
        expect(
            "$name.client_finished_aad",
            String(PairingCrypto.recordAad(sid, PairingCrypto.DIR_TO_DEVICE, 0)),
            v.getString("client_finished_aad"),
        )
        val ciphertext = unb64u(v.getString("client_finished_ciphertext"))
        val tag = unb64u(v.getString("client_finished_tag"))
        val plaintext = PairingCrypto.open(keys.mobileTxKey, sid, PairingCrypto.DIR_TO_DEVICE, 0, ciphertext, tag)
        expect("$name.client_finished_plaintext", String(plaintext), v.getString("client_finished_plaintext"))
        // …and sealing that plaintext the phone's way reproduces it exactly.
        val sealed = PairingCrypto.seal(keys.mobileTxKey, sid, PairingCrypto.DIR_TO_DEVICE, 0, plaintext)
        expect("$name.client_finished_ciphertext", b64u(sealed.ciphertext), v.getString("client_finished_ciphertext"))
        expect("$name.client_finished_tag", b64u(sealed.tag), v.getString("client_finished_tag"))
        // A tampered tag must not authenticate.
        val badTag = tag.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        check(runCatching {
            PairingCrypto.open(keys.mobileTxKey, sid, PairingCrypto.DIR_TO_DEVICE, 0, ciphertext, badTag)
        }.isFailure) { "$name: tampered record decrypted" }

        println("PASS ble.$name (p256, ecdh, transcript, keys, session id, client_finished record)")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val vectors = JSONObject(Vectors.require(Vectors.BLE)).getJSONArray("vectors")
        check(vectors.length() > 0) { "no BLE vectors" }
        for (i in 0 until vectors.length()) checkVector(vectors.getJSONObject(i))

        // Device → mobile round trip at a non-zero counter, plus key-pair sanity.
        val random = java.security.SecureRandom()
        val (priv, pub) = PairingCrypto.generateP256KeyPair(random)
        check(priv.size == 32 && pub.size == 65 && pub[0] == 0x04.toByte()) { "bad generated key pair" }
        check(PairingCrypto.p256PublicKey(priv).contentEquals(pub)) { "generated public key mismatch" }
        val key = ByteArray(32) { it.toByte() }
        val sealed = PairingCrypto.seal(key, "sid", PairingCrypto.DIR_FROM_DEVICE, 7, "hi".toByteArray())
        check(String(PairingCrypto.open(key, "sid", PairingCrypto.DIR_FROM_DEVICE, 7, sealed.ciphertext, sealed.tag)) == "hi")
        check(runCatching { PairingCrypto.open(key, "sid", PairingCrypto.DIR_FROM_DEVICE, 8, sealed.ciphertext, sealed.tag) }.isFailure) {
            "record opened under the wrong counter"
        }
        println("ALL BLE VECTORS MATCH (${vectors.length()})")
    }
}
