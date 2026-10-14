package com.luxwatch.gadget

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.luxwatch.gadget.pairing.PairingCrypto
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

/**
 * BLE peripheral implementing the Muse gadget pairing protocol v5.
 *
 * Spec: ~/workspace/lux-watch/docs/PROTOCOL.md §6 (from
 * linux/src/musegadget/{pairing,ble_setup,ble_framing,ble_server}.py).
 *
 * The watch advertises as MuseGadgetXXXXXX; the Muse phone app connects,
 * runs the P-256 ECDH pairing handshake, then provisions device tokens
 * via provision_v2, delivered through [PairingCallbacks.onProvisioned].
 */
class BlePairingServer(
    private val context: Context,
    private val bleName: String,
    private val nodeId: String,
    private val deviceId: String,
    private val mac: String,
    private val firmwareVersion: String = "0.1.0",
    private val sdkToken: String? = null,
    private val callbacks: PairingCallbacks,
) {
    interface PairingCallbacks {
        fun onProvisioned(
            accessToken: String,
            refreshToken: String,
            tokenType: String,
            username: String,
            apiUrl: String,
            apiUrlV2: String?,
            noiseHost: String,
        )
        fun onUnpair()

        /** Pairing can't proceed (e.g. advertising failed). Called on a binder thread. */
        fun onError(message: String) {}
    }

    companion object {
        private const val TAG = "BlePairingServer"

        val SERVICE_UUID: UUID = UUID.fromString("7fdd3d1c-38ea-46cf-8b46-314ecf5f240c")
        val RX_UUID: UUID = UUID.fromString("4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01")
        val TX_UUID: UUID = UUID.fromString("d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MAX_PACKET_BYTES = 160
        private const val MAX_MESSAGE_BYTES = 8192
        private const val CHUNK_STAGGER_MS = 50L

        // Pairing protocol v5 (crypto + transcript constants live in PairingCrypto).
        private const val PAIRING_POLICY = "confirm_app"
        private const val CONFIRM_TIMEOUT_SECONDS = 0
        private const val CURRENT_CONNECTION_LABEL = "Use current connection"

        private const val CLIENT_FINISHED_TIMEOUT_S = 60L
        private const val CONFIRMED_TIMEOUT_S = 120L

        private val random = SecureRandom()

        /**
         * Runtime permissions [start] needs. Android 12+ only; on API 30 the
         * legacy BLUETOOTH/BLUETOOTH_ADMIN permissions are install-time grants.
         */
        fun requiredPermissions(): List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                emptyList()
            }
    }

    private val identity = PairingCrypto.Identity(
        deviceId = deviceId,
        nodeId = nodeId,
        mac = mac,
        firmwareVersion = firmwareVersion,
    )

    // ---- pairing session (protocol v5 state machine) ----

    private enum class PairState { IDLE, WAIT_CLIENT_FINISHED, READY }

    private inner class PairingSession {
        private var state = PairState.IDLE
        private var rxKey: ByteArray? = null
        private var txKey: ByteArray? = null
        private var sessionIdB64 = ""
        private var rxCounter = 0L
        private var txCounter = 0L
        private var deadlineMs = 0L
        private val lock = Any()

        fun handleHello(msg: JSONObject): JSONObject {
            val version = msg.opt("version")
            if ((version !is Int && version !is Long) || (version as Number).toInt() != PairingCrypto.VERSION ||
                msg.optString("pairing_auth") != "none" ||
                msg.optString("pairing_policy") != PAIRING_POLICY
            ) {
                throw PairingException("error_pairing_invalid_hello")
            }
            // New hello resets any prior session (matches Python's _reset_locked).
            reset()
            clientFinishedSeen = false
            val mobilePub: ByteArray
            val mobileNonce: ByteArray
            try {
                mobilePub = PairingCrypto.b64urlDecode(msg.optString("mobile_pub"))
                mobileNonce = PairingCrypto.b64urlDecode(msg.optString("mobile_nonce"))
            } catch (_: IllegalArgumentException) {
                throw PairingException("error_pairing_invalid_hello")
            }
            if (mobilePub.size != 65 || mobilePub[0] != 0x04.toByte() ||
                mobileNonce.size != PairingCrypto.NONCE_BYTES
            ) {
                throw PairingException("error_pairing_invalid_hello")
            }

            val (devicePriv, devicePub) = PairingCrypto.generateP256KeyPair(random)
            val deviceNonce = ByteArray(PairingCrypto.NONCE_BYTES).also { random.nextBytes(it) }
            val derived = try {
                PairingCrypto.deviceSession(
                    identity, PAIRING_POLICY, CONFIRM_TIMEOUT_SECONDS,
                    mobilePub, mobileNonce, devicePriv, devicePub, deviceNonce,
                )
            } catch (_: IllegalArgumentException) {
                // Not a valid P-256 point.
                throw PairingException("error_pairing_invalid_hello")
            }
            val keys = derived.keys

            synchronized(lock) {
                rxKey = keys.mobileTxKey; txKey = keys.mobileRxKey
                sessionIdB64 = keys.sessionIdB64
                rxCounter = 0; txCounter = 0
                state = PairState.WAIT_CLIENT_FINISHED
                deadlineMs = System.currentTimeMillis() + CLIENT_FINISHED_TIMEOUT_S * 1000
            }
            return JSONObject().apply {
                put("type", "pairing_ready")
                put("version", PairingCrypto.VERSION)
                put("device_id", deviceId)
                put("node_id", nodeId)
                put("mac", mac)
                put("model", PairingCrypto.MODEL)
                put("firmware_version", firmwareVersion)
                put("pairing_auth", "none")
                put("pairing_auth_epoch", 0)
                put("pairing_policy", PAIRING_POLICY)
                put("device_pub", PairingCrypto.b64urlEncode(devicePub))
                put("device_nonce", PairingCrypto.b64urlEncode(deviceNonce))
                put("transcript_hash", PairingCrypto.b64urlEncode(derived.transcriptHash))
                put("session_id", sessionIdB64)
            }
        }

        /** Decrypts one mobile→device envelope; returns the plaintext JSON string. */
        fun decrypt(envelope: JSONObject): String {
            synchronized(lock) {
                checkActive()
                if (envelope.optString("session_id") != sessionIdB64) fail("wrong session")
                val counter = envelope.optString("counter").toLongOrNull()
                    ?: fail("bad counter")
                if (counter != rxCounter) fail("unexpected counter")
                val pt = try {
                    PairingCrypto.open(
                        rxKey!!, sessionIdB64, PairingCrypto.DIR_TO_DEVICE, counter,
                        ciphertext = PairingCrypto.b64urlDecode(envelope.optString("ciphertext")),
                        tag = PairingCrypto.b64urlDecode(envelope.optString("tag")),
                    )
                } catch (_: Exception) {
                    fail("decrypt failed")
                }
                rxCounter++
                return pt.toString(Charsets.UTF_8)
            }
        }

        /** Encrypts one device→mobile record; returns the envelope JSON. */
        fun encryptJson(plaintext: String): JSONObject {
            Log.d(TAG, "TX encrypted record: ${plaintext.length} bytes")
            synchronized(lock) {
                checkActive()
                val counter = txCounter
                val sealed = PairingCrypto.seal(
                    txKey!!, sessionIdB64, PairingCrypto.DIR_FROM_DEVICE, counter,
                    plaintext.toByteArray(),
                )
                txCounter++
                return JSONObject().apply {
                    put("type", "pairing_encrypted")
                    put("session_id", sessionIdB64)
                    put("counter", counter.toString())
                    put("ciphertext", PairingCrypto.b64urlEncode(sealed.ciphertext))
                    put("tag", PairingCrypto.b64urlEncode(sealed.tag))
                }
            }
        }

        fun encryptStatus(status: String): JSONObject {
            Log.i(TAG, "TX status (encrypted): $status")
            val msg = JSONObject().apply {
                put("type", "status")
                put("status", status)
            }
            if (status == "pairing_confirmed") {
                // The Muse app needs the SDK token to set the gadget up (SDK README:
                // "Every gadget needs a token to pair"). Log presence, never the value.
                Log.i(TAG, "pairing_confirmed carries SDK token: ${sdkToken != null}")
                if (sdkToken != null) msg.put("sdk_token", sdkToken)
            }
            return encryptJson(msg.toString())
        }

        fun confirm() {
            synchronized(lock) {
                check(state == PairState.WAIT_CLIENT_FINISHED) { "not waiting for finished" }
                state = PairState.READY
                deadlineMs = System.currentTimeMillis() + CONFIRMED_TIMEOUT_S * 1000
            }
        }

        val isConfirmed: Boolean
            get() = synchronized(lock) {
                checkActive()
                state == PairState.READY
            }

        fun reset() {
            synchronized(lock) {
                state = PairState.IDLE
                rxKey = null; txKey = null
                sessionIdB64 = ""
                rxCounter = 0; txCounter = 0
                deadlineMs = 0
            }
        }

        private fun checkActive() {
            if (state == PairState.IDLE) fail("no session")
            if (System.currentTimeMillis() > deadlineMs) fail("session expired")
        }

        private fun fail(reason: String): Nothing {
            reset()
            throw PairingException("error_pairing_decrypt")
        }
    }

    private class PairingException(val errorJson: String) :
        RuntimeException(errorJson)

    // ---- BLE plumbing ----

    private val executor = Executors.newSingleThreadExecutor()
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var adapter: BluetoothAdapter? = null
    private var originalAdapterName: String? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedDevice: BluetoothDevice? = null
    private var notifyEnabled = false
    private var mtu = 23
    private var running = false

    private val session = PairingSession()
    private val sendLock = Any()

    /** Reassembles 0xFE-framed chunks strictly in-order (§6.3). */
    private inner class ChunkAssembler {
        private var expectedTotal = -1
        private var nextIndex = 0
        private val buf = ByteArrayOutputStream()

        /** Returns the complete message, or null if more chunks are needed. */
        fun feed(packet: ByteArray): ByteArray? {
            if (packet.isEmpty() || packet[0] != 0xFE.toByte()) {
                reset()
                return packet
            }
            if (packet.size < 3) return null // malformed; wait
            val index = packet[1].toInt() and 0xFF
            val total = packet[2].toInt() and 0xFF
            val fragment = packet.copyOfRange(3, packet.size)
            if (index == 0 || total != expectedTotal) {
                // Restart.
                expectedTotal = total
                nextIndex = 0
                buf.reset()
            }
            if (index != nextIndex) {
                reset() // out-of-order; discard
                return null
            }
            if (buf.size() + fragment.size > MAX_MESSAGE_BYTES) {
                reset()
                return null
            }
            buf.write(fragment)
            nextIndex++
            return if (nextIndex >= total) {
                val msg = buf.toByteArray()
                reset()
                msg
            } else null
        }

        private fun reset() {
            expectedTotal = -1
            nextIndex = 0
            buf.reset()
        }
    }

    private val assembler = ChunkAssembler()
    private val preparedWrites = mutableMapOf<String, ByteArrayOutputStream>()

    private fun feedRx(packet: ByteArray) {
        val message = synchronized(assembler) { assembler.feed(packet) } ?: return
        Log.d(TAG, "RX message: ${message.size} bytes")
        executor.execute { handleMessage(message) }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectedDevice = device
                notifyEnabled = false
                mtu = 23
                Log.i(TAG, "BLE connected: ${device.address}")
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                if (connectedDevice?.address == device.address) {
                    connectedDevice = null
                    notifyEnabled = false
                }
                session.reset()
                Log.i(TAG, "BLE disconnected")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "server MTU now $mtu")
            this@BlePairingServer.mtu = mtu
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                notifyEnabled = value.isNotEmpty() && value[0] == 0x01.toByte()
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                Log.i(TAG, "CCCD notify=$notifyEnabled")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // TX reads aren't used by the flow; return empty success.
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray,
        ) {
            if (characteristic.uuid != RX_UUID) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                return
            }
            if (preparedWrite) {
                // Long write: Android hands us the pieces and then onExecuteWrite
                // (BlueZ does this transparently for the Linux SDK). Buffer them.
                Log.i(TAG, "RX prepared write: offset=$offset len=${value.size}")
                synchronized(preparedWrites) {
                    val buf = preparedWrites.getOrPut(device.address) { ByteArrayOutputStream() }
                    if (offset != buf.size()) Log.w(TAG, "prepared write offset $offset != ${buf.size()}")
                    buf.write(value)
                }
                if (responseNeeded) {
                    // Prepared-write responses must echo the value and offset.
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                return
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            Log.d(TAG, "RX write: len=${value.size} chunked=${value.isNotEmpty() && value[0] == 0xFE.toByte()}")
            feedRx(value)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buf = synchronized(preparedWrites) { preparedWrites.remove(device.address) }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (execute && buf != null) {
                Log.i(TAG, "RX long write executed: len=${buf.size()}")
                feedRx(buf.toByteArray())
            }
        }
    }

    // ---- message dispatch (§6.4) ----

    private fun handleMessage(raw: ByteArray) {
        val text = try {
            raw.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            return
        }
        val msg = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        Log.i(TAG, "RX action: ${msg.optString("action").ifEmpty { "?" }}")
        try {
            when (msg.optString("action")) {
                "get_device_info" -> sendPlain(deviceInfo())
                "pairing_client_hello" -> sendPlain(session.handleHello(msg))
                "pairing_encrypted" -> {
                    val inner = JSONObject(session.decrypt(msg))
                    handleDecrypted(inner)
                }
                else -> {
                    // Unknown plaintext after hello is blocked; ignore.
                    if (msg.optString("action").isNotEmpty()) {
                        Log.w(TAG, "ignoring plaintext action ${msg.optString("action")}")
                    }
                }
            }
        } catch (e: PairingException) {
            // Pre-session errors go out as the bare status string, like the SDK's
            // plaintext send_status (ble_setup.py PLAINTEXT_STATUSES).
            sendFramed(e.errorJson.toByteArray(Charsets.UTF_8))
            if (e.errorJson == "error_pairing_decrypt") {
                // Spec: disconnect 0.3 s after a record failure (not after a bad hello).
                Thread.sleep(300)
                dropConnection()
            }
        } catch (e: Exception) {
            Log.w(TAG, "message handling failed", e)
        }
    }

    private var clientFinishedSeen = false

    private fun handleDecrypted(msg: JSONObject) {
        val action = msg.optString("action")
        Log.i(TAG, "RX action: ${action.ifEmpty { "?" }} (encrypted)")
        if (!clientFinishedSeen) {
            if (action != "pairing_client_finished") {
                throw PairingException("error_pairing_decrypt")
            }
            clientFinishedSeen = true
            session.confirm()
            sendEncrypted(session.encryptStatus("pairing_confirmed"))
            return
        }
        when (action) {
            "provision_v2" -> handleProvision(msg)
            "unpair" -> {
                if (!session.isConfirmed) {
                    sendEncrypted(session.encryptStatus("error_pairing_confirm_required"))
                    return
                }
                callbacks.onUnpair()
                session.reset()
                clientFinishedSeen = false
            }
            "wifi_scan" -> {
                // One open entry for the current connection (network.py
                // current_connection_entry): "secure": false makes the app skip
                // the password field. Unlike the SDK, offer it even when the
                // watch looks offline: Wear OS often reports its Bluetooth-proxy
                // or idle network as not validated, and the watch doesn't need the
                // network until it connects to Muse after pairing.
                Log.i(TAG, "wifi_scan: online=${isOnline()} (${networkSummary()})")
                val networks = org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("ssid", CURRENT_CONNECTION_LABEL)
                        put("rssi", -40)
                        put("secure", false)
                    })
                }
                val resp = JSONObject().apply {
                    put("type", "wifi_scan_result")
                    put("networks", networks)
                }
                sendEncrypted(session.encryptJson(resp.toString()))
            }
            else -> {
                Log.w(TAG, "unknown decrypted action: $action")
                sendEncrypted(session.encryptStatus("error_unknown_action"))
            }
        }
    }

    private fun handleProvision(msg: JSONObject) {
        if (!session.isConfirmed) {
            sendEncrypted(session.encryptStatus("error_pairing_confirm_required"))
            return
        }
        // Like the SDK (ble_setup.py _handle_provision): ssid/password must be
        // present as strings but may be empty — the watch is already online and
        // ignores them; the app sends an empty password for the open entry we
        // offer in wifi_scan.
        val accessToken = msg.optString("access_token")
        val refreshToken = msg.optString("refresh_token")
        val tokenType = msg.optString("token_type")
        // Keys only — never log token values.
        Log.i(TAG, "provision_v2 fields: ${msg.keys().asSequence().sorted().joinToString()}")
        if (msg.opt("ssid") !is String || msg.opt("password") !is String ||
            accessToken.isEmpty() || refreshToken.isEmpty() || tokenType != "device"
        ) {
            sendEncrypted(session.encryptStatus("error_missing_credentials"))
            return
        }
        // The app tracks setup through these statuses before auth_ok. The watch
        // stores the tokens without using the network (it connects to Muse after
        // pairing), so don't fail setup on a momentary "offline" reading.
        sendEncrypted(session.encryptStatus("wifi_connecting"))
        Log.i(TAG, "provision: online=${isOnline()} (${networkSummary()})")
        sendEncrypted(session.encryptStatus("wifi_connected"))
        try {
            callbacks.onProvisioned(
                accessToken,
                refreshToken,
                tokenType,
                msg.optString("username"),
                msg.optString("api_url"),
                msg.optString("api_url_v2"),
                msg.optString("noise_host"),
            )
            sendEncrypted(session.encryptStatus("auth_ok"))
        } catch (e: Exception) {
            Log.w(TAG, "provisioning failed", e)
            sendEncrypted(session.encryptStatus("auth_failed"))
        }
    }

    /**
     * True when the active network offers internet (Wi-Fi, LTE or via the phone).
     * Not requiring VALIDATED: Wear OS's Bluetooth proxy often lacks it while working.
     */
    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** For logs: transports and capabilities of the active network. */
    private fun networkSummary(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "no ConnectivityManager"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "no active network"
        return caps.toString()
    }

    private fun deviceInfo(): JSONObject = JSONObject().apply {
        put("type", "device_info")
        put("node_id", nodeId)
        put("version", firmwareVersion)
        put("device_id", deviceId)
        put("mac", mac)
        put("model", PairingCrypto.MODEL)
        put("pairing_protocol", PairingCrypto.VERSION)
        put("pairing_auth", "none")
        put("pairing_auth_epoch", 0)
        put("pairing_policy", PAIRING_POLICY)
        put("build_sha", "")
        put("network_ready", isOnline())
    }

    // ---- sending (fragmented per §6.3, serialized under sendLock) ----

    private fun sendPlain(obj: JSONObject) {
        Log.i(TAG, "TX ${obj.optString("type")}")
        sendFramed(obj.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sendEncrypted(obj: JSONObject) {
        sendFramed(obj.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sendFramed(data: ByteArray) {
        val device = connectedDevice ?: return Unit.also { Log.w(TAG, "TX dropped: no connected phone") }
        val server = gattServer ?: return Unit.also { Log.w(TAG, "TX dropped: GATT server closed") }
        if (!notifyEnabled) Log.w(TAG, "TX without notifications enabled (${data.size} bytes)")
        val tx = txCharacteristic ?: return
        synchronized(sendLock) {
            val packets = frame(data)
            for (p in packets) {
                tx.value = p
                if (notifyEnabled) {
                    server.notifyCharacteristicChanged(device, tx, false)
                } else {
                    // Fall back to leaving the value for reads.
                    tx.value = p
                }
                if (packets.size > 1) Thread.sleep(CHUNK_STAGGER_MS)
            }
        }
    }

    /** Splits data into ≤160-byte packets, 0xFE-framed when more than one. */
    private fun frame(data: ByteArray): List<ByteArray> {
        val fragSize = maxOf(20, minOf(mtu - 3, MAX_PACKET_BYTES - 3))
        if (data.size <= fragSize) {
            // Single packet — but must not start with 0xFE.
            if (data.isNotEmpty() && data[0] == 0xFE.toByte()) {
                return frameChunked(data, fragSize)
            }
            return listOf(data)
        }
        return frameChunked(data, fragSize)
    }

    private fun frameChunked(data: ByteArray, fragSize: Int): List<ByteArray> {
        val total = (data.size + fragSize - 1) / fragSize
        require(total <= 255) { "message too large" }
        return (0 until total).map { i ->
            val start = i * fragSize
            val end = minOf(start + fragSize, data.size)
            val frag = data.copyOfRange(start, end)
            byteArrayOf(0xFE.toByte(), i.toByte(), total.toByte()) + frag
        }
    }

    private fun dropConnection() {
        val device = connectedDevice ?: return
        try {
            gattServer?.cancelConnection(device)
        } catch (_: Exception) {
        }
    }

    // ---- lifecycle ----

    private fun requireBlePermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("BLE pairing needs permissions: $missing")
        }
    }

    /** Starts advertising + GATT server. Throws if BLE peripheral mode is unavailable. */
    fun start() {
        Log.i(TAG, "pairing start: SDK token ${if (sdkToken != null) "set" else "MISSING"}")
        requireBlePermissions()
        if (running) return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter
            ?: throw IllegalStateException("Bluetooth not available")

        advertiser = adapter.bluetoothLeAdvertiser
            ?: throw IllegalStateException(
                "BLE advertising not supported on this watch — pairing via the Muse app is unavailable"
            )

        val server = manager.openGattServer(context, gattCallback)
            ?: throw IllegalStateException("Could not open GATT server")
        gattServer = server

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        if (!server.addService(service)) {
            server.close()
            throw IllegalStateException("Could not add GATT service")
        }
        txCharacteristic = tx

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        // Legacy advertising carries 31 bytes. Flags (3) + 128-bit service UUID (18)
        // + manufacturer data (5) fit; the 16-char local name (18) goes in the scan
        // response (BlueZ does this split automatically for the Linux SDK).
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addManufacturerData(0xFFFF, byteArrayOf(0x00))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        // Advertising can only carry the adapter's own name, so rename the watch's
        // Bluetooth adapter for the pairing window and put the old name back in stop().
        // The name must stay "MuseGadget" + node-id suffix: the Muse app matches the
        // text after the prefix against the node_id (identity.py ble_name).
        try {
            if (adapter.name != bleName) {
                originalAdapterName = adapter.name
                adapter.name = bleName
            }
            this.adapter = adapter
        } catch (_: Exception) {
        }

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "advertising as $bleName")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "advertise failed: $errorCode")
                val reason = when (errorCode) {
                    AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "advertisement too large"
                    AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
                    AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already advertising"
                    AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Bluetooth internal error"
                    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "not supported on this watch"
                    else -> "error $errorCode"
                }
                callbacks.onError("BLE advertising failed: $reason")
            }
        }
        advertiseCallback = cb
        advertiser?.startAdvertising(settings, data, scanResponse, cb)
        running = true
    }

    fun stop() {
        running = false
        // Give the watch its own Bluetooth name back (best effort).
        try {
            originalAdapterName?.let { adapter?.name = it }
        } catch (_: Exception) {
        }
        originalAdapterName = null
        adapter = null
        try {
            advertiseCallback?.let { advertiser?.stopAdvertising(it) }
        } catch (_: Exception) {
        }
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        advertiser = null
        gattServer = null
        advertiseCallback = null
        connectedDevice = null
        session.reset()
        clientFinishedSeen = false
    }
}
