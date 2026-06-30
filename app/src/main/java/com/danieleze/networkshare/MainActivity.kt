package com.danieleze.networkshare

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import com.danieleze.networkshare.AppControl.Companion.isUnlocked
import com.danieleze.networkshare.ui.theme.AppTheme
import com.danieleze.networkshare.ui.theme.NetworkShareTheme
import com.danieleze.networkshare.ui.screens.DiscoveryScreen
import com.danieleze.networkshare.ui.screens.BiometricGateScreen
import com.danieleze.networkshare.ui.screens.FilePickerSection
import com.danieleze.networkshare.ui.screens.NetworkListScreen
import com.danieleze.networkshare.ui.screens.LocationOffDialog
import com.danieleze.networkshare.ui.screens.UnknownNetworkDialog
import com.danieleze.networkshare.ui.screens.NotificationPermissionDialog
import com.danieleze.networkshare.ui.screens.NoNetworkDialog
import com.danieleze.networkshare.ui.screens.UserGuideScreen
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * MainActivity — owns the Activities & UI.
 */
class MainActivity : androidx.fragment.app.FragmentActivity() {

    companion object {
        var instance: MainActivity? = null
        private const val REQ_PIN = 9999
    }

    val viewModel: AppControl by viewModels()

    // ── Private fields ────────────────────────────────────────────────────────
    private var pausedAtTime = 0L
    private var isShowingAd = false
    private var interstitialAd: InterstitialAd? = null

