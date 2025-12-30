package com.example.networkshare

import android.Manifest
import android.content.Intent
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.example.networkshare.ui.theme.NetworkShareTheme
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private val activeServers = mutableListOf<WebDAVServer>()
    
    private var serverAddresses by mutableStateOf("Scanning for storage...")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { isGranted ->
        if (isGranted.values.all { it }) {
            checkAndStartServers()
        } else {
            Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initPermissions()

        setContent {
            NetworkShareTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ServerStatusScreen(
                        addresses = serverAddresses,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun initPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:${applicationContext.packageName}".toUri()
                }
                startActivity(intent)
            } else {
                checkAndStartServers()
            }
        } else {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
        }
    }

    private fun checkAndStartServers() {
        if (activeServers.isNotEmpty()) return

        val ip = getLocalIpAddress() ?: "127.0.0.1"
        val displayInfo = StringBuilder()

        val internalRoot = Environment.getExternalStorageDirectory()
        activeServers.add(WebDAVServer(8080, internalRoot))
        displayInfo.append("Internal Storage:\nhttp://$ip:8080/\n\n")

        val externalDirs = getExternalFilesDirs(null)
        var nextPort = 8081

        externalDirs?.forEach { dir ->
            if (dir != null) {
                val path = dir.absolutePath
                if (path.contains("/storage/") && !path.contains("/emulated/")) {
                
                    val storagePath = path.split("/Android/")[0]
                    val sdRoot = File(storagePath)

                    if (sdRoot.exists() && sdRoot.canRead()) {
                        try {
                            activeServers.add(WebDAVServer(nextPort, sdRoot))
                            displayInfo.append("SD Card (${sdRoot.name}):\nhttp://$ip:$nextPort/\n\n")
                            nextPort++
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        serverAddresses = if (activeServers.size <= 1 && displayInfo.length < 30) {
            displayInfo.append("No SD Card detected.").toString()
        } else {
            displayInfo.toString().trim()
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

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            checkAndStartServers()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeServers.forEach { it.stopServer() }
        activeServers.clear()
    }
}

@Composable
fun ServerStatusScreen(addresses: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp)) {
        Text(
            text = "Network Share Active",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Text(
            text = "Enter these addresses in your PC File Explorer:",
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Surface(
            tonalElevation = 4.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = addresses,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}