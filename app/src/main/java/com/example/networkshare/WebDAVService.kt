package com.example.networkshare

import android.app.*
import android.content.Context
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
import androidx.core.content.edit

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
    private val networkHardwareReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action

            // This handles Hotspot toggle
            if (action == "android.net.wifi.WIFI_AP_STATE_CHANGED") {
                val state = intent.getIntExtra("wifi_state", 11)
                if (state == 11 || state == 14) {
                    verifyAndStop()
                }
            }

            // This handles WiFi toggle
            if (action == android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION) {
                val state = intent.getIntExtra(android.net.wifi.WifiManager.EXTRA_WIFI_STATE, 1)
                if (state == android.net.wifi.WifiManager.WIFI_STATE_DISABLED) {
                    verifyAndStop()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == "REFRESH_INFO") {
            if (activeServers.isEmpty() && selectedPaths.isNotEmpty()) {
                startWebDAVServers()
            }
            broadcastCurrentAddresses()
            return START_STICKY
        }

        if (!isNetworkAvailable()) {
            android.widget.Toast.makeText(this, "WiFi or Hotspot required to start", android.widget.Toast.LENGTH_SHORT).show()
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
            .setContentTitle("Network sharing is turned on")
            .setContentText("Your phone can be accessed by your PC")
            .setOngoing(true)
            .setColor("#2BAED5".toColorInt())
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "TURN OFF", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val filter = android.content.IntentFilter().apply {
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        registerReceiver(networkHardwareReceiver, filter)
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
        activeServers.forEach { it.stopServer() }
        activeServers.clear()

        if (!isNetworkAvailable()) {
            sendBroadcast(Intent("com.example.networkshare.SERVER_STOPPED"))
            stopSelf()
            return
        }

        var nextPort = 8080
        val maxPort = 8089

        val externalDirs = getExternalFilesDirs(null)
        val roots = externalDirs.filterNotNull().map { dir ->
            if (dir.absolutePath.contains("/Android/")) {
                dir.absolutePath.split("/Android/")[0]
            } else {
                dir.absolutePath
            }
        }.distinct().map { File(it) }

        roots.forEach { root ->
            val allowedInThisRoot = selectedPaths.filter { it.startsWith(root.absolutePath) }

            if (allowedInThisRoot.isNotEmpty() && nextPort <= maxPort) {
                while (isPortBusy(nextPort) && nextPort <= maxPort) {
                    nextPort++
                }

                if (nextPort <= maxPort) {
                    try {
                        activeServers.add(WebDAVServer(nextPort, root, this, allowedInThisRoot))
                        nextPort++
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to start server for ${root.absolutePath}: ${e.message}")
                    }
                }
            }
        }

        broadcastCurrentAddresses()
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

        if (activeServers.isEmpty()) {
            statusSummary.append("No folders selected.\nGo to 'Choose Shared Paths' to start.")
        }

        data class AddressItem(
            val label: String,
            val folderName: String,
            val url: String,
            val isStorage: Boolean,
            val isTempVip: Boolean
        )

        val addressList = mutableListOf<AddressItem>()

        activeServers.forEach { server ->
            val rootPath = server.rootDirectory.absolutePath
            val storageLabel = when {
                rootPath.contains("emulated/0") -> "Internal Storage"
                rootPath.lowercase().contains("usb") -> "USB OTG (${server.rootDirectory.name})"
                else -> "SD Card (${server.rootDirectory.name})"
            }

            selectedPaths.filter { it.startsWith(rootPath) }.forEach { path ->
                val folder = File(path)
                val isRoot = path == rootPath
                val relativePath = path.removePrefix(rootPath).trimStart('/')
                val isTempVip = path == tempPriorityPath

                addressList.add(AddressItem(
                    label = storageLabel,
                    folderName = folder.name,
                    url = "http://$ip:${server.port}/$relativePath",
                    isStorage = isRoot,
                    isTempVip = isTempVip
                ))
            }
        }

        addressList.sortWith(
            compareByDescending<AddressItem> { it.isTempVip }
                .thenByDescending { it.isStorage }
                .thenBy { it.folderName.lowercase() }
        )

        addressList.forEach { item ->
            val displayName = if (item.isStorage) item.label else item.folderName
            statusSummary.append("$displayName:\n${item.url}\n\n")
        }

        val intent = Intent("com.example.networkshare.ADDRESSES_UPDATED")
        intent.putExtra("address_list", statusSummary.toString().trim().ifEmpty { "No folders selected." })
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

    private fun isNetworkAvailable(): Boolean {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()

            interfaces.any { intf ->

                intf.isUp && !intf.isLoopback && (
                        intf.name.contains("wlan") ||
                                intf.name.contains("ap") ||
                                intf.name.contains("softap")
                        )
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun verifyAndStop() {
        // Wait 1.5 seconds for the hardware to settle
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val currentIp = getLocalIpAddress()
            val hardwareActive = isNetworkAvailable()

            // If no hardware is on, or the IP is gone/local, stop the service
            if (!hardwareActive || currentIp == null || currentIp == "127.0.0.1") {
            Log.d(tag, "Strong check failed. Hardware Active: $hardwareActive, IP: $currentIp")
            stopSelf()
        }
        }, 1500)
    }

    override fun onDestroy() {
        Log.d(tag, "Service stopping, cleaning up servers...")
        try {
            unregisterReceiver(networkHardwareReceiver)
        } catch (e: Exception) {
            Log.e(tag, "Receiver was not registered: ${e.message}")
        }
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
            "Network Sharing",
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
        var scannedItems = mutableStateListOf<FolderItem>()
        var isScanning = mutableStateOf(false)
        var selectedPaths = mutableStateListOf<String>()
        var tempPriorityPath: String? = null
        val activeServers = mutableListOf<WebDAVServer>()

        fun savePaths(context: Context) {
            val prefs = context.getSharedPreferences("network_share_prefs", MODE_PRIVATE)
            prefs.edit{
                putStringSet("shared_paths", selectedPaths.toSet())
            }
        }

        fun loadPaths(context: Context) {
            val prefs = context.getSharedPreferences("network_share_prefs", MODE_PRIVATE)
            val saved = prefs.getStringSet("shared_paths", emptySet())
            selectedPaths.clear()
            selectedPaths.addAll(saved ?: emptySet())
        }

        fun toggleSelection(context: Context, path: String) {
            val parentPath = selectedPaths.firstOrNull { path.startsWith("$it/") && path != it }

            if (parentPath != null) return

            if (selectedPaths.contains(path)) {
                selectedPaths.remove(path)
            } else {
                selectedPaths.add(path)
            }
            savePaths(context)
        }

        fun requestFolderScan(directory: File?) {
            if (directory == null) return
            isScanning.value = true

            Thread {
                try {
                    val items = directory.listFiles()?.filter { it.isDirectory }?.map {
                        FolderItem(
                            file = it,
                            name = it.name,
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
