package com.example.networkshare

import android.app.*
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.graphics.toColorInt
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class WebDAVService : Service() {

    private val activeServers = mutableListOf<WebDAVServer>()
    private val channelId = "WebDAV_Service_Channel"
    private val tag = "WebDAVService"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val contentPendingIntent = PendingIntent.getActivity(
            this,
            1,
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

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
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Network discovery is turned on")
            .setContentText("Your phone can be accessed by your PC")
            .setOngoing(true)
            .setColor("#5BACD6".toColorInt())
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "TURN OFF", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)

        startWebDAVServers()

        return START_STICKY
    }

    private fun startWebDAVServers() {
        if (activeServers.isNotEmpty()) return

        val ip = getLocalIpAddress() ?: "127.0.0.1"
        Log.d(tag, "Starting servers on IP: $ip")

        try {
            activeServers.add(WebDAVServer(8080, Environment.getExternalStorageDirectory(), this))
        } catch (e: Exception) {
            Log.e(tag, "Failed to start internal server: ${e.message}")
        }

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
                            activeServers.add(WebDAVServer(nextPort, sdRoot, this))
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
        sendBroadcast(Intent("com.example.networkshare.SERVER_STOPPED"))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Network Discovery",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }
}