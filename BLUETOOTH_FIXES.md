# Bluetooth Connection Issues - Diagnosis & Fixes

## Issues Found

### 1. **Silent Permission Failures** ⚠️
**Location**: `BleScanner.kt` and `BleAdvertiser.kt`

**Problem**: When `hasPermissions()` returns false, operations silently fail with early returns.
```kotlin
fun startScanning() {
    if (!hasPermissions()) {
        Log.e(TAG, "Missing BLE permissions")
        return  // ← Silent exit, user doesn't know what failed
    }
    // ...
}
```

**Impact**: User doesn't see why mesh isn't working.

---

### 2. **GATT Connection Timeout** ⚠️
**Location**: `BleScanner.kt` - `gattCallback.onConnectionStateChange()`

**Problem**: No timeout mechanism for GATT connections. If a device is slow or unresponsive, it hangs.

---

### 3. **BluetoothAdapter Null Check Missing** ⚠️
**Location**: `BleScanner.kt` - Line 24-26

```kotlin
private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
private var scanner: BluetoothLeScanner? = null

fun startScanning() {
    if (!hasPermissions()) return
    
    scanner = bluetoothAdapter?.bluetoothLeScanner  // ← Could be null
    if (scanner == null) {
        Log.e(TAG, "BLE scanner not available")
        return
    }
}
```

