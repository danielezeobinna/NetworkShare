package com.danieleze.networkshare

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.os.storage.StorageManager
import androidx.compose.runtime.mutableStateOf
import androidx.core.graphics.toColorInt
import androidx.core.app.NotificationCompat
import java.io.File
import androidx.core.content.edit
import android.annotation.SuppressLint
import android.app.NotificationManager
import java.util.Collections
import android.os.Build
import android.app.NotificationChannel
import android.app.PendingIntent
import fi.iki.elonen.NanoHTTPD
import com.danieleze.networkshare.NetworkManager.ACTION_ALLOW
import com.danieleze.networkshare.NetworkManager.ACTION_ALLOW_ONCE
import com.danieleze.networkshare.NetworkManager.ACTION_BLOCK
import com.danieleze.networkshare.NetworkManager.CHANNEL_ID
import com.danieleze.networkshare.NetworkManager.EXTRA_SSID

class WebDAVService : Service(), TransferListener,
    NetworkManager.NetworkEventListener, WebDAVServerConfig {

    private var currentSsid: String = ""
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wifiJustEnabled = false
    private val activeServers = mutableListOf<WebDAVServer>()
    private val channelId = "WebDAV_Service_Channel"
    private val tag = "WebDAVService"
    private val safetyChannelId = "WebDAV_Safety_Alerts"

    // ── NetworkEventListener implementation ──────────────────
    // NetworkManager calls these when network state changes.

    override fun getUsername() = username.value
    override fun getPassword() = password.value
    override fun isAuthEnabled() = isAuthEnabled.value
    override fun isNetworkTrusted() = isNetworkTrusted.value
    override fun isCancelled(fileName: String) = WebDAVService.isCancelled(fileName)
    override fun clearCancel(fileName: String) = WebDAVService.clearCancel(fileName)
    override fun generateToken() = WebDAVService.generateToken()
    override fun showSafetyAlert(fileName: String) = postSafetyAlert(fileName)

    override fun sharedItems(uri: String): Any? {
        return FileManager.sharedItems(uri)
    }

    override fun getCustomResponse(uri: String, uncPath: String): NanoHTTPD.Response? {
        val fileName = uri.trimEnd('/').substringAfterLast('/')
        return when (fileName) {
            "ic_ns.png" -> {
                try {
                    val stream = assets.open("ic_ns.png")
                    NanoHTTPD.newChunkedResponse(
                        NanoHTTPD.Response.Status.OK,
                        "image/png",
                        stream
                    )
                } catch (_: Exception) { null }
            }
            else -> null
        }
    }

    override fun getDirectoryHtml(uncPath: String): String? {
        return try {
            assets.open("browser_instructions.html")
                .bufferedReader()
                .readText()
                .replace("{{UNC_PATH}}", uncPath)
        } catch (_: Exception) { null }
    }

    override fun onNetworkTrustChanged(state: NetworkState, ssid: String) {
        isNetworkTrusted.value = state == NetworkState.TRUSTED
        networkState.value = state
        when (state) {
            NetworkState.TRUSTED -> {
                if (activeServers.isNotEmpty()) restoreSharingNotification(this)
            }
            NetworkState.NO_NETWORK -> {
                if (activeServers.isNotEmpty()) showNoNetworkNotification(this)
                broadcastCurrentAddresses()
            }
            NetworkState.UNTRUSTED -> {
                when (NetworkManager.getTrust(ssid)) {
                    NetworkManager.Trust.BLOCKED -> showBlockedNetworkNotification(this)
                    else -> if (activeServers.isNotEmpty()) showNoNetworkNotification(this)
                }
            }
        }
    }

    override fun onRefreshServersIfNeeded() {
        val newIp = NetworkManager.getLocalIpAddress() ?: "0.0.0.0"
        val currentIp = activeServers.firstOrNull()?.boundIp
        if (newIp != currentIp && newIp != "0.0.0.0") {
            startWebDAVServers()
        }
    }

    override fun onUnknownNetworkDetected(ssid: String, silent: Boolean) {
        pendingTrustSsid.value = ssid
        showTrustNotification(this, ssid, silent)
    }

    override fun onHotspotEnabled() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startWebDAVServers()
        }, 1500)
        if (isWaitingForHotspot) {
            isWaitingForHotspot = false
            val bringForward = Intent(this, MainActivity::class.java)
            bringForward.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(bringForward)
        }
    }

    override fun onWifiEnabled() {
        wifiJustEnabled = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startWebDAVServers()
        }, 1500)
    }

    // ── Service lifecycle ─────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == "REFRESH_INFO") {
            if (activeServers.isEmpty() && FileManager.selectedPaths.isNotEmpty()) {
                startWebDAVServers()
            }
            broadcastCurrentAddresses()
            return START_STICKY
        }

        if (intent?.action == "RESTORE_NOTIFICATION") {
            pendingTrustSsid.value = null
            restoreSharingNotification(this)
            return START_STICKY
        }

        if (intent?.action == "RESTART_SERVERS") {
            isStartingServers = false
            startWebDAVServers()
            return START_STICKY
        }

        createNotificationChannel()
        ensureChannel(getSystemService(NotificationManager::class.java))

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 1, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, WebDAVService::class.java).apply { action = "STOP_SERVICE" }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
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

        NetworkManager.registerHardwareReceiver(this)

        // Acquire CPU wake lock
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "NetworkShare::WebDAVWakeLock"
            ).also {
                it.setReferenceCounted(false)
                @SuppressLint("WakelockTimeout")
                it.acquire()
            }
        }

        // Acquire high-perf Wi-Fi lock
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

        NetworkManager.setEventListener(this)

        startWebDAVServers()

        NetworkManager.registerNetworkCallback(
            context          = this,
            onSsidChanged    = { ssid ->
                currentSsid = ssid
                NetworkManager.updateNetworkTrust(
                    context                   = this,
                    currentSsid               = currentSsid,
                    wifiJustEnabled           = wifiJustEnabled,
                    onWifiJustEnabledConsumed = { wifiJustEnabled = false }
                )
            },
            onNetworkLost    = {
                currentSsid = ""
                NetworkManager.updateNetworkTrust(
                    context                   = this,
                    currentSsid               = currentSsid,
                    wifiJustEnabled           = wifiJustEnabled,
                    onWifiJustEnabledConsumed = { wifiJustEnabled = false }
                )
            }
        )

        broadcastCurrentAddresses()

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        Log.d(tag, "Service stopping, cleaning up servers...")
        NetworkManager.unregisterHardwareReceiver(this)
        activeServers.forEach { it.stopServer() }
        activeServers.clear()
        val stopIntent = Intent("com.danieleze.networkshare.SERVER_STOPPED")
        stopIntent.setPackage(packageName)
        sendBroadcast(stopIntent)
        NetworkManager.unregisterNetworkCallback(this)
        NetworkManager.setEventListener(null)
        NetworkManager.allowOnceNetworks.clear()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null


    // ── Server management ─────────────────────────────────────

    private var isStartingServers = false
    private var nextPort = 8080

    private fun startWebDAVServers() {
        if (isStartingServers) return
        isStartingServers = true
        stopActiveServers()
        NetworkManager.load(this)
        ensureChannel(getSystemService(NotificationManager::class.java))

        if (!isOnHotspot()) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                NetworkManager.updateNetworkTrust(
                    context = this,
                    currentSsid = currentSsid,
                    wifiJustEnabled = wifiJustEnabled,
                    onWifiJustEnabledConsumed = { wifiJustEnabled = false }
                )
            }, 1500)
        }

        val boundIp = NetworkManager.getLocalIpAddress() ?: "0.0.0.0"

        FileManager.storageRoots.clear()
        val storageManager = getSystemService(STORAGE_SERVICE) as StorageManager
        val externalDirs = getExternalFilesDirs(null).filterNotNull()
        val usedLabels = mutableSetOf<String>()

        externalDirs.forEach { dir ->
            val volume = storageManager.getStorageVolume(dir) ?: return@forEach

            val rootPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory?.absolutePath ?: dir.absolutePath.split("/Android/")[0]
            } else {
                dir.absolutePath.split("/Android/")[0]
            }

            val description = volume.getDescription(this) ?: "External Drive"

            val label = when {
                !volume.isRemovable || volume.isPrimary -> "Internal Storage"
                else -> {
                    val volumeName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        volume.mediaStoreVolumeName?.lowercase() ?: ""
                    } else ""

                    val containsUsb = volumeName.contains("usb") ||
                            description.lowercase().contains("usb") ||
                            rootPath.lowercase().contains("usb")

                    val containsSd = volumeName.contains("sd") ||
                            description.lowercase().contains("sd") ||
                            rootPath.lowercase().contains("sd")

                    val isUsb = containsUsb && !containsSd

                    val kind = if (isUsb) "USB OTG" else "SD Card"

                    if (description.contains(kind, ignoreCase = true) ||
                        (isUsb && description.contains("usb", ignoreCase = true))) {
                        description
                    } else {
                        "$kind ($description)"
                    }
                }
            }

            // Disambiguate if two volumes resolve to the same label
            var finalLabel = label
            var suffix = 2
            while (!usedLabels.add(finalLabel)) {
                finalLabel = "$label #$suffix"
                suffix++
            }

            FileManager.storageRoots[finalLabel] = rootPath
        }

        nextPort = 8080
        val port = findAvailablePort()
        try {
            activeServers.add(
                WebDAVServer(port, this, this, this, boundIp)
            )
            Log.d(tag, "Unified server started on port $port")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start server: ${e.message}")
        }

        isStartingServers = false
        broadcastCurrentAddresses()
    }

    private fun stopActiveServers() {
        activeServers.forEach { it.stopServer() }
        activeServers.clear()
    }

    private fun isOnHotspot(): Boolean {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList().any { intf ->
                intf.isUp && !intf.isLoopback &&
                        (intf.name.contains("ap") || intf.name.contains("softap"))
            }
        } catch (_: Exception) { false }
    }

    private fun findAvailablePort(): Int {
        while (isPortBusy(nextPort)) {
            nextPort++
        }
        return nextPort++
    }



    private fun isPortBusy(port: Int): Boolean {
        return try {
            java.net.ServerSocket(port).use { false }
        } catch (_: Exception) { true }
    }

    private fun broadcastCurrentAddresses() {
        val server = activeServers.firstOrNull()
        val boundIp = server?.boundIp ?: "0.0.0.0"
        val port = server?.port ?: 8080
        val isValidNetwork = boundIp != "0.0.0.0"
        val statusSummary = StringBuilder()

        data class AddressItem(
            val label: String,
            val folderName: String,
            val url: String,
            val isStorage: Boolean,
            val isTempVip: Boolean
        )

        val addressList = mutableListOf<AddressItem>()

        FileManager.storageRoots.forEach { (label, rootPath) ->
            FileManager.selectedPaths.filter { it.startsWith(rootPath) }.forEach { path ->
                val folder = File(path)
                val isRoot = path == rootPath
                val relativePath = path.removePrefix(rootPath).trimStart('/')
                val url = if (relativePath.isEmpty())
                    "http://$boundIp:$port/$label"
                else
                    "http://$boundIp:$port/$label/$relativePath"

                addressList.add(AddressItem(
                    label = label,
                    folderName = folder.name,
                    url = url,
                    isStorage = isRoot,
                    isTempVip = path == FileManager.tempPriorityPath
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

        val intent = Intent("com.danieleze.networkshare.ADDRESSES_UPDATED")
        intent.putExtra("address_list", statusSummary.toString().trim().ifEmpty { "No folders selected." })
        intent.putExtra("is_valid_network", isValidNetwork)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    // ── Notifications ─────────────────────────────────────────

    fun showTrustNotification(context: Context, ssid: String, silent: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)

        fun pendingFor(action: String): PendingIntent {
            val i = Intent(context, NetworkActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_SSID, ssid)
            }
            return PendingIntent.getBroadcast(
                context,
                ssid.hashCode() xor action.hashCode(),
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Unknown Network Detected")
            .setContentText("\"$ssid\" is unknown. Allow file sharing on this network?")
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setColor("#2BAED5".toColorInt())
            .setAutoCancel(false)
            .also { if (silent) it.setSilent(true) }
            .addAction(0, "Allow",      pendingFor(ACTION_ALLOW))
            .addAction(0, "Allow Once", pendingFor(ACTION_ALLOW_ONCE))
            .addAction(0, "Block",      pendingFor(ACTION_BLOCK))
            .build()

        manager?.notify(1, notification)
    }

    fun showNoNetworkNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, 1, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(context, WebDAVService::class.java).apply { action = "STOP_SERVICE" }
        val stopPendingIntent = PendingIntent.getService(
            context, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "WebDAV_Service_Channel")
            .setSmallIcon(R.drawable.ic_stat_name)
            .setColor(android.graphics.Color.GRAY)
            .setContentTitle("Network sharing is turned on")
            .setContentText("No network available. Connect or create a network to start sharing.")
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "TURN OFF", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        manager?.notify(1, notification)
    }

    fun showBlockedNetworkNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, 1, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(context, WebDAVService::class.java).apply { action = "STOP_SERVICE" }
        val stopPendingIntent = PendingIntent.getService(
            context, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "WebDAV_Service_Channel")
            .setSmallIcon(R.drawable.ic_stat_name)
            .setColor(android.graphics.Color.GRAY)
            .setContentTitle("Network sharing is turned on")
            .setContentText("This is a blocked network. Allow this network or connect to a trusted network to start sharing.")
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "TURN OFF", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        manager?.notify(1, notification)
    }

    fun restoreSharingNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, 1, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(context, WebDAVService::class.java).apply { action = "STOP_SERVICE" }
        val stopPendingIntent = PendingIntent.getService(
            context, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "WebDAV_Service_Channel")
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Network sharing is turned on")
            .setContentText("Your phone can be accessed by other devices on this network")
            .setShowWhen(false)
            .setOngoing(true)
            .setColor("#2BAED5".toColorInt())
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "TURN OFF", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()

        manager?.notify(1, notification)
    }

    fun ensureChannel(manager: NotificationManager?) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Network Trust Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when connecting to an unknown WiFi network"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 250, 250)
            enableLights(true)
        }
        manager?.createNotificationChannel(channel)
    }

    fun postSafetyAlert(fileName: String) {
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

    // ── Transfer listener ─────────────────────────────────────

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

        val percent = if (totalBytes > 0L) ((currentBytes * 100L) / totalBytes).toInt() else 0

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

    // ── Utilities ─────────────────────────────────────────────

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

    // ── Companion ─────────────────────────────────────────────

    companion object {
        var isRunning = false
        var isWaitingForHotspot = false
        var networkState = mutableStateOf(NetworkState.NO_NETWORK)
        var isAuthEnabled = mutableStateOf(false)
        var isNetworkTrusted = mutableStateOf(false)
        var pendingTrustSsid = mutableStateOf<String?>(null)
        var username = mutableStateOf("user")
        var password = mutableStateOf("pass")

        private val cancelledFiles = Collections.synchronizedSet(mutableSetOf<String>())

        fun cancelTransfer(fileName: String) { cancelledFiles.add(fileName) }
        fun isCancelled(fileName: String): Boolean = cancelledFiles.contains(fileName)
        fun clearCancel(fileName: String) { cancelledFiles.remove(fileName) }

        fun generateToken(): String {
            val input = "${username.value}:${password.value}:NetworkShare"
            return java.security.MessageDigest.getInstance("MD5")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        fun savePaths(context: Context) {
            val prefs = context.getSharedPreferences("network_share_prefs", MODE_PRIVATE)
            prefs.edit {
                putBoolean("auth_enabled", isAuthEnabled.value)
                putString("username", username.value)
                putString("password", password.value)
                putStringSet("shared_paths", FileManager.selectedPaths.toSet())
            }
        }

        fun loadPaths(context: Context) {
            val prefs = context.getSharedPreferences("network_share_prefs", MODE_PRIVATE)
            isAuthEnabled.value = prefs.getBoolean("auth_enabled", true)
            username.value = prefs.getString("username", "user") ?: "user"
            password.value = prefs.getString("password", "pass") ?: "pass"
            val saved = prefs.getStringSet("shared_paths", null)
            FileManager.selectedPaths.clear()
            if (saved != null) {
                FileManager.selectedPaths.addAll(saved)
            } else {
                val internalRoot = android.os.Environment.getExternalStorageDirectory()
                val defaultFolderNames = listOf(
                    "DCIM", "Documents", "Download",
                    "Movies", "Music", "NetworkShare", "Pictures"
                )
                val defaults = defaultFolderNames
                    .map { File(internalRoot, it) }
                    .filter { it.exists() && it.isDirectory }
                    .map { it.absolutePath }
                FileManager.selectedPaths.addAll(defaults)
                savePaths(context)
            }
        }
    }
}

class TransferCancelReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fileName = intent.getStringExtra("file_name") ?: return
        WebDAVService.cancelTransfer(fileName)
    }
}

enum class NetworkState { NO_NETWORK, UNTRUSTED, TRUSTED }