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
import android.annotation.SuppressLint
import android.app.NotificationManager
import java.util.Collections
import android.os.Build

data class FolderItem(
    val file: File,
    val name: String,
    val hasSubFolders: Boolean
)

class WebDAVService : Service(), TransferListener {
    private var currentSsid: String = ""
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val activeServers = mutableListOf<WebDAVServer>()
    private val channelId = "WebDAV_Service_Channel"
    private val tag = "WebDAVService"
    private val safetyChannelId = "WebDAV_Safety_Alerts"
    private val networkHardwareReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action

            // This handles Hotspot toggle
            if (action == "android.net.wifi.WIFI_AP_STATE_CHANGED") {
                val state = intent.getIntExtra("wifi_state", -1)
                when (state) {
                    13 -> { // WIFI_AP_STATE_ENABLED — hotspot just turned ON
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            startWebDAVServers()
                        }, 1500) // small delay so the softap interface is fully up
                    }
                    11, 14 -> { // WIFI_AP_STATE_DISABLING / DISABLED — hotspot turning OFF
                        verifyAndStop()
                    }
                }
            }

            // This handles WiFi toggle
            if (action == android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION) {
                val state = intent.getIntExtra(android.net.wifi.WifiManager.EXTRA_WIFI_STATE, 1)
                if (state == android.net.wifi.WifiManager.WIFI_STATE_ENABLED) {  // ← add this
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        startWebDAVServers()
                    }, 1500)
                }
                if (state == android.net.wifi.WifiManager.WIFI_STATE_DISABLED) {
                    verifyAndStop()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
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

        if (intent?.action == "RESTART_SERVERS") {
            // Full server restart — used by pull-to-refresh
            isStartingServers = false  // reset guard in case a previous call was stuck
            startWebDAVServers()
            return START_STICKY
        }

        createNotificationChannel()
        NetworkTrustManager.ensureChannel(      // ← add this
            getSystemService(NotificationManager::class.java)
        )

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
            .setContentText("Your phone can be accessed by other devices on this network")
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

        // Acquire CPU wake lock
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "NetworkShare::WebDAVWakeLock"
            ).also {
                it.setReferenceCounted(false)
                @SuppressLint("WakelockTimeout")
                it.acquire() // no timeout
            }
        }

        // Acquire high-perf WiFi lock
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE)
                    as android.net.wifi.WifiManager
            wifiLock = wm.createWifiLock(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else
                    @Suppress("DEPRECATION")
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "NetworkShare::WebDAVWifiLock"
            ).also { it.acquire() }
        }
        startForeground(1, notification)

        startWebDAVServers()
        registerNetworkCallback()
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

    // --- Progress Notification Logic ---
    override fun onTransferProgress(fileName: String, currentBytes: Long, totalBytes: Long, isDownload: Boolean) {
        val notificationId = fileName.hashCode()
        val manager = getSystemService(NotificationManager::class.java)

        val cancelIntent = Intent(this, TransferCancelReceiver::class.java).apply {
            putExtra("file_name", fileName)
        }
        val pendingCancel = PendingIntent.getBroadcast(
            this, notificationId, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val percent = if (totalBytes > 0L) {
            ((currentBytes * 100L) / totalBytes).toInt()
        } else 0

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(if (isDownload) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
            .setContentTitle(if (isDownload) "Downloading $fileName" else "Uploading $fileName")
            .setColor("#2BAED5".toColorInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent, false)
            .setContentText("${formatSize(currentBytes)} / ${formatSize(totalBytes)}")
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "CANCEL", pendingCancel)

        manager?.notify(notificationId, builder.build())
    }

    override fun onTransferComplete(fileName: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(fileName.hashCode())
    }

    @SuppressLint("DefaultLocale")
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return String.format("%.2f %s", size, units[unitIndex])
    }

    private var isStartingServers = false

    private fun startWebDAVServers() {
        if (isStartingServers) {
            Log.d(tag, "startWebDAVServers() already in progress — skipping duplicate call")
            return
        }
        isStartingServers = true

        activeServers.forEach { it.stopServer() }
        activeServers.clear()

        // ← add this block
        NetworkTrustManager.load(this)          // ← move to top of startWebDAVServers()
        NetworkTrustManager.ensureChannel(
            getSystemService(NotificationManager::class.java)
        )

        val isOnHotspot = try {
            NetworkInterface.getNetworkInterfaces().toList().any { intf ->
                intf.isUp && !intf.isLoopback && (
                        intf.name.contains("ap") || intf.name.contains("softap")
                        )
            }
        } catch (_: Exception) { false }

        if (!isOnHotspot) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (activeServers.isNotEmpty()) {
                    updateNetworkTrust()
                }
            }, 1500)
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

        val boundIp = getLocalIpAddress() ?: "0.0.0.0"

        roots.forEach { root ->
            val allowedInThisRoot = selectedPaths.filter { it.startsWith(root.absolutePath) }

            if (allowedInThisRoot.isNotEmpty() && nextPort <= maxPort) {
                while (isPortBusy(nextPort) && nextPort <= maxPort) {
                    nextPort++
                }

                if (nextPort <= maxPort) {
                    try {
                        activeServers.add(WebDAVServer(nextPort, root, this, allowedInThisRoot, this, boundIp))
                        nextPort++
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to start server for ${root.absolutePath}: ${e.message}")
                    }
                }
            }
        }

        isStartingServers = false
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
        val statusSummary = StringBuilder()

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
                    url = "http://${server.boundIp}:${server.port}/$relativePath",
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

        val boundIp = activeServers.firstOrNull()?.boundIp ?: "0.0.0.0"
        val isValidNetwork = boundIp != "0.0.0.0"

        val intent = Intent("com.example.networkshare.ADDRESSES_UPDATED")
        intent.putExtra("address_list", statusSummary.toString().trim().ifEmpty { "No folders selected." })
        intent.putExtra("is_valid_network", isValidNetwork)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun refreshServersIfNeeded() {
        val newIp = getLocalIpAddress() ?: "0.0.0.0"
        val currentIp = activeServers.firstOrNull()?.boundIp
        if (newIp != currentIp && newIp != "0.0.0.0") {
            startWebDAVServers()
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager

        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ — use FLAG_INCLUDE_LOCATION_INFO for real SSID
            object : android.net.ConnectivityManager.NetworkCallback(
                FLAG_INCLUDE_LOCATION_INFO
            ) {
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: android.net.NetworkCapabilities
                ) {
                    val info = caps.transportInfo as? android.net.wifi.WifiInfo
                    val ssid = info?.ssid?.removeSurrounding("\"") ?: ""
                    if (ssid.isNotBlank() && ssid != "<unknown ssid>") {
                        currentSsid = ssid
                        updateNetworkTrust()
                        Log.d(tag, "NetworkCallback SSID: $ssid")

                        // ← add this block
                        if (!NetworkTrustManager.isHotspot(ssid)) {
                            when (NetworkTrustManager.getTrust(ssid)) {
                                NetworkTrustManager.Trust.UNKNOWN -> {
                                    Log.d(tag, "Unknown network — showing notification for: $ssid")
                                    val silent = isAppInForeground()
                                    pendingTrustSsid.value = ssid
                                    NetworkTrustManager.showTrustNotification(this@WebDAVService, ssid, silent)
                                }
                                NetworkTrustManager.Trust.BLOCKED -> {
                                    Log.d(tag, "Blocked network: $ssid — server will return 403")
                                }
                                else -> {
                                    Log.d(tag, "Trusted network: $ssid")
                                }
                            }
                        }
                    }
                    refreshServersIfNeeded()
                }

                override fun onLost(network: android.net.Network) {
                    currentSsid = ""
                    updateNetworkTrust()
                }
            }
        } else {
            // Android 8-11 — fallback without the flag
            object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: android.net.NetworkCapabilities
                ) {
                    val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        (caps.transportInfo as? android.net.wifi.WifiInfo)
                            ?.ssid?.removeSurrounding("\"") ?: ""
                    } else {
                        @Suppress("DEPRECATION")
                        (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
                            .connectionInfo.ssid?.removeSurrounding("\"") ?: ""
                    }

                    if (ssid.isNotBlank() && ssid != "<unknown ssid>") {
                        currentSsid = ssid
                        updateNetworkTrust()

                        // ← add this block
                        if (!NetworkTrustManager.isHotspot(ssid)) {
                            when (NetworkTrustManager.getTrust(ssid)) {
                                NetworkTrustManager.Trust.UNKNOWN -> {
                                    Log.d(tag, "Unknown network — showing notification for: $ssid")
                                    val silent = isAppInForeground()
                                    pendingTrustSsid.value = ssid
                                    NetworkTrustManager.showTrustNotification(this@WebDAVService, ssid, silent)
                                }
                                NetworkTrustManager.Trust.BLOCKED -> {
                                    Log.d(tag, "Blocked network: $ssid — server will return 403")
                                }
                                else -> Log.d(tag, "Trusted network: $ssid")
                            }
                        }
                    }

                    val newIp = getLocalIpAddress() ?: "0.0.0.0"
                    val currentIp = activeServers.firstOrNull()?.boundIp
                    if (newIp != currentIp && newIp != "0.0.0.0") {
                        startWebDAVServers()
                    }
                }

                override fun onLost(network: android.net.Network) {
                    currentSsid = ""
                    updateNetworkTrust()
                }
            }
        }

        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
                .filter { intf ->
                    intf.isUp && !intf.isLoopback && (
                            intf.name.contains("wlan") ||
                                    intf.name.contains("ap") ||
                                    intf.name.contains("softap") ||
                                    intf.name.contains("rndis")
                            )
                }

            val priority = listOf("rndis", "softap", "ap", "wlan")

            for (prefix in priority) {
                val match = interfaces
                    .firstOrNull { it.name.contains(prefix) }
                    ?.inetAddresses?.toList()
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
                if (match != null) return match
            }

            null
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

    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        return appProcesses.any {
            it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    it.processName == packageName
        }
    }

    private fun updateNetworkTrust() {
        val isOnHotspot = try {
            val wifiManager = applicationContext
                .getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiManager) as Boolean
        } catch (_: Exception) { false }

        if (isOnHotspot) {
            isNetworkTrusted.value = true
            networkState.value = NetworkState.TRUSTED
            if (activeServers.isNotEmpty()) {
                NetworkTrustManager.restoreSharingNotification(this)
            }
            Log.d(tag, "Hotspot active — always trusted")
            return
        }

        val ssid = if (currentSsid.isNotBlank()) {
            currentSsid
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val connectivityManager = applicationContext
                .getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = connectivityManager.activeNetwork
            val caps = if (network != null)
                connectivityManager.getNetworkCapabilities(network) else null
            val info = caps?.transportInfo as? android.net.wifi.WifiInfo
            info?.ssid?.removeSurrounding("\"") ?: ""
        } else {
            val wifiManager = applicationContext
                .getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo.ssid?.removeSurrounding("\"") ?: ""
        }

        Log.d(tag, "WLAN — SSID: '$ssid' | trust: ${NetworkTrustManager.getTrust(ssid)}")

        when {
            ssid.isBlank() || ssid == "<unknown ssid>" -> {
                val locationIntent = Intent("com.example.networkshare.CHECK_LOCATION")
                locationIntent.setPackage(packageName)
                sendBroadcast(locationIntent)
                isNetworkTrusted.value = false
                networkState.value = NetworkState.NO_NETWORK
                if (activeServers.isNotEmpty()) {
                    NetworkTrustManager.showNoNetworkNotification(this)
                }
            }

            NetworkTrustManager.isHotspot(ssid) -> {
                isNetworkTrusted.value = true
                networkState.value = NetworkState.TRUSTED
            }

            NetworkTrustManager.getTrust(ssid) == NetworkTrustManager.Trust.ALLOWED ||
                    NetworkTrustManager.getTrust(ssid) == NetworkTrustManager.Trust.ALLOW_ONCE -> {
                isNetworkTrusted.value = true
                networkState.value = NetworkState.TRUSTED
                if (activeServers.isNotEmpty()) {
                    NetworkTrustManager.restoreSharingNotification(this)
                }
            }

            else -> {
                isNetworkTrusted.value = false
                networkState.value = NetworkState.UNTRUSTED
                if (NetworkTrustManager.getTrust(ssid) == NetworkTrustManager.Trust.BLOCKED) {
                    NetworkTrustManager.showBlockedNetworkNotification(this)
                } else if (activeServers.isNotEmpty()) {
                    NetworkTrustManager.showNoNetworkNotification(this)
                }
            }
        }
    }

    private fun verifyAndStop() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isNetworkAvailable()) {
                networkState.value = NetworkState.NO_NETWORK
                isNetworkTrusted.value = false
                if (activeServers.isNotEmpty()) {
                    NetworkTrustManager.showNoNetworkNotification(this)
                }
                broadcastCurrentAddresses()
            } else {
                refreshServersIfNeeded()
            }
        }, 1500)
    }


    override fun onDestroy() {
        isRunning = false
        Log.d(tag, "Service stopping, cleaning up servers...")
        try {
            unregisterReceiver(networkHardwareReceiver)
        } catch (e: Exception) {
            Log.e(tag, "Receiver was not registered: ${e.message}")
        }
        activeServers.forEach { it.stopServer() }
        activeServers.clear()
        val stopIntent = Intent("com.example.networkshare.SERVER_STOPPED")
        stopIntent.setPackage(packageName)
        sendBroadcast(stopIntent)
        networkCallback?.let {
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE)
                        as android.net.ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        NetworkTrustManager.allowOnceNetworks.clear()  // ← add this
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
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
        var isRunning = false
        var networkState = mutableStateOf(NetworkState.NO_NETWORK)
        var isAuthEnabled = mutableStateOf(false)  // always overwritten by loadPaths()
        var isNetworkTrusted = mutableStateOf(false)
        var pendingTrustSsid = mutableStateOf<String?>(null)
        var username = mutableStateOf("user")
        var password = mutableStateOf("pass")
        private val cancelledFiles = Collections.synchronizedSet(mutableSetOf<String>())

        fun cancelTransfer(fileName: String) {
            cancelledFiles.add(fileName)
        }

        fun isCancelled(fileName: String): Boolean {
            return cancelledFiles.contains(fileName)
        }

        fun clearCancel(fileName: String) {
            cancelledFiles.remove(fileName)
        }
        var scannedItems = mutableStateListOf<FolderItem>()
        var isScanning = mutableStateOf(false)
        var selectedPaths = mutableStateListOf<String>()
        var tempPriorityPath: String? = null

        fun savePaths(context: Context) {
            val prefs = context.getSharedPreferences("network_share_prefs", MODE_PRIVATE)
            prefs.edit{
                putBoolean("auth_enabled", isAuthEnabled.value)
                putString("username", username.value)
                putString("password", password.value)
                putStringSet("shared_paths", selectedPaths.toSet())
            }
        }

        fun loadPaths(context: Context) {
            val prefs = context.getSharedPreferences("network_share_prefs", MODE_PRIVATE)
            isAuthEnabled.value = prefs.getBoolean("auth_enabled", true)
            username.value = prefs.getString("username", "user") ?: "user"
            password.value = prefs.getString("password", "pass") ?: "pass"
            val saved = prefs.getStringSet("shared_paths", null)
            selectedPaths.clear()
            if (saved != null) {
                // Previously saved selection — restore it as-is (even if empty)
                selectedPaths.addAll(saved)
            } else {
                // First install: seed default internal-storage folders
                val internalRoot = android.os.Environment.getExternalStorageDirectory()
                val defaultFolderNames = listOf(
                    "DCIM", "Documents", "Download",
                    "Movies", "Music", "NetworkShare", "Pictures"
                )
                val defaults = defaultFolderNames
                    .map { File(internalRoot, it) }
                    .filter { it.exists() && it.isDirectory }
                    .map { it.absolutePath }
                selectedPaths.addAll(defaults)
                // Persist so subsequent launches restore the user's own choices
                savePaths(context)
            }
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

class TransferCancelReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fileName = intent.getStringExtra("file_name") ?: return
        // Tell the Service to stop this specific file
        WebDAVService.cancelTransfer(fileName)
    }
}

enum class NetworkState { NO_NETWORK, UNTRUSTED, TRUSTED }