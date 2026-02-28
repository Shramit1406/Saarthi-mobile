# Bluetooth Connection Troubleshooting - Step by Step

## If Mesh Status Shows: "❌ Error starting mesh hardware"

### Step 1: Check Basic Requirements (2 minutes)

**On BOTH phones**, go to Settings and verify:

```
☐ Bluetooth → ON (toggle blue/enabled)
☐ Location Services → ON
  - Settings → Location → Toggle ON
  - Allow "High Accuracy" mode
☐ App Permissions → Check App Info
  - Settings → Apps → Saarthi
  - Permissions → Grant ALL:
    □ Bluetooth (all options)
    □ Location (all options)
    □ Notifications
```

**If any are OFF/not granted**:
1. Go back to the app
2. Allow when prompted OR
3. Close and reopen app to trigger permission dialog

---

## Step 2: Rebuild & Reinstall (5 minutes)

If you made code changes:

```powershell
cd e:\Saarthi

# Clean build
.\gradlew clean assembleDebug

# Uninstall old version
adb uninstall com.shramit.saarthi

# Deploy fresh
adb install app/build/outputs/apk/debug/app-debug.apk

# Launch app
adb shell am start -n com.shramit.saarthi/.MainActivity
```

---

## Step 3: Watch the Logs (Real-time Diagnostics)

Open **2 PowerShell windows** - one for each phone:

**Terminal 1 (Phone A - User)**:
```powershell
adb devices  # Find first device ID
adb -s DEVICE_ID_A logcat -s "BleScanner:D" "BleAdvertiser:D" "MeshManager:D" "*:E"
```

**Terminal 2 (Phone B - Police)**:
```powershell
adb devices  # Find second device ID
adb -s DEVICE_ID_B logcat -s "BleScanner:D" "BleAdvertiser:D" "MeshManager:D" "*:E"
```

Now launch the app on both phones and **watch the logs as they appear**.

---

## What to Look For in Logs

### ✅ SUCCESS Pattern (What You Want to See):

**Phone A & B (Both should show this)**:
```
D/BleAdvertiser: ✅ SNM advertising started successfully
D/MeshManager: Mesh initialized: USER_ABC123 (CITIZEN)
D/BleScanner: ✅ Started Hardware BLE scanning
D/BleScanner: 🔍 Node Found: AA:BB:CC:DD:EE:FF (Hash: -1234567, RSSI: -45)
D/BleScanner: 👑 Election WON
D/BleScanner: 🔌 Initiating GATT connection to AA:BB:CC:DD:EE:FF...
D/BleScanner: Connected to AA:BB:CC:DD:EE:FF, requesting MTU...
D/BleScanner: ✅ SNM service FOUND on AA:BB:CC:DD:EE:FF — peer ready
D/MeshManager: 🔗 1 peer(s) connected
```

**→ If you see this, mesh is working! Now test SOS.**

---

### ❌ FAILURE Pattern 1: Permission Issue

**You'll see**:
```
W/BleScanner: ❌ MISSING: BLUETOOTH_SCAN permission (Android 12+)
W/BleScanner: ❌ MISSING: BLUETOOTH_CONNECT permission (Android 12+)
W/BleAdvertiser: ❌ MISSING: BLUETOOTH_ADVERTISE permission
E/BleScanner: ❌ Cannot scan — missing Bluetooth permissions
```

**Fix**:
1. Go to phone Settings → Apps → Saarthi → Permissions
2. Enable all Bluetooth and Location permissions
3. Restart app

---

### ❌ FAILURE Pattern 2: Bluetooth Disabled

**You'll see**:
```
E/BleAdvertiser: ❌ ERROR: Bluetooth is DISABLED — user must enable it in Settings
E/BleScanner: ❌ ERROR: Bluetooth is DISABLED — user must enable it in Settings
```

**Fix**:
1. Go to Settings → Bluetooth
2. Toggle Bluetooth ON
3. Restart app

---

### ❌ FAILURE Pattern 3: Bluetooth Not Supported

**You'll see**:
```
E/BleScanner: ❌ FATAL: Bluetooth hardware not supported on this device!
E/BleAdvertiser: ❌ FATAL: Bluetooth hardware not supported on this device!
E/BleScanner: ❌ BLE scanner not available on this device
```

**Fix**: Device doesn't support Bluetooth LE
- Try using a different phone
- Check device specs (should have BLE support in 2016+)

