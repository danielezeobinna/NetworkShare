package com.danieleze.networkshare

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.danieleze.networkshare.ui.theme.AppTheme
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import java.io.File

abstract class AppControl : androidx.fragment.app.FragmentActivity() {

    companion object {
        private const val REQ_PIN = 9999
        var isUnlocked by mutableStateOf(false)
        var instance: AppControl? = null
    }

    // ── State exposed to the UI ───────────────────────────────────────────────
    var isValidNetwork by mutableStateOf(true); protected set
    var serverAddresses by mutableStateOf(""); protected set
    var isDiscoveryOn by mutableStateOf(false); protected set
    var isPending by mutableStateOf(false); protected set
    var appTheme by mutableStateOf(AppTheme.SYSTEM); protected set
    var showLocationOffDialog by mutableStateOf(false)
    var showNetworkDialog by mutableStateOf(false)
    var showUnknownNetworkDialog by mutableStateOf(false)
    var showNotificationDialog by mutableStateOf(false)
    var pendingNotificationCheck = false
    var pendingLocationCheck = false
    var pendingStorageCheck = false

    // ── Private fields ────────────────────────────────────────────────────────
    private var pausedAtTime = 0L
    private var isShowingAd = false
    private var interstitialAd: InterstitialAd? = null

    // ── Permission launcher ───────────────────────────────────────────────────
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleService(true)
    }

    // ── Broadcast receiver ────────────────────────────────────────────────────
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.danieleze.networkshare.SERVER_STOPPED" -> {
                    isDiscoveryOn = false
                }

                "com.danieleze.networkshare.CHECK_LOCATION" -> {
                    checkLocationForUntrustedNetwork()
                }

                "com.danieleze.networkshare.ADDRESSES_UPDATED" -> {
                    val data = intent.getStringExtra("address_list")
                    val validNetwork = intent.getBooleanExtra("is_valid_network", true)
                    if (data != null) {
                        serverAddresses = data
                        isValidNetwork = validNetwork
                        saveAddresses(data)
                    }
                }
            }
        }
    }

    // ── Storage helpers ───────────────────────────────────────────────────────
    internal fun getAvailableStorages(): List<File> {
        val storages = mutableListOf<File>()
        getExternalFilesDirs(null).forEach { dir ->
            if (dir != null) {
                val path = dir.absolutePath
                val rootPath = if (path.contains("/Android/")) path.split("/Android/")[0] else path
                val rootFile = File(rootPath)
                if (rootFile.exists() && rootFile.canRead() && !storages.contains(rootFile)) {
                    storages.add(rootFile)
                }
            }
        }
        return storages
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @SuppressLint("ObsoleteSdkInt")
    override fun onCreate(savedInstanceState: Bundle?) {
        instance = null
        super.onCreate(savedInstanceState)
        instance = this
        Log.d("AppControl", "STARTED — app opened")
        startService(Intent(this, AppControlService::class.java))

        if (savedInstanceState == null) isUnlocked = false

        isPending = false
        isDiscoveryOn = isServiceRunning()
        WebDAVService.loadPaths(this.applicationContext)

        if (savedInstanceState == null) {
            handleIncomingShare(intent)
            loadAddresses()
        }

        MobileAds.initialize(this.applicationContext) {}
        loadInterstitialAd()

        val savedTheme = getPreferences(MODE_PRIVATE).getString("app_theme", "SYSTEM")
        appTheme = AppTheme.valueOf(savedTheme ?: "SYSTEM")
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction("com.danieleze.networkshare.SERVER_STOPPED")
            addAction("com.danieleze.networkshare.ADDRESSES_UPDATED")
            addAction("com.danieleze.networkshare.CHECK_LOCATION")
        }
        val listenFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            RECEIVER_NOT_EXPORTED else 0
        registerReceiver(receiver, filter, listenFlag)
    }

    override fun onStop() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        isDiscoveryOn = isServiceRunning()

        if (pausedAtTime > 0L && System.currentTimeMillis() - pausedAtTime >= 30_000L) {
            isUnlocked = false
        }
        pausedAtTime = 0L

        // Returning from interstitial — skip auth re-check
        if (isShowingAd) {
            isShowingAd = false
            refreshServiceIfRunning()
            val pending = WebDAVService.pendingTrustSsid.value
            if (pending != null && isDiscoveryOn) showUnknownNetworkDialog = true
            return
        }

        refreshServiceIfRunning()
        val pending = WebDAVService.pendingTrustSsid.value
        if (pending != null && isDiscoveryOn) showUnknownNetworkDialog = true
    }

    override fun onPause() {
        super.onPause()
        if (!isShowingAd && !isChangingConfigurations) {
            pausedAtTime = System.currentTimeMillis()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PIN && resultCode == RESULT_OK) {
            initPermissions()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (!granted) {
                stopService(Intent(this, WebDAVService::class.java))
                isDiscoveryOn = false
                Toast.makeText(
                    this,
                    "Storage permission is required to use NetworkShare",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                isUnlocked = true
            }
        }
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        Log.d("AppControl", "STOPPED — app closed")
        stopService(Intent(this, AppControlService::class.java))
        super.onDestroy()
    }

    // ── Authentication ────────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    fun showBiometricPrompt() {
        val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        val biometricManager = BiometricManager.from(this)

        if (!km.isDeviceSecure) {
            initPermissions(); return
        }

        val launchLegacyPin = {
            @Suppress("DEPRECATION")
            val intent = km.createConfirmDeviceCredentialIntent("NetworkShare Security", null)
            if (intent != null) startActivityForResult(intent, REQ_PIN) else initPermissions()
        }

        val canUseBiometrics = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS

        if (!canUseBiometrics) {
            launchLegacyPin(); return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(
            this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    initPermissions()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> launchLegacyPin()

                        BiometricPrompt.ERROR_USER_CANCELED -> { /* let user retry */
                        }

                        else -> launchLegacyPin()
                    }
                }
            })

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("NetworkShare Security")
            .setSubtitle("Authenticate to manage your server")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            builder.setNegativeButtonText("Try Another Way")
        }

        biometricPrompt.authenticate(builder.build())
    }

    // ── Permission init ───────────────────────────────────────────────────────
    private fun initPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:${applicationContext.packageName}".toUri()
                }
                pendingStorageCheck = true
                startActivity(intent)
                startService(Intent(this, AppControlService::class.java))
            } else {
                isUnlocked = true
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            androidx.core.app.ActivityCompat.requestPermissions(this, permissions, 101)
        }
    }

    // ── Service control ───────────────────────────────────────────────────────
    fun handleToggle(start: Boolean) {
        if (start) {
            val notifManager = getSystemService(android.app.NotificationManager::class.java)
            if (!notifManager.areNotificationsEnabled()) {
                showNotificationDialog = true
                return
            }
        }

        if (isPending) return
        isPending = true

        if (start && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toggleService(start)
        }

        // Show interstitial opportunistically when turning on
        if (start) {
            interstitialAd?.let { ad ->
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        interstitialAd = null; loadInterstitialAd()
                    }

                    override fun onAdFailedToShowFullScreenContent(
                        error: com.google.android.gms.ads.AdError
                    ) {
                        interstitialAd = null; loadInterstitialAd()
                    }
                }
                isShowingAd = true
                ad.show(this)
            }
        }
    }

    fun isServiceRunning() = WebDAVService.isRunning

    private fun toggleService(start: Boolean) {
        val intent = Intent(this, WebDAVService::class.java)
        try {
            if (start) {
                startForegroundService(intent); isDiscoveryOn = true
            } else {
                stopService(intent); isDiscoveryOn = false
            }
        } finally {
            window.decorView.postDelayed({ isPending = false }, 500)
        }
    }

    private fun refreshServiceIfRunning() {
        if (!isDiscoveryOn) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startService(Intent(this, WebDAVService::class.java).apply { action = "REFRESH_INFO" })
        }, 1500)
    }

    // ── Location helpers ──────────────────────────────────────────────────────
    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                coarse == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun requestLocationPermissions() {
        androidx.core.app.ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            101
        )
    }

    fun checkLocationForUntrustedNetwork() {
        if (!hasLocationPermission()) {
            requestLocationPermissions(); return
        }
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val isOn = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        if (!isOn) showLocationOffDialog = true
    }

    // ── Ad loading ────────────────────────────────────────────────────────────
    private fun loadInterstitialAd() {
        InterstitialAd.load(
            this,
            BuildConfig.ADMOB_INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                }
            }
        )
    }

    // ── Preference helpers ────────────────────────────────────────────────────
    private fun saveAddresses(addresses: String) {
        getPreferences(MODE_PRIVATE).edit { putString("last_addresses", addresses) }
    }

    private fun loadAddresses() {
        serverAddresses = getPreferences(MODE_PRIVATE)
            .getString("last_addresses", "loading...") ?: ""
    }

    fun saveTheme(theme: AppTheme) {
        appTheme = theme
        getPreferences(MODE_PRIVATE).edit { putString("app_theme", theme.name) }
    }

    // ── Incoming share handling ───────────────────────────────────────────────
    fun handleIncomingShare(intent: Intent) {
        val action = intent.action
        val type = intent.type
        if (Intent.ACTION_SEND == action && type != null) {
            val uri = androidx.core.content.IntentCompat.getParcelableExtra(
                intent, Intent.EXTRA_STREAM, android.net.Uri::class.java
            )
            if (uri != null) saveUriToSharedFolder(uri)
        } else if (Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            androidx.core.content.IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, android.net.Uri::class.java
            )?.forEach { saveUriToSharedFolder(it) }
        }
    }

    private fun saveUriToSharedFolder(uri: android.net.Uri) {
        try {
            val rootDir = Environment.getExternalStorageDirectory()
            val networkShareDir = File(rootDir, "NetworkShare")

            if (!networkShareDir.exists()) {
                if (!networkShareDir.mkdirs())
                    Log.e("NetworkShare", "Could not create root folder, check permissions")
            }

            val fileName = getFileName(uri) ?: "shared_${System.currentTimeMillis()}"
            val extension = fileName.substringAfterLast('.', "").lowercase()

            val isDirectory = contentResolver.getType(uri) == "vnd.android.cursor.item/directory"
            val subFolder = when {
                isDirectory -> "Folders"
                extension in listOf("apk", "exe") -> "Apps"
                extension in listOf("mp3", "wav", "m4a", "flac", "ogg") -> "Audio"
                extension in listOf("mp4", "mkv", "mov", "avi", "webm") -> "Video"
                extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> "Pictures"
                extension in listOf(
                    "pdf",
                    "doc",
                    "docx",
                    "txt",
                    "xls",
                    "xlsx",
                    "ppt",
                    "pptx"
                ) -> "Documents"

                else -> "Others"
            }

            val targetDir = File(networkShareDir, subFolder).also { if (!it.exists()) it.mkdirs() }
            val destFile = File(targetDir, fileName)

            contentResolver.openInputStream(uri)?.use { it.copyTo(destFile.outputStream()) }

            val path = networkShareDir.absolutePath
            if (!FileManager.selectedPaths.contains(path)) {
                FileManager.selectedPaths.add(path)
                WebDAVService.savePaths(this)
            }
            FileManager.tempPriorityPath = path

            Toast.makeText(this, "Shared on NetworkShare", Toast.LENGTH_SHORT).show()
            startService(Intent(this, WebDAVService::class.java).apply { action = "REFRESH_INFO" })

        } catch (e: Exception) {
            Log.e("NetworkShare", "Error: ${e.message}")
            Toast.makeText(this, "Error saving file", Toast.LENGTH_LONG).show()
        }
    }

    private fun getFileName(uri: android.net.Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// CopyFileAddressActivity — lightweight activity invoked by the share sheet
// ─────────────────────────────────────────────────────────────────────────────
class CopyFileAddressActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebDAVService.loadPaths(this)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type == null) {
            toast("Unsupported share type"); return
        }
        val uri = androidx.core.content.IntentCompat.getParcelableExtra(
            intent, Intent.EXTRA_STREAM, android.net.Uri::class.java
        ) ?: run { toast("No file found in share"); return }

        if (!WebDAVService.isRunning) {
            toast("NetworkShare is not running. Turn it on first."); return
        }

        val realPath = resolveRealPath(uri) ?: run {
            toast("Could not resolve file path. Try sharing from a different file manager."); return
        }

        val url = buildUrl(realPath) ?: run {
            toast("This file is not in a shared folder.\nAdd its folder in Choose Shared Paths first."); return
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("File Address", url))
        toast("File address copied!")
    }

    private fun resolveRealPath(uri: android.net.Uri): String? {
        if (uri.scheme == "content") {
            try {
                val docId = android.provider.DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("primary:"))
                    return "${Environment.getExternalStorageDirectory()}/${docId.removePrefix("primary:")}"
                if (docId.contains(":")) {
                    val parts = docId.split(":", limit = 2)
                    return "/storage/${parts[0]}/${parts[1]}"
                }
            } catch (_: Exception) {
            }
            try {
                contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex("_data")
                        if (index != -1) {
                            val path = cursor.getString(index)
                            if (!path.isNullOrBlank()) return path
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (uri.scheme == "file") return uri.path
        return null
    }

    private fun buildUrl(realPath: String): String? {
        val isShared = FileManager.selectedPaths.any { shared ->
            realPath == shared || realPath.startsWith("$shared/")
        }
        if (!isShared) return null

        val root = getExternalFilesDirs(null).filterNotNull().mapNotNull { dir ->
            val path = dir.absolutePath
            if (path.contains("/Android/")) path.split("/Android/")[0] else path
        }.map { File(it) }.firstOrNull { realPath.startsWith(it.absolutePath) } ?: return null

        val ip = getLocalIp() ?: return null
        val port = getPortForRoot(root.absolutePath) ?: return null

        val relative = realPath
            .removePrefix(root.absolutePath).trimStart('/')
            .split("/")
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        return if (WebDAVService.isAuthEnabled.value) {
            val token = WebDAVService.generateToken()
            "http://$ip:$port/$relative?token=$token"
        } else {
            "http://$ip:$port/$relative"
        }
    }

    private fun getLocalIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }?.hostAddress
    } catch (_: Exception) {
        null
    }

    private fun getPortForRoot(rootPath: String): Int? {
        val roots = getExternalFilesDirs(null).filterNotNull().mapNotNull { dir ->
            val path = dir.absolutePath
            if (path.contains("/Android/")) path.split("/Android/")[0] else path
        }.distinct()

        var port = 8080
        for (root in roots) {
            val hasShared = FileManager.selectedPaths.any { it.startsWith(root) }
            if (hasShared) {
                if (root == rootPath) return port
                port++
            }
        }
        return null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}


