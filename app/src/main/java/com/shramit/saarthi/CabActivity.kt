package com.shramit.saarthi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.shramit.saarthi.services.DirectSOSService
import com.shramit.saarthi.services.MeshForegroundService
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.UUID

class CabActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sosService: DirectSOSService
    private lateinit var userIdText: TextView
    private lateinit var policeCountText: TextView
    private lateinit var policeStatusText: TextView
    private lateinit var statusText: TextView
    private lateinit var meshStatusText: TextView
    private lateinit var meshPeerCountText: TextView
    private lateinit var mapView: MapView
    private var myLocationMarker: Marker? = null
    private var isFirstFix = true

    private var userId: String = ""
    private val PERMISSION_REQUEST = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize OSMDroid configuration BEFORE setting content view
        Configuration.getInstance().load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
        Configuration.getInstance().userAgentValue = applicationContext.packageName
        
        setContentView(R.layout.activity_cab)

        // Load or generate stable User ID
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        userId = prefs.getString("stable_user_id", "") ?: ""
        if (userId.isEmpty()) {
            userId = "USER_" + UUID.randomUUID().toString().substring(0, 8).uppercase()
            prefs.edit().putString("stable_user_id", userId).apply()
        }
        userIdText = findViewById(R.id.userId)
        policeCountText = findViewById(R.id.policeCount)
        policeStatusText = findViewById(R.id.policeStatus)
        statusText = findViewById(R.id.statusText)
        meshStatusText = findViewById(R.id.meshStatus)
        meshPeerCountText = findViewById(R.id.meshPeerCount)
        mapView = findViewById(R.id.mapView)

        val sosButton = findViewById<CardView>(R.id.sosButton)
        val isPoliceMode = isPoliceDevice()
        if (isPoliceMode) {
            sosButton.visibility = android.view.View.GONE
        }
        
        // Initialize Map
        setupMap()

        // Set user ID and hidden toggle
        userIdText.text = if (isPoliceMode) "POLICE_NODE" else userId
        userIdText.setOnLongClickListener {
            togglePoliceMode()
            true
        }

        findViewById<android.view.View>(R.id.meshNetworkHeader).setOnLongClickListener {
            val isDemo = !sosService.getMeshManager()!!.isDemoMode
            sosService.enableDemoMode(isDemo)
            Toast.makeText(this, "Mesh Demo Mode: ${if(isDemo) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
            true
        }

        // Initialize services
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sosService = DirectSOSService(this)
        setupSOSAlertListener()

        // Show initial status
        statusText.text = "🔍 Checking Bluetooth & Permissions..."
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
        
        // Check permissions first, then start mesh
        checkPermissions()

        // SOS button click
        sosButton.setOnClickListener {
            if (isPoliceMode) {
                Toast.makeText(this, "Police mode: SOS sending is disabled", Toast.LENGTH_SHORT).show()
            } else {
                checkLocationAndSendSOS()
            }
        }

        // Trigger immediate location fix for startup zoom
        requestStartupLocation()

        if (isPoliceMode) {
            setupPoliceMode()
        } else {
            policeStatusText.text = "✅ Listening for SOS via mesh"
        }
    }
    
    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setBuiltInZoomControls(true)
        mapView.setMultiTouchControls(true)
        
        // Default center to India
        val mapController = mapView.controller
        mapController.setZoom(5.0)
        val startPoint = GeoPoint(20.5937, 78.9629)
        mapController.setCenter(startPoint)

    }

    private fun requestStartupLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    updateLocationOnMap(it.latitude, it.longitude)
                }
            }
        }
    }

    private fun updateLocationOnMap(latitude: Double, longitude: Double) {
        val geoPoint = GeoPoint(latitude, longitude)
        
        // Auto-zoom only on first fix
        if (isFirstFix) {
            mapView.controller.setZoom(18.0)
            mapView.controller.animateTo(geoPoint)
            isFirstFix = false
        }
        
        // Remove old marker
        myLocationMarker?.let { mapView.overlays.remove(it) }
        
        // Add new blue marker
        myLocationMarker = Marker(mapView)
        myLocationMarker?.position = geoPoint
        myLocationMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        myLocationMarker?.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation)
        myLocationMarker?.title = "My Location"
        mapView.overlays.add(myLocationMarker)
        mapView.invalidate()
    }

    private fun plotSOSOnMap(latitude: Double, longitude: Double, senderId: String, hops: Int) {
        val geoPoint = GeoPoint(latitude, longitude)
        
        // Zoom out slightly to show both my location and SOS if possible
        mapView.controller.animateTo(geoPoint)
        if (mapView.zoomLevelDouble > 14) {
            mapView.controller.setZoom(14.0)
        }
        
        val redMarker = Marker(mapView)
        redMarker.position = geoPoint
        redMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        // Red color tint
        val icon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation)?.mutate()
        icon?.setTint(ContextCompat.getColor(this, R.color.purple_500)) // Using an existing red/purple color
        redMarker.icon = icon
        redMarker.title = "🚨 SOS from $senderId"
        redMarker.snippet = "Mesh Relay: $hops hop(s) away"
        
        mapView.overlays.add(redMarker)
        mapView.invalidate()
    }

    /**
     * Start the BLE mesh network.
     */
    private fun startMeshNetwork() {
        val isPolice = isPoliceDevice()
        val nodeId = if (isPolice) "POLICE_${UUID.randomUUID().toString().substring(0, 8)}" else userId

        // Initialize the mesh through SOS service
        sosService.initializeMesh(nodeId, isPolice)

        // Listen for mesh status updates
        sosService.onMeshStatusChanged { status ->
            runOnUiThread {
                meshStatusText.text = status
            }
        }

        sosService.onPeerCountChanged { count ->
            runOnUiThread {
                meshPeerCountText.text = "$count mesh peer(s) connected"
                policeCountText.text = "$count peer(s) in mesh range"
            }
        }

        // Start foreground service
        val serviceIntent = Intent(this, MeshForegroundService::class.java).apply {
            putExtra(MeshForegroundService.EXTRA_NODE_ID, nodeId)
            putExtra(MeshForegroundService.EXTRA_IS_POLICE, isPolice)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        statusText.text = "🟢 Mesh active — ready for SOS"
        statusText.setTextColor(ContextCompat.getColor(this, R.color.purple_500))
    }

    private fun checkLocationAndSendSOS() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {

            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    updateLocationOnMap(it.latitude, it.longitude)
                    sendSOS(it.latitude, it.longitude)
                } ?: run {
                    Toast.makeText(this, "Unable to get location", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "Location permission needed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendSOS(latitude: Double, longitude: Double) {
        val peerCount = sosService.getMeshManager()?.getMeshStats()?.connectedPeers ?: 0

        AlertDialog.Builder(this)
            .setTitle("🚨 Send SOS via Mesh")
            .setMessage(
                "Send emergency alert through BLE mesh network?\n\n" +
                "📡 Connected peers: $peerCount\n" +
                "📍 Location: ${"%.4f".format(latitude)}, ${"%.4f".format(longitude)}\n" +
                "🔄 Max hops: 5\n\n" +
                "Your SOS will relay through nearby phones to reach police."
            )
            .setPositiveButton("YES, SEND SOS") { _, _ ->
                try {
                    // Send via BLE mesh
                    sosService.sendSOSToPolice(latitude, longitude)

                    // Update UI on main thread
                    statusText.text = "📡 SOS broadcast to mesh • Relaying..."
                    statusText.setTextColor(ContextCompat.getColor(this, R.color.purple_500))
                    meshStatusText.text = "🚨 SOS active — broadcasting to $peerCount peer(s)"
                    
                    Toast.makeText(this, "✅ SOS sent successfully!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("SOS", "Error sending SOS: ${e.message}", e)
                    Toast.makeText(this, "❌ Error sending SOS: ${e.message}", Toast.LENGTH_LONG).show()
                    statusText.text = "❌ SOS Send Failed"
                    statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isPoliceDevice(): Boolean {
        // Use SharedPreferences to store role for testing
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean("is_police_mode", false)
    }

    private fun togglePoliceMode() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val current = prefs.getBoolean("is_police_mode", false)
        prefs.edit().putBoolean("is_police_mode", !current).apply()
        
        AlertDialog.Builder(this)
            .setTitle("Role Changed")
            .setMessage("App role changed to ${if (!current) "POLICE" else "CITIZEN"}.\n\nThe app will now restart to apply mesh roles.")
            .setPositiveButton("Restart Now") { _, _ ->
                finish()
                startActivity(intent)
            }
            .setCancelable(false)
            .show()
    }

    private fun setupPoliceMode() {
        // Change UI for police
        userIdText.text = "POLICE_${UUID.randomUUID().toString().substring(0, 8)}"
        findViewById<TextView>(R.id.userType).text = "Police Officer"
        policeStatusText.text = "✅ Listening for SOS via mesh"
    }

    private fun setupSOSAlertListener() {
        sosService.startListeningForSOS { senderId, timestamp, lat, lon ->
            runOnUiThread {
                // Create notification channel if needed (Android 8.0+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        "sos_alerts",
                        "SOS Alerts",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                    channel.description = "Emergency SOS alerts from Saarthi mesh"
                    val notificationManager = getSystemService(android.app.NotificationManager::class.java)
                    notificationManager?.createNotificationChannel(channel)
                }
                
                // Create pending intent to open the app
                val notificationIntent = Intent(this, CabActivity::class.java)
                notificationIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this,
                    0,
                    notificationIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                
                // Show system notification
                val notification = NotificationCompat.Builder(this, "sos_alerts")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("🚨 SOS ALERT from $senderId")
                    .setContentText("Location: ${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()
                
                val notificationManager = NotificationManagerCompat.from(this)
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    notificationManager.notify(System.currentTimeMillis().toInt(), notification)
                }
                
                statusText.text = "🚨 SOS from $senderId"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.purple_500))
                
                // Plot on map
                plotSOSOnMap(lat, lon, senderId, 1) // Assuming 1 hop for now, UI can be updated to include hops

                // Show alert dialog
                AlertDialog.Builder(this)
                    .setTitle("🚨 SOS ALERT RECEIVED")
                    .setMessage(
                        """
                        Citizen ID: $senderId
                        Location: $lat, $lon
                        Time: $timestamp
                        Received via: BLE Mesh
                        
                        Tap to view on map and respond.
                        """.trimIndent()
                    )
                    .setPositiveButton("View Map") { _, _ ->
                        val mapIntent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon")
                        )
                        startActivity(mapIntent)
                    }
                    .setNegativeButton("Acknowledge", null)
                    .show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDetach()
        sosService.cleanup()
        stopService(Intent(this, MeshForegroundService::class.java))
    }
    
    private var permissionDialog: AlertDialog? = null

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        
        // Re-check permissions and dismiss dialog if now granted
        val needed = getNeededPermissions()
        if (needed.isEmpty()) {
            permissionDialog?.dismiss()
            permissionDialog = null
            
            if (isMeshReady()) {
                if (statusText.text.contains("Initializing")) {
                    startMeshNetwork()
                }
            } else {
                showPrerequisiteDialog()
            }
        } else {
            // Only show if not already showing
            if (permissionDialog == null || !permissionDialog!!.isShowing) {
                showMandatoryPermissionDialog()
            }
        }
    }

    private fun getNeededPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissions() {
        val neededPermissions = getNeededPermissions()

        if (neededPermissions.isNotEmpty()) {
            Toast.makeText(this, "⚠️ Requesting permissions...", Toast.LENGTH_LONG).show()
            ActivityCompat.requestPermissions(
                this,
                neededPermissions.toTypedArray(),
                PERMISSION_REQUEST
            )
        } else {
            permissionDialog?.dismiss()
            permissionDialog = null
            
            if (isMeshReady()) {
                if (statusText.text.contains("Initializing") || statusText.text.contains("Checking")) {
                    startMeshNetwork()
                }
            } else {
                showPrerequisiteDialog()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "✅ Permissions granted! Checking Bluetooth...", Toast.LENGTH_SHORT).show()
                permissionDialog?.dismiss()
                permissionDialog = null
                
                if (isMeshReady()) {
                    startMeshNetwork()
                } else {
                    showPrerequisiteDialog()
                }
            } else {
                Toast.makeText(this, "❌ Permissions denied - mesh won't work", Toast.LENGTH_LONG).show()
                showMandatoryPermissionDialog()
            }
        }
    }

    private fun showMandatoryPermissionDialog() {
        if (permissionDialog?.isShowing == true) return

        val missing = getNeededPermissions().joinToString("\n• ") { it.substringAfterLast(".") }

        permissionDialog = AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("Saarthi requires the following permissions to build the offline Mesh Safety Network:\n\n• $missing\n\nWithout these, SOS alerts cannot be sent or received.\n\nPlease grant all permissions to continue.")
            .setCancelable(false)
            .setPositiveButton("GRANT PERMISSIONS") { _, _ ->
                checkPermissions()
            }
            .setNegativeButton("EXIT APP") { _, _ ->
                finishAffinity()
            }
            .create()
        
        permissionDialog?.show()
    }

    private fun isMeshReady(): Boolean {
        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        
        val isBluetoothOn = bluetoothAdapter?.isEnabled == true
        val isGpsOn = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                     locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        
        return isBluetoothOn && isGpsOn
    }

    private fun showPrerequisiteDialog() {
        if (permissionDialog?.isShowing == true) return

        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        
        val isBluetoothOff = bluetoothAdapter?.isEnabled != true
        val isGpsOff = !locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) &&
                      !locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

        val message = StringBuilder("Mesh network requires hardware services to be active:\n\n")
        if (isBluetoothOff) message.append("• Bluetooth is currently OFF\n")
        if (isGpsOff) message.append("• Location Services (GPS) are currently OFF\n")
        message.append("\nPlease enable these to establish the safety mesh.")

        permissionDialog = AlertDialog.Builder(this)
            .setTitle("Mesh Hardware Required")
            .setMessage(message.toString())
            .setCancelable(false)
            .setPositiveButton("SETTINGS") { _, _ ->
                if (isBluetoothOff) {
                    startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                } else {
                    startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            .setNegativeButton("EXIT") { _, _ ->
                finishAffinity()
            }
            .create()
        
        permissionDialog?.show()
    }
}
