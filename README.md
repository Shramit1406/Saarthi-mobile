My project in simple terms:

1)What this app is
      Saarthi is an Android app.
      Main purpose: send emergency SOS through nearby phones using Bluetooth mesh, even without internet.
2)Language: Kotlin
      Kotlin is the programming language your app is written in (.kt files).
      Think of Kotlin as “the code words” Android understands (through compilation).
3)Build tool: Gradle
      Gradle is the build system that compiles code, packages APK, and manages libraries.
4)Files:
      build.gradle.kts (project-level)
      app/build.gradle.kts (app module)
      settings.gradle.kts
      .kts means Gradle config is written in Kotlin syntax.
5)IDE: Android Studio
      Android Studio is where you edit/run/debug.
      It uses Gradle in the background when you press Build/Run.
      App package / identity
6)App id: com.shramit.saarthi
      This is the unique identity Android uses for install/update.
      Screens (UI)
7)MainActivity.kt: first screen.
8)CabActivity.kt: map + mesh + SOS behavior.
      XML layout files in app/src/main/res/layout/... define how screens look.
      AndroidManifest.xml
      Declares app components and permissions.
      Includes Bluetooth, location, notification permissions.
      Registers activities and foreground service.
      Libraries you use
      androidx core UI/support libs
      play-services-location for location
      osmdroid for map
      nordicsemi ble for BLE operations
      gson for serialization
      coroutines for async/background work
      bouncycastle crypto library (present in project)
      BLE mesh logic (core)
9) BleAdvertiser.kt: phone acts like beacon/server.
10) BleScanner.kt: phone scans/connects to peers.
11) MeshManager.kt: routing + relay + dedup + ACK logic.
12) SOSPacket.kt: packet structure sent over BLE.
13) Service:-
      MeshForegroundService.kt keeps mesh alive while app runs in background.
      Required by Android for long-running Bluetooth work.
      APK and install
      APK path: app/build/outputs/apk/debug/app-debug.apk
      Installed to phones with adb install -r ...
      Git/GitHub
      Git tracks code changes locally.
      GitHub stores remote backup/collaboration copy.
      I committed and pushed changes to main.
