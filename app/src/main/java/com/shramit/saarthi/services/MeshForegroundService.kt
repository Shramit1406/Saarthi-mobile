package com.shramit.saarthi.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shramit.saarthi.MainActivity
import com.shramit.saarthi.R
import com.shramit.saarthi.mesh.MeshManager

/**
 * Foreground service to keep BLE mesh alive in the background.
 * Required on Android 8.0+ for persistent BLE scanning/advertising.
 */
class MeshForegroundService : Service() {

    companion object {
        private const val TAG = "MeshForegroundService"
        private const val CHANNEL_ID = "saarthi_mesh_channel"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_NODE_ID = "node_id"
        const val EXTRA_IS_POLICE = "is_police"
    }

    private var meshManager: MeshManager? = null
    private val binder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun getService(): MeshForegroundService = this@MeshForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Initialize MeshManager strictly once on creation
        meshManager = MeshManager(this)
        Log.d(TAG, "MeshForegroundService created — MeshManager initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nodeId = intent?.getStringExtra(EXTRA_NODE_ID) ?: "NODE_UNKNOWN"
        val isPolice = intent?.getBooleanExtra(EXTRA_IS_POLICE, false) ?: false

        // Start as foreground service
        val notification = createNotification("Saarthi mesh active — protecting you")
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Configure manager once ID is known
        meshManager?.let {
            it.initialize(nodeId, isPolice)
            it.start()
            Log.d(TAG, "Mesh manager configured and started: $nodeId")
        }

        return START_STICKY
    }

    fun getMeshManager(): MeshManager? = meshManager

    override fun onBind(intent: Intent?): android.os.IBinder = binder

    override fun onDestroy() {
        meshManager?.stop()
        meshManager = null
        Log.d(TAG, "MeshForegroundService destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Saarthi Mesh Network",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps mesh network active for SOS relay"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ Saarthi Active")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
