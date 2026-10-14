**Title:** Android app: provision_v2 write throws IllegalArgumentException at MTU 517 ("Can't connect" after Wi-Fi) — cap chunks at 512

### Summary

On Android 15+ the Muse app can't finish community-gadget setup when the gadget's GATT server accepts the default 517-byte ATT MTU. Pairing completes, `wifi_scan` works, then after **Use current connection → Connect** the app shows **"Can't connect"**. The `provision_v2` record is never sent: `HatchDeviceBleManager.writeChunked` sizes chunks as `negotiatedMtu - 3` = 514 bytes, and `BluetoothGatt.writeCharacteristic` rejects any value over 512 bytes.

`linux/AGENTS.md` already documents this ("The Android app writes `negotiatedMtu - 3` bytes with no cap, and Android rejects writes over 512 bytes … Symptom if it's missing: pairing works up to Wi-Fi, then 'Couldn't connect' in the app") and works around it on the gadget side with BlueZ `ExchangeMTU = 256` / `CONFIG_BT_NIMBLE_ATT_PREFERRED_MTU=256`. That workaround isn't available to every gadget: a gadget built on Android/Wear OS can't set its GATT server MTU (`BluetoothGattServer` has no MTU API, and since Android 14 the stack always exchanges 517 and ignores later `requestMtu` calls). So the app needs the fix.

### Environment

- Phone: OnePlus CPH2551, Android 16
- Muse app: `com.facebook.aura` 9.0.0.23.178
- Gadget: community pairing v5 (`hatch_link`, `pairing_auth: none`, `confirm_app`) running on a Wear OS watch (Galaxy Watch4 Classic, Android 16), protocol-compatible with `linux/` (device_info, pairing_ready, records verified against `linux/tests/vectors/link_pairing_v5.json`)

### Phone log (redacted)

```
I HatchDeviceBleManager: GATT connected: <gadget>
D BluetoothGatt: configureMTU() - device: <gadget> mtu: 247
I gatt_api: GATTC_ConfigureMTU: Configuring ATT mtu size conn_id:17 mtu:517 user mtu 247
D BluetoothGatt: onConfigureMTU() - Device=<gadget> mtu=517 status=0
I HatchDeviceBleManager: MTU changed: mtu=517, success=true
...
E HatchDeviceBleManager: Write failed: value should not be longer than max length of an attribute value
E HatchDeviceBleManager: java.lang.IllegalArgumentException: value should not be longer than max length of an attribute value
	at android.bluetooth.BluetoothGatt.writeCharacteristic(BluetoothGatt.java:1596)
	at com.facebook.aura.common.ble.AuraBleConnectionImpl.writeCharacteristic-yxL6bBk(:199)
	at com.facebook.aura.devices.pairing.HatchDeviceBleManager.writeChunked-gIAlu-s(:315)
	at com.facebook.aura.devices.pairing.HatchDeviceBleManager.writeEncryptedProvisionRecord(:93)
	at com.facebook.aura.devices.pairing.LinkEncryptedSetupCoordinator.provision(:559)
	...
```

The gadget side sees `pairing_client_hello`, `pairing_client_finished`, `wifi_scan`, then nothing: no `provision_v2` write ever arrives.

### Why it happens

- The app asks for MTU 247, but since Android 14 the stack exchanges 517 regardless (`user mtu 247` → `mtu:517` above), so a gadget with a stock 517 server MTU negotiates 517.
- `writeChunked` uses `mtu - 3` = 514-byte packets (0xFE header + 511-byte fragments). The provision record (~2.8 KB per #31) always needs full-size chunks.
- Since Android 15 (API 35), `BluetoothGatt.writeCharacteristic(characteristic, value, writeType)` throws `IllegalArgumentException` when `value.length > GATT_MAX_ATTR_LEN` (512). The check is in the API 35 and 36 framework sources but not in API 34. Earlier, smaller messages fit in one packet, which is why only the provision step fails.

### Suggested fix

Cap the chunk size in `HatchDeviceBleManager.writeChunked` at the ATT attribute limit:

```kotlin
val chunkSize = minOf(negotiatedMtu - 3, 512)
```

(The same applies to any other path that sizes writes from the MTU.) Then gadgets work at the default 517 MTU, and the BlueZ `ExchangeMTU = 256` requirement in `linux/AGENTS.md` becomes optional.

### Independent reports

- Reproduced separately with a macOS virtual gadget ([wong2/muse-client](https://github.com/wong2/muse-client), Pixel phone, `com.facebook.aura`): same stack trace, `provision_v2` never arrives. macOS CoreBluetooth peripherals can't cap the ATT MTU either, so there's no gadget-side workaround.
- The same kind of gadget pairs fine from the Muse **iOS** app (muse-client's pairing was verified with iOS), which narrows the bug to the Android app.

### Reproduce

Use any community gadget whose GATT server negotiates MTU 517 (e.g. the Linux SDK with BlueZ's default `ExchangeMTU`, as AGENTS.md describes), pair from an Android 15+ phone, and choose a network at the Wi-Fi step → "Can't connect".
