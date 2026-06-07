package com.danieleze.networkshare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.edit

// ─────────────────────────────────────────────────────────────
//  Network Manager
// ─────────────────────────────────────────────────────────────
object NetworkManager {

    private const val PREFS       = "network_trust_prefs"
    private const val KEY_ALLOWED = "allowed_networks"
    private const val KEY_BLOCKED = "blocked_networks"

    const val CHANNEL_ID       = "network_trust_channel"
    const val EXTRA_SSID       = "extra_ssid"
    const val ACTION_ALLOW      = "com.danieleze.networkshare.NETWORK_ALLOW"
    const val ACTION_ALLOW_ONCE = "com.danieleze.networkshare.NETWORK_ALLOW_ONCE"
    const val ACTION_BLOCK      = "com.danieleze.networkshare.NETWORK_BLOCK"

    // Persisted
    val allowedNetworks = mutableStateListOf<String>()
    val blockedNetworks = mutableStateListOf<String>()

    // In-memory only — wiped when service stops
    val allowOnceNetworks = mutableSetOf<String>()

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
        // Clear pending trust so dialog doesn't re-trigger
        WebDAVService.pendingTrustSsid.value = null
        // Restore the "Network sharing is on" notification
        (context.applicationContext as? WebDAVService)?.restoreSharingNotification(context)
            ?: run {
                val intent = Intent(context, WebDAVService::class.java).apply {
                    action = "RESTORE_NOTIFICATION"
                }
                context.startService(intent)
            }
    }
}