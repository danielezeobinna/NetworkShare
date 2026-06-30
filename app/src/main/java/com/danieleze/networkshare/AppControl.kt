package com.danieleze.networkshare

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.danieleze.networkshare.ui.theme.AppTheme
import java.io.File

class AppControl(application: Application) : androidx.lifecycle.AndroidViewModel(application) {

    companion object {
        var isUnlocked by mutableStateOf(false)
        private const val PREFS_NAME = "app_control_prefs"
    }

    // ── State exposed to the UI ───────────────────────────────────────────────
    var isValidNetwork by mutableStateOf(true)
    var serverAddresses by mutableStateOf("")
    var isDiscoveryOn by mutableStateOf(false)
    var isPending by mutableStateOf(false)
    var appTheme by mutableStateOf(AppTheme.SYSTEM)
    var showLocationOffDialog by mutableStateOf(false)
    var showNetworkDialog by mutableStateOf(false)
    var showUnknownNetworkDialog by mutableStateOf(false)
    var showNotificationDialog by mutableStateOf(false)
    var pendingNotificationCheck = false
    var pendingLocationCheck = false
    var pendingStorageCheck = false

    init {
        loadAddresses()
        val savedTheme = prefs().getString("app_theme", "SYSTEM")
        appTheme = AppTheme.valueOf(savedTheme ?: "SYSTEM")
    }

    // ── Preference helpers ────────────────────────────────────────────────────
    private fun prefs() =
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveAddresses(addresses: String) {
        prefs().edit { putString("last_addresses", addresses) }
    }

    fun loadAddresses() {
        serverAddresses = prefs().getString("last_addresses", "loading...") ?: ""
    }

    fun saveTheme(theme: AppTheme) {
        appTheme = theme
        prefs().edit { putString("app_theme", theme.name) }
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
            val control = MainActivity.instance

            if (control == null) {
                Log.d("AppControlService", "MainActivity gone — stopping service")
                stopSelf()
                return
            }

            checkPendingPermissions(control)

            val vm = control.viewModel
            val stillHasPendingCheck = vm.pendingNotificationCheck ||
                    vm.pendingLocationCheck ||
                    vm.pendingStorageCheck

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

    private fun checkPendingPermissions(control: MainActivity) {
        val vm = control.viewModel

        if (vm.pendingNotificationCheck) {
            val notifManager = getSystemService(android.app.NotificationManager::class.java)
            if (notifManager.areNotificationsEnabled()) {
                Log.d("AppControlService", "Notification permission granted — returning to app")
                vm.pendingNotificationCheck = false
                vm.showNotificationDialog = false
                bringAppForward()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    control.handleToggle(true)
                }
            }
        }

        if (vm.pendingLocationCheck) {
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            val isOn = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            if (isOn) {
                Log.d("AppControlService", "Location turned on — returning to app")
                vm.pendingLocationCheck = false
                vm.showLocationOffDialog = false
                bringAppForward()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    control.checkLocationForUntrustedNetwork()
                }
            }
        }

        // All files access permission check
        if (vm.pendingStorageCheck) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Log.d("AppControlService", "All files access granted — returning to app")
                    vm.pendingStorageCheck = false
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