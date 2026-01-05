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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private var serverAddresses by mutableStateOf("Service is off")
    private var isDiscoveryOn by mutableStateOf(false)

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
            isDiscoveryOn = false
            serverAddresses = "Service is off"
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.example.networkshare.SERVER_STOPPED")

        val listenFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RECEIVER_NOT_EXPORTED
        } else {
            0
        }

        registerReceiver(receiver, filter, listenFlag)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        initPermissions()

        isDiscoveryOn = isServiceRunning()
        if (isDiscoveryOn) updateAddresses()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        DiscoveryScreen(
                            isOn = isDiscoveryOn,
                            addresses = serverAddresses,
                            onToggle = { start -> handleToggle(start) },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val running = isServiceRunning()
        isDiscoveryOn = running
        if (running) {
            updateAddresses()
        } else {
            serverAddresses = "Service is off"
        }
    }

    private fun handleToggle(start: Boolean) {
        if (start && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toggleService(start)
        }
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
        if (start) {
            startForegroundService(intent)
            isDiscoveryOn = true
            updateAddresses()
        } else {
            stopService(intent)
            isDiscoveryOn = false
            serverAddresses = "Service is off"
        }
    }

    private fun updateAddresses() {
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        serverAddresses = "Internal: http://$ip:8080/\nSD Card: http://$ip:8081/\n(Check notification for status)"
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
    addresses: String,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Network discovery", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(text = "Your phone can be accessed by your PC on the network", fontSize = 14.sp)
            }
            Switch(checked = isOn, onCheckedChange = { onToggle(it) })
        }

        if (isOn) {
            Spacer(modifier = Modifier.height(32.dp))
            Surface(tonalElevation = 8.dp, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(text = addresses, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
            }
        }
    }
}