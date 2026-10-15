package com.luxwatch.gadget

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.luxwatch.gadget.pairing.TokenTransfer
import com.luxwatch.gadget.pairing.TokenTransfer.Status
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Takes the SDK token from the "Send token to watch" web page over Bluetooth
 * while Settings → SDK token is open (protocol: [TokenTransfer]). Nothing is
 * saved here: [onReceived] hands the token to the screen, which asks first.
 * Callbacks run on the main thread. Needs the same Bluetooth permissions as
 * pairing ([BlePairingServer.requiredPermissions]).
 */
class TokenReceiver(
    private val context: Context,
    private val onReceived: (token: String) -> Unit,
    private val onInvalid: () -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "TokenReceiver"
        private val SERVICE = UUID.fromString(TokenTransfer.SERVICE_UUID)
        private val KEY = UUID.fromString(TokenTransfer.KEY_UUID)
        private val TOKEN = UUID.fromString(TokenTransfer.TOKEN_UUID)
        private val STATUS = UUID.fromString(TokenTransfer.STATUS_UUID)
    }

    private val main = Handler(Looper.getMainLooper())
    private val receiver = TokenTransfer.Receiver()
    private val assembler = TokenTransfer.Assembler()
    private val prepared = mutableMapOf<String, ByteArrayOutputStream>()
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertising: AdvertiseCallback? = null

    /** What the page sees when it reads STATUS. */
    @Volatile var status = Status.READY

    /** Throws if Bluetooth or its permissions are missing. */
    fun start() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: throw IllegalStateException("Bluetooth not available")
        val adv = adapter.bluetoothLeAdvertiser ?: throw IllegalStateException("Bluetooth is off")
        val gatt = manager.openGattServer(context, callback) ?: throw IllegalStateException("Could not open GATT server")
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    KEY, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ,
                ),
            )
            addCharacteristic(
                BluetoothGattCharacteristic(
                    TOKEN, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE,
                ),
            )
            addCharacteristic(
                BluetoothGattCharacteristic(
                    STATUS, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ,
                ),
            )
        }
        if (!gatt.addService(service)) {
            gatt.close()
            throw IllegalStateException("Could not add GATT service")
        }
        server = gatt
        advertiser = adv
        advertise(withName = true)
    }

    /**
     * Flags + the 128-bit service UUID fill most of an advertisement; the
     * watch's name goes in the scan response so the phone's chooser shows it,
     * unless it's too long, then the chooser lists the watch without a name.
     */
    private fun advertise(withName: Boolean) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(SERVICE)).build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(withName).build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "waiting for a token")
            }

            override fun onStartFailure(errorCode: Int) {
                if (withName && errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE) {
                    main.post { if (server != null) advertise(withName = false) }
                } else {
                    Log.e(TAG, "advertise failed: $errorCode")
                    main.post { onError("Bluetooth error $errorCode") }
                }
            }
        }
        advertising = cb
        try {
            advertiser?.startAdvertising(settings, data, scanResponse, cb)
        } catch (e: SecurityException) {
            onError("Bluetooth permission needed")
        }
    }

    fun stop() {
        try {
            advertising?.let { advertiser?.stopAdvertising(it) }
        } catch (_: Exception) {
        }
        try {
            server?.close()
        } catch (_: Exception) {
        }
        advertising = null
        advertiser = null
        server = null
    }

    private fun handle(message: ByteArray) {
        val token = try {
            receiver.open(message)
        } catch (e: TokenTransfer.TransferError) {
            Log.w(TAG, "rejected a token: ${e.message}")
            status = Status.INVALID
            main.post(onInvalid)
            return
        }
        // Never logged.
        status = Status.CONFIRM
        main.post { onReceived(token) }
    }

    private fun feed(bytes: ByteArray) {
        // A new send: what the page reads next is about this one.
        if (bytes.isNotEmpty() && bytes[0].toInt() == 0) status = Status.READY
        val message = synchronized(assembler) { assembler.feed(bytes) } ?: return
        handle(message)
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, if (newState == BluetoothGatt.STATE_CONNECTED) "phone connected" else "phone disconnected")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val value = when (characteristic.uuid) {
                KEY -> receiver.publicKey
                STATUS -> status.wire.toByteArray()
                else -> ByteArray(0)
            }
            // Long reads come in pieces at increasing offsets.
            val part = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (characteristic.uuid != TOKEN) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            if (preparedWrite) {
                // A phone that sends a chunk as a long write anyway.
                synchronized(prepared) {
                    prepared.getOrPut(device.address) { ByteArrayOutputStream() }.write(value)
                }
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                return
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            feed(value)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buf = synchronized(prepared) { prepared.remove(device.address) }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (execute && buf != null) feed(buf.toByteArray())
        }
    }
}
