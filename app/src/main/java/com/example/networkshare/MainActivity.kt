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
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import java.io.File // Fixes 'File' errors
import androidx.compose.foundation.shape.RoundedCornerShape // Fixes 'RoundedCornerShape'
import androidx.compose.foundation.lazy.items // Fixes the 'items' list error

class MainActivity : ComponentActivity() {

    private var serverAddresses by mutableStateOf("Internal Storage:\nhttp://0.0.0.0:8080/")
    private var isDiscoveryOn by mutableStateOf(false)
    private var isPending by mutableStateOf(false)

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
                }
                "com.example.networkshare.ADDRESSES_UPDATED" -> {
                    val data = intent.getStringExtra("address_list")
                    if (data != null) {
                        serverAddresses = data
                        saveAddresses(data)
                    }
                }
            }
        }
    }

    internal fun getAvailableStorages(): List<File> {
        val storages = mutableListOf<File>()
        // This gets all accessible paths (Internal, SD, USB)
        val dirs = getExternalFilesDirs(null)

        dirs.forEach { dir ->
            if (dir != null) {
                val path = dir.absolutePath
                // We strip the Android/data/... part to get the root
                val rootPath = if (path.contains("/Android/")) {
                    path.split("/Android/")[0]
                } else { path }

                val rootFile = File(rootPath)

                // Check if it's already in our list to avoid duplicates
                if (rootFile.exists() && rootFile.canRead() && !storages.contains(rootFile)) {
                    storages.add(rootFile)
                }
            }
        }
        return storages
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initPermissions()

        loadAddresses()

        isDiscoveryOn = isServiceRunning()
        if (isDiscoveryOn) updateAddresses()

        setContent {
            NetworkShareTheme {
                val isPickerOpen = remember { mutableStateOf(false) }

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!isPickerOpen.value) {
                        DiscoveryScreen(
                            isOn = isDiscoveryOn,
                            isPending = isPending,
                            addresses = serverAddresses,
                            onToggle = { start -> handleToggle(start) },
                            onOpenPicker = { isPickerOpen.value = true }
                        )
                    } else {
                        FilePickerSection(onBack = { isPickerOpen.value = false })
                    }
                }
            }
        }
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

    override fun onResume() {
        super.onResume()
        val running = isServiceRunning()
        isDiscoveryOn = running

        if (running) {
            val intent = Intent(this, WebDAVService::class.java).apply {
                action = "REFRESH_INFO"
            }
            startService(intent)
        }
    }

    private fun saveAddresses(addresses: String) {
        val sharedPref = getPreferences(MODE_PRIVATE)
        sharedPref.edit(commit = false) {
            putString("last_addresses", addresses)
        }
    }

    private fun loadAddresses() {
        val sharedPref = getPreferences(MODE_PRIVATE)
        val saved = sharedPref.getString("last_addresses", "Internal Storage:\nhttp://0.0.0.0:8080/")
        serverAddresses = saved ?: "Internal Storage:\nhttp://0.0.0.0:8080/"
    }

    private fun handleToggle(start: Boolean) {
        if (isPending) return
        isPending = true
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
        try {
            if (start) {
                startForegroundService(intent)
                isDiscoveryOn = true
                updateAddresses()
            } else {
                stopService(intent)
                isDiscoveryOn = false
            }
        } finally {
            window.decorView.postDelayed({ isPending = false }, 500)
        }
    }

    private fun updateAddresses() {
        if (isDiscoveryOn && serverAddresses.contains("0.0.0.0")) {
            serverAddresses = "Scanning storages..."
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
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))
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
                enabled = !isPending,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = if (isDark) Color.Black else Color.White,
                    uncheckedThumbColor = if (isDark) Color.White else Color(0xFF666660),

                    uncheckedTrackColor = if (isDark) Color(0xFF666660) else Color(0xFFEEF1F3),

                    uncheckedBorderColor = Color(0xFF666660)
                )
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Row(
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = Color.DarkGray),
                        onClick = onOpenPicker
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_sp),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "Choose Shared Paths...",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Windows Explorer Addresses:",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
            color = if (isOn) MaterialTheme.colorScheme.onSurface
            else Color.Gray
        )
        Surface(
            tonalElevation = 4.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
            color = if (isOn) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Text(
                text = addresses,
                modifier = Modifier
                    .padding(16.dp)
                    .graphicsLayer(alpha = if (isOn) 1f else 0.5f),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = if (isOn) MaterialTheme.colorScheme.onSurface
                else Color.Gray
            )
        }
    }
}

@Composable
fun FilePickerSection(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as MainActivity
    var currentPath by remember { mutableStateOf<File?>(null) }

    // Observe the new FolderItem list
    val itemsToShow = WebDAVService.scannedItems.sortedBy { it.name.lowercase() }
    val isLoading = WebDAVService.isScanning.value

    LaunchedEffect(currentPath) {
        if (currentPath == null) {
            WebDAVService.scannedItems.clear()
            // Convert storage files to FolderItems
            val roots = activity.getAvailableStorages().map {
                FolderItem(it, it.name, true)
            }
            WebDAVService.scannedItems.addAll(roots)
        } else {
            WebDAVService.requestFolderScan(currentPath)
        }
    }

    // Logic for the Back Button
    BackHandler {
        if (currentPath == null) {
            onBack() // Exit the dark screen
        } else {
            // Go up one level. If we are at the root of a storage, go back to Storage List
            val parent = currentPath?.parentFile
            val storages = activity.getAvailableStorages()

            // If the current path is one of our root storages, the "parent" should be the storage list (null)
            currentPath = if (storages.any { it.absolutePath == currentPath?.absolutePath }) {
                null
            } else {
                parent
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(24.dp)
    ) {
        // Dynamic Title: Shows "Storage" or the name of the folder you are in
        Text(
            text = currentPath?.name ?: "Storage & Folders",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        if (currentPath != null) {
            Text(
                text = currentPath!!.absolutePath,
                color = Color.Gray,
                fontSize = 12.sp,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(alpha = if (isLoading) 0.5f else 1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = itemsToShow,
                key = { it.file.absolutePath } // Keying by path is very important for speed
            ) { folderItem ->
                val isStorageRoot = currentPath == null

                val label = remember(folderItem, currentPath) {
                    if (isStorageRoot && folderItem.file.absolutePath.contains("emulated/0")) {
                        "Internal Storage"
                    } else if (isStorageRoot) {
                        "SD Card (${folderItem.name})"
                    } else {
                        folderItem.name
                    }
                }

                StorageRow(
                    name = label,
                    path = if (isStorageRoot) folderItem.file.absolutePath else "Folder",
                    onClick = {
                        // ZERO LAG: Use the pre-calculated boolean
                        if (folderItem.hasSubFolders || isStorageRoot) {
                            currentPath = folderItem.file
                        } else {
                            Toast.makeText(context, "No sub-folders inside", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun StorageRow(name: String, path: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = Color.Gray),
                onClick = onClick
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            // If the path says "Folder", use a folder icon, otherwise use a drive icon
            Text(if (path == "Folder") "📁" else "💾", fontSize = 20.sp)
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(text = name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (path != "Folder") {
                Text(text = path, color = Color.DarkGray, fontSize = 12.sp)
            }
        }
    }
}