package com.shramit.saarthi.mesh

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE Central role — scans for nearby Saarthi mesh nodes,
 * connects to them, and sends/receives SOS packets via GATT.
 *
 * Inspired by BitChat's scanning + connection management.
 */
class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"
        private const val SCAN_PERIOD_MS = 10_000L  // Scan for 10 seconds
        private const val MAX_CONNECTIONS = 7        // Limit concurrent connections
        private const val SNM_MANUFACTURER_ID = 0xFFFF
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var myNodeHash: Int = 0
    private var scanScope: CoroutineScope? = null

    // Connected peers: address -> GATT connection
    private val connectedPeers = ConcurrentHashMap<String, BluetoothGatt>()

    // Track discovered peers and their roles
    data class PeerInfo(
        val address: String,
        val isPolice: Boolean = false,
        val gatt: BluetoothGatt? = null,
        val lastSeen: Long = System.currentTimeMillis()
    )

    private val discoveredPeers = ConcurrentHashMap<String, PeerInfo>()

    // Callbacks
    var onPeerDiscovered: ((PeerInfo) -> Unit)? = null
    var onPeerConnected: ((PeerInfo) -> Unit)? = null
    var onPeerDisconnected: ((String) -> Unit)? = null
    var onPacketReceived: ((ByteArray, String) -> Unit)? = null  // data, peerAddress
    var onPeerRoleRead: ((String, Boolean) -> Unit)? = null       // address, isPolice
    var onNearbyPeersChanged: ((Int) -> Unit)? = null             // count of visible nodes

    val peerCount: Int get() = connectedPeers.size

    /**
     * Set the local node hash for election.
     */
    fun setNodeHash(hash: Int) {
        this.myNodeHash = hash
    }

    /**
     * Start scanning for Saarthi mesh nodes.
     */
    fun startScanning() {
        if (!hasPermissions()) {
            Log.e(TAG, "❌ Cannot scan — missing Bluetooth permissions")
            return
        }

        if (!isBluetoothAvailable()) {
            Log.e(TAG, "❌ Cannot scan — Bluetooth unavailable")
            return
        }

        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "❌ BLE scanner not available on this device")
            return
        }

        // Hardware filtering is much more reliable on Android
        val filters = mutableListOf<ScanFilter>()
        filters.add(ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleAdvertiser.SOS_SERVICE_UUID))
            .build())

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .build()

        try {
            scanner?.startScan(filters, settings, scanCallback)
            isScanning = true
            Log.d(TAG, "Started Hardware BLE scanning for Saarthi mesh nodes")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan: ${e.message}")
            // Fallback: search without filter if hardware filtering fails
            try {
                scanner?.startScan(null, settings, scanCallback)
                isScanning = true
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback scan failed: ${e2.message}")
            }
        }
    }

    /**
     * Start continuous scanning — scan, pause, repeat.
     */
    fun startContinuousScanning(scope: CoroutineScope) {
        this.scanScope = scope
        scope.launch {
            while (isActive) {
                startScanning()
                delay(SCAN_PERIOD_MS)
                stopScanning()
                delay(2_000) // Brief pause between scans for battery
            }
        }
    }

    /**
     * Stop scanning.
     */
    fun stopScanning() {
        if (!hasPermissions()) return
        try {
            if (isScanning) {
                scanner?.stopScan(scanCallback)
                isScanning = false
                Log.d(TAG, "Stopped BLE scanning")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping scan: ${e.message}")
        }
    }

    /**
     * Send an SOS packet to all connected peers via GATT write.
     */
    fun broadcastPacket(data: ByteArray) {
        if (!hasPermissions()) return
        val currentScope = scanScope ?: return

        for ((address, gatt) in connectedPeers) {
            currentScope.launch {
                try {
                    val service = gatt.getService(BleAdvertiser.SOS_SERVICE_UUID)
                    val dataChar = service?.getCharacteristic(BleAdvertiser.SOS_DATA_CHAR_UUID)

                    if (dataChar != null) {
                        dataChar.value = data
                        dataChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        gatt.writeCharacteristic(dataChar)
                        Log.d(TAG, "Broadcasting packet to peer $address (${data.size} bytes)")
                    } else {
                        Log.w(TAG, "Data characteristic not found on peer $address")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception writing to $address: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing to $address: ${e.message}")
                }
            }
        }
    }

    /**
     * Send a packet to a specific peer.
     */
    fun sendPacketToPeer(data: ByteArray, peerAddress: String) {
        if (!hasPermissions()) return
        val currentScope = scanScope ?: return

        val gatt = connectedPeers[peerAddress] ?: return
        currentScope.launch {
            try {
                val service = gatt.getService(BleAdvertiser.SOS_SERVICE_UUID)
                val dataChar = service?.getCharacteristic(BleAdvertiser.SOS_DATA_CHAR_UUID)
                if (dataChar != null) {
                    dataChar.value = data
                    dataChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    gatt.writeCharacteristic(dataChar)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to $peerAddress: ${e.message}")
            }
        }
    }

    private fun getPseudoAddress(): String {
        val prefs = context.getSharedPreferences("ble_mesh", Context.MODE_PRIVATE)
        var address = prefs.getString("pseudo_address", "")
        if (address.isNullOrEmpty()) {
            address = (1..6).joinToString(":") { 
                "%02X".format((0..255).random()) 
            }
            prefs.edit().putString("pseudo_address", address).apply()
        }
        return address
    }
    fun cleanup() {
        scanScope = null
        stopScanning()
        if (!hasPermissions()) return

        try {
            for ((_, gatt) in connectedPeers) {
                gatt.close()
            }
            connectedPeers.clear()
            discoveredPeers.clear()
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during cleanup: ${e.message}")
        }
    }

    // ── Scan callback ─────────────────────────────────────────────────

    private val connectionAttempts = ConcurrentHashMap<String, Long>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val address = device.address
                
                // SNM Discovery: Extract identity from Manufacturer Data
                val scanRecord = result.scanRecord ?: return
                val manufacturerData = scanRecord.getManufacturerSpecificData(SNM_MANUFACTURER_ID)
                
                // If manufacturerData is null, check for Service UUID as a backup
                if (manufacturerData == null) {
                    val hasSosService = scanRecord.serviceUuids?.any { it.uuid == BleAdvertiser.SOS_SERVICE_UUID } ?: false
                    if (!hasSosService) return
                }

                val now = System.currentTimeMillis()
                
                // Track as visible
                val peer = discoveredPeers.getOrPut(address) { PeerInfo(address) }
                discoveredPeers[address] = peer.copy(lastSeen = now)
                
                val nearbyCount = discoveredPeers.values.count { now - it.lastSeen < 20_000 }
                onNearbyPeersChanged?.invoke(nearbyCount)

                val lastAttempt = connectionAttempts[address] ?: 0L
                
                // Aggressive Bonding: Reduce backoff for SNM links
                if (!connectedPeers.containsKey(address) && (now - lastAttempt > 8_000L)) {
                    if (connectedPeers.size < MAX_CONNECTIONS) {
                        
                        // EXTRACT PEER HASH from Manufacturer Data
                        val peerHash = if (manufacturerData != null && manufacturerData.size >= 4) {
                            ByteBuffer.wrap(manufacturerData).getInt()
                        } else {
                            0
                        }

                        // Use long unsigned comparison (handles negative hashCodes)
                        val myUnsigned = myNodeHash.toLong() and 0xFFFFFFFFL
                        val peerUnsigned = peerHash.toLong() and 0xFFFFFFFFL

                        Log.d(TAG, "🔍 Node Found: $address (Hash: $peerHash, RSSI: ${result.rssi})")
                        
                        // Deterministic Election: Higher hash initiates connection, lower hash waits briefly then retries
                        val lastAttempt = connectionAttempts[address] ?: 0
                        val timeSinceLastAttempt = now - lastAttempt
                        
                        if (myUnsigned > peerUnsigned) {
                            // I have higher hash: initiate immediately
                            Log.d(TAG, "👑 Election WON ($myUnsigned > $peerUnsigned). Connecting as Master.")
                            if (timeSinceLastAttempt > 3000) { // Retry every 3 seconds
                                connectionAttempts[address] = now
                                connectToDevice(device)
                            }
                        } else if (myUnsigned < peerUnsigned) {
                            // I have lower hash: let them connect, but retry sometimes
                            Log.d(TAG, "🛡️ Election LOST ($myUnsigned < $peerUnsigned). Other device should connect; I'll retry in 10s...")
                            if (timeSinceLastAttempt > 10000) { // Slave retries every 10 seconds
                                connectionAttempts[address] = now
                                connectToDevice(device)
                            }
                        } else {
                            // Rare tie: use pseudo-address (random MAC)
                            Log.d(TAG, "⚖️ Election TIE ($myUnsigned). Tie-breaking...")
                            if (getPseudoAddress() > address) {
                                if (timeSinceLastAttempt > 3000) {
                                    connectionAttempts[address] = now
                                    connectToDevice(device)
                                }
                            } else if (timeSinceLastAttempt > 10000) {
                                connectionAttempts[address] = now
                                connectToDevice(device)
                            }
                        }
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            isScanning = false
        }
    }

    // ── Device connection ─────────────────────────────────────────────

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasPermissions()) {
            Log.e(TAG, "❌ Cannot connect — missing permissions")
            return
        }

        if (!isBluetoothAvailable()) {
            Log.e(TAG, "❌ Cannot connect — Bluetooth unavailable")
            return
        }

        try {
            Log.d(TAG, "🔌 Initiating GATT connection to ${device.address}...")
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error connecting to ${device.address}: ${e.message}", e)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (!hasPermissions()) return

            val address = gatt?.device?.address ?: return

            try {
                when (newState) {
                    BluetoothGatt.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected to $address, requesting MTU...")
                        gatt.requestMtu(512) // Request larger MTU for efficiency
                    }
                    BluetoothGatt.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected from $address")
                        connectedPeers.remove(address)
                        discoveredPeers.remove(address)
                        gatt.close()
                        onPeerDisconnected?.invoke(address)
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception in connection state change: ${e.message}")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu for ${gatt?.device?.address}")
            gatt?.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (!hasPermissions()) return
            
            val address = gatt?.device?.address ?: return
            
            // Check status first
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "❌ Service discovery FAILED on $address (status: $status)")
                when (status) {
                    2 -> Log.e(TAG, "   ↳ GATT_FAILED (generic error — try restarting Bluetooth)")
                    8 -> Log.e(TAG, "   ↳ Connection terminated")
                    22 -> Log.e(TAG, "   ↳ Write not permitted")
                    else -> Log.e(TAG, "   ↳ Check device logs for details")
                }
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
                        // Pull latest queued packet as fallback, even if notification is delayed/missed.
                        gatt.readCharacteristic(dataChar)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "❌ Security exception enabling notifications: ${e.message}")
                    }

                    val peer = PeerInfo(address = address, gatt = gatt)
                    discoveredPeers[address] = peer
                    onPeerConnected?.invoke(peer)
                    
                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    } catch (e: SecurityException) { }
                } else {
                    Log.w(TAG, "❌ Data characteristic NOT FOUND on $address")
                    Log.w(TAG, "   Expected UUID: ${BleAdvertiser.SOS_DATA_CHAR_UUID}")
                    Log.w(TAG, "   This device may not be a Saarthi node")
                }
            } else {
                Log.w(TAG, "❌ SNM service NOT FOUND on $address")
                Log.w(TAG, "   Expected Service UUID: ${BleAdvertiser.SOS_SERVICE_UUID}")
                Log.w(TAG, "   This device may not be a Saarthi node or has different configuration")
                try {
                    gatt.disconnect()
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception disconnecting: ${e.message}")
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val address = gatt?.device?.address ?: return
            val data = characteristic?.value
            if (characteristic?.uuid == BleAdvertiser.SOS_DATA_CHAR_UUID && data != null) {
                Log.d(TAG, "Received SNM notification from $address (${data.size} bytes)")
                onPacketReceived?.invoke(data, address)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            handleCharacteristicRead(gatt, characteristic, value)
        }

        private fun handleCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            value: ByteArray?
        ) {
            val address = gatt?.device?.address ?: return
            if (characteristic?.uuid == BleAdvertiser.SOS_DATA_CHAR_UUID && value != null && value.isNotEmpty()) {
                Log.d(TAG, "Received SNM read payload from $address (${value.size} bytes)")
                onPacketReceived?.invoke(value, address)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            val address = gatt?.device?.address ?: "unknown"
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Successfully wrote to $address")
            } else {
                Log.e(TAG, "Failed to write to $address, status: $status")
            }
        }
    }

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
                Log.w(TAG, "❌ MISSING: BLUETOOTH_CONNECT permission (Android 12+)")
                return false
            }
            if (!hasScan) {
                Log.w(TAG, "❌ MISSING: BLUETOOTH_SCAN permission (Android 12+)")
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

    private fun isBluetoothAvailable(): Boolean {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "❌ FATAL: Bluetooth hardware not supported on this device!")
            return false
        }
        if (!bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "❌ ERROR: Bluetooth is DISABLED — user must enable it in Settings")
            return false
        }
        return true
    }
}
