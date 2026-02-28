# Saarthi Mesh Network: 2-Phone Demo Guide

## Quick Start (5-10 Minutes)

### Prerequisites Checklist
```
✅ Two Android phones with API 26+ (Android 8.0+)
✅ Both have Bluetooth capability
✅ Both have BLE support (check Settings > System > About Phone)
✅ Bluetooth enabled on both
✅ Location services enabled on both
✅ USB debugger tools or access to adb
```

---

## Phase 1: Build & Deploy (5 minutes)

### Step 1: Build APK
```powershell
# Navigate to workspace
cd e:\Saarthi

# Build debug APK
.\gradlew assembleDebug

# Output location:
# app/build/outputs/apk/debug/app-debug.apk
```

### Step 2: Find Your Phone Identifiers
```powershell
# List connected devices
adb devices

# Output example:
# List of attached devices
# ZZ1234ABCD    device  (Phone A - Device A)
# ZZ5678EFGH    device  (Phone B - Device B)

# Note both device IDs
```

### Step 3: Install on Phone A (User)
```powershell
adb -s ZZ1234ABCD install app/build/outputs/apk/debug/app-debug.apk

# Wait for "Success" message
```

### Step 4: Install on Phone B (Police)
```powershell
adb -s ZZ5678EFGH install app/build/outputs/apk/debug/app-debug.apk

# Wait for "Success" message
```

---

## Phase 2: Configuration (1 minute per phone)

### Phone A - User Role Configuration

1. **Launch the app** → You'll see Saarthi splash screen
2. **Tap "Launch Cab"** → Navigates to CabActivity (main interface)
3. **Identify mode**: 
   - Look at top-left: Should show your User ID (e.g., `USER_A1B2C3D4`)
   - This is the **default** - confirming User role ✓
4. **Verify permissions**: App will request:
   - ✅ Bluetooth Connect (BLE data exchange)
   - ✅ Bluetooth Scan (discover peers)
   - ✅ Location Fine (for SOS coordinates)
   - **Tap "Allow All" or grant each**
5. **Wait for mesh startup**: 
   - UI should show "🟢 Mesh active — searching for peers..."
   - This means advertiser + scanner started ✓

### Phone B - Police Role Configuration

1. **Launch app** → Saarthi splash
2. **Tap "Launch Cab"** → CabActivity
3. **Switch to Police role**:
   - **Long-click on the User ID** in top-left
   - Should toggle to `POLICE_NODE` mode
   - If it doesn't, look for a toggle switch or settings menu
4. **Grant permissions** (same as Phone A)
5. **Mesh startup**: 
   - Should also show "🟢 Mesh active — searching for peers..."
   - Now Police role is active ✓

⚠️ **If you don't see the mesh status line**:
- Check if there's a "Start Mesh" button you need to tap
- Look for mesh controls in the UI
- Check logs: `adb logcat -s MeshManager:D`

---

## Phase 3: Mesh Discovery (10-30 seconds)

### Bring Phones Within Range
- **Optimal distance**: 2-5 meters apart
- **Typical BLE range**: Up to 30 meters (through walls)
- **Poor conditions**: Might need closer proximity

### Monitor Mesh Status

**Phone A (User)**:
```
Expected progression:
"🟢 Mesh active — searching for peers..."
   ↓ (5-10 seconds)
"📡 1 node(s) nearby, connecting..."
   ↓ (3-5 seconds)
"🔗 1 peer(s) connected"
```

**Phone B (Police)**:
```
Same progression as Phone A
"🔗 1 peer(s) connected"
```

**What's happening**:
- Each phone is advertising via BLE Peripheral role
- Each phone is scanning via BLE Central role
- When they find each other, they establish GATT connection
- Peer count increases for both

### Troubleshooting Discovery

| Issue | Check |
|-------|-------|
| Still "searching for peers" after 30s | Bluetooth off? Move closer? Restart mesh? |
| "❌ Error starting mesh hardware" | BLE not supported / Permissions not granted |
| Only 1 phone shows peer | One didn't start mesh properly |

---

## Phase 4: Send SOS (User → Police)

### Step 1: Prepare Phone A (User)

Ensure you see:
```
User ID: USER_XXXX (in top-left)
Mesh Status: 🔗 1 peer(s) connected
```

