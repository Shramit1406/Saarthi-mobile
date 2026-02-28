
package com.shramit.saarthi.mesh

import android.content.Context
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Core mesh routing engine for Saarthi SOS.
 *
 * Manages Saarthi Native Mesh (SNM) BLE roles:
 * - SOS packet origination and broadcast
 * - Multi-hop relay with TTL decrement
 * - Packet deduplication (SNM-style)
 * - Police node detection
 * - Peer management
 */
class MeshManager(private val context: Context) {

    companion object {
        private const val TAG = "MeshManager"
        private const val MAX_SEEN_PACKETS = 500  // Dedup cache size
        private const val PACKET_EXPIRY_MS = 300_000L  // 5 minutes
    }

    private val advertiser = BleAdvertiser(context)
    private val scanner = BleScanner(context)
    private var meshScope: CoroutineScope? = null

    // Deduplication: messageId -> timestamp
    private val seenPackets = ConcurrentHashMap<String, Long>()

    // Our identity
    private var nodeId: String = "NODE_UNKNOWN"
    private var isPolice: Boolean = false

    // Status
    var isRunning: Boolean = false
        private set
        
    var isDemoMode: Boolean = false
        private set

    private var lastMeshStatus: String = "⌛ Initializing mesh..."

    // Callbacks
    var onSOSReceived: ((SOSPacket) -> Unit)? = null
    var onSOSRelayed: ((SOSPacket) -> Unit)? = null
    var onPeerCountChanged: ((Int) -> Unit)? = null
    
    var onMeshStatusChanged: ((String) -> Unit)? = null
        set(value) {
            field = value
            // Immediately notify new subscriber of current state
            value?.invoke(lastMeshStatus)
        }

    private fun updateStatus(status: String) {
        lastMeshStatus = status
        Log.d(TAG, "Mesh Status: $status")
        onMeshStatusChanged?.invoke(status)
    }

    /**
     * Initialize the mesh with this device's identity.
     */
    fun initialize(nodeId: String, isPolice: Boolean) {
        this.nodeId = nodeId
        this.isPolice = isPolice

        advertiser.setPoliceRole(isPolice)
        advertiser.setNodeId(nodeId)
        scanner.setNodeHash(nodeId.hashCode())

        // Wire up advertiser callbacks
        advertiser.onPacketReceived = { data, device ->
            handleIncomingPacket(data, device?.address ?: "unknown")
        }

        // Wire up scanner callbacks
        scanner.onPacketReceived = { data, address ->
            handleIncomingPacket(data, address)
        }

        scanner.onPeerConnected = { peer ->
            Log.d(TAG, "Peer connected: ${peer.address}")
            onPeerCountChanged?.invoke(scanner.peerCount)
            updateStatus("🔗 ${scanner.peerCount} peer(s) connected")
        }

        scanner.onPeerDisconnected = { address ->
            Log.d(TAG, "Peer disconnected: $address")
            onPeerCountChanged?.invoke(scanner.peerCount)
            updateStatus("🔗 ${scanner.peerCount} peer(s) connected")
        }

        scanner.onNearbyPeersChanged = { count ->
            if (scanner.peerCount == 0) {
                if (count > 0) {
                    updateStatus("📡 $count node(s) nearby, connecting...")
                } else {
                    updateStatus("🟢 Mesh active — scanning...")
                }
            }
        }

        Log.d(TAG, "Mesh initialized: $nodeId (${if (isPolice) "POLICE" else "CITIZEN"})")
    }

    /**
     * Start the mesh network (both advertising and scanning).
     */
    fun start() {
        if (isRunning) {
            // Already running, but ensure UI gets the current state
            updateStatus("🟢 Mesh active — searching for peers...")
            return
        }

        try {
            // Recreate scope on every start to ensure fresh lifecycle
            meshScope?.cancel()
            meshScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            val scope = meshScope!!

            if (isDemoMode) {
                startDemoSimulation(scope)
            } else {
                advertiser.startAdvertising(scope)
                scanner.startContinuousScanning(scope)
            }
            isRunning = true

            // Start dedup cache cleanup
            scope.launch {
                while (isActive) {
                    cleanupSeenPackets()
                    delay(60_000) // Cleanup every minute
                }
            }

            updateStatus("🟢 Mesh active — searching for peers...")
            Log.d(TAG, "Mesh started (demo=$isDemoMode)")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting mesh: ${e.message}", e)
            updateStatus("❌ Error starting mesh hardware")
        }
    }

