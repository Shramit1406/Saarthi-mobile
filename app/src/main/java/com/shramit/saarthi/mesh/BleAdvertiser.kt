package com.shramit.saarthi.mesh

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

/**
 * BLE Peripheral role — advertises this device as a Saarthi mesh node.
 * 
 * Sets up a GATT server with a custom SOS service UUID.
 * Other scanning devices can discover this node, connect, and exchange SOS packets.
 * 
 * Inspired by BitChat's dual-role BLE architecture where each device
 * acts as both central and peripheral simultaneously.
 */
class BleAdvertiser(private val context: Context) {

    companion object {
        private const val TAG = "BleAdvertiser"
        
        // Saarthi Native Mesh (SNM) Service UUID
        val SOS_SERVICE_UUID: UUID = UUID.fromString("8C2D3E1A-9B4F-4C5A-9BDE-8E1D2C3A4B5C")
        
        // SNM Characteristic for all data exchange (Read/Write/Notify)
        val SOS_DATA_CHAR_UUID: UUID = UUID.fromString("D1B2C3A4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
        
        // SNM Manufacturer ID (Using 0xFFFF for private/internal prototyping)
        private const val SNM_MANUFACTURER_ID = 0xFFFF
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var isAdvertising = false
    private var dataCharacteristic: BluetoothGattCharacteristic? = null
    private var myNodeHash: Int = 0
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()

    // Buffer of outgoing packets for connected peers to read
    private var outboundPacketData: ByteArray? = null

    // Callback for when a packet is written to us (received via mesh)
    var onPacketReceived: ((ByteArray, BluetoothDevice?) -> Unit)? = null

    // Device role bytes
    private var roleValue: ByteArray = byteArrayOf(0x00) // 0x00 = citizen, 0x01 = police

    fun setPoliceRole(isPolice: Boolean) {
        roleValue = if (isPolice) byteArrayOf(0x01) else byteArrayOf(0x00)
    }

    fun setNodeId(nodeId: String) {
        myNodeHash = nodeId.hashCode()
        Log.d(TAG, "My Node Hash: $myNodeHash")
    }

    /**
     * Start advertising as a Saarthi mesh node and set up the GATT server.
     */
    fun startAdvertising(scope: CoroutineScope) {
        if (!hasPermissions()) {
            Log.e(TAG, "❌ Cannot advertise — missing Bluetooth permissions")
            return
        }

        if (!isBluetoothAvailable()) {
            Log.e(TAG, "❌ Cannot advertise — Bluetooth unavailable")
            return
        }

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "❌ BLE advertising not supported on this device")
            return
        }

        setupGattServer()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // Primary Discovery Data: Service UUID + Manufacturer Identity (Hash)
        // We put BOTH in the primary packet to ensure sub-1s identification.
        val hashBytes = ByteBuffer.allocate(4).putInt(myNodeHash).array()
        
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SOS_SERVICE_UUID))
            .addManufacturerData(SNM_MANUFACTURER_ID, hashBytes)
            .build()
            
        // Scan Response: Not needed for identity anymore, but kept for future expansion
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .build()

        try {
            advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
            isAdvertising = true
            Log.d(TAG, "Started SNM advertising (UUID + Manufacturer Hash)")
            
            // Start refresh cycle to prevent silent timeouts
            startRefreshCycle(scope)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SNM advertising: ${e.message}")
        }
    }

    private var refreshJob: Job? = null
    private fun startRefreshCycle(scope: CoroutineScope) {
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.Main) {
            while (isAdvertising) {
                delay(60_000) // Restart every minute
                if (isAdvertising) {
                    Log.d(TAG, "SNM Refresh Cycle: Restarting advertiser...")
                    stopAdvertising()
                    startAdvertising(scope)
                }
            }
        }
    }

    /**
     * Set up the GATT server with SOS service and characteristics.
     */
    private fun setupGattServer() {
        if (!hasPermissions()) return

        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            if (gattServer == null) {
                Log.e(TAG, "Failed to open GATT server")
                return
            }

            val service = BluetoothGattService(
                SOS_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            // Consolidated Data Characteristic — READ/WRITE/NOTIFY
            val dataChar = BluetoothGattCharacteristic(
                SOS_DATA_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or 
                        BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            // CRITICAL: Add Client Characteristic Configuration Descriptor (CCCD)
            // This is MANDATORY for Android peers to subscribe to notifications.
            val cccd = BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"),
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            dataChar.addDescriptor(cccd)

            dataCharacteristic = dataChar
            service.addCharacteristic(dataChar)

            gattServer?.addService(service)
            Log.d(TAG, "GATT server set up with SNM service and CCCD")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up GATT server: ${e.message}", e)
        }
    }

    /**
     * Queue a packet for connected peers to read.
     */
    fun queueOutboundPacket(data: ByteArray) {
        outboundPacketData = data
        Log.d(TAG, "Queued outbound packet (${data.size} bytes)")
        
        try {
            // Notify all connected peers that data is available.
            val characteristic = dataCharacteristic
            if (characteristic != null && gattServer != null) {
                characteristic.value = data
                var notified = 0
                for ((_, device) in connectedDevices) {
                    @Suppress("DEPRECATION")
                    val success = gattServer?.notifyCharacteristicChanged(device, characteristic, false) ?: false
                    if (success) notified++
                }
                Log.d(TAG, "Notified $notified/${connectedDevices.size} connected peer(s) of new packet")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception notifying peers: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying peers: ${e.message}")
        }
    }

    /**
     * Stop advertising and close the GATT server.
     */
    fun stopAdvertising() {
        if (!hasPermissions()) return
        try {
            refreshJob?.cancel()
            if (isAdvertising) {
                advertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
            }
            connectedDevices.clear()
            gattServer?.close()
            gattServer = null
            Log.d(TAG, "Stopped SNM advertising")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping advertising: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "✅ SNM advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "❌ SNM advertising FAILED with error code: $errorCode")
            when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> Log.e(TAG, "   ↳ Advertising data is too large")
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> Log.e(TAG, "   ↳ Too many concurrent advertisers")
                ADVERTISE_FAILED_ALREADY_STARTED -> Log.e(TAG, "   ↳ Already advertising")
                ADVERTISE_FAILED_INTERNAL_ERROR -> Log.e(TAG, "   ↳ Internal BLE error — restart Bluetooth")
                else -> Log.e(TAG, "   ↳ Unknown error — check system Bluetooth logs")
            }
            isAdvertising = false
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            when (newState) {
                BluetoothGattServer.STATE_CONNECTED -> {
                    Log.d(TAG, "Device connected: ${device?.address}")
                    device?.address?.let { connectedDevices[it] = device }
                }
                BluetoothGattServer.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Device disconnected: ${device?.address}")
                    device?.address?.let { connectedDevices.remove(it) }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (!hasPermissions()) return

            try {
                if (characteristic?.uuid == SOS_DATA_CHAR_UUID) {
                    val data = outboundPacketData ?: ByteArray(0)
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        if (offset < data.size) data.copyOfRange(offset, data.size) else ByteArray(0)
                    )
                } else {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_FAILURE, 0, null
                    )
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception in read request: ${e.message}")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (!hasPermissions()) return

            try {
                if (characteristic?.uuid == SOS_DATA_CHAR_UUID && value != null) {
                    Log.d(TAG, "Received packet write from ${device?.address} (${value.size} bytes)")
                    onPacketReceived?.invoke(value, device)

                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null
                        )
                    }
                } else if (responseNeeded) {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_FAILURE, 0, null
                    )
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception in write request: ${e.message}")
            }
        }
    }

    private fun hasPermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val hasConnect = ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            val hasAdvertise = ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
            
            if (!hasConnect) {
                Log.w(TAG, "❌ MISSING: BLUETOOTH_CONNECT permission")
            }
            if (!hasAdvertise) {
                Log.w(TAG, "❌ MISSING: BLUETOOTH_ADVERTISE permission")
            }
            
            hasConnect && hasAdvertise
        } else {
            val hasLocation = ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            if (!hasLocation) {
                Log.w(TAG, "❌ MISSING: ACCESS_FINE_LOCATION permission")
            }
            
            hasLocation
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