### Step 2: Trigger SOS

**Method A: Red SOS Button**
```
1. Locate big red circular SOS button (bottom-right area)
2. TAP IT
3. App requests location (may take 3-5 seconds)
```

**Method B: Route Deviation**
```
1. If available, look for "Trigger Deviation" button
2. Choose "🚨 URGENT: SEND SOS"
3. Confirms location and sends
```

**What the app does**:
```
1. Gets current GPS location
2. Shows confirmation dialog:
   - "Send emergency alert through BLE mesh?"
   - Shows location coordinates
   - Shows "Connected peers: 1"
3. You tap "YES, SEND SOS"
4. Broadcasts SOS packet via BLE mesh
```

### Step 3: Phone A SOS Confirmation

You should see:
```
Status Text: "📡 SOS broadcast to mesh • Relaying..."
Mesh Status: "🚨 SOS active — broadcasting to 1 peer(s)"
Map: Red marker appears at your location (SOS point)
```

---

## Phase 5: Police Receives SOS (Phone B)

### Expected Police Response

**Immediately after Phone A sends SOS**, Phone B should display:

```
🚨 EMERGENCY ALERT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
User Location:
  📍 28.5355° N, 77.3910° E (example)
  
User ID: USER_A1B2C3D4

Mesh Path (Relay Hops):
  📡 USER_A1B2C3D4 → POLICE_NODE
  
Status: ✅ RECEIVED (ACK sent)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**UI Changes**:
- Map zooms to SOS location
- Red emergency marker placed on map
- Alert sound/notification may play (depends on implementation)
- Status shows "Mesh Status: 📨 [ACK received from POLICE_NODE]"

### What's Happening Under the Hood

```
Phone A (User)                    Phone B (Police)
│                                 │
├─ Send SOS Packet               │
│  - messageId: UUID1            │
│  - senderId: USER_A1B2C3D4     │
│  - latitude: 28.5355           │
│  - longitude: 77.3910          │
│  - TTL: 5                      │
│  - hopPath: [USER_A1B2C3D4]   │
│                                 │
├─ Broadcast via Scanner         │
│  (write to Phone B GATT)       │
└────────────────────────────>  ├─ Receive packet
                                 ├─ Check: isPolice?
                                 ├─ YES! Process as emergency
                                 ├─ Display alert
                                 │
                                 └─ Create ACK packet
                                    - messageId: UUID2
                                    - type: TYPE_SOS_ACK
                                    - ttl: 5
                                    - Send back to Phone A
                                       │
  ┌──────────────────────────────<─┘
  │
  └─ Receive ACK
     Display: "✅ SOS Acknowledged by police"
```

---

## Phase 6: Verification Checklist

After SOS is sent and received, verify:

```
✅ Phone A Status: "🟢 Mesh active" + red marker at location
✅ Phone B Status: Emergency alert displayed + red marker + "✅ RECEIVED"
✅ Map Updates: Both show SOS location
✅ Peer Count: Both show "1 peer(s) connected"
✅ Logs show: "🚨 SOS RECEIVED BY POLICE: USER_XXXX at (lat, lon)"
```

---

## Advanced Tests (If Phase 6 works)

### Test A: Simple Acknowledgement
```
1. Phone B automatically sends ACK
2. Phone A should receive and display
3. Status changes from "broadcasting" to "acknowledged"
```

### Test B: Multiple SOS
```
1. Send SOS from Phone A
2. Wait 5 seconds for acknowledgement
3. Send another SOS from Phone A
4. Phone B should receive both (different messageIds)
```

### Test C: Police Initiates Transmission
```
1. Look for "Send Message to User" on Phone B
2. Send response/location/status to Phone A
3. Phone A displays Police response
(Depends on implementation)
```

### Test D: Demo Mode (No Hardware)
```
1. Long-click on "Mesh Network" header on either phone
2. Toast shows "Mesh Demo Mode: ON"
3. Mesh simulates:
   - Peer discovery
   - Mock SOS after 10 seconds (if Police)
4. Useful for testing without hardware
```

---

## Monitoring in Real-Time

### View Mesh Logs
```powershell
# Terminal 1: Phone A logs
adb -s ZZ1234ABCD logcat -s MeshManager:D BleScanner:D BleAdvertiser:D