    /**
     * Toggles Demo Mode (Mock Mesh).
     */
    fun toggleDemoMode(enabled: Boolean) {
        val wasRunning = isRunning
        if (wasRunning) stop()
        isDemoMode = enabled
        if (wasRunning) start()
    }

    private fun startDemoSimulation(scope: CoroutineScope) {
        scope.launch {
            Log.d(TAG, "Demo simulation started...")
            updateStatus("⌛ Initializing demo...")
            delay(3000)
            if (!isRunning || !isDemoMode) return@launch
            
            // Simulate finding a peer
            onPeerCountChanged?.invoke(1)
            updateStatus("🔗 1 peer(s) connected (DEMO)")
            
            // If we are police, simulate receiving an SOS after 10s
            if (isPolice) {
                delay(7000)
                if (!isRunning || !isDemoMode) return@launch
                val mockSOS = SOSPacket(
                    senderId = "CITIZEN_DEMO_42",
                    latitude = 28.5355, // Mock coords
                    longitude = 77.3910,
                    hopPath = mutableListOf("CITIZEN_DEMO_42", "RELAY_NODE")
                )
                handleSOSAlert(mockSOS)
            }
        }
    }

    /**
     * Send an SOS alert into the mesh.
     * This creates a new SOSPacket and broadcasts it to all connected peers.
     */
    fun sendSOS(latitude: Double, longitude: Double) {
        try {
            val packet = SOSPacket(
                senderId = nodeId,
                latitude = latitude,
                longitude = longitude,
                ttl = SOSPacket.DEFAULT_TTL,
                hopPath = mutableListOf(nodeId)
            )

            // Mark as seen (don't relay our own packet back)
            seenPackets[packet.messageId] = System.currentTimeMillis()

            val data = packet.toBytes()

            // Broadcast via scanner (write to connected peers)
            scanner.broadcastPacket(data)

            // Also queue on advertiser (for peers that connect to us)
            advertiser.queueOutboundPacket(data)

            updateStatus("📡 SOS broadcast to ${scanner.peerCount} peer(s)")
            Log.d(TAG, "SOS sent: $packet")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SOS: ${e.message}", e)
            updateStatus("❌ Error sending SOS")
            throw e // Re-throw for caller to handle
        }
    }

    /**
     * Handle an incoming packet from any source (advertiser or scanner).
     */
    private fun handleIncomingPacket(data: ByteArray, fromAddress: String) {
        val packet = SOSPacket.fromBytes(data) ?: run {
            Log.w(TAG, "Failed to parse packet from $fromAddress")
            return
        }

        Log.d(TAG, "Received packet from $fromAddress: $packet")

        // Deduplication check
        if (seenPackets.containsKey(packet.messageId)) {
            Log.d(TAG, "Duplicate packet ${packet.messageId}, ignoring")
            return
        }
        seenPackets[packet.messageId] = System.currentTimeMillis()

        when (packet.type) {
            SOSPacket.TYPE_SOS_ALERT -> handleSOSAlert(packet)
            SOSPacket.TYPE_SOS_ACK -> handleSOSAck(packet)
            SOSPacket.TYPE_HEARTBEAT -> { /* Just for peer discovery, already handled */ }
            SOSPacket.TYPE_POLICE_BEACON -> { /* Police presence notification */ }
        }
    }

    /**
     * Handle an incoming SOS alert.
     * If we're police, process it. Otherwise, relay it.
     */
    private fun handleSOSAlert(packet: SOSPacket) {
        if (isPolice) {
            // We're a police node — this SOS has reached its destination!
            Log.d(TAG, "🚨 SOS RECEIVED BY POLICE: ${packet.senderId} at (${packet.latitude}, ${packet.longitude})")
            Log.d(TAG, "   Hop path: ${packet.hopPath.joinToString(" → ")}")
            Log.d(TAG, "   Hops taken: ${packet.hopCount}")

            onSOSReceived?.invoke(packet)

            // Send acknowledgement back through mesh
            sendAcknowledgement(packet)
        } else {
            // We're a citizen relay node — forward the packet
            relayPacket(packet)
        }

        // Always notify even relay nodes (for UI status updates)
        onSOSReceived?.invoke(packet)
    }

    /**
     * Relay an SOS packet: decrement TTL, add ourselves to path, rebroadcast.
     */
    private fun relayPacket(packet: SOSPacket) {
        val relayPacket = packet.createRelayPacket(nodeId) ?: run {
            Log.d(TAG, "Packet TTL expired, not relaying: ${packet.messageId}")
            return
        }

        val data = relayPacket.toBytes()

        // Broadcast to all connected peers
        scanner.broadcastPacket(data)
        advertiser.queueOutboundPacket(data)

        onSOSRelayed?.invoke(relayPacket)
        onMeshStatusChanged?.invoke("🔄 Relayed SOS from ${packet.senderId} (TTL: ${relayPacket.ttl})")
        Log.d(TAG, "Relayed packet: $relayPacket")
    }

    /**
     * Handle an SOS acknowledgement from police.
     */
    private fun handleSOSAck(packet: SOSPacket) {
        Log.d(TAG, "✅ SOS Acknowledged by police for sender: ${packet.senderId}")
        onSOSReceived?.invoke(packet)
    }

    /**
     * Send an acknowledgement that police received the SOS.
     */
    private fun sendAcknowledgement(originalPacket: SOSPacket) {
        val ackPacket = SOSPacket(
            type = SOSPacket.TYPE_SOS_ACK,
            senderId = nodeId,
            latitude = originalPacket.latitude,
            longitude = originalPacket.longitude,
            ttl = SOSPacket.DEFAULT_TTL,
            hopPath = mutableListOf(nodeId),
            isAcknowledgement = true
        )

        seenPackets[ackPacket.messageId] = System.currentTimeMillis()
        val data = ackPacket.toBytes()
        scanner.broadcastPacket(data)
        advertiser.queueOutboundPacket(data)

        Log.d(TAG, "Sent SOS acknowledgement")
    }

    /**
     * Clean up expired entries from the deduplication cache.
     */
    private fun cleanupSeenPackets() {
        val now = System.currentTimeMillis()
        val expired = seenPackets.filter { now - it.value > PACKET_EXPIRY_MS }
        expired.forEach { seenPackets.remove(it.key) }

        if (expired.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${expired.size} expired packet IDs")
        }

        // Also cap the size
        if (seenPackets.size > MAX_SEEN_PACKETS) {
            val oldest = seenPackets.entries.sortedBy { it.value }.take(seenPackets.size - MAX_SEEN_PACKETS)
            oldest.forEach { seenPackets.remove(it.key) }
        }
    }

    /**
     * Get current mesh statistics.
     */
    fun getMeshStats(): MeshStats {
        return MeshStats(
            connectedPeers = scanner.peerCount,
            isAdvertising = isRunning,
            isScanning = isRunning,
            nodeId = nodeId,
            isPolice = isPolice,
            seenPacketCount = seenPackets.size
        )
    }

    /**
     * Stop the mesh network and clean up resources.
     */
    fun stop() {
        isRunning = false
        meshScope?.cancel()
        meshScope = null
        advertiser.stopAdvertising()
        scanner.cleanup()
        seenPackets.clear()
        updateStatus("⚫ Mesh stopped")
        Log.d(TAG, "Mesh stopped")
    }

    data class MeshStats(
        val connectedPeers: Int,
        val isAdvertising: Boolean,
        val isScanning: Boolean,
        val nodeId: String,
        val isPolice: Boolean,
        val seenPacketCount: Int
    )
}