**Issue**: If `bluetoothAdapter` is null (device doesn't support Bluetooth), the app crashes in other methods.

---

### 4. **No Bluetooth State Validation** ⚠️
**Problem**: App never checks if Bluetooth is actually ENABLED at runtime.

The `isMeshReady()` method checks this in CabActivity, but BleScanner/BleAdvertiser don't verify it.

---

### 5. **GATT Service Discovery Failure** ⚠️
**Location**: `BleScanner.kt` - `onServicesDiscovered()`

```kotlin
override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
    if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) return
    
    val sosService = gatt.getService(BleAdvertiser.SOS_SERVICE_UUID)
    
    if (sosService != null) {
        // Connected!
    } else {
        Log.w(TAG, "SNM service not found on $address, disconnecting")
    }
}
```

**Issue**: If service discovery fails (status != GATT_SUCCESS), connection silently fails.

---

### 6. **No Explicit MTU Request Error Handling** ⚠️
**Location**: `BleScanner.kt`

```kotlin
override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
    when (newState) {
        BluetoothGatt.STATE_CONNECTED -> {
            gatt.requestMtu(512)  // ← No error feedback if this fails
        }
    }
}
```

---

## Root Cause Checklist

If your app can't connect, check these **in order**:

```
☐ Bluetooth is ON (Settings > Bluetooth)
☐ Location services are ON (needed for BLE scanning)
☐ App permissions granted:
    • BLUETOOTH_SCAN (Android 12+)
    • BLUETOOTH_CONNECT (Android 12+)
    • BLUETOOTH_ADVERTISE (Android 12+)
    • ACCESS_FINE_LOCATION (all versions)
    • ACCESS_COARSE_LOCATION (all versions)
☐ Both phones launching app
☐ Both phones have mesh started (status shows "🔗 X peer(s) connected")
```

---

## Fixes

### Fix #1: Add Diagnostic Logging to BleScanner

Replace the permission check with better diagnostics:

```kotlin
// In BleScanner.kt, replace hasPermissions() with:

private fun hasPermissions(): Boolean {
    val isUsingModernBle = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    
    if (isUsingModernBle) {
        val hasConnect = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        val hasScan = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        
        if (!hasConnect) {
            Log.w(TAG, "❌ MISSING: BLUETOOTH_CONNECT permission")
            return false
        }
        if (!hasScan) {
            Log.w(TAG, "❌ MISSING: BLUETOOTH_SCAN permission")
            return false
        }
        return true
    } else {
        val hasLocation = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        if (!hasLocation) {
            Log.w(TAG, "❌ MISSING: ACCESS_FINE_LOCATION permission")
            return false
        }
        return true
    }
}
```

### Fix #2: Add Bluetooth Adapter Validation

```kotlin
// Add this to BleScanner.kt after line 24

init {
    if (bluetoothAdapter == null) {
        Log.e(TAG, "❌ FATAL: Bluetooth not supported on this device!")
    } else if (!bluetoothAdapter!!.isEnabled) {
        Log.e(TAG, "❌ Bluetooth is currently DISABLED. Enable in Settings.")
    }
}

fun isBluetoothAvailable(): Boolean {
    if (bluetoothAdapter == null) {
        Log.e(TAG, "Bluetooth hardware not available")
        return false
    }
    if (!bluetoothAdapter!!.isEnabled) {
        Log.e(TAG, "Bluetooth is disabled — user must enable it")
        return false
    }
    return true
}
```

### Fix #3: Add Connection Timeout

```kotlin
// Add to BleScanner.kt

private val connectionTimeouts = ConcurrentHashMap<String, Job>()

private fun connectToDevice(device: BluetoothDevice) {
    if (!hasPermissions() || !isBluetoothAvailable()) return

    try {
        Log.d(TAG, "Connecting to ${device.address}...")
        device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        
        // Set 15-second timeout
        val timeoutJob = scanScope?.launch {
            delay(15_000)
            if (connectedPeers.containsKey(device.address).not()) {
                Log.e(TAG, "⏱️ Connection timeout for ${device.address}")
                connectedPeers.remove(device.address)
            }
        }
        if (timeoutJob != null) {
            connectionTimeouts[device.address] = timeoutJob
        }
    } catch (e: Exception) {
        Log.e(TAG, "❌ Error connecting to ${device.address}: ${e.message}", e)
    }
}

// Cancel timeout when connection successful
override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
    val address = gatt?.device?.address ?: return
    
    when (newState) {
        BluetoothGatt.STATE_CONNECTED -> {
            connectionTimeouts[address]?.cancel()
            connectionTimeouts.remove(address)
            Log.d(TAG, "Connected to $address, requesting MTU...")
            gatt.requestMtu(512)
        }
        BluetoothGatt.STATE_DISCONNECTED -> {
            connectionTimeouts[address]?.cancel()
            connectionTimeouts.remove(address)
            // ... rest of disconnect logic
        }
    }
}
```

### Fix #4: Add GATT Discovery Error Handling

```kotlin
// In BleScanner.kt, update onServicesDiscovered()

override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
    if (!hasPermissions()) return
    
    val address = gatt?.device?.address ?: return
    
    // ✅ NEW: Check status first
    if (status != BluetoothGatt.GATT_SUCCESS) {
        Log.e(TAG, "❌ Service discovery FAILED on $address (status: $status)")
        Log.e(TAG, "   Possible causes:")
        Log.e(TAG, "   - Device disconnected")
        Log.e(TAG, "   - Peer device doesn't support BLE")
        Log.e(TAG, "   - Service UUID mismatch")
        try {
            gatt.disconnect()
        } catch (e: SecurityException) {}
        return
    }
    
    if (gatt == null) return
    
    val sosService = gatt.getService(BleAdvertiser.SOS_SERVICE_UUID)

    if (sosService != null) {
        val dataChar = sosService.getCharacteristic(BleAdvertiser.SOS_DATA_CHAR_UUID)
        if (dataChar != null) {
            connectedPeers[address] = gatt
            Log.d(TAG, "✅ SNM service FOUND on $address — peer ready")

            try {
                gatt.setCharacteristicNotification(dataChar, true)
                val descriptor = dataChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                    Log.d(TAG, "✅ Enabled SNM notifications for $address")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "❌ Security exception enabling notifications: ${e.message}")
            }

            val peer = PeerInfo(address = address, gatt = gatt)
            discoveredPeers[address] = peer
            onPeerConnected?.invoke(peer)
            
            try {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            } catch (e: SecurityException) {}
        } else {
            Log.w(TAG, "❌ Data characteristic NOT FOUND on $address (service exists but no UUID match)")
        }
    } else {
        Log.w(TAG, "❌ SNM service NOT FOUND on $address")
        Log.w(TAG, "   Expected UUID: ${BleAdvertiser.SOS_SERVICE_UUID}")
        Log.w(TAG, "   This peer might not be a Saarthi node or uses different UUID")
        try {
            gatt.disconnect()
        } catch (e: SecurityException) {}
    }
}
```

### Fix #5: Add Bluetooth State Check to BleAdvertiser

```kotlin
// In BleAdvertiser.kt init block

init {
    val adapter = bluetoothAdapter
    if (adapter == null) {
        Log.e(TAG, "❌ FATAL: Bluetooth not supported on this device!")
    } else if (!adapter.isEnabled) {
        Log.e(TAG, "❌ Bluetooth is currently DISABLED")
    }
}

private fun isBluetoothAvailable(): Boolean {
    if (bluetoothAdapter == null) {
        Log.e(TAG, "Bluetooth hardware not available")
        return false
    }
    if (!bluetoothAdapter!!.isEnabled) {
        Log.e(TAG, "Bluetooth is disabled")
        return false
    }
    return true
}

fun startAdvertising(scope: CoroutineScope) {
    if (!hasPermissions()) {
        Log.e(TAG, "❌ Missing Bluetooth permissions")
        return
    }
    
    if (!isBluetoothAvailable()) {
        Log.e(TAG, "❌ Bluetooth not available (disabled or not supported)")
        return
    }
    
    advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
    if (advertiser == null) {
        Log.e(TAG, "❌ BLE advertising not supported on this device")
        return
    }
    
    // ... rest of startup
}
```

---

## Quick Test: Run in Logcat

After making fixes, run this command and watch the logs:

```powershell
adb logcat -s "BleScanner:D" "BleAdvertiser:D" "MeshManager:D" | Select-String -Pattern "^|Connected|Service|❌|✅"
```

You should see:
```
✅ SNM advertising started
✅ Started Hardware BLE scanning
✅ Node Found: AA:BB:CC:DD:EE:FF
✅ Election WON
✅ Connected to AA:BB:CC:DD:EE:FF
✅ SNM service FOUND
✅ Enabled SNM notifications
```

Or errors like:
```
❌ MISSING: BLUETOOTH_SCAN permission
❌ Bluetooth is currently DISABLED
❌ Service discovery FAILED (status: 257)
```

---

## How to Apply Fixes

Option A: **Quick Test (Demo Mode)**
```kotlin
// In CabActivity.kt, temporarily add after startMeshNetwork():
meshManager.toggleDemoMode(true)
// This simulates peers without hardware
```

Option B: **Full Fix** - Apply the code above to:
- `BleScanner.kt` - Add validation methods
- `BleAdvertiser.kt` - Add validation methods
- Test with 2 real phones

---

## Expected Working Logs (After Fixes)

```
D/MeshManager: Mesh initialized: USER_ABC123 (CITIZEN)
D/BleAdvertiser: ✅ SNM advertising started
D/BleScanner: ✅ Started Hardware BLE scanning
D/BleScanner: 🔍 Node Found: AA:BB:CC:DD:EE:FF (Hash: -1234567, RSSI: -45)
D/BleScanner: 👑 Election WON (-1234567 > 123456). Connecting as Master.
D/BleScanner: Connecting to AA:BB:CC:DD:EE:FF...
D/BleScanner: Connected to AA:BB:CC:DD:EE:FF, requesting MTU...
D/BleScanner: MTU changed to 512 for AA:BB:CC:DD:EE:FF
D/BleScanner: ✅ SNM service FOUND on AA:BB:CC:DD:EE:FF — peer ready
D/BleScanner: ✅ Enabled SNM notifications for AA:BB:CC:DD:EE:FF
D/MeshManager: 🔗 1 peer(s) connected
```

---

## Common Error Codes & Solutions

| Error | Meaning | Solution |
|-------|---------|----------|
| `status: 2` (GATT_FAILED) | Generic GATT error | Restart Bluetooth on both phones |
| `status: 8` (GATT_CONN_TERMINATE_LOCAL_HOST) | You disconnected | Check for timeouts |
| `status: 22` (GATT_WRITE_NOT_PERMITTED) | Characteristic permission denied | Check GATT server permissions |
| `Service discovery FAILED` | Services not found | Peer is not a Saarthi node |
| `MTU changed to 23` | Default MTU, not 512 | Device may not support large MTU |

