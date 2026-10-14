package com.luxwatch.gadget

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Encrypted storage for device identity and pairing credentials.
 * Mirrors identity.json / pairing.json from the Python SDK (0600, atomic).
 */
class GadgetStorage(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "lux_gadget_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // ---- identity (stable across upgrades and unpairing) ----

    /** MAC-shaped identity "xx:xx:xx:xx:xx:xx", generated once. */
    val mac: String
        get() {
            var mac = prefs.getString("identity_mac", null)
            if (mac == null || !MAC_RE.matches(mac)) {
                mac = generateMac()
                prefs.edit().putString("identity_mac", mac).apply()
            }
            return mac
        }

    /** Last 6 hex digits of the MAC, no colons. */
    val suffix: String get() = mac.replace(":", "").takeLast(6)

    /** "homelink-" + suffix */
    val nodeId: String get() = "homelink-$suffix"

    /** "hatch-link:" + full MAC */
    val deviceId: String get() = "hatch-link:$mac"

    /** "MuseGadget" + UPPERCASE suffix */
    val bleName: String get() = "MuseGadget" + suffix.uppercase()

    private fun generateMac(): String {
        val b = ByteArray(6).also { SecureRandom().nextBytes(it) }
        b[0] = ((b[0].toInt() and 0xFC) or 0x02).toByte()
        return b.joinToString(":") { "%02x".format(it) }
    }

    // ---- pairing ----

    data class Pairing(
        val accessToken: String,
        val refreshToken: String,
        val tokenType: String,
        val username: String,
        val apiUrl: String,
        val apiUrlV2: String?,
        val noiseHost: String,
        val savedAt: Long,
    )

    val pairing: Pairing?
        get() {
            val access = prefs.getString("pairing_access_token", null) ?: return null
            val refresh = prefs.getString("pairing_refresh_token", null) ?: return null
            return Pairing(
                accessToken = access,
                refreshToken = refresh,
                tokenType = prefs.getString("pairing_token_type", "device") ?: "device",
                username = prefs.getString("pairing_username", "") ?: "",
                apiUrl = prefs.getString("pairing_api_url", "https://api.muse.ai") ?: "https://api.muse.ai",
                apiUrlV2 = prefs.getString("pairing_api_url_v2", null)?.takeIf { it.isNotBlank() },
                // The app may send "" — fall back like the SDK (`or DEFAULT_NOISE_HOST`).
                noiseHost = prefs.getString("pairing_noise_host", null)?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_NOISE_HOST,
                savedAt = prefs.getLong("pairing_saved_at", 0),
            )
        }

    fun savePairing(
        accessToken: String,
        refreshToken: String,
        tokenType: String,
        username: String,
        apiUrl: String,
        apiUrlV2: String?,
        noiseHost: String,
    ) {
        prefs.edit()
            .putString("pairing_access_token", accessToken)
            .putString("pairing_refresh_token", refreshToken)
            .putString("pairing_token_type", tokenType)
            .putString("pairing_username", username)
            .putString("pairing_api_url", apiUrl)
            .putString("pairing_api_url_v2", apiUrlV2)
            .putString("pairing_noise_host", noiseHost)
            .putLong("pairing_saved_at", System.currentTimeMillis())
            .commit() // on disk before anything else: losing these means re-pairing
    }

    /**
     * Muse rotates the refresh token on every refresh and rejects the old one,
     * so the new pair goes to disk synchronously: if the app died before an
     * async write, the pairing would be lost.
     */
    fun updateTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString("pairing_access_token", accessToken)
            .putString("pairing_refresh_token", refreshToken)
            .putLong("pairing_saved_at", System.currentTimeMillis())
            .commit()
    }

    fun clearPairing() {
        prefs.edit()
            .remove("pairing_access_token")
            .remove("pairing_refresh_token")
            .remove("pairing_token_type")
            .remove("pairing_username")
            .remove("pairing_api_url")
            .remove("pairing_api_url_v2")
            .remove("pairing_noise_host")
            .remove("pairing_saved_at")
            .apply()
    }

    // ---- SDK token (mgst_…, from gadgets.muse.ai) ----

    /** Survives unpairing, like the SDK's sdk_token file. */
    var sdkToken: String?
        get() = prefs.getString("sdk_token", null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("sdk_token") else putString("sdk_token", value)
                remove("sdk_token_reported") // a new token needs reporting
            }.apply()
        }

    /** Muse has seen the current SDK token (one successful refresh carried it). */
    var sdkTokenReported: Boolean
        get() = prefs.getBoolean("sdk_token_reported", false)
        set(value) {
            prefs.edit().putBoolean("sdk_token_reported", value).apply()
        }

    companion object {
        const val DEFAULT_NOISE_HOST = "hatch.metaaivm.com"
        private val MAC_RE = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")
    }
}
