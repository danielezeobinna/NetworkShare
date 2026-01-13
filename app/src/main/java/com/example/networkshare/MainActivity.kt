package com.example.networkshare

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.example.networkshare.ui.theme.NetworkShareTheme
import java.net.Inet4Address
import java.net.NetworkInterface
import android.net.ConnectivityManager

class MainActivity : ComponentActivity() {

    private var serverAddresses by mutableStateOf("Service is off")
    private var isDiscoveryOn by mutableStateOf(false)
    private var isPending by mutableStateOf(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { isGranted ->
        if (!isGranted.values.all { it }) {
            Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleService(true)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.networkshare.SERVER_STOPPED" -> {
                    isDiscoveryOn = false
                    serverAddresses = "Service is off"
                }
                "com.example.networkshare.ADDRESSES_UPDATED" -> {
                    val data = intent.getStringExtra("address_list")
                    serverAddresses = data ?: "No storage found"
                }
            }
        }
    }

    private val hotspotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra("wifi_state", 11)
            if (state == 11 || state == 10) {
                if (isDiscoveryOn) {
                    val ip = getLocalIpAddress()
                    val isLocal = ip != null && (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172."))

                    if (!isLocal) {
                        toggleService(false)
                        isDiscoveryOn = false
                        Toast.makeText(applicationContext, "Network lost: Server stopped", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initPermissions()

        isDiscoveryOn = isServiceRunning()
        if (isDiscoveryOn) updateAddresses()

        setContent {
            NetworkShareTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DiscoveryScreen(
                        isOn = isDiscoveryOn,
                        isPending = isPending,
                        addresses = serverAddresses,
                        onToggle = { start -> handleToggle(start) }
                    )
                }
            }
        }
        startNetworkWatchdog()
        registerReceiver(hotspotReceiver, IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"))
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction("com.example.networkshare.SERVER_STOPPED")
            addAction("com.example.networkshare.ADDRESSES_UPDATED")
        }
        val listenFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RECEIVER_NOT_EXPORTED
        } else { 0 }
        registerReceiver(receiver, filter, listenFlag)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(hotspotReceiver) } catch (_: Exception) {}
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback?.let { cm.unregisterNetworkCallback(it) }
    }

    override fun onResume() {
        super.onResume()
        val running = isServiceRunning()
        isDiscoveryOn = running

        if (running) {
            val intent = Intent(this, WebDAVService::class.java).apply {
                action = "REFRESH_INFO"
            }
            startService(intent)
        } else {
            serverAddresses = "Service is off"
        }
    }

    private fun startNetworkWatchdog() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = android.net.NetworkRequest.Builder().build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: android.net.Network) { checkAndTurnOff() }
            override fun onCapabilitiesChanged(network: android.net.Network, nc: android.net.NetworkCapabilities) { checkAndTurnOff() }

            private fun checkAndTurnOff() {
                runOnUiThread {
                    val ip = getLocalIpAddress()
                    val isLocal = ip != null && (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172."))

                    if (isDiscoveryOn && !isLocal) {
                        toggleService(false)
                        isDiscoveryOn = false
                        serverAddresses = "Service is off"
                        Toast.makeText(applicationContext, "Network lost: Server OFF", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        networkCallback?.let { cm.registerNetworkCallback(request, it) }
    }

    private fun handleToggle(start: Boolean) {
        if (isPending) return

        if (start) {
            val currentIp = getLocalIpAddress()
            val isLocal = currentIp != null && (
                    currentIp.startsWith("192.") ||
                            currentIp.startsWith("10.") ||
                            currentIp.startsWith("172.")
                    )

            if (!isLocal) {
                Toast.makeText(this, "WiFi or Hotspot required", Toast.LENGTH_SHORT).show()
                isDiscoveryOn = false
                return
            }
        }

        isPending = true
        if (start && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toggleService(start)
        }
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

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Integer.MAX_VALUE).any {
            it.service.className == WebDAVService::class.java.name
        }
    }

    private fun toggleService(start: Boolean) {
        val intent = Intent(this, WebDAVService::class.java)
        try {
            if (start) {
                startForegroundService(intent)
                isDiscoveryOn = true
                updateAddresses()
            } else {
                stopService(intent)
                isDiscoveryOn = false
                serverAddresses = "Service is off"
            }
        } finally {
            window.decorView.postDelayed({ isPending = false }, 500)
        }
    }

    private fun updateAddresses() {
        if (isDiscoveryOn) {
            if (serverAddresses == "Service is off") {
                serverAddresses = "Scanning storages..."
            }
        } else {
            serverAddresses = "Service is off"
        }
    }

    private fun initPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:${applicationContext.packageName}".toUri()
                }
                startActivity(intent)
            }
        } else {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
        }
    }
}

@Composable
fun DiscoveryScreen(
    isOn: Boolean,
    isPending: Boolean,
    addresses: String,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Network discovery",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Text(
                    text = "Your phone can be accessed by your PC on the network",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }

            Switch(
                checked = isOn,
                onCheckedChange = { onToggle(it) },
                enabled = !isPending
            )
        }

        if (isOn) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Windows Explorer Addresses:",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Surface(
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = addresses,
                    modifier = Modifier.padding(16.dp),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}