# Quick Start: Test Bluetooth Fixes (5 Minutes)

## Pre-Test Checklist (1 minute)

**On EACH phone** (must be done FIRST):

```
Settings → Bluetooth
  ✅ Toggle is ON (blue)

Settings → Location  
  ✅ Toggle is ON
  
Settings → Apps → Saarthi → Permissions
  ✅ Bluetooth: All toggles ON
  ✅ Location: All toggles ON
  ✅ Notifications: ON
```

If any are OFF → Turn ON → **Close and reopen Saarthi app**

---

## Deploy APK (2 minutes)

Find your device IDs:
```powershell
adb devices

# Output will show:
# DEVICE_ID_A    device
# DEVICE_ID_B    device
```

Install on both:
```powershell
# Phone A
adb -s DEVICE_ID_A install app/build/outputs/apk/debug/app-debug.apk

# Phone B
adb -s DEVICE_ID_B install app/build/outputs/apk/debug/app-debug.apk

# Wait for both to show "Success"
```

---

## Monitor Logs (2 minutes - Do This Simultaneously)

**Open 2 PowerShell windows side-by-side**

**Window 1 (Phone A)**:
```powershell
adb -s DEVICE_ID_A logcat -s "BleScanner:D" "BleAdvertiser:D" "MeshManager:D" | FindStr /V "^$"
```

**Window 2 (Phone B)**:
```powershell  
adb -s DEVICE_ID_B logcat -s "BleScanner:D" "BleAdvertiser:D" "MeshManager:D" | FindStr /V "^$"
```

Now launch the app on both phones (tap icon on each device).

---

## What to Expect

### First 3 Seconds
```
D/BleAdvertiser: ✅ SNM advertising started successfully
D/MeshManager: Mesh initialized: USER_ABC123
```

### Next 5-10 Seconds
```
D/BleScanner: ✅ Started Hardware BLE scanning
D/BleScanner: 🔍 Node Found: AA:BB:CC:DD:EE:FF (RSSI: -45)
D/BleScanner: 👑 Election WON
D/BleScanner: 🔌 Initiating GATT connection...
D/BleScanner: Connected to AA:BB:CC:DD:EE:FF, requesting MTU...
D/BleScanner: ✅ SNM service FOUND
D/BleScanner: ✅ Enabled SNM notifications
D/MeshManager: 🔗 1 peer(s) connected
```

✅ **If you see this → Mesh is working!**

---

## If You See Errors Instead

### Error: "MISSING: BLUETOOTH_SCAN permission"
```
Fix: Settings → Apps → Saarthi → Permissions → Bluetooth
     Toggle ALL to ON
     Close and reopen app
```

### Error: "Bluetooth is DISABLED"
```
Fix: Settings → Bluetooth
     Toggle ON
     Wait 3 seconds
     Reopen app
```

### Error: "Service discovery FAILED"
```
Fix: Complete Bluetooth restart:
     Settings → Bluetooth → OFF (wait 3 sec)
     Settings → Bluetooth → ON
     Close app, reopen
     Wait 15 seconds for mesh to find peer
```

### Error: "SNM service NOT FOUND"
```
Issue: Device found is NOT a Saarthi app
Fix: Verify you're testing with 2 phones running Saarthi
     (not mixing with other BLE apps)
```

---

## Test SOS (After "1 peer(s) connected")

Once both phones show `🔗 1 peer(s) connected`:

**Phone A (User)**:
1. Tap the large **RED circle** (SOS button)
2. Allow location permission if prompted
3. Tap **"YES, SEND SOS"**

**Phone B (Police)**:
1. Should immediately show emergency alert
2. Map marks sender location
3. Alert shows: "🚨 SOS FROM USER_ABC123"

✅ **If you see the alert → Mesh network demo is successful!**

---

## Expected Timeline

```
0s:    App launches
0-2s:  Advertising starts
3-5s:  Scanning starts
5-10s: Peer discovered
7-15s: GATT connection + service discovery
15s:   "🔗 1 peer(s) connected"
20s+:  Ready to send SOS
```

If not connected after 30s → Troubleshoot with [BLUETOOTH_TROUBLESHOOTING.md](BLUETOOTH_TROUBLESHOOTING.md)

---

## One-Line Diagnostic Log

To capture everything in one file for analysis:

```powershell
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
adb logcat -s "BleScanner:D" "BleAdvertiser:D" "MeshManager:D" > "mesh_log_${timestamp}.txt"

# Run for 60 seconds with both phones in range
# Press Ctrl+C to stop
# Share the file if you need help
```

---

## Success Indicators ✅

When it's working, you'll see:

| Indicator | Location | Meaning |
|-----------|----------|---------|
| "SNM advertising started successfully" | Logs | Phone advertising is working |
| "Started Hardware BLE scanning" | Logs | Phone is scanning for peers |
| "Node Found: AA:BB:CC" | Logs | Peer device discovered! |
| "👑 Election WON" | Logs | Elected to be connection master |
| "SNM service FOUND" | Logs | GATT service discovered |
| "🔗 1 peer(s) connected" | UI + Logs | **Connection successful!** |
| Red SOS received on peer | UI | **Mesh network working!** |

---

## Common Questions

**Q: Why do I see both "Election WON" and "Election LOST"?**
A: Normal! Each phone independently decides who connects. One wins, one loses.

**Q: Why is RSSI showing like -80?**
A: Signal strength (dBm). -45 = strong, -80 = weak. Move phones closer if high.

**Q: Can I test with Demo Mode?**
A: Yes! Long-click "Mesh Network" header → Demo Mode ON
   This simulates peers without hardware (for UI testing).

**Q: Do I need WiFi for mesh to work?**
A: No! BLE mesh works completely offline (that's the point).

**Q: How far apart can phones be?**
A: 30+ meters typical, but:
   - Direct line of sight: 50m+
   - Through walls: 10-20m
   - Metal/obstacles: 5m

---

## If You Get Stuck

1. Check all prerequisites above
2. Run a fresh build: `.\gradlew clean assembleDebug`
3. Review [BLUETOOTH_TROUBLESHOOTING.md](BLUETOOTH_TROUBLESHOOTING.md) - Step 4
4. Try Emergency Reset (last section of troubleshooting guide)
5. Share logs from `mesh_log_*.txt`

