package com.danieleze.networkshare

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import io.github.webdanlib.webdav.server.*
import java.io.File
import java.util.Collections

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {

            WebDAVService.loadPaths(context)

            val serviceIntent = Intent(context, WebDAVService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}

class WebDAVService : Service(),
    NetworkManager.NetworkEventListener, Config {

    private var currentSsid: String = ""
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wifiJustEnabled = false
    private val channelId = "WebDAV_Service_Channel"
    private val tag = "WebDAVService"
    private val safetyChannelId = "WebDAV_Safety_Alerts"
    private val trustChannelId = "network_trust_channel"
    private val wsDiscoveryService = WSDiscoveryService(
        context = this,
        friendlyName = username.value
    )

    // ── NetworkEventListener implementation ──────────────────

    override fun onNetworkTrustChanged(state: NetworkState, ssid: String) {
        val wasTrusted = networkState.value == NetworkState.TRUSTED
        isNetworkTrusted.value = state == NetworkState.TRUSTED
        networkState.value = state
        when (state) {
            NetworkState.TRUSTED -> {
                if (isRunning) restoreSharingNotification(this)
                wsDiscoveryService.start()
            }

            NetworkState.NO_NETWORK -> {
                if (isRunning) getSystemService(NotificationManager::class.java)?.notify(
                    1,
                    defaultNotification(continuing = wasTrusted)
                )
                wsDiscoveryService.stop()
                broadcastCurrentAddresses()
            }

            NetworkState.UNTRUSTED -> {
                wsDiscoveryService.stop()
                when (NetworkManager.getTrust(ssid)) {
                    NetworkManager.Trust.BLOCKED -> showBlockedNetworkNotification(this)
                    else -> if (isRunning) getSystemService(NotificationManager::class.java)?.notify(
                        1,
                        defaultNotification(continuing = wasTrusted)
                    )
                }
            }
        }
    }

    override fun onRefreshServersIfNeeded() {
        val newIp = NetworkManager.getLocalIpAddress()
        val currentIp = activeServers.firstOrNull()?.ip
        if (newIp != null && newIp != currentIp) {
            startWebDAVServer()
        }
    }

    override fun onUnknownNetworkDetected(ssid: String, silent: Boolean) {
        pendingTrustSsid.value = ssid
        showTrustNotification(this, ssid, silent)
    }

    override fun onHotspotEnabled() {
        Handler(Looper.getMainLooper()).postDelayed({
            startWebDAVServer()
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
        Handler(Looper.getMainLooper()).postDelayed({
            startWebDAVServer()
        }, 1500)
    }

    // ── WebDAN server implementation ──────────────────

    override fun authUsername(): String = username.value
    override fun authPassword() = password.value
    override fun authSwitch() = isAuthEnabled.value
    override fun authToken() = generateToken()

    override fun getIpAddress(): String? = NetworkManager.getLocalIpAddress()
    override fun getObjects(uri: String): Any? {
        if (!isNetworkTrusted.value) {
            return DavResponse(403, "Forbidden", "text/plain; charset=utf-8", "Network not trusted")
        }
        return FileManager.getObjects(uri)
    }

    override fun getDirectoryGETResponse(uncPath: String): DavResponse? {
        val templateFile = FileManager.browserInstructionsFile() ?: return null
        if (!templateFile.exists()) return null
        val customHtml = templateFile.readText().replace("{{UNC_PATH}}", uncPath)
        return DavResponse(200, "OK", "text/html; charset=utf-8", customHtml)
    }

    override fun onTransferProgress(
        fileName: String,
        currentBytes: Long,
        totalBytes: Long,
        isReceiving: Boolean
    ) = showProgressNotification(fileName, currentBytes, totalBytes, isReceiving)

    override fun onTransferComplete(fileName: String) {
        clearCancel(fileName)
        dismissProgress(fileName)
    }

    override fun onTransferError(fileName: String, message: String) {
        clearCancel(fileName)
        dismissProgress(fileName)
        showTransferFailedToast(fileName, message)
    }

    override fun onTransferCancel(fileName: String, state: CancelState): Boolean {
        return when (state) {
            CancelState.CHECK -> isCancelRequested(fileName)
            CancelState.CANCELLED -> {
                dismissProgress(fileName)
                false
            }
        }
    }

    override fun onTransferBlocked(fileName: String) = showSafetyAlert(fileName)

    // ── Service lifecycle ─────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == "REFRESH_INFO") {
            if (activeServers.isEmpty() && FileManager.selectedPaths.isNotEmpty()) {
                startWebDAVServer()
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
            startWebDAVServer()
            return START_STICKY
        }

        createNotificationChannel()
        ensureChannel(getSystemService(NotificationManager::class.java))

        val notification = defaultNotification()

        NetworkManager.registerHardwareReceiver(this)
        FileManager.registerMediaReceiver(this)

        // Acquire CPU wake lock
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
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

        startWebDAVServer()

        NetworkManager.registerNetworkCallback(
            context = this,
            onSsidChanged = { ssid ->
                currentSsid = ssid
                NetworkManager.updateNetworkTrust(
                    context = this,
                    currentSsid = currentSsid,
                    wifiJustEnabled = wifiJustEnabled,
                    onWifiJustEnabledConsumed = { wifiJustEnabled = false }
                )
            },
            onNetworkLost = {
                currentSsid = ""
                NetworkManager.updateNetworkTrust(
                    context = this,
                    currentSsid = currentSsid,
                    wifiJustEnabled = wifiJustEnabled,
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
        FileManager.unregisterMediaReceiver(this)
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

    private fun startWebDAVServer() {
        if (isStartingServers) return
        isStartingServers = true
        stopActiveServers()

        ensureChannel(getSystemService(NotificationManager::class.java))
        NetworkManager.refreshAll(this, currentSsid, wifiJustEnabled) { wifiJustEnabled = false }

        FileManager.refreshStorageRoots(this)

        val addresses = NetworkManager.getAllLocalIpAddresses()
        if (addresses.size > 1) {
            showMultipleNetworksWarning()
        }

        val ip = NetworkManager.getLocalIpAddress()
        if (ip == null) {
            Log.w(tag, "No IP address available — server not started")
            isStartingServers = false
            broadcastCurrentAddresses()
            return
        }

        val server = startServer(this, this)
        if (server != null) {
            activeServers.add(server)
            wsDiscoveryService.webdavAddress = friendlyServerAddress
            Log.d(tag, "Server started on port ${server.port}")
        } else {
            Log.e(tag, "Failed to start server after multiple attempts")
        }

        isStartingServers = false
        wsDiscoveryService.start()
        broadcastCurrentAddresses()
    }

    private fun stopActiveServers() {
        activeServers.forEach { it.stopServer() }
        activeServers.clear()
        wsDiscoveryService.stop()
    }

    private fun broadcastCurrentAddresses() {
        val server = activeServers.firstOrNull()
        val isValidNetwork = (server?.ip ?: "0.0.0.0") != "0.0.0.0"
        val friendlyBase = friendlyServerAddress
        val ipBase = server?.serverAddress ?: "http://0.0.0.0:8080"

        data class AddressItem(
            val label: String, val folderName: String,
            val friendlyUrl: String, val ipUrl: String,
            val isStorage: Boolean, val isTempVip: Boolean
        )

        val addressList = mutableListOf<AddressItem>()

        FileManager.storageRoots.forEach { (label, rootPath) ->
            FileManager.selectedPaths.filter { it.startsWith(rootPath) }.forEach { path ->
                val folder = File(path)
                val isRoot = path == rootPath
                val relativePath = path.removePrefix(rootPath).trimStart('/')
                val safeLabel = FileManager.urlSafeSegment(label)
                val safeRelative =
                    relativePath.split("/").joinToString("/") { FileManager.urlSafeSegment(it) }
                val suffix =
                    if (safeRelative.isEmpty()) "/$safeLabel" else "/$safeLabel/$safeRelative"

                addressList.add(
                    AddressItem(
                        label = label,
                        folderName = folder.name,
                        friendlyUrl = "$friendlyBase$suffix",
                        ipUrl = "$ipBase$suffix",
                        isStorage = isRoot,
                        isTempVip = path == FileManager.tempPriorityPath
                    )
                )
            }
        }

        addressList.sortWith(
            compareByDescending<AddressItem> { it.isTempVip }
                .thenByDescending { it.isStorage }
                .thenBy { it.folderName.lowercase() }
        )

        val friendlySummary = StringBuilder()
        val ipSummary = StringBuilder()
        addressList.forEach { item ->
            val displayName = if (item.isStorage) item.label else item.folderName
            friendlySummary.append("$displayName:\n${item.friendlyUrl}\n\n")
            ipSummary.append("$displayName:\n${item.ipUrl}\n\n")
        }

        val intent = Intent("com.danieleze.networkshare.ADDRESSES_UPDATED")
        intent.putExtra(
            "address_list",
            friendlySummary.toString().trim().ifEmpty { "No folders selected." })
        intent.putExtra(
            "address_list_fallback",
            ipSummary.toString().trim().ifEmpty { "No folders selected." })
        intent.putExtra("is_valid_network", isValidNetwork)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    // ── Notifications ─────────────────────────────────────────

    private fun defaultNotification(continuing: Boolean = false): Notification =
        buildServiceNotification(
            title = "Network sharing is off",
            text = if (continuing)
                "No network available. Connect or create a network to continue sharing."
            else
                "No network available. Connect or create a network to start sharing.",
            silent = true,
            trusted = false
        )

    private fun buildServiceNotification(
        title: String,
        text: String,
        silent: Boolean,
        trusted: Boolean
    ): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 1, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, WebDAVService::class.java).apply { action = "STOP_SERVICE" }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setColor(if (trusted) "#2BAED5".toColorInt() else android.graphics.Color.GRAY)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "TURN OFF", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(silent)
            .build()
    }

    fun showTrustNotification(context: Context, ssid: String, silent: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(
            1, buildServiceNotification(
                title = "Network sharing is off",
                text = "\"$ssid\" is unknown. Open the app to decide whether to allow it.",
                silent = silent,
                trusted = false
            )
        )
    }

    fun showBlockedNetworkNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(
            1, buildServiceNotification(
                title = "Network sharing is off",
                text = "This network is blocked. Open the app to manage trusted networks.",
                silent = true,
                trusted = false
            )
        )
    }

    fun restoreSharingNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(
            1, buildServiceNotification(
                title = "Network sharing is on",
                text = "Your phone can be accessed by other devices on this network",
                silent = true,
                trusted = true
            )
        )
    }

    fun ensureChannel(manager: NotificationManager?) {
        val channel = NotificationChannel(
            trustChannelId,
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

    private fun dismissProgress(fileName: String) {
        getSystemService(NotificationManager::class.java)?.cancel(fileName.hashCode())
    }

    private fun showMultipleNetworksWarning() {
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                this,
                "You're connected to multiple networks. For a secure, reliable share, connect to only one at a time.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showTransferFailedToast(fileName: String, message: String) {
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                this,
                "Transfer failed: $fileName ($message)",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    fun showSafetyAlert(fileName: String) {
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                this,
                "Safety Lock Active: $fileName. Please wait for 25 seconds",
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
            .setContentText("A recent transfer of '$fileName' failed. Please wait for up to 25 seconds before trying to delete.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor("#2BAED5".toColorInt())
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(25000)
            .setContentIntent(pendingIntent)

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify("safety_$fileName".hashCode(), builder.build())
    }

    private fun showProgressNotification(
        fileName: String,
        currentBytes: Long,
        totalBytes: Long,
        isReceiving: Boolean
    ) {
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
            .setSmallIcon(if (isReceiving) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
            .setContentTitle(if (isReceiving) "Downloading $fileName" else "Uploading $fileName")
            .setColor("#2BAED5".toColorInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent, false)
            .setContentText("${formatSize(currentBytes)} / ${formatSize(totalBytes)}")
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "CANCEL", pendingCancel)

        manager?.notify(notificationId, builder.build())
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

    // ── Companion ─────────────────────────────────────────────

    companion object {
        var isRunning = false
        var isWaitingForHotspot = false
        var networkState = mutableStateOf(NetworkState.NO_NETWORK)
        var isAuthEnabled = mutableStateOf(false)
        var isNetworkTrusted = mutableStateOf(false)
        var pendingTrustSsid = mutableStateOf<String?>(null)
        var username = mutableStateOf(sanitizeHostnameSegment(Build.MODEL))
        var password = mutableStateOf("pass")
        var useFallbackAddress = mutableStateOf(false)
        val activeServers = mutableListOf<Server>()

        private fun sanitizeHostnameSegment(raw: String): String {
            return raw
                .trim()
                .replace(Regex("\\s+"), "-")
                .filter { it.isLetterOrDigit() || it == '-' }
                .trim('-')
                .ifEmpty { "device" }
        }

        val friendlyServerAddress: String
            get() {
                val server = activeServers.firstOrNull() ?: return "http://0.0.0.0:8080"
                return "${server.scheme}://${username.value.lowercase()}:${server.port}"
            }

        fun baseAddressForPort(port: Int): String {
            val server =
                activeServers.firstOrNull { it.port == port } ?: activeServers.firstOrNull()
            val scheme = server?.scheme ?: "http"
            return if (useFallbackAddress.value) {
                "$scheme://${server?.ip ?: "0.0.0.0"}:$port"
            } else {
                friendlyServerAddress
            }
        }

        private val cancelledFiles = Collections.synchronizedSet(mutableSetOf<String>())

        fun requestCancel(fileName: String) {
            cancelledFiles.add(fileName)
        }

        fun isCancelRequested(fileName: String): Boolean {
            return cancelledFiles.remove(fileName)
        }

        fun clearCancel(fileName: String) {
            cancelledFiles.remove(fileName)
        }

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
                putBoolean("use_fallback_address", useFallbackAddress.value)
                putStringSet("shared_paths", FileManager.selectedPaths.toSet())
            }
        }

        fun loadPaths(context: Context) {
            val prefs = context.getSharedPreferences("network_share_prefs", MODE_PRIVATE)
            isAuthEnabled.value = prefs.getBoolean("auth_enabled", true)
            username.value =
                sanitizeHostnameSegment(prefs.getString("username", Build.MODEL) ?: Build.MODEL)
            password.value = prefs.getString("password", "pass") ?: "pass"
            useFallbackAddress.value = prefs.getBoolean("use_fallback_address", false)
            val saved = prefs.getStringSet("shared_paths", null)
            val usedDefaults = FileManager.applySelectedPaths(saved)
            if (usedDefaults) savePaths(context)
        }
    }
}

class TransferCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fileName = intent.getStringExtra("file_name") ?: return
        WebDAVService.requestCancel(fileName)
    }
}

enum class NetworkState { NO_NETWORK, UNTRUSTED, TRUSTED }