// ─────────────────────────────────────────────────────────────────────────────
// AppControlService — background service tied to app lifetime.
// ─────────────────────────────────────────────────────────────────────────────
class AppControlService : Service() {

    companion object {
        var isRunning = false
            private set
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isHeartbeatActive = false

    private val heartbeat = object : Runnable {
        override fun run() {
            val control = AppControl.instance

            if (control == null) {
                Log.d("AppControlService", "AppControl gone — stopping service")
                stopSelf()
                return
            }

            checkPendingPermissions(control)

            val stillHasPendingCheck = control.pendingNotificationCheck ||
                    control.pendingLocationCheck ||
                    control.pendingStorageCheck

            if (stillHasPendingCheck) {
                handler.postDelayed(this, 1000L)
            } else {
                Log.d("AppControlService", "All permissions cleared. Stopping heartbeat loop.")
                isHeartbeatActive = false
            }
        }
    }

    private fun startHeartbeatIfNeeded() {
        if (!isHeartbeatActive) {
            isHeartbeatActive = true
            Log.d("AppControlService", "Starting 1-second permission heartbeat loop")
            handler.post(heartbeat) // Starts immediately
        }
    }

    private fun checkPendingPermissions(control: AppControl) {

        // Notification permission check
        if (control.pendingNotificationCheck) {
            val notifManager = getSystemService(android.app.NotificationManager::class.java)
            if (notifManager.areNotificationsEnabled()) {
                Log.d("AppControlService", "Notification permission granted — returning to app")
                control.pendingNotificationCheck = false
                control.showNotificationDialog = false
                bringAppForward()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    control.handleToggle(true)
                }
            }
        }

        // Location permission check
        if (control.pendingLocationCheck) {
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            val isOn = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            if (isOn) {
                Log.d("AppControlService", "Location turned on — returning to app")
                control.pendingLocationCheck = false
                control.showLocationOffDialog = false
                bringAppForward()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    control.checkLocationForUntrustedNetwork()
                }
            }
        }

        // All files access permission check
        if (control.pendingStorageCheck) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Log.d("AppControlService", "All files access granted — returning to app")
                    control.pendingStorageCheck = false
                    bringAppForward()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        AppControl.isUnlocked = true
                    }
                }
            }
        }
    }

    private fun bringAppForward() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d("AppControlService", "Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startHeartbeatIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(heartbeat)
        isHeartbeatActive = false
        Log.d("AppControlService", "Service Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}