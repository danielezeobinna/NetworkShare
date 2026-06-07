package com.danieleze.networkshare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.edit
import java.net.Inet4Address
import java.net.NetworkInterface
import android.app.ActivityManager

// ─────────────────────────────────────────────────────────────
//  Network Manager
// ─────────────────────────────────────────────────────────────
object NetworkManager {

    private const val PREFS       = "network_trust_prefs"
    private const val KEY_ALLOWED = "allowed_networks"
    private const val KEY_BLOCKED = "blocked_networks"
    private const val TAG         = "NetworkManager"

    const val CHANNEL_ID        = "network_trust_channel"
    const val EXTRA_SSID        = "extra_ssid"
    const val ACTION_ALLOW      = "com.danieleze.networkshare.NETWORK_ALLOW"
    const val ACTION_ALLOW_ONCE = "com.danieleze.networkshare.NETWORK_ALLOW_ONCE"
    const val ACTION_BLOCK      = "com.danieleze.networkshare.NETWORK_BLOCK"

    // Persisted
    val allowedNetworks = mutableStateListOf<String>()
    val blockedNetworks = mutableStateListOf<String>()

    // In-memory only — wiped when service stops
    val allowOnceNetworks = mutableSetOf<String>()

    // ── Callback interface ────────────────────────────────────
    // WebDAVService implements this so NetworkManager can trigger
    // server restarts and notification updates without a hard reference.

    interface NetworkEventListener {
        fun onNetworkTrustChanged(state: NetworkState, ssid: String)
        fun onRefreshServersIfNeeded()
        fun onUnknownNetworkDetected(ssid: String, silent: Boolean)
        fun onHotspotEnabled()
        fun onWifiEnabled()
    }

    private var eventListener: NetworkEventListener? = null

    fun setEventListener(listener: NetworkEventListener?) {
        eventListener = listener
    }

    // ── Hardware broadcast receiver ───────────────────────────
    // Listens for hotspot and Wi-Fi toggle events and delegates
    // network-state decisions to NetworkManager.
    private var hardwareReceiver: BroadcastReceiver? = null

    fun registerHardwareReceiver(context: Context) {
        hardwareReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action

                if (action == "android.net.wifi.WIFI_AP_STATE_CHANGED") {
                    val state = intent.getIntExtra("wifi_state", -1)
                    when (state) {
                        13 -> eventListener?.onHotspotEnabled()
                        11, 14 -> verifyAndStop()
                    }
                }

                if (action == android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION) {
                    val state = intent.getIntExtra(android.net.wifi.WifiManager.EXTRA_WIFI_STATE, 1)
                    when (state) {
                        android.net.wifi.WifiManager.WIFI_STATE_ENABLED ->
                            eventListener?.onWifiEnabled()
                        android.net.wifi.WifiManager.WIFI_STATE_DISABLED ->
                            verifyAndStop()
                    }
                }
            }
        }

        val filter = android.content.IntentFilter().apply {
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        context.registerReceiver(hardwareReceiver, filter)
    }

    fun unregisterHardwareReceiver(context: Context) {
        hardwareReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        hardwareReceiver = null
    }

    // ── Persistence ───────────────────────────────────────────

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        allowedNetworks.clear()
        blockedNetworks.clear()
        allowedNetworks.addAll(prefs.getStringSet(KEY_ALLOWED, emptySet()) ?: emptySet())
        blockedNetworks.addAll(prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet())
    }

    private fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putStringSet(KEY_ALLOWED, allowedNetworks.toSet())
            putStringSet(KEY_BLOCKED, blockedNetworks.toSet())
        }
    }

    fun isAppInForeground(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        return appProcesses.any {
            it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    it.processName == context.packageName
        }
    }

    // ── Trust management ──────────────────────────────────────

    fun allow(context: Context, ssid: String) {
        blockedNetworks.remove(ssid)
        allowOnceNetworks.remove(ssid)
        if (!allowedNetworks.contains(ssid)) allowedNetworks.add(ssid)
        save(context)
    }

    fun allowOnce(ssid: String) {
        blockedNetworks.remove(ssid)
        allowedNetworks.remove(ssid)
        allowOnceNetworks.add(ssid)
    }

    fun block(context: Context, ssid: String) {
        allowedNetworks.remove(ssid)
        allowOnceNetworks.remove(ssid)
        if (!blockedNetworks.contains(ssid)) blockedNetworks.add(ssid)
        save(context)
    }

    fun remove(context: Context, ssid: String) {
        allowedNetworks.remove(ssid)
        blockedNetworks.remove(ssid)
        allowOnceNetworks.remove(ssid)
        save(context)
    }

    enum class Trust { ALLOWED, ALLOW_ONCE, BLOCKED, UNKNOWN }

    fun getTrust(ssid: String): Trust = when {
        allowedNetworks.contains(ssid)   -> Trust.ALLOWED
        allowOnceNetworks.contains(ssid) -> Trust.ALLOW_ONCE
        blockedNetworks.contains(ssid)   -> Trust.BLOCKED
        else                             -> Trust.UNKNOWN
    }

    fun isHotspot(ssid: String) = ssid.isBlank() || ssid == "<unknown ssid>"

    // ── Network utilities ─────────────────────────────────────

    /**
     * Returns the device's local IPv4 address on the active Wi-Fi,
     * hotspot, soft-AP, or RNDIS (USB tethering) interface.
     * Returns null if no suitable address is found.
     */
    fun getLocalIpAddress(): String? {
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

    /**
     * Returns true if any Wi-Fi, hotspot, or soft-AP interface is
     * currently up and has a non-loopback address.
     */
    fun isNetworkAvailable(): Boolean {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().any { intf ->
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

    // ── Network callback registration ─────────────────────────

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    fun registerNetworkCallback(
        context: Context,
        onSsidChanged: (ssid: String) -> Unit,
        onNetworkLost: () -> Unit,
        isAppInForeground: () -> Boolean
    ) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
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
                        onSsidChanged(ssid)
                        Log.d(TAG, "NetworkCallback SSID: $ssid")

                        if (!isHotspot(ssid)) {
                            when (getTrust(ssid)) {
                                Trust.UNKNOWN -> {
                                    Log.d(TAG, "Unknown network — showing notification for: $ssid")
                                    val silent = isAppInForeground()
                                    eventListener?.onUnknownNetworkDetected(ssid, silent)
                                }
                                Trust.BLOCKED -> {
                                    Log.d(TAG, "Blocked network: $ssid — server will return 403")
                                }
                                else -> {
                                    Log.d(TAG, "Trusted network: $ssid")
                                }
                            }
                        }
                    }
                    eventListener?.onRefreshServersIfNeeded()
                }

                override fun onLost(network: android.net.Network) {
                    onNetworkLost()
                }
            }
        } else {
            // Android 8–11 — fallback without the flag
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
                        (context.applicationContext.getSystemService(Context.WIFI_SERVICE)
                                as android.net.wifi.WifiManager)
                            .connectionInfo.ssid?.removeSurrounding("\"") ?: ""
                    }

                    if (ssid.isNotBlank() && ssid != "<unknown ssid>") {
                        onSsidChanged(ssid)

                        if (!isHotspot(ssid)) {
                            when (getTrust(ssid)) {
                                Trust.UNKNOWN -> {
                                    Log.d(TAG, "Unknown network — showing notification for: $ssid")
                                    val silent = isAppInForeground()
                                    eventListener?.onUnknownNetworkDetected(ssid, silent)
                                }
                                Trust.BLOCKED -> {
                                    Log.d(TAG, "Blocked network: $ssid — server will return 403")
                                }
                                else -> Log.d(TAG, "Trusted network: $ssid")
                            }
                        }
                    }

                    eventListener?.onRefreshServersIfNeeded()
                }

                override fun onLost(network: android.net.Network) {
                    onNetworkLost()
                }
            }
        }

        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    /**
     * Unregisters the previously registered network callback.
     * Safe to call even if no callback was registered.
     */
    fun unregisterNetworkCallback(context: Context) {
        networkCallback?.let {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                        as android.net.ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    // ── Network trust state evaluation ───────────────────────

    fun updateNetworkTrust(
        context: Context,
        currentSsid: String,
        wifiJustEnabled: Boolean,
        onWifiJustEnabledConsumed: () -> Unit
    ) {
        val isOnHotspot = try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiManager) as Boolean
        } catch (_: Exception) { false }

        if (isOnHotspot) {
            eventListener?.onNetworkTrustChanged(NetworkState.TRUSTED, "")
            Log.d(TAG, "Hotspot active — always trusted")
            return
        }

        val ssid = if (currentSsid.isNotBlank()) {
            currentSsid
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val connectivityManager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = connectivityManager.activeNetwork
            val caps = if (network != null) connectivityManager.getNetworkCapabilities(network) else null
            val info = caps?.transportInfo as? android.net.wifi.WifiInfo
            info?.ssid?.removeSurrounding("\"") ?: ""
        } else {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo.ssid?.removeSurrounding("\"") ?: ""
        }

        Log.d(TAG, "WLAN — SSID: '$ssid' | trust: ${getTrust(ssid)}")

        when {
            ssid.isBlank() || ssid == "<unknown ssid>" -> {
                if (wifiJustEnabled) {
                    val locationIntent = Intent("com.danieleze.networkshare.CHECK_LOCATION")
                    locationIntent.setPackage(context.packageName)
                    context.sendBroadcast(locationIntent)
                    onWifiJustEnabledConsumed()
                }
                eventListener?.onNetworkTrustChanged(NetworkState.NO_NETWORK, ssid)
            }

            isHotspot(ssid) -> {
                eventListener?.onNetworkTrustChanged(NetworkState.TRUSTED, ssid)
            }

            getTrust(ssid) == Trust.ALLOWED || getTrust(ssid) == Trust.ALLOW_ONCE -> {
                eventListener?.onNetworkTrustChanged(NetworkState.TRUSTED, ssid)
            }

            else -> {
                eventListener?.onNetworkTrustChanged(NetworkState.UNTRUSTED, ssid)
            }
        }
    }

    fun verifyAndStop() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isNetworkAvailable()) {
                eventListener?.onNetworkTrustChanged(NetworkState.NO_NETWORK, "")
            } else {
                eventListener?.onRefreshServersIfNeeded()
            }
        }, 1500)
    }
}

// ─────────────────────────────────────────────────────────────
//  Notification Action Receiver
// ─────────────────────────────────────────────────────────────
class NetworkActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ssid = intent.getStringExtra(NetworkManager.EXTRA_SSID) ?: return
        when (intent.action) {
            NetworkManager.ACTION_ALLOW      -> NetworkManager.allow(context, ssid)
            NetworkManager.ACTION_ALLOW_ONCE -> NetworkManager.allowOnce(ssid)
            NetworkManager.ACTION_BLOCK      -> NetworkManager.block(context, ssid)
        }
        context.startService(
            Intent(context, WebDAVService::class.java).apply {
                action = "RESTORE_NOTIFICATION"
            }
        )
    }
}