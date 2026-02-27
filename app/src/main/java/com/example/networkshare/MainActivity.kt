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
import android.util.Log
import android.widget.Toast
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import java.io.File
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import android.app.KeyguardManager
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

class MainActivity : androidx.fragment.app.FragmentActivity() {
    companion object {
        private const val REQ_PIN = 9999
    }

    private var isUnlocked by mutableStateOf(false)
    private var serverAddresses by mutableStateOf("Internal Storage:\nhttp://0.0.0.0:8080/")
    private var isDiscoveryOn by mutableStateOf(false)
    private var isPending by mutableStateOf(false)
    private var showNetworkDialog by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleService(true) { showNetworkDialog = true }
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
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        WebDAVService.loadPaths(this)
        handleIncomingShare(intent)
        loadAddresses()

        isDiscoveryOn = isServiceRunning()
        showBiometricPrompt()
        if (isDiscoveryOn) updateAddresses()

        setContent {
            NetworkShareTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when {
                        !isUnlocked -> {
                            BiometricGateScreen(onUnlockClick = { showBiometricPrompt() })
                        }

                        else -> {
                            val isPickerOpen = remember { mutableStateOf(false) }
                            if (!isPickerOpen.value) {
                                DiscoveryScreen(
                                    isOn = isDiscoveryOn,
                                    isPending = isPending,
                                    addresses = serverAddresses,
                                    onToggle = { start, showDialog -> handleToggle(start, showDialog) },
                                    onOpenPicker = { isPickerOpen.value = true }
                                )
                            } else {
                                FilePickerSection(onBack = { isPickerOpen.value = false })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showBiometricPrompt() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val biometricManager = BiometricManager.from(this)

        // 1. If the phone has NO security at all, just let them in.
        if (!km.isDeviceSecure) {
            isUnlocked = true
            initPermissions()
            return
        }

        // 2. Define the legacy PIN fallback function to avoid repeating code
        val launchLegacyPin = {
            @Suppress("DEPRECATION")
            val intent = km.createConfirmDeviceCredentialIntent(
                "NetworkShare Security", // The Title (Keep this so they know WHY they are being asked)
                null                      // Passing null for the description
            )

            if (intent != null) {
                startActivityForResult(intent, REQ_PIN)
            } else {
                // Fallback if for some reason the intent couldn't be created
                isUnlocked = true
                initPermissions()
            }
        }

        // 3. Check if Biometrics (Fingerprint/Face) are available/enrolled
        val canAuth = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

        // If on Android 9 (or below) and no biometrics are set up, go straight to PIN
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            launchLegacyPin()
            return
        }

        // --- BIOMETRIC PROMPT SETUP ---
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isUnlocked = true
                    initPermissions()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)

                    when (errorCode) {
                        // 1. User clicked "Use PIN / Pattern"
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                            launchLegacyPin()
                        }

                        // 2. Too many failed attempts! Switch to PIN automatically.
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                            launchLegacyPin()
                        }

                        // 3. User explicitly swiped away or hit back
                        BiometricPrompt.ERROR_USER_CANCELED -> {
                        }

                        // 4. Anything else (Hardware error, sensor dirty, etc.)
                        else -> {
                            Toast.makeText(this@MainActivity, errString, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("NetworkShare Security")
            .setSubtitle("Authenticate to manage your server")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: Unified Fingerprint + PIN screen
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            // Android 9: Custom "Use PIN" button that triggers our legacy fallback
            builder.setNegativeButtonText("Try Another Way")
        }

        biometricPrompt.authenticate(builder.build())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_PIN) {
            if (resultCode == RESULT_OK) {
                isUnlocked = true
                initPermissions()
            } else {
                Toast.makeText(this, "Authentication failed", Toast.LENGTH_SHORT).show()
                finish()
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
        isUnlocked = false
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        val running = isServiceRunning()
        isDiscoveryOn = running

        if (!isUnlocked) {
            showBiometricPrompt()
        }

        if (running) {
            val intent = Intent(this, WebDAVService::class.java).apply {
                action = "REFRESH_INFO"
            }
            startService(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
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

    private fun handleToggle(start: Boolean, onShowDialog: () -> Unit) {
        if (isPending) return
        isPending = true
        if (start && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toggleService(start, onShowDialog)
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Integer.MAX_VALUE).any {
            it.service.className == WebDAVService::class.java.name
        }
    }

    private fun toggleService(start: Boolean, onShowDialog: () -> Unit) {
        if (start) {
            if (!isNetworkAvailable()) {
                onShowDialog()
                showNetworkDialog = true
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
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            androidx.core.app.ActivityCompat.requestPermissions(this, permissions, 101)
        }
    }

    private fun handleIncomingShare(intent: Intent) {
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            val uri = androidx.core.content.IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_STREAM,
                android.net.Uri::class.java
            )
            if (uri != null) {
                saveUriToSharedFolder(uri)
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            val uris = androidx.core.content.IntentCompat.getParcelableArrayListExtra(
                intent,
                Intent.EXTRA_STREAM,
                android.net.Uri::class.java
            )
            uris?.forEach { saveUriToSharedFolder(it) }
        }
    }

    private fun saveUriToSharedFolder(uri: android.net.Uri) {
        try {
            val rootDir = Environment.getExternalStorageDirectory()
            val sharedDir = File(rootDir, "SharedItems")

            if (!sharedDir.exists()) {
                val created = sharedDir.mkdirs()
                if (!created) {
                    Log.e("NetworkShare", "Could not create root folder, check permissions")
                }
            }

            val fileName = getFileName(uri) ?: "shared_${System.currentTimeMillis()}"
            val destFile = File(sharedDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val path = sharedDir.absolutePath
            if (!WebDAVService.selectedPaths.contains(path)) {
                WebDAVService.selectedPaths.add(path)
                WebDAVService.savePaths(this)
            }

            WebDAVService.tempPriorityPath = path

            Toast.makeText(this, "New item added to SharedItems!", Toast.LENGTH_SHORT).show()

            val refreshIntent = Intent(this, WebDAVService::class.java).apply {
                action = "REFRESH_INFO"
            }
            startService(refreshIntent)

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

@Composable
fun DiscoveryScreen(
    isOn: Boolean,
    isPending: Boolean,
    addresses: String,
    onToggle: (Boolean, () -> Unit) -> Unit,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var showNetworkDialog by remember { mutableStateOf(false) }

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
                    text = "Network Share",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp
                )
                Text(
                    text = "Your phone can be accessed by other devices on the network",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 18.sp
                )
            }

            Switch(
                checked = isOn,
                onCheckedChange = { onToggle(it) { showNetworkDialog = true } },
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
                    state = listState,
                    modifier = Modifier
                        .drawScrollbar(listState, color = Color.DarkGray.copy(alpha = 0.6f))
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
                            color = if (isUrl && isOn) {
                                MaterialTheme.colorScheme.primary
                            } else if (isOn) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                Color.Gray
                            },
                            modifier = Modifier.padding(
                                bottom = if (isUrl) 16.dp else 2.dp,
                                top = 2.dp
                            )
                        )
                    }
                }
            }
        }

        if (showNetworkDialog) {
            Dialog(
                onDismissRequest = { showNetworkDialog = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.98f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showNetworkDialog = false },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .offset(y = (-24).dp)
                            .fillMaxWidth(0.95f)
                            .padding(horizontal = 4.dp),
                        shape = RoundedCornerShape(28.dp),
                        color = if (isSystemInDarkTheme()) Color(0xFF252525) else Color(0xFFFCFCFC),
                        tonalElevation = 6.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = "No Network Detected",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Network sharing is only possible when your device is part of a network. Join a Wi-Fi network or create a network using hotspot.",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // LEFT BUTTON (Hotspot)
                                TextButton(
                                    onClick = {
                                        showNetworkDialog = false
                                        val intent = Intent("android.settings.TETHER_SETTINGS")
                                        try { context.startActivity(intent) } catch (_: Exception) {
                                            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        // We use clickable here to force the custom ripple color
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = ripple(color = Color.DarkGray), // Your dark gray ripple
                                            onClick = { /* This is handled by the TextButton's onClick */ }
                                        )
                                ) {
                                    Text("Hotspot", color = Color(0xFF2BAED5), fontSize = 16.sp)
                                }

                                // THE DIVIDER LINE (The subtle line you wanted)
                                VerticalDivider(
                                    modifier = Modifier
                                        .height(20.dp)
                                        .width(1.dp),
                                    color = Color.Gray.copy(alpha = 0.2f)
                                )

                                // RIGHT BUTTON (Wi-Fi)
                                TextButton(
                                    onClick = {
                                        showNetworkDialog = false
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            context.startActivity(Intent("android.settings.panel.action.WIFI"))
                                        } else {
                                            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = ripple(color = Color.DarkGray), // Your dark gray ripple
                                            onClick = { /* This is handled by the TextButton's onClick */ }
                                        )
                                ) {
                                    Text("Wi-Fi", color = Color(0xFF2BAED5), fontSize = 16.sp)
                                }
                            }
                        }
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

    val handleBack = {
        if (currentPath == null) {
            val intent = Intent(context, WebDAVService::class.java).apply {
                action = "REFRESH_INFO"
            }
            context.startService(intent)
            onBack()
        } else {
            val storages = activity.getAvailableStorages()
            currentPath = if (storages.any { it.absolutePath == currentPath?.absolutePath }) {
                null
            } else {
                currentPath?.parentFile
            }
            WebDAVService.scannedItems.clear()
        }
    }

    val listState = rememberLazyListState()

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

    BackHandler(enabled = true) {
        handleBack()
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
                onClick = { handleBack() },
                modifier = Modifier.size(32.dp).offset(x = (-8).dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.baseline_chevron_left),
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp)
                )
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
                        .clickable {
                            currentPath = null
                            WebDAVService.scannedItems.clear()
                        }
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
                            Icon(
                                painter = painterResource(id = R.drawable.baseline_chevron_right),
                                contentDescription = null,
                                tint = Color(0xFF666660),
                                modifier = Modifier.size(22.dp).padding(horizontal = 2.dp)
                            )
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
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .drawScrollbar(listState, color = Color.DarkGray.copy(alpha = 0.6f))
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
                Icon(
                    painter = painterResource(id = R.drawable.baseline_check), // Your generated Vector Asset
                    contentDescription = "Selected",
                    tint = if (isInherited) {
                        inheritedCheck
                    } else if (isSystemInDarkTheme()) {
                        Color.Black
                    } else {
                        Color.White
                    },
                    modifier = Modifier
                        .size(22.dp) // Slightly larger than the 14.sp text to maintain "weight"
                        .padding(2.dp)
                )
            } else if (isPartiallyChecked) {
                Box(modifier = Modifier.size(10.dp).background(Color(0xFF2BAED5), RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
fun BiometricGateScreen(onUnlockClick: () -> Unit) {
    // Automatically try to show the prompt when this screen appears
    LaunchedEffect(Unit) { onUnlockClick() }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.baseline_lock), // Or a lock icon
            contentDescription = null,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("App Locked", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onUnlockClick) {
            Text(text = "Verify Authentication",
                color = if (isSystemInDarkTheme()) Color.Black else Color.White
            )
        }
    }
}

fun Modifier.drawScrollbar(
    state: LazyListState,
    color: Color = Color(0xFF2BAED5).copy(alpha = 0.6f)
): Modifier = drawWithContent {
    drawContent()

    val layoutInfo = state.layoutInfo
    val totalItems = layoutInfo.totalItemsCount

    if (totalItems > 0 && layoutInfo.visibleItemsInfo.isNotEmpty()) {
        val viewportHeight = size.height

        // 1. Calculate how much of the total content is visible
        val visibleItemsCount = layoutInfo.visibleItemsInfo.size
        val scrollbarHeight = (viewportHeight * visibleItemsCount / totalItems).coerceAtLeast(32f)

        // 2. SMOOTH MATH: Calculate position based on pixel offset, not just item index
        val firstVisibleItem = layoutInfo.visibleItemsInfo.first()
        val firstItemOffset = firstVisibleItem.offset.toFloat()
        val itemHeight = firstVisibleItem.size.toFloat()

        // This creates a fractional progress (e.g., 2.5 instead of just 2)
        val scrollProgress = (state.firstVisibleItemIndex - (firstItemOffset / itemHeight)) / totalItems
        val scrollbarOffsetY = scrollProgress * viewportHeight

        // 3. SPACING: Move it away from the edge
        val thickness = 4.dp.toPx()
        val marginEnd = 6.dp.toPx() // This pushes it away from the right edge

        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - marginEnd - thickness, scrollbarOffsetY.coerceIn(0f, viewportHeight - scrollbarHeight)),
            size = Size(thickness, scrollbarHeight),
            cornerRadius = CornerRadius(thickness / 2, thickness / 2)
        )
    }
}