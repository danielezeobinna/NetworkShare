package com.example.networkshare

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.graphics.toColorInt
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

data class FolderItem(
    val file: File,
    val name: String,
    val hasSubFolders: Boolean
)

class WebDAVService : Service() {

    private val activeServers = mutableListOf<WebDAVServer>()
    private val channelId = "WebDAV_Service_Channel"
    private val tag = "WebDAVService"
    private val safetyChannelId = "WebDAV_Safety_Alerts"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == "REFRESH_INFO") {
            broadcastCurrentAddresses()
            return START_STICKY
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
            .setColor("#2BAED5".toColorInt())
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "TURN OFF", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)

        startWebDAVServers()
        broadcastCurrentAddresses()

        return START_STICKY
    }

    fun showSafetyAlert(fileName: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                this,
                "Safety Lock Active: $fileName. Please wait for 60 seconds",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, safetyChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("File Safety Lock Active")
            .setContentText("A recent transfer of '$fileName' failed. Please wait for up to a minute before interacting with again.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor("#2BAED5".toColorInt())
            .setAutoCancel(true)
            .setTimeoutAfter(60000)
            .setContentIntent(pendingIntent)

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(fileName.hashCode(), builder.build())
    }

    private fun startWebDAVServers() {
        if (activeServers.isNotEmpty()) return

        val ip = getLocalIpAddress() ?: "127.0.0.1"
        var nextPort = 8080
        val maxPort = 8089

        val externalDirs = getExternalFilesDirs(null)
        val statusSummary = StringBuilder()

        externalDirs?.forEach { dir ->
            if (dir != null && nextPort <= maxPort) {
                val path = dir.absolutePath

                val storageRootPath = if (path.contains("/Android/")) {
                    path.split("/Android/")[0]
                } else { path }

                val rootFile = File(storageRootPath)

                if (rootFile.exists() && rootFile.canRead()) {

                    while (isPortBusy(nextPort) && nextPort <= maxPort) {
                        nextPort++
                    }

                    if (nextPort <= maxPort) {
                        try {
                            activeServers.add(WebDAVServer(nextPort, rootFile, this))

                            val label = if (storageRootPath.contains("emulated/0")) "Internal" else rootFile.name
                            statusSummary.append("$label: http://$ip:$nextPort/\n")

                            nextPort++
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to start server for $storageRootPath: ${e.message}")
                        }
                    }
                }
            }
        }

        val intent = Intent("com.example.networkshare.ADDRESSES_UPDATED")
        intent.putExtra("address_list", statusSummary.toString().trim())
        sendBroadcast(intent)
    }

    private fun isPortBusy(port: Int): Boolean {
        return try {
            java.net.ServerSocket(port).use { false }
        } catch (_: Exception) {
            true
        }
    }

    private fun broadcastCurrentAddresses() {
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        val statusSummary = StringBuilder()

        activeServers.forEach { server ->
            val path = server.rootDirectory.absolutePath
            val name = server.rootDirectory.name

            val label = when {
                path.contains("emulated/0") -> "Internal Storage"

                path.lowercase().contains("usb") -> "USB OTG ($name)"

                else -> "SD Card ($name)"
            }

            statusSummary.append("$label:\nhttp://$ip:${server.port}/\n\n")
        }

        val intent = Intent("com.example.networkshare.ADDRESSES_UPDATED")
        intent.putExtra("address_list", statusSummary.toString().trim())
        sendBroadcast(intent)
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
        val manager = getSystemService(NotificationManager::class.java)

        val discoveryChannel = NotificationChannel(
            channelId,
            "Network Discovery",
            NotificationManager.IMPORTANCE_LOW
        )
        manager?.createNotificationChannel(discoveryChannel)

        val safetyChannel = NotificationChannel(
            safetyChannelId,
            "File Safety Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts you when a file is locked for safety"
            enableLights(true)
            vibrationPattern = longArrayOf(0, 250, 250, 250)
        }
        manager?.createNotificationChannel(safetyChannel)
    }

    companion object {
        var scannedItems = mutableStateListOf<FolderItem>() // Changed from File to FolderItem
        var isScanning = mutableStateOf(false)
        var selectedPaths = mutableStateListOf<String>()

        fun toggleSelection(path: String) {
            if (selectedPaths.contains(path)) {
                selectedPaths.remove(path)
            } else {
                selectedPaths.add(path)
            }
        }

        fun requestFolderScan(directory: File?) {
            if (directory == null) return
            isScanning.value = true

            Thread {
                try {
                    // ALL DISK ACTIVITY HAPPENS HERE IN THE BACKGROUND
                    val items = directory.listFiles()?.filter { it.isDirectory }?.map {
                        FolderItem(
                            file = it,
                            name = it.name,
                            // Pre-calculate this so the UI doesn't lag!
                            hasSubFolders = it.listFiles()?.any { sub -> sub.isDirectory } ?: false
                        )
                    } ?: emptyList()

                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        scannedItems.clear()
                        scannedItems.addAll(items)
                        isScanning.value = false
                    }
                } catch (_: Exception) {
                    isScanning.value = false
                }
            }.start()
        }
    }
}
