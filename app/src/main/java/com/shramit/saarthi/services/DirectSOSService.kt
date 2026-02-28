package com.shramit.saarthi.services

import android.content.Context
import android.util.Log
import com.shramit.saarthi.mesh.MeshManager
import com.shramit.saarthi.mesh.SOSPacket

/**
 * SOS Service — refactored from direct Bluetooth Classic to BLE mesh.
 *
 * Acts as interface between UI (MainActivity) and the mesh layer.
 * Sends SOS via mesh and listens for incoming SOS alerts.
 */
class DirectSOSService(private val context: Context) {

    private val TAG = "DirectSOSService"
    private var meshService: MeshForegroundService? = null
    private var isBound = false

    private var pendingNodeId: String? = null
    private var pendingIsPolice: Boolean? = null
    
    // Callbacks to register when service binds
    private var onSOSReceivedListener: ((String, String, Double, Double) -> Unit)? = null
    private var meshStatusCallback: ((String) -> Unit)? = null
    private var peerCountCallback: ((Int) -> Unit)? = null

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as MeshForegroundService.LocalBinder
            meshService = binder.getService()
            isBound = true
            Log.d(TAG, "Bound to MeshForegroundService")
            
            // If we have pending initialization, apply it now
            val nodeId = pendingNodeId
            val isPolice = pendingIsPolice
            if (nodeId != null && isPolice != null) {
                Log.d(TAG, "Applying pending initialization: $nodeId")
                initializeMesh(nodeId, isPolice)
                pendingNodeId = null
                pendingIsPolice = null
            }

            // Apply any pending callbacks
            val manager = meshService?.getMeshManager()
            if (manager != null) {
                wireUpManager(manager)
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            meshService = null
            isBound = false
            Log.d(TAG, "Unbound from MeshForegroundService")
        }
    }

    init {
        Log.d(TAG, "Initializing DirectSOSService - binding to MeshForegroundService")
        val intent = android.content.Intent(context, MeshForegroundService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun wireUpManager(manager: MeshManager) {
        manager.onSOSReceived = { packet ->
            onSOSReceivedListener?.invoke(
                packet.senderId,
                packet.timestamp.toString(),
                packet.latitude,
                packet.longitude
            )
        }
        
        meshStatusCallback?.let { 
            Log.d(TAG, "Applying meshStatusCallback to bound manager")
            manager.onMeshStatusChanged = it 
        }
        peerCountCallback?.let { 
            Log.d(TAG, "Applying peerCountCallback to bound manager")
            manager.onPeerCountChanged = it 
        }
        
        Log.d(TAG, "Manager wired up via Binder")
    }

    fun initializeMesh(nodeId: String, isPolice: Boolean) {
        if (!isBound) {
            Log.d(TAG, "Service not bound yet, deferring initialization for $nodeId")
            pendingNodeId = nodeId
            pendingIsPolice = isPolice
        }

        val intent = android.content.Intent(context, MeshForegroundService::class.java).apply {
            putExtra(MeshForegroundService.EXTRA_NODE_ID, nodeId)
            putExtra(MeshForegroundService.EXTRA_IS_POLICE, isPolice)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Service start command sent for $nodeId")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting MeshForegroundService: ${e.message}")
        }
    }

    fun sendSOSToPolice(latitude: Double, longitude: Double) {
        try {
            val manager = meshService?.getMeshManager()
            if (manager == null) {
                Log.e(TAG, "Mesh Service not bound or manager not ready!")
                throw IllegalStateException("Mesh not initialized")
            }
            Log.d(TAG, "Sending SOS via mesh: lat=$latitude, lon=$longitude")
            manager.sendSOS(latitude, longitude)
            Log.d(TAG, "SOS sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendSOSToPolice: ${e.message}", e)
            throw e // Re-throw so UI can catch it
        }
    }

    fun startListeningForSOS(onSOSReceived: (String, String, Double, Double) -> Unit) {
        this.onSOSReceivedListener = onSOSReceived
        meshService?.getMeshManager()?.let { wireUpManager(it) }
    }

    fun getMeshManager(): MeshManager? = meshService?.getMeshManager()

    fun onMeshStatusChanged(callback: (String) -> Unit) {
        this.meshStatusCallback = callback
        meshService?.getMeshManager()?.let { it.onMeshStatusChanged = callback }
    }

    fun onPeerCountChanged(callback: (Int) -> Unit) {
        this.peerCountCallback = callback
        meshService?.getMeshManager()?.let { it.onPeerCountChanged = callback }
    }

    fun enableDemoMode(enabled: Boolean) {
        meshService?.getMeshManager()?.toggleDemoMode(enabled)
    }

    fun cleanup() {
        if (isBound) {
            context.unbindService(connection)
            isBound = false
        }
    }
}