    // ── Permission launcher ───────────────────────────────────────────────────
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleService(true)
    }

    // ── Broadcast receiver ────────────────────────────────────────────────────
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.danieleze.networkshare.SERVER_STOPPED" -> {
                    viewModel.isDiscoveryOn = false
                }

                "com.danieleze.networkshare.CHECK_LOCATION" -> {
                    checkLocationForUntrustedNetwork()
                }

                "com.danieleze.networkshare.ADDRESSES_UPDATED" -> {
                    val data = intent.getStringExtra("address_list")
                    val validNetwork = intent.getBooleanExtra("is_valid_network", true)
                    if (data != null) {
                        viewModel.serverAddresses = data
                        viewModel.isValidNetwork = validNetwork
                        viewModel.saveAddresses(data)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        instance = null
        super.onCreate(savedInstanceState)
        instance = this
        Log.d("AppControl", "STARTED — app opened")
        startService(Intent(this, AppControlService::class.java))

        if (savedInstanceState == null) isUnlocked = false

        viewModel.isPending = false
        viewModel.isDiscoveryOn = viewModel.isServiceRunning()
        WebDAVService.loadPaths(this.applicationContext)

        if (savedInstanceState == null) {
            handleIncomingShare(intent)
            viewModel.loadAddresses()
        }

        MobileAds.initialize(this.applicationContext) {}
        loadInterstitialAd()
        FileManager.clearVirtualDesktopIniCache()
        enableEdgeToEdge()

        setContent {
            NetworkShareTheme(appTheme = viewModel.appTheme) {
                val darkTheme = when (viewModel.appTheme) {
                    AppTheme.LIGHT -> false
                    AppTheme.DARK -> true
                    AppTheme.SYSTEM -> isSystemInDarkTheme()
                }

                val showUserGuide = remember { mutableStateOf(false) }
                val isPickerOpen = remember { mutableStateOf(false) }
                val showAllowedNetworks = remember { mutableStateOf(false) }
                val showBlockedNetworks = remember { mutableStateOf(false) }
                val currentPickerPath = remember { mutableStateOf<File?>(null) }
                var navigatingForward by remember { mutableStateOf(true) }

                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as Activity).window
                        WindowCompat.getInsetsController(window, view).apply {
                            isAppearanceLightStatusBars = !darkTheme
                            isAppearanceLightNavigationBars = !darkTheme
                        }
                        @Suppress("DEPRECATION")
                        window.navigationBarColor =
                            if (darkTheme) "#010101".toColorInt() else "#EEF1F3".toColorInt()
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            AnimatedContent(
                                targetState = isUnlocked,
                                transitionSpec = {
                                    if (targetState)
                                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                                    else
                                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                                },
                                label = "unlockTransition"
                            ) { unlocked ->
                                if (!unlocked) {
                                    BiometricGateScreen(onUnlockClick = { showBiometricPrompt() })
                                } else {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            val pendingTrustSsid =
                                                WebDAVService.pendingTrustSsid.value

                                            LaunchedEffect(pendingTrustSsid) {
                                                if (pendingTrustSsid != null && viewModel.isDiscoveryOn)
                                                    viewModel.showUnknownNetworkDialog = true
                                            }

                                            LocationOffDialog(
                                                show = viewModel.showLocationOffDialog,
                                                appTheme = viewModel.appTheme,
                                                onDismiss = { viewModel.showLocationOffDialog = false }
                                            )
                                            UnknownNetworkDialog(
                                                show = viewModel.showUnknownNetworkDialog,
                                                ssid = pendingTrustSsid,
                                                appTheme = viewModel.appTheme,
                                                onDismiss = { viewModel.showUnknownNetworkDialog = false }
                                            )
                                            NotificationPermissionDialog(
                                                show = viewModel.showNotificationDialog,
                                                appTheme = viewModel.appTheme,
                                                onDismiss = { viewModel.showNotificationDialog = false }
                                            )
                                            NoNetworkDialog(
                                                show = viewModel.showNetworkDialog,
                                                appTheme = viewModel.appTheme,
                                                onDismiss = { viewModel.showNetworkDialog = false }
                                            )

                                            val screenState = when {
                                                showUserGuide.value -> "userGuide"
                                                showAllowedNetworks.value -> "allowedNetworks"
                                                showBlockedNetworks.value -> "blockedNetworks"
                                                !isPickerOpen.value -> "discovery"
                                                else -> "filePicker"
                                            }

                                            AnimatedContent(
                                                targetState = screenState to navigatingForward,
                                                transitionSpec = {
                                                    if (targetState.second)
                                                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                                                    else
                                                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                                                },
                                                label = "screenTransition"
                                            ) { (state, _) ->
                                                when (state) {
                                                    "userGuide" -> UserGuideScreen(
                                                        onBack = {
                                                            navigatingForward =
                                                                false; showUserGuide.value = false
                                                        }
                                                    )

                                                    "allowedNetworks" -> NetworkListScreen(
                                                        title = "Allowed Networks",
                                                        networks = NetworkManager.allowedNetworks,
                                                        iconRes = R.drawable.ic_wifi,
                                                        onRemove = { ssid ->
                                                            NetworkManager.remove(
                                                                this@MainActivity,
                                                                ssid
                                                            )
                                                        },
                                                        onBack = {
                                                            navigatingForward =
                                                                false; showAllowedNetworks.value =
                                                            false
                                                        }
                                                    )

                                                    "blockedNetworks" -> NetworkListScreen(
                                                        title = "Blocked Networks",
                                                        networks = NetworkManager.blockedNetworks,
                                                        iconRes = R.drawable.ic_wifi,
                                                        onRemove = { ssid ->
                                                            NetworkManager.remove(
                                                                this@MainActivity,
                                                                ssid
                                                            )
                                                        },
                                                        onBack = {
                                                            navigatingForward =
                                                                false; showBlockedNetworks.value =
                                                            false
                                                        }
                                                    )

                                                    "discovery" -> DiscoveryScreen(
                                                        isOn = viewModel.isDiscoveryOn,
                                                        isPending = viewModel.isPending,
                                                        addresses = viewModel.serverAddresses,
                                                        onToggle = { start -> handleToggle(start) },
                                                        onReload = {
                                                            if (viewModel.isDiscoveryOn) {
                                                                startService(
                                                                    Intent(
                                                                        this@MainActivity,
                                                                        WebDAVService::class.java
                                                                    ).apply {
                                                                        action = "RESTART_SERVERS"
                                                                    })
                                                            }
                                                        },
                                                        onNoNetwork = { viewModel.showNetworkDialog = true },
                                                        onDismissNetworkDialog = {
                                                            viewModel.showNetworkDialog = false
                                                        },
                                                        onOpenPicker = {
                                                            navigatingForward =
                                                                true; isPickerOpen.value = true
                                                        },
                                                        onOpenAllowedNetworks = {
                                                            navigatingForward =
                                                                true; showAllowedNetworks.value =
                                                            true
                                                        },
                                                        onOpenBlockedNetworks = {
                                                            navigatingForward =
                                                                true; showBlockedNetworks.value =
                                                            true
                                                        },
                                                        onOpenUserGuide = {
                                                            navigatingForward =
                                                                true; showUserGuide.value = true
                                                        },
                                                        currentTheme = viewModel.appTheme,
                                                        onThemeChange = { theme -> viewModel.saveTheme(theme) },
                                                        isDark = darkTheme,
                                                    )

                                                    else -> FilePickerSection(
                                                        onBack = {
                                                            navigatingForward =
                                                                false; isPickerOpen.value = false
                                                        },
                                                        currentPath = currentPickerPath
                                                    )
                                                }
                                            }
                                        }

                                        val imeVisible =
                                            WindowInsets.ime.getBottom(LocalDensity.current) > 0
                                        var adHeight by remember { mutableStateOf(0.dp) }

                                        @Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
                                        if (!imeVisible) {
                                            AndroidView(
                                                factory = { context ->
                                                    AdView(context).apply {
                                                        val dm = context.resources.displayMetrics
                                                        val adWidthPx = dm.widthPixels.toFloat()
                                                        val adWidth =
                                                            (adWidthPx / dm.density).toInt()
                                                        setAdSize(
                                                            AdSize.getInlineAdaptiveBannerAdSize(
                                                                adWidth,
                                                                65
                                                            )
                                                        )
                                                        adUnitId = BuildConfig.ADMOB_BANNER_ID
                                                        adListener = object : AdListener() {
                                                            override fun onAdLoaded() {
                                                                adHeight = 65.dp
                                                            }

                                                            override fun onAdFailedToLoad(e: LoadAdError) {
                                                                adHeight = 0.dp
                                                            }
                                                        }
                                                        loadAd(AdRequest.Builder().build())
                                                    }
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 0.dp, max = adHeight)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction("com.danieleze.networkshare.SERVER_STOPPED")
            addAction("com.danieleze.networkshare.ADDRESSES_UPDATED")
            addAction("com.danieleze.networkshare.CHECK_LOCATION")
        }
        val listenFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            RECEIVER_NOT_EXPORTED else 0
        registerReceiver(receiver, filter, listenFlag)
    }

    override fun onStop() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.isDiscoveryOn = viewModel.isServiceRunning()

        if (pausedAtTime > 0L && System.currentTimeMillis() - pausedAtTime >= 30_000L) {
            isUnlocked = false
        }
        pausedAtTime = 0L

        // Returning from interstitial — skip auth re-check
        if (isShowingAd) {
            isShowingAd = false
            refreshServiceIfRunning()
            val pending = WebDAVService.pendingTrustSsid.value
            if (pending != null && viewModel.isDiscoveryOn) viewModel.showUnknownNetworkDialog = true
            return
        }

        refreshServiceIfRunning()
        val pending = WebDAVService.pendingTrustSsid.value
        if (pending != null && viewModel.isDiscoveryOn) viewModel.showUnknownNetworkDialog = true
    }

    override fun onPause() {
        super.onPause()
        if (!isShowingAd && !isChangingConfigurations) {
            pausedAtTime = System.currentTimeMillis()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PIN && resultCode == RESULT_OK) {
            initPermissions()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (!granted) {
                stopService(Intent(this, WebDAVService::class.java))
                viewModel.isDiscoveryOn = false
                Toast.makeText(
                    this,
                    "Storage permission is required to use NetworkShare",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                isUnlocked = true
            }
        }
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        Log.d("AppControl", "STOPPED — app closed")
        stopService(Intent(this, AppControlService::class.java))
        super.onDestroy()
    }


    // ── Authentication ────────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    fun showBiometricPrompt() {
        val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        val biometricManager = BiometricManager.from(this)

        if (!km.isDeviceSecure) {
            initPermissions(); return
        }

        val launchLegacyPin = {
            @Suppress("DEPRECATION")
            val intent = km.createConfirmDeviceCredentialIntent("NetworkShare Security", null)
            if (intent != null) startActivityForResult(intent, REQ_PIN) else initPermissions()
        }

        val canUseBiometrics = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS

        if (!canUseBiometrics) {
            launchLegacyPin(); return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(
            this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    initPermissions()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> launchLegacyPin()

                        BiometricPrompt.ERROR_USER_CANCELED -> { /* let user retry */
                        }

                        else -> launchLegacyPin()
                    }
                }
            })

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("NetworkShare Security")
            .setSubtitle("Authenticate to manage your server")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            builder.setNegativeButtonText("Try Another Way")
        }

        biometricPrompt.authenticate(builder.build())
    }

    // ── Permission init ───────────────────────────────────────────────────────
    private fun initPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:${applicationContext.packageName}".toUri()
                }
                viewModel.pendingStorageCheck = true
                startActivity(intent)
                startService(Intent(this, AppControlService::class.java))
            } else {
                isUnlocked = true
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            androidx.core.app.ActivityCompat.requestPermissions(this, permissions, 101)
        }
    }

    // ── Service control ───────────────────────────────────────────────────────
    fun handleToggle(start: Boolean) {
        if (start) {
            val notifManager = getSystemService(android.app.NotificationManager::class.java)
            if (!notifManager.areNotificationsEnabled()) {
                viewModel.showNotificationDialog = true
                return
            }
        }

        if (viewModel.isPending) return
        viewModel.isPending = true

        if (start && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toggleService(start)
        }

        // Show interstitial opportunistically when turning on
        if (start) {
            interstitialAd?.let { ad ->
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        interstitialAd = null; loadInterstitialAd()
                    }

                    override fun onAdFailedToShowFullScreenContent(
                        error: com.google.android.gms.ads.AdError
                    ) {
                        interstitialAd = null; loadInterstitialAd()
                    }
                }
                isShowingAd = true
                ad.show(this)
            }
        }
    }

    private fun toggleService(start: Boolean) {
        val intent = Intent(this, WebDAVService::class.java)
        try {
            if (start) {
                startForegroundService(intent); viewModel.isDiscoveryOn = true
            } else {
                stopService(intent); viewModel.isDiscoveryOn = false
            }
        } finally {
            window.decorView.postDelayed({ viewModel.isPending = false }, 500)
        }
    }

    private fun refreshServiceIfRunning() {
        if (!viewModel.isDiscoveryOn) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startService(Intent(this, WebDAVService::class.java).apply { action = "REFRESH_INFO" })
        }, 1500)
    }

    // ── Location helpers ──────────────────────────────────────────────────────
    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                coarse == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun requestLocationPermissions() {
        androidx.core.app.ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            101
        )
    }

    fun checkLocationForUntrustedNetwork() {
        if (!hasLocationPermission()) {
            requestLocationPermissions(); return
        }
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val isOn = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        if (!isOn) viewModel.showLocationOffDialog = true
    }

    // ── Ad loading ────────────────────────────────────────────────────────────
    private fun loadInterstitialAd() {
        InterstitialAd.load(
            this,
            BuildConfig.ADMOB_INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                }
            }
        )
    }

    // ── Incoming share handling ───────────────────────────────────────────────
    fun handleIncomingShare(intent: Intent) {
        val action = intent.action
        val type = intent.type
        if (Intent.ACTION_SEND == action && type != null) {
            val uri = androidx.core.content.IntentCompat.getParcelableExtra(
                intent, Intent.EXTRA_STREAM, android.net.Uri::class.java
            )
            if (uri != null) saveUriToSharedFolder(uri)
        } else if (Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            androidx.core.content.IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, android.net.Uri::class.java
            )?.forEach { saveUriToSharedFolder(it) }
        }
    }

    private fun saveUriToSharedFolder(uri: android.net.Uri) {
        try {
            val rootDir = Environment.getExternalStorageDirectory()
            val networkShareDir = File(rootDir, "NetworkShare")

            if (!networkShareDir.exists()) {
                if (!networkShareDir.mkdirs())
                    Log.e("NetworkShare", "Could not create root folder, check permissions")
            }

            val fileName = getFileName(uri) ?: "shared_${System.currentTimeMillis()}"
            val extension = fileName.substringAfterLast('.', "").lowercase()

            val isDirectory = contentResolver.getType(uri) == "vnd.android.cursor.item/directory"
            val subFolder = when {
                isDirectory -> "Folders"
                extension in listOf("apk", "exe") -> "Apps"
                extension in listOf("mp3", "wav", "m4a", "flac", "ogg") -> "Audio"
                extension in listOf("mp4", "mkv", "mov", "avi", "webm") -> "Video"
                extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> "Pictures"
                extension in listOf(
                    "pdf",
                    "doc",
                    "docx",
                    "txt",
                    "xls",
                    "xlsx",
                    "ppt",
                    "pptx"
                ) -> "Documents"

                else -> "Others"
            }

            val targetDir = File(networkShareDir, subFolder).also { if (!it.exists()) it.mkdirs() }
            val destFile = File(targetDir, fileName)

            contentResolver.openInputStream(uri)?.use { it.copyTo(destFile.outputStream()) }

            val path = networkShareDir.absolutePath
            if (!FileManager.selectedPaths.contains(path)) {
                FileManager.selectedPaths.add(path)
                WebDAVService.savePaths(this)
            }
            FileManager.tempPriorityPath = path

            Toast.makeText(this, "Shared on NetworkShare", Toast.LENGTH_SHORT).show()
            startService(Intent(this, WebDAVService::class.java).apply { action = "REFRESH_INFO" })

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

// ─────────────────────────────────────────────────────────────────────────────
// Scrollbar modifier (shared utility)
// ─────────────────────────────────────────────────────────────────────────────

fun Modifier.draggableScrollbar(
    state: LazyListState,
    coroutineScope: CoroutineScope,
    color: Color = Color.DarkGray.copy(alpha = 0.6f)
): Modifier = this.composed {
    var isPressed by remember { mutableStateOf(false) }
    this
        .drawWithContent {
            drawContent()
            val layoutInfo = state.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.size < totalItems) {
                val viewportHeight = size.height
                val scrollbarHeight =
                    (viewportHeight * visibleItems.size / totalItems).coerceAtLeast(64f)
                val scrollProgress = state.firstVisibleItemIndex.toFloat() / totalItems
                val scrollbarOffsetY = scrollProgress * viewportHeight
                val thickness = if (isPressed) 8.dp.toPx() else 6.dp.toPx()
                val barColor = if (isPressed) Color(0xFF2BAED5).copy(alpha = 0.6f) else color
                val marginEnd = 8.dp.toPx()
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(
                        size.width - marginEnd - thickness,
                        scrollbarOffsetY.coerceIn(0f, viewportHeight - scrollbarHeight)
                    ),
                    size = Size(thickness, scrollbarHeight),
                    cornerRadius = CornerRadius(thickness / 2, thickness / 2)
                )
            }
        }
        .pointerInput(state) {
            detectDragGestures(
                onDragStart = { isPressed = true },
                onDragEnd = { isPressed = false },
                onDragCancel = { isPressed = false },
                onDrag = { change, _ ->
                    change.consume()
                    val totalItems = state.layoutInfo.totalItemsCount
                    if (totalItems > 0) {
                        val targetIndex = ((change.position.y / size.height) * totalItems).toInt()
                        coroutineScope.launch {
                            state.scrollToItem(
                                targetIndex.coerceIn(
                                    0,
                                    totalItems - 1
                                )
                            )
                        }
                    }
                }
            )
        }
}