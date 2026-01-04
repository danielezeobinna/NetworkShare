package com.example.networkshare

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class WebDAVService : Service() {

    private val activeServers = mutableListOf<WebDAVServer>()
    private val channelId = "WebDAV_Service_Channel" // Fixed naming convention
    private val tag = "WebDAVService"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle the STOP action from the notification button
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()

        // Setup the STOP button for the notification
        val stopIntent = Intent(this, WebDAVService::class.java).apply {
            action = "STOP_SERVICE"
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Network discovery is turned on")
            .setContentText("Your phone can be accessed by your PC")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "TURN OFF", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Start in foreground
        startForeground(1, notification)

        // Start the server logic
        startWebDAVServers()

        return START_STICKY
    }

    private fun startWebDAVServers() {
        if (activeServers.isNotEmpty()) return

        val ip = getLocalIpAddress() ?: "127.0.0.1"
        Log.d(tag, "Starting servers on IP: $ip")

        // 1. Internal Storage
        try {
            activeServers.add(WebDAVServer(8080, Environment.getExternalStorageDirectory()))
        } catch (e: Exception) {
            Log.e(tag, "Failed to start internal server: ${e.message}")
        }

        // 2. SD Card Logic
        val externalDirs = getExternalFilesDirs(null)
        var nextPort = 8081
        externalDirs?.forEach { dir: File? ->
            if (dir != null) {
                val path = dir.absolutePath
                if (path.contains("/storage/") && !path.contains("/emulated/")) {
                    val storagePath = path.split("/Android/")[0]
                    val sdRoot = File(storagePath)
                    if (sdRoot.exists() && sdRoot.canRead()) {
                        try {
                            activeServers.add(WebDAVServer(nextPort, sdRoot))
                            nextPort++
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to start SD server: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    override fun onDestroy() {
        Log.d(tag, "Service stopping, cleaning up servers...")
        activeServers.forEach { it.stopServer() }
        activeServers.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Network Discovery",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}