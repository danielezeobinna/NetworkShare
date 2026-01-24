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
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import java.io.File
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext

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
        val dirs = getExternalFilesDirs(null)

        dirs.forEach { dir ->
            if (dir != null) {
                val path = dir.absolutePath
                val rootPath = if (path.contains("/Android/")) {
                    path.split("/Android/")[0]
                } else { path }

                val rootFile = File(rootPath)

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
        WebDAVService.loadPaths(this)
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
        if (start) {
            if (!isNetworkAvailable()) {
                Toast.makeText(this, "Turn on WiFi or Hotspot first", Toast.LENGTH_SHORT).show()
                isDiscoveryOn = false
                isPending = false
                return
            }
        }

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

    private fun isNetworkAvailable(): Boolean {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()

            interfaces.any { intf ->

                intf.isUp && !intf.isLoopback && (
                        intf.name.contains("wlan") ||
                                intf.name.contains("ap") ||
                                intf.name.contains("softap") ||
                                intf.name.contains("rndis")
                        )
            }
        } catch (_: Exception) {
            false
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
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val intent = Intent(context, WebDAVService::class.java).apply {
            action = "REFRESH_INFO"
        }
        context.startService(intent)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Network discovery",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp
                )
                Text(
                    text = "Your phone can be accessed by your PC on the network",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
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
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 150.dp),
            color = if (isOn) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier
                        .padding(16.dp)
                        .graphicsLayer(alpha = if (isOn) 1f else 0.5f)
                ) {
                    val addressList = addresses.split("\n").filter { it.isNotBlank() }

                    itemsIndexed(addressList) { _, address ->
                        val isUrl = address.startsWith("http")

                        Text(
                            text = address,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = if (isOn) MaterialTheme.colorScheme.onSurface else Color.Gray,
                            modifier = Modifier.padding(
                                bottom = if (isUrl) 16.dp else 2.dp,
                                top = 2.dp
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FilePickerSection(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as MainActivity
    var currentPath by remember { mutableStateOf<File?>(null) }

    val pathParts = remember(currentPath) {
        val parts = mutableListOf<File>()
        var temp = currentPath
        while (temp != null) {
            parts.add(0, temp)
            temp = temp.parentFile
        }
        parts
    }

    val itemsToShow = WebDAVService.scannedItems.sortedBy { it.name.lowercase() }
    val isLoading = WebDAVService.isScanning.value

    LaunchedEffect(currentPath) {
        if (currentPath == null) {
            WebDAVService.scannedItems.clear()
            val roots = activity.getAvailableStorages().map {
                FolderItem(it, it.name, true)
            }
            WebDAVService.scannedItems.addAll(roots)
        } else {
            WebDAVService.requestFolderScan(currentPath)
        }
    }

    BackHandler {
        if (currentPath == null) {
            val intent = Intent(context, WebDAVService::class.java).apply {
                action = "REFRESH_INFO"
            }
            context.startService(intent)

            onBack()
        } else {
            val parent = currentPath?.parentFile
            val storages = activity.getAvailableStorages()

            currentPath = if (storages.any { it.absolutePath == currentPath?.absolutePath }) {
                null
            } else {
                parent
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()
    ) {
        // 1. Create the ScrollState outside the row to control it
        val breadcrumbScrollState = rememberScrollState()

        fun formatBreadcrumbName(name: String): String {
            if (name.length > 15) {
                return name.take(6) + "..." + name.takeLast(6)
            }
            return name
        }

// 2. Auto-scroll to the end whenever the path changes
        LaunchedEffect(pathParts.size) {
            breadcrumbScrollState.animateScrollTo(breadcrumbScrollState.maxValue)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp) // Added more top/bottom padding
        ) {
            // Back Button (<)
            IconButton(
                onClick = {
                    // This triggers the same logic as your BackHandler
                    if (currentPath == null) onBack() else {
                        val storages = activity.getAvailableStorages()
                        currentPath = if (storages.any { it.absolutePath == currentPath?.absolutePath }) null else currentPath?.parentFile
                    }
                },
                modifier = Modifier.size(32.dp).offset(x = (-8).dp)
            ) {
                Text("<", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }

            // Increased gap to "bring the breadcrumb list down more"
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // FIXED ICON (ic_sp)
                Image(
                    painter = painterResource(id = R.drawable.ic_sp),
                    contentDescription = null,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable { currentPath = null }
                )

                // The Scrollable Path
                Row(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .horizontalScroll(breadcrumbScrollState),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pathParts.forEachIndexed { index, file ->
                        val rawName = when {
                            file.absolutePath.endsWith("emulated/0") -> "Internal"
                            file.absolutePath.startsWith("/storage/") && file.parentFile?.path == "/storage" -> {
                                if (file.name.contains("-")) "SD Card" else "USB"
                            }
                            else -> file.name
                        }

                        // Skip showing segments like "storage" or "emulated"
                        // only if they are just parents of the actual storage roots
                        if (file.path != "/storage" && file.path != "/storage/emulated") {
                            Text("  >  ", color = Color(0xFF666660), fontSize = 14.sp)
                            Text(
                                text = formatBreadcrumbName(rawName),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (index == pathParts.size - 1) MaterialTheme.colorScheme.onSurface else Color.Gray,
                                modifier = Modifier.clickable { currentPath = file }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant
                )
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(alpha = if (isLoading) 0f else 1f),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(itemsToShow, key = { it.file.absolutePath }) { folderItem ->

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
                        path = if (currentPath == null) folderItem.file.absolutePath else "Folder",
                        fullPath = folderItem.file.absolutePath,
                        onClick = {
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
}

@Composable
fun StorageRow(
    name: String,
    path: String,
    fullPath: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val isChecked = remember(fullPath, WebDAVService.selectedPaths.size) {
        WebDAVService.selectedPaths.contains(fullPath)
    }

    val isInherited = remember(fullPath, WebDAVService.selectedPaths.size) {
        WebDAVService.selectedPaths.any { shared ->
            fullPath.startsWith("$shared/") && fullPath != shared
        }
    }

    val inheritedColor = if (isSystemInDarkTheme()) Color.Gray else Color.LightGray

    val inheritedCheck = if (isSystemInDarkTheme()) Color.LightGray else Color.White

    val isPartiallyChecked = remember(fullPath, WebDAVService.selectedPaths.size) {
        !isChecked && !isInherited && WebDAVService.selectedPaths.any { shared ->
            shared.startsWith("$fullPath/") && shared != fullPath
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(
                id = if (path == "Folder") R.drawable.ic_folder else R.drawable.ic_drive
            ),
            contentDescription = null,
            modifier = Modifier
                .padding(end = 16.dp)
                .offset(y = (-4).dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
            Text(
                text = name,
                color = if (isInherited) Color.Gray else MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp
            )
            HorizontalDivider(
                modifier = Modifier
                    .offset(y = (4).dp)
                    .padding(top = 8.dp),
                thickness = 0.5.dp,
                color = Color.Gray.copy(alpha = 0.3f)
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .padding(4.dp)
                .offset(y = (-4).dp)
                .border(
                    2.dp,
                    if (isInherited) inheritedColor
                    else if (isChecked || isPartiallyChecked) Color(0xFF2BAED5) else Color.Gray,
                    RoundedCornerShape(6.dp)
                )
                .background(
                    if (isInherited) inheritedColor
                    else if (isChecked) Color(0xFF2BAED5) else Color.Transparent,
                    RoundedCornerShape(6.dp)
                )
                .clickable {
                    if (!isInherited) {
                        WebDAVService.toggleSelection(context, fullPath)

                        if (WebDAVService.activeServers.isNotEmpty()) {
                            val intent = Intent(context, WebDAVService::class.java).apply {
                                action = "START_SERVERS"
                            }
                            context.startForegroundService(intent)
                        }
                    }
                }
        ) {
            if (isChecked || isInherited) {
                Text(
                    text = "✓",
                    color = if (isInherited) inheritedCheck else if (isSystemInDarkTheme()) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            } else if (isPartiallyChecked) {
                Box(modifier = Modifier.size(10.dp).background(Color(0xFF2BAED5), RoundedCornerShape(2.dp)))
            }
        }
    }
}