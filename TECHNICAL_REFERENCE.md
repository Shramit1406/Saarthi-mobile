# Saarthi Mesh Network: Technical Quick Reference

## Core API Reference

### MeshManager
**Location**: `app/src/main/java/com/shramit/saarthi/mesh/MeshManager.kt`

#### Main Methods
```kotlin
// Initialize mesh with role
fun initialize(nodeId: String, isPolice: Boolean)

// Start the mesh network
fun start()

// Stop the mesh network
fun stop()

// Send SOS alert
fun sendSOS(latitude: Double, longitude: Double)

// Get mesh statistics
fun getMeshStats(): MeshStats

// Toggle demo mode (for testing without hardware)
fun toggleDemoMode(enabled: Boolean)
```

#### Callbacks
```kotlin
// Called when any SOS/ACK received
var onSOSReceived: ((SOSPacket) -> Unit)?

// Called when relaying an SOS
var onSOSRelayed: ((SOSPacket) -> Unit)?

// Called when peer count changes
var onPeerCountChanged: ((Int) -> Unit)?

// Called when mesh status updates (UI text)
var onMeshStatusChanged: ((String) -> Unit)?
```

#### Example Usage (in CabActivity)
```kotlin
val meshManager = MeshManager(context)
meshManager.initialize(nodeId = "USER_ABC123", isPolice = false)
meshManager.start()

// Listen for SOS
meshManager.onSOSReceived = { packet ->
    Toast.makeText(context, "SOS from ${packet.senderId}", Toast.LENGTH_LONG).show()
}

// Send SOS
meshManager.sendSOS(latitude = 28.5355, longitude = 77.3910)
```

---

### BleScanner (Central Role)
**Location**: `app/src/main/java/com/shramit/saarthi/mesh/BleScanner.kt`

#### Key Properties
```kotlin
val peerCount: Int          // Number of connected peers
```

#### Main Methods
```kotlin
// Start scanning for Saarthi mesh nodes
fun startScanning()

// Stop scanning
fun stopScanning()

// Continuously scan in background
fun startContinuousScanning(scope: CoroutineScope)

// Broadcast a packet to all connected peers
fun broadcastPacket(data: ByteArray)

// Cleanup connections
fun cleanup()
```

#### Callbacks
```kotlin
var onPeerDiscovered: ((PeerInfo) -> Unit)?      // New peer found
var onPeerConnected: ((PeerInfo) -> Unit)?       // Successfully connected
var onPeerDisconnected: ((String) -> Unit)?      // Disconnected
var onPacketReceived: ((ByteArray, String) -> Unit)?  // Data + peer address
var onNearbyPeersChanged: ((Int) -> Unit)?       // Visible node count changed
```

---

### BleAdvertiser (Peripheral Role)
**Location**: `app/src/main/java/com/shramit/saarthi/mesh/BleAdvertiser.kt`

#### Key Properties
```kotlin
val SOS_SERVICE_UUID: UUID = UUID.fromString("8C2D3E1A-9B4F-4C5A-9BDE-8E1D2C3A4B5C")
val SOS_DATA_CHAR_UUID: UUID = UUID.fromString("D1B2C3A4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
```

#### Main Methods
```kotlin
// Start advertising as mesh node
fun startAdvertising(scope: CoroutineScope)

// Stop advertising
fun stopAdvertising()

// Set this device's police role
fun setPoliceRole(isPolice: Boolean)

// Set node ID for identification
fun setNodeId(nodeId: String)

// Queue packet for peers to read
fun queueOutboundPacket(data: ByteArray)
```

#### Callbacks
```kotlin
var onPacketReceived: ((ByteArray, BluetoothDevice?) -> Unit)?  // Peer wrote to us
```

---

### SOSPacket
**Location**: `app/src/main/java/com/shramit/saarthi/mesh/SOSPacket.kt`

#### Packet Types
```kotlin
const val TYPE_SOS_ALERT = 0x01      // Emergency signal
const val TYPE_SOS_ACK = 0x02        // Police confirmation
const val TYPE_HEARTBEAT = 0x03      // Peer discovery
const val TYPE_POLICE_BEACON = 0x04  // Police presence
```

#### Create Packet
```kotlin
val packet = SOSPacket(
    messageId = UUID.randomUUID().toString(),
    type = SOSPacket.TYPE_SOS_ALERT,
    senderId = "USER_ABC123",
    latitude = 28.5355,
    longitude = 77.3910,
    ttl = 5,
    hopPath = mutableListOf("USER_ABC123"),
    isAcknowledgement = false
)
```

#### Serialization
```kotlin
// To bytes (for BLE transmission)
val bytes = packet.toBytes()

// From bytes (when received)
val receivedPacket = SOSPacket.fromBytes(data)

// Create relay packet (decrements TTL, adds hop)
val relayPacket = packet.createRelayPacket(myNodeId)
if (relayPacket != null) {
    // Safe to relay (TTL > 0)
    sendRelayPacket(relayPacket)
}
```

---

### Models

#### User Model
```kotlin
data class User(
    val id: String,              // "USER_A1B2C3D4"
    val name: String = "Citizen",
    val deviceId: String,
    val meshNodeId: String,      // Used in mesh
    val isPolice: Boolean = false,
    val emergencyContact: String,
    val createdAt: Long
)
```

#### Police Model
```kotlin
data class Police(
    val id: String,              // "POLICE_xyz"
    val stationName: String,
    val stationId: String,
    val deviceId: String,
    val jurisdiction: String,
    val meshNodeId: String,      // Used in mesh
    val meshRole: Byte = 0x01,   // 0x01 = police
    val isActive: Boolean
)
```

---

## Integration in CabActivity

### Mesh Initialization
```kotlin
fun startMeshNetwork() {
    val isPolice = isPoliceDevice()
    val nodeId = if (isPolice) "POLICE_${UUID()}" else userId
    
    sosService.initializeMesh(nodeId, isPolice)
    sosService.onMeshStatusChanged { status ->
        runOnUiThread { meshStatusText.text = status }
    }
}
```

### Toggle Police Mode
```kotlin
private fun togglePoliceMode() {
    // Long-click on user ID in UI
    // Switches isPolice flag
    // Requires restarting mesh
}
```

### Send SOS from UI
```kotlin
fun checkLocationAndSendSOS() {
    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
        location?.let { sendSOS(it.latitude, it.longitude) }
    }
}

fun sendSOS(latitude: Double, longitude: Double) {
    sosService.sendSOSToPolice(latitude, longitude)
    // Shows confirmation dialog
}
```

---

## Mesh Packet Flow Diagram

### User → Police (Direct Connection)
```
┌─────────────────────┐
│  Phone A: USER      │
│ ┌─────────────────┐ │
│ │  MeshManager    │ │
│ └────────┬────────┘ │
│          │ sendSOS()│
│     ┌────▼────┐     │
│     │  Create │     │
│     │ SOSPacket  │ │
│     └────┬────┘     │
│          │ toBytes()│
│    ┌─────▼─────┐    │
│    │BleScanner │    │
│    │Broadcast  │    │
│    └─────┬─────┘    │
└─────────┼──────────┘
          │ GattWrite
         │ ▼ 
┌────────────────────┐
│  Phone B: POLICE   │
│ ┌────────────────┐ │
│ │BleAdvertiser   │ │
│ │onCharWrite     │ │
│ └────────┬───────┘ │
│          │ Call    │
│    ┌─────▼──────┐  │
│    │MeshManager │  │
│    │handlePacket  │ │
│    └─────┬──────┘  │
│          │         │
│   ┌──────▼──────┐  │
│   │ isPolice?   │  │
│   │ YES!        │  │
│   └──────┬──────┘  │
│          │ invoke  │
│    ┌─────▼──────┐  │
│    │onSOSReceived │ │
│    │Display Alert │ │
│    └─────────────┘ │
└────────────────────┘
```

### Acknowledgement (Police → User)
```
┌─────────────────────┐
│  Phone B: POLICE    │
│  ┌───────────────┐  │
│  │ After receiving│  │
│  │ SOS, auto-send│  │
│  │ ACK back      │  │
│  └────────┬──────┘  │
└───────────┼─────────┘
            │ SOSPacket(type=ACK)
            │ toBytes()
            │ BleScanner.broadcast()
            ▼
┌─────────────────────┐
│  Phone A: USER      │
│  ┌───────────────┐  │
│  │ BleAdvertiser │  │
│  │ receives on   │  │
│  │ GATT Write    │  │
│  └────────┬──────┘  │
│           │         │
│    ┌──────▼───────┐ │
│    │ handleSOSAck │ │
│    │ Display: ✅  │ │
│    └──────────────┘ │
└─────────────────────┘
```

---

## Key Properties and State

### Mesh Status States
```
"⌛ Initializing mesh..."
"🟢 Mesh active — searching for peers..."
"📡 N node(s) nearby, connecting..."
"🔗 N peer(s) connected"
"❌ Error starting mesh hardware"
"⚫ Mesh stopped"
"🔄 Relayed SOS from USER_ABC (TTL: 4)"
"📡 SOS broadcast to N peer(s)"
"📨 SOS Acknowledged by police"
```

### Deduplication Cache
```kotlin
seenPackets: Map<messageId, timestamp>
MAX_SEEN_PACKETS = 500
PACKET_EXPIRY_MS = 300_000 (5 minutes)
```

### TTL (Time-To-Live)
```kotlin
DEFAULT_TTL = 5
MAX_TTL = 7

// Each relay decrements:
relayPacket.ttl = originalPacket.ttl - 1

// If ttl <= 0, packet is not relayed
if (relayPacket.ttl > 0) {
    broadcast(relayPacket)
}
```

---

## Common Issues & Solutions

### Issue: "No peers discovered"
```kotlin
// Check:
1. Permissions granted? (BLE, Location)
2. Both phones at < 30m?
3. BleScanner.startContinuousScanning() called?
4. Check logs: adb logcat -s BleScanner:D
```

### Issue: "SOS never reaches Police"
```kotlin
// Check:
1. Police role set correctly? isPolice = true
2. Police phone's MeshManager.start() called?
3. TTL not expired? (Max 5 hops)
4. Check logs for "🚨 SOS RECEIVED BY POLICE"
```

### Issue: "Duplicate SOS alerts"
```kotlin
// Expected behavior:
1. Dedup cache expires after 5 minutes
2. After 5 min, same SOS can be relayed again
3. This is correct (prevents infinite retention)
```

---

## Testing Commands

### Start Demo Mode
```kotlin
meshManager.toggleDemoMode(true)
// Simulates peer discovery + mock SOS
// No hardware required
```

### Check Mesh Stats
```kotlin
val stats = meshManager.getMeshStats()
Log.d("Stats", stats.toString())
// Output: MeshStats(
//   connectedPeers=1,
//   isAdvertising=true,
//   isScanning=true,
//   nodeId=USER_ABC123,
//   isPolice=false,
//   seenPacketCount=5
// )
```

### View BLE Activity
```powershell
adb logcat -s "BleAdvertiser:D" "BleScanner:D" "MeshManager:D"

# Look for:
# - "Peer connected"
# - "onCharacteristicWrite: received"
# - "🚨 SOS RECEIVED BY POLICE"
```

---

## File Dependencies

```
CabActivity.kt (UI)
  ↓
DirectSOSService (Service layer)
  ↓
MeshManager (Orchestrator)
  ├─→ BleAdvertiser (Peripheral)
  ├─→ BleScanner (Central)
  └─→ SOSPacket (Data model)
      ├─→ User.kt (Model)
      └─→ Police.kt (Model)
```

---

## Permissions Required

```xml
<!-- Bluetooth -->
android.permission.BLUETOOTH (API < 31)
android.permission.BLUETOOTH_ADMIN (API < 31)
android.permission.BLUETOOTH_CONNECT (API 31+)
android.permission.BLUETOOTH_SCAN (API 31+)
android.permission.BLUETOOTH_ADVERTISE (API 31+)

<!-- Location (required for BLE scanning) -->
android.permission.ACCESS_FINE_LOCATION
android.permission.ACCESS_COARSE_LOCATION

<!-- Notifications -->
android.permission.POST_NOTIFICATIONS

<!-- Background service -->
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE
```

Request at runtime on API 30+:
```kotlin
ActivityCompat.requestPermissions(this,
    arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION
    ),
    PERMISSION_REQUEST_CODE
)
```