# Terminal 2: Phone B logs
adb -s ZZ5678EFGH logcat -s MeshManager:D BleScanner:D BleAdvertiser:D
```

### Expected Log Output

**Phone A Sending SOS**:
```
D/MeshManager: SOS sent: SOSPacket(
    messageId=...,
    type=0x01,
    senderId=USER_A1B2C3D4,
    latitude=28.5355,
    longitude=77.3910,
    ttl=5,
    hopPath=[USER_A1B2C3D4]
)
D/MeshManager: 📡 SOS broadcast to 1 peer(s)
D/BleScanner: Writing packet to peer ZZ5678EFGH (111 bytes)
```

**Phone B Receiving SOS**:
```
D/BleAdvertiser: onCharacteristicWriteRequest: SOSPacket received
D/MeshManager: Received packet from ZZ1234ABCD: SOSPacket(...)
D/MeshManager: 🚨 SOS RECEIVED BY POLICE: USER_A1B2C3D4 at (28.5355, 77.3910)
D/MeshManager:    Hop path: USER_A1B2C3D4 → POLICE_NODE
D/MeshManager: Sent SOS acknowledgement
```

---

## Troubleshooting Flowchart

```
Are phones discovering each other?
├─ NO →  Move closer (< 2m)
│        └─ BLE off? → Enable Bluetooth
│        └─ Permissions? → Grant all Bluetooth + Location
│        └─ No BLE? → Device not supported
│
└─ YES → Proceed to SOS test
         │
         Can User send SOS?
         ├─ NO →  Location permission denied?
         │        └─ Grant in Settings > Apps > Saarthi > Permissions
         │       
         │        No "SOS" button visible?
         │        └─ Look for RED circle button
         │        └─ Or "Trigger Deviation" button
         │
         └─ YES → Does Police receive?
                  ├─ NO →  Check Phone B logs
                  │        └─ "onPacketReceived" should be called
                  │        └─ isPolice = true?
                  │        └─ Toggle Police mode again
                  │
                  └─ YES → ✅ DEMO SUCCESSFUL!
```

---

## After Successful Demo

### Next Steps

1. **Test Multi-Hop** (if 3+ phones available):
   ```
   Phone A (User) --[5m]--> Phone C (Relay) --[5m]--> Phone B (Police)
   SOS should route through C to reach B
   ```

2. **Test Out-of-Range**:
   ```
   Move phones > 30m apart
   Peer count should drop to 0
   SOS should fail to reach Police
   Move back → Reconnection should happen
   ```

3. **Add Encryption** (future):
   ```
   Modify BleAdvertiser.kt to encrypt packets
   Use BouncyCastle (already in dependencies)
   X25519 + AES-256-GCM
   ```

---

## File Reference

If you need to modify behavior:

| File | Purpose |
|------|---------|
| [MeshManager.kt](app/src/main/java/com/shramit/saarthi/mesh/MeshManager.kt) | Core mesh logic, SOS routing |
| [BleScanner.kt](app/src/main/java/com/shramit/saarthi/mesh/BleScanner.kt) | Central role, peer discovery |
| [BleAdvertiser.kt](app/src/main/java/com/shramit/saarthi/mesh/BleAdvertiser.kt) | Peripheral role, GATT server |
| [SOSPacket.kt](app/src/main/java/com/shramit/saarthi/mesh/SOSPacket.kt) | Packet format, serialization |
| [CabActivity.kt](app/src/main/java/com/shramit/saarthi/CabActivity.kt) | UI, user interaction |
| [User.kt](app/src/main/java/com/shramit/saarthi/models/User.kt) | User model |
| [Police.kt](app/src/main/java/com/shramit/saarthi/models/Police.kt) | Police model |

---

## Success Metrics

✅ Demo is successful when:
1. Two phones discover each other (peer count = 1)
2. User sends SOS → Message appears in Phone A logs
3. Police receives SOS → Alert appears on Phone B
4. Police phone shows sender location on map
5. Police sends acknowledgement → Phone A receives it

**Expected timeline**: 30-60 seconds from launch to successful SOS delivery.