---

### ❌ FAILURE Pattern 4: Service Discovery Failed

**You'll see**:
```
D/BleScanner: 🔍 Node Found: AA:BB:CC:DD:EE:FF
D/BleScanner: 🔌 Initiating GATT connection to AA:BB:CC:DD:EE:FF...
D/BleScanner: Connected to AA:BB:CC:DD:EE:FF, requesting MTU...
E/BleScanner: ❌ Service discovery FAILED on AA:BB:CC:DD:EE:FF (status: 2)
E/BleScanner:    ↳ GATT_FAILED (generic error — try restarting Bluetooth)
```

**Fix**:
1. Restart Bluetooth on both phones:
   - Settings → Bluetooth → Toggle OFF (wait 3 sec) → ON
2. Close app completely
3. Reopen app on both phones
4. Wait 10 seconds for discovery

---

### ❌ FAILURE Pattern 5: Service UUID Mismatch

**You'll see**:
```
D/BleScanner: ✅ SNM service FOUND on AA:BB:CC:DD:EE:FF
E/BleScanner: ❌ Data characteristic NOT FOUND on AA:BB:CC:DD:EE:FF
E/BleScanner:    Expected UUID: D1B2C3A4-E5F6-4A5B-8C9D-0E1F2A3B4C5D
E/BleScanner:    This device may not be a Saarthi node
```

**Fix**:
- The device found is not a Saarthi node
- Only test with two phones running this app
- Verify both apps have identical UUIDs

---

## Step 4: Run Demo Mode (No Hardware Test)

If you're having BLE issues but want to test the app logic:

```powershell
# In PowerShell, launch app:
adb shell am start -n com.shramit.saarthi/.CabActivity

# Then long-click "Mesh Network" header on phone
# Tap again to enable Demo Mode
# Toast shows: "Mesh Demo Mode: ON"
```

**Demo Mode**:
- ✅ Simulates peer discovery (no actual BLE)
- ✅ Simulates SOS packet exchange
- ✅ Tests UI logic
- ✅ No Bluetooth hardware needed

Use this to verify app works before debugging hardware.

---

## Step 5: Full Diagnostic Report

If you're still stuck, gather this information:

```powershell
# Get full system info
adb shell getprop | grep -i bluetooth

# Get BLE support
adb shell getprop ro.bluetooth.library_name

# Get Android version
adb shell getprop ro.build.version.sdk_int

# Get all logs from last 5 minutes
adb logcat -d > bluetooth_logs.txt

# View
Get-Content bluetooth_logs.txt | Select-String -Pattern "Ble|Mesh|Bluetooth"
```

Save output and share if needed.

---

## Common Issues Quick Reference

| Symptom | Check | Fix |
|---------|-------|-----|
| "searching for peers" forever | Bluetooth logs for ❌ errors | Grant permissions + restart BLE |
| Only 1 phone shows peer | Both apps running? | Launch app on 2nd phone |
| Connection drops | Device logs show status codes | Restart Bluetooth completely |
| "Service not found" | Are both phones Saarthi apps? | Verify same APK on both |
| Very slow (>30 sec to connect) | RSSI value in logs | Move phones closer |

---

## Emergency Reset

If nothing works:

```powershell
# Uninstall completely
adb uninstall com.shramit.saarthi

# Clear Bluetooth cache
adb shell pm clear com.android.bluetooth

# Restart Bluetooth
adb shell svc bluetooth disable
adb shell svc bluetooth enable

# Wait 5 seconds
Start-Sleep -Seconds 5

# Reinstall
adb install app/build/outputs/apk/debug/app-debug.apk

# Grant permissions
adb shell pm grant com.shramit.saarthi android.permission.BLUETOOTH_SCAN
adb shell pm grant com.shramit.saarthi android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.shramit.saarthi android.permission.BLUETOOTH_ADVERTISE
adb shell pm grant com.shramit.saarthi android.permission.ACCESS_FINE_LOCATION

# Launch
adb shell am start -n com.shramit.saarthi/.MainActivity
```

---

## If Still Stuck

1. Share the log file: `bluetooth_logs.txt`
2. Tell me:
   - What error text appears on screen?
   - What's in the logs (use grep patterns above)?
   - Android version on both phones?
   - Are phones the same model or different?
3. Try with different phone pairs

