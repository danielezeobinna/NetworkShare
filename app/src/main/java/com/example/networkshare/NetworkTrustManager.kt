package com.example.networkshare

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.app.NotificationCompat

// ─────────────────────────────────────────────────────────────
//  Trust Manager
// ─────────────────────────────────────────────────────────────
object NetworkTrustManager {

    private const val PREFS       = "network_trust_prefs"
    private const val KEY_ALLOWED = "allowed_networks"
    private const val KEY_BLOCKED = "blocked_networks"

    const val CHANNEL_ID       = "network_trust_channel"
    const val EXTRA_SSID       = "extra_ssid"
    const val ACTION_ALLOW      = "com.example.networkshare.NETWORK_ALLOW"
    const val ACTION_ALLOW_ONCE = "com.example.networkshare.NETWORK_ALLOW_ONCE"
    const val ACTION_BLOCK      = "com.example.networkshare.NETWORK_BLOCK"

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

    // ── Notification ─────────────────────────────────────────

    fun showTrustNotification(context: Context, ssid: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)

        fun pendingFor(action: String): PendingIntent {
            val i = Intent(context, NetworkTrustActionReceiver::class.java).apply {
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
            .setContentTitle("Unknown Network")
            .setContentText("\"$ssid\" is not a trusted network. Allow sharing on it?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setColor("#2BAED5".toColorInt())
            .setAutoCancel(true)
            .addAction(0, "Allow",      pendingFor(ACTION_ALLOW))
            .addAction(0, "Allow Once", pendingFor(ACTION_ALLOW_ONCE))
            .addAction(0, "Block",      pendingFor(ACTION_BLOCK))
            .build()

        manager?.notify(ssid.hashCode(), notification)
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
}

// ─────────────────────────────────────────────────────────────
//  Notification Action Receiver
// ─────────────────────────────────────────────────────────────
class NetworkTrustActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ssid = intent.getStringExtra(NetworkTrustManager.EXTRA_SSID) ?: return
        when (intent.action) {
            NetworkTrustManager.ACTION_ALLOW      -> NetworkTrustManager.allow(context, ssid)
            NetworkTrustManager.ACTION_ALLOW_ONCE -> NetworkTrustManager.allowOnce(ssid)
            NetworkTrustManager.ACTION_BLOCK      -> NetworkTrustManager.block(context, ssid)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(ssid.hashCode())
    }
}