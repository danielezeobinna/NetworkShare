package com.example.networkshare

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import com.example.networkshare.ui.theme.AppTheme
import com.example.networkshare.ui.theme.LocalDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import kotlinx.coroutines.delay

class MainActivity : androidx.fragment.app.FragmentActivity() {
    companion object {
        private const val REQ_PIN = 9999
    }

    private var isUnlocked by mutableStateOf(false)
    private var lastUnlockedTime = 0L
    private val cooldownMs = 25_000L
    private var isShowingAd = false
    private var isValidNetwork by mutableStateOf(true)
    private var serverAddresses by mutableStateOf("")
    private var isDiscoveryOn by mutableStateOf(false)  // will be set in onCreate
    private var isPending by mutableStateOf(false)
    private var showUnknownNetworkDialog by mutableStateOf(false)
    private var showNotificationDialog by mutableStateOf(false)
    private var interstitialAd: InterstitialAd? = null
    private var appTheme by mutableStateOf(AppTheme.SYSTEM)

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
                "com.example.networkshare.CHECK_LOCATION" -> {
                    checkLocationForUntrustedNetwork()
                }
                "com.example.networkshare.ADDRESSES_UPDATED" -> {
                    val data = intent.getStringExtra("address_list")
                    val validNetwork = intent.getBooleanExtra("is_valid_network", true)
                    if (data != null) {
                        serverAddresses = data
                        isValidNetwork = validNetwork  // ← add this state
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("lastUnlockedTime", lastUnlockedTime)
        outState.putBoolean("isUnlocked", isUnlocked)
    }

    @SuppressLint("ObsoleteSdkInt")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState != null) {
            lastUnlockedTime = savedInstanceState.getLong("lastUnlockedTime", 0L)
            isUnlocked = savedInstanceState.getBoolean("isUnlocked", false)
        }

        isPending = false
        isDiscoveryOn = isServiceRunning()
        WebDAVService.loadPaths(this)  // always restore saved state
        if (savedInstanceState == null) {
            handleIncomingShare(intent)
            loadAddresses()
        }

        MobileAds.initialize(this) {}
        loadInterstitialAd()

        val savedTheme = getPreferences(MODE_PRIVATE).getString("app_theme", "SYSTEM")
        appTheme = AppTheme.valueOf(savedTheme ?: "SYSTEM")

        setContent {
            NetworkShareTheme(appTheme = appTheme) {
                val darkTheme = when (appTheme) {
                    AppTheme.LIGHT -> false
                    AppTheme.DARK -> true
                    AppTheme.SYSTEM -> isSystemInDarkTheme()
                }

                val showUserGuide = remember { mutableStateOf(false) }
                val isPickerOpen = remember { mutableStateOf(false) }
                val showAllowedNetworks = remember { mutableStateOf(false) }
                val showBlockedNetworks = remember { mutableStateOf(false) }
                val currentPickerPath = remember { mutableStateOf<File?>(null) }

                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as Activity).window
                        WindowCompat.getInsetsController(window, view).apply {
                            isAppearanceLightStatusBars = !darkTheme
                            isAppearanceLightNavigationBars = !darkTheme
                        }
                        @Suppress("DEPRECATION")
                        window.navigationBarColor = if (darkTheme) "#010101".toColorInt() else "#EEF1F3".toColorInt()
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                    ) {
                        // Everything that was in your Surface before
                        Box(modifier = Modifier.weight(1f)) {
                            when {
                                !isUnlocked -> {
                                    BiometricGateScreen(onUnlockClick = { showBiometricPrompt() })
                                }

                                else -> {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            val pendingTrustSsid = WebDAVService.pendingTrustSsid.value

                                            LaunchedEffect(pendingTrustSsid) {
                                                if (pendingTrustSsid != null && isDiscoveryOn) showUnknownNetworkDialog = true
                                            }

                                            if (showUnknownNetworkDialog && pendingTrustSsid != null) {
                                                val isDark = when (appTheme) {
                                                    AppTheme.LIGHT -> false
                                                    AppTheme.DARK -> true
                                                    AppTheme.SYSTEM -> isSystemInDarkTheme()
                                                }
                                                Dialog(
                                                    onDismissRequest = { showUnknownNetworkDialog = false },
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
                                                            ) { showUnknownNetworkDialog = false },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Surface(
                                                            modifier = Modifier
                                                                .offset(y = (-24).dp)
                                                                .fillMaxWidth(0.95f)
                                                                .padding(horizontal = 4.dp),
                                                            shape = RoundedCornerShape(28.dp),
                                                            color = if (isDark) Color(0xFF252525) else Color(0xFFFCFCFC),
                                                            tonalElevation = 6.dp
                                                        ) {
                                                            Column(
                                                                modifier = Modifier.padding(24.dp),
                                                                horizontalAlignment = Alignment.Start
                                                            ) {
                                                                Text(
                                                                    text = "Unknown Network Detected",
                                                                    fontWeight = FontWeight.Bold,
                                                                    fontSize = 20.sp,
                                                                    color = MaterialTheme.colorScheme.onSurface
                                                                )

                                                                Spacer(modifier = Modifier.height(16.dp))

                                                                Text(
                                                                    text = "\"$pendingTrustSsid\" is an unknown network. Choose whether NetworkShare should share files on this network.",
                                                                    fontSize = 16.sp,
                                                                    color = MaterialTheme.colorScheme.onSurface
                                                                )

                                                                Spacer(modifier = Modifier.height(24.dp))

                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Column(modifier = Modifier.fillMaxWidth()) {
                                                                        TextButton(
                                                                            onClick = {
                                                                                NetworkTrustManager.allow(
                                                                                    this@MainActivity,
                                                                                    pendingTrustSsid
                                                                                )
                                                                                WebDAVService.pendingTrustSsid.value =
                                                                                    null
                                                                                NetworkTrustManager.restoreSharingNotification(
                                                                                    this@MainActivity
                                                                                )
                                                                                showUnknownNetworkDialog =
                                                                                    false
                                                                            },
                                                                            modifier = Modifier.fillMaxWidth()
                                                                        ) {
                                                                            Text(
                                                                                "Allow",
                                                                                color = Color(
                                                                                    0xFF2BAED5
                                                                                ),
                                                                                fontSize = 16.sp
                                                                            )
                                                                        }

                                                                        TextButton(
                                                                            onClick = {
                                                                                NetworkTrustManager.allowOnce(
                                                                                    pendingTrustSsid
                                                                                )
                                                                                WebDAVService.pendingTrustSsid.value =
                                                                                    null
                                                                                NetworkTrustManager.restoreSharingNotification(
                                                                                    this@MainActivity
                                                                                )
                                                                                showUnknownNetworkDialog =
                                                                                    false
                                                                            },
                                                                            modifier = Modifier.fillMaxWidth()
                                                                        ) {
                                                                            Text(
                                                                                "Allow Once",
                                                                                color = Color(
                                                                                    0xFF2BAED5
                                                                                ),
                                                                                fontSize = 16.sp
                                                                            )
                                                                        }

                                                                        TextButton(
                                                                            onClick = {
                                                                                NetworkTrustManager.block(
                                                                                    this@MainActivity,
                                                                                    pendingTrustSsid
                                                                                )
                                                                                WebDAVService.pendingTrustSsid.value =
                                                                                    null
                                                                                NetworkTrustManager.restoreSharingNotification(
                                                                                    this@MainActivity
                                                                                )
                                                                                showUnknownNetworkDialog =
                                                                                    false
                                                                            },
                                                                            modifier = Modifier.fillMaxWidth()
                                                                        ) {
                                                                            Text(
                                                                                "Block",
                                                                                color = Color(
                                                                                    0xFF2BAED5
                                                                                ),
                                                                                fontSize = 16.sp
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            if (showNotificationDialog) {
                                                val isDark = when (appTheme) {
                                                    AppTheme.LIGHT -> false
                                                    AppTheme.DARK -> true
                                                    AppTheme.SYSTEM -> isSystemInDarkTheme()
                                                }
                                                Dialog(
                                                    onDismissRequest = { showNotificationDialog = false },
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
                                                            ) { showNotificationDialog = false },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Surface(
                                                            modifier = Modifier
                                                                .offset(y = (-24).dp)
                                                                .fillMaxWidth(0.95f)
                                                                .padding(horizontal = 4.dp),
                                                            shape = RoundedCornerShape(28.dp),
                                                            color = if (isDark) Color(0xFF252525) else Color(0xFFFCFCFC),
                                                            tonalElevation = 6.dp
                                                        ) {
                                                            Column(
                                                                modifier = Modifier.padding(24.dp),
                                                                horizontalAlignment = Alignment.Start
                                                            ) {
                                                                Text(
                                                                    text = "Notifications Required",
                                                                    fontWeight = FontWeight.Bold,
                                                                    fontSize = 20.sp,
                                                                    color = MaterialTheme.colorScheme.onSurface
                                                                )

                                                                Spacer(modifier = Modifier.height(16.dp))

                                                                Text(
                                                                    text = "Android requires notifications to be enabled for NetworkShare to run. Please enable notifications for this app in Settings.",
                                                                    fontSize = 16.sp,
                                                                    color = MaterialTheme.colorScheme.onSurface
                                                                )

                                                                Spacer(modifier = Modifier.height(24.dp))

                                                                HorizontalDivider(
                                                                    modifier = Modifier.padding(
                                                                        horizontal = 8.dp
                                                                    ),
                                                                    thickness = 0.5.dp,
                                                                    color = Color.Gray.copy(
                                                                        alpha = 0.2f
                                                                    )
                                                                )

                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    TextButton(
                                                                        onClick = {
                                                                            showNotificationDialog = false
                                                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                                                                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                                                                }
                                                                                startActivity(intent)
                                                                            }
                                                                        },
                                                                        modifier = Modifier
                                                                            .weight(1f)
                                                                            // We use clickable here to force the custom ripple color
                                                                            .clickable(
                                                                                interactionSource = remember { MutableInteractionSource() },
                                                                                onClick = { /* This is handled by the TextButton's onClick */ }
                                                                            )
                                                                    ) {
                                                                        Text("Open Settings", color = Color(0xFF2BAED5), fontSize = 18.sp)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            when {
                                                showUserGuide.value -> UserGuideScreen(
                                                    onBack = { showUserGuide.value = false }
                                                )

                                                showAllowedNetworks.value -> NetworkListScreen(
                                                    title = "Allowed Networks",
                                                    networks = NetworkTrustManager.allowedNetworks,
                                                    iconRes = R.drawable.ic_wifi,
                                                    onRemove = { ssid ->
                                                        NetworkTrustManager.remove(
                                                            this@MainActivity,
                                                            ssid
                                                        )
                                                    },
                                                    onBack = { showAllowedNetworks.value = false }
                                                )

                                                showBlockedNetworks.value -> NetworkListScreen(
                                                    title = "Blocked Networks",
                                                    networks = NetworkTrustManager.blockedNetworks,
                                                    iconRes = R.drawable.ic_wifi,
                                                    onRemove = { ssid ->
                                                        NetworkTrustManager.remove(
                                                            this@MainActivity,
                                                            ssid
                                                        )
                                                    },
                                                    onBack = { showBlockedNetworks.value = false }
                                                )

                                                !isPickerOpen.value -> DiscoveryScreen(
                                                    isOn = isDiscoveryOn,
                                                    isPending = isPending,
                                                    addresses = serverAddresses,
                                                    onToggle = { start->
                                                        handleToggle(start)
                                                    },
                                                    onReload = {
                                                        if (isDiscoveryOn) {
                                                            val svcIntent = Intent(this@MainActivity, WebDAVService::class.java).apply {
                                                                action = "RESTART_SERVERS"
                                                            }
                                                            startService(svcIntent)
                                                        }
                                                    },
                                                    onOpenPicker = { isPickerOpen.value = true },
                                                    onOpenAllowedNetworks = {
                                                        showAllowedNetworks.value = true
                                                    },   // ← add
                                                    onOpenBlockedNetworks = {
                                                        showBlockedNetworks.value = true
                                                    },    // ← add
                                                    onOpenUserGuide = {
                                                        showUserGuide.value = true
                                                    },
                                                    currentTheme = appTheme,
                                                    onThemeChange = { theme ->
                                                        appTheme = theme
                                                        getPreferences(MODE_PRIVATE).edit { putString("app_theme", theme.name) }
                                                    },
                                                    isDark = when (appTheme) {
                                                        AppTheme.LIGHT -> false
                                                        AppTheme.DARK -> true
                                                        AppTheme.SYSTEM -> isSystemInDarkTheme()
                                                    },
                                                )

                                                else -> FilePickerSection(
                                                    onBack = { isPickerOpen.value = false },
                                                    currentPath = currentPickerPath
                                                )
                                            }
                                        }

                                        val imeVisible = WindowInsets.ime
                                            .getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

                                        var adHeight by remember { mutableStateOf(0.dp) }

                                        @Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
                                        if (!imeVisible) {
                                            AndroidView(
                                                factory = { context ->
                                                    AdView(context).apply {
                                                        val displayMetrics =
                                                            context.resources.displayMetrics
                                                        val adWidthPixels =
                                                            displayMetrics.widthPixels.toFloat()
                                                        val density = displayMetrics.density
                                                        val adWidth =
                                                            (adWidthPixels / density).toInt()
                                                        setAdSize(
                                                            AdSize.getInlineAdaptiveBannerAdSize(
                                                                adWidth,
                                                                65
                                                            )
                                                        )
                                                        adUnitId =
                                                            BuildConfig.ADMOB_BANNER_ID
                                                        adListener = object : AdListener() {
                                                            override fun onAdLoaded() {
                                                                // Ad loaded — give it space, max 65dp
                                                                adHeight = 65.dp
                                                            }

                                                            override fun onAdFailedToLoad(error: LoadAdError) {
                                                                // No ad — collapse space completely
                                                                adHeight = 0.dp
                                                            }
                                                        }
                                                        loadAd(AdRequest.Builder().build())
                                                    }
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(
                                                        min = 0.dp,
                                                        max = adHeight
                                                    ) // ← collapses when no ad
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

    @Suppress("DEPRECATION")
    private fun showBiometricPrompt() {
        val now = System.currentTimeMillis()
        if (isUnlocked && now - lastUnlockedTime < cooldownMs) {
            return  // Still within cooldown, skip prompt
        }

        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val biometricManager = BiometricManager.from(this)

        // 1. No security at all — just let them in
        if (!km.isDeviceSecure) {
            isUnlocked = true
            lastUnlockedTime = System.currentTimeMillis()
            initPermissions()
            return
        }

        // 2. Legacy PIN fallback — works on all versions
        val launchLegacyPin = {
            @Suppress("DEPRECATION")
            val intent = km.createConfirmDeviceCredentialIntent(
                "NetworkShare Security",
                null
            )
            if (intent != null) {
                startActivityForResult(intent, REQ_PIN)
            } else {
                isUnlocked = true
                lastUnlockedTime = System.currentTimeMillis()
                initPermissions()
            }
        }

        // 3. Check biometric availability
        val canUseBiometrics = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS

        // 4. If no biometrics available on ANY Android version — go straight to PIN
        if (!canUseBiometrics) {
            launchLegacyPin()
            return
        }

        // 5. Biometrics ARE available — show biometric prompt
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isUnlocked = true
                    lastUnlockedTime = System.currentTimeMillis()
                    initPermissions()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                            launchLegacyPin()
                        }
                        BiometricPrompt.ERROR_USER_CANCELED -> {
                            // Do nothing — let them try again via the button
                        }
                        else -> {
                            // Hardware error, sensor issue etc — fall to PIN
                            launchLegacyPin()
                        }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_PIN) {
            if (resultCode == RESULT_OK) {
                isUnlocked = true
                lastUnlockedTime = System.currentTimeMillis()
                initPermissions()
            } else {
                // User cancelled or failed — stay on gate screen, do nothing
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction("com.example.networkshare.SERVER_STOPPED")
            addAction("com.example.networkshare.ADDRESSES_UPDATED")
            addAction("com.example.networkshare.CHECK_LOCATION")
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
        isDiscoveryOn = isServiceRunning()

        // Returning from interstitial ad — skip auth re-check
        if (isShowingAd) {
            isShowingAd = false
            // still do the refresh and pending dialog below
            if (isDiscoveryOn) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val intent = Intent(this, WebDAVService::class.java).apply {
                        action = "REFRESH_INFO"
                    }
                    startService(intent)
                }, 1500)
            }
            val pending = WebDAVService.pendingTrustSsid.value
            if (pending != null && isDiscoveryOn) showUnknownNetworkDialog = true
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastUnlockedTime >= cooldownMs) {
            isUnlocked = false
            return
        }

        // Genuinely unlocked and within cooldown
        if (isDiscoveryOn) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val intent = Intent(this, WebDAVService::class.java).apply {
                    action = "REFRESH_INFO"
                }
                startService(intent)
            }, 1500)
        }
        val pending = WebDAVService.pendingTrustSsid.value
        if (pending != null && isDiscoveryOn) {
            showUnknownNetworkDialog = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    private fun saveAddresses(addresses: String) {
        getPreferences(MODE_PRIVATE).edit {
            putString("last_addresses", addresses)
        }
    }

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

    private fun loadAddresses() {
        serverAddresses = getPreferences(MODE_PRIVATE)
            .getString("last_addresses", "loading...") ?: ""
    }

    private fun handleToggle(start: Boolean) {
        if (start) {
            val notifManager = getSystemService(android.app.NotificationManager::class.java)
            if (!notifManager.areNotificationsEnabled()) {
                showNotificationDialog = true
                return
            }
        }

        if (isPending) return
        isPending = true

        if (start) {
            interstitialAd?.let { ad ->
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        interstitialAd = null
                        loadInterstitialAd()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            toggleService(true)
                        }
                    }
                    override fun onAdFailedToShowFullScreenContent(error: AdError) {
                        interstitialAd = null
                        loadInterstitialAd()
                        // Ad failed, just start service anyway
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            toggleService(true)
                        }
                    }
                }
                isShowingAd = true
                ad.show(this)
                return
            }
        }

        // No ad loaded or turning off — proceed normally
        if (start && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toggleService(start)
        }
    }

    private fun isServiceRunning(): Boolean {
        return WebDAVService.isRunning
    }

    private fun toggleService(start: Boolean) {
        val intent = Intent(this, WebDAVService::class.java)
        try {
            if (start) {
                startForegroundService(intent)
                isDiscoveryOn = true
            } else {
                stopService(intent)
                isDiscoveryOn = false
            }
        } finally {
            window.decorView.postDelayed({ isPending = false }, 500)
        }
    }

    private fun initPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ — MANAGE_EXTERNAL_STORAGE opens system settings page
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:${applicationContext.packageName}".toUri()
                }
                // Unlock optimistically — settings page has no callback.
                // onResume() re-checks storage state when user returns.
                isUnlocked = true
                startActivity(intent)
            } else {
                // Already granted — unlock now
                isUnlocked = true
            }
        } else {
            // Android 10 and below — request READ/WRITE at runtime.
            // isUnlocked is only set in onRequestPermissionsResult after granted.
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            androidx.core.app.ActivityCompat.requestPermissions(this, permissions, 101)
        }
    }

    fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
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
            // Don't stop the service — just request the permission.
            // The service will re-evaluate trust when the callback returns.
            requestLocationPermissions()
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
                isDiscoveryOn = false
            }
            if (granted) {
                // Storage confirmed — unlock and show discovery screen
                isUnlocked = true
            } else {
                // Stay locked — storage is required
                Toast.makeText(this, "Storage permission is required to use NetworkShare", Toast.LENGTH_LONG).show()
            }
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
            val networkShareDir = File(rootDir, "NetworkShare")

            if (!networkShareDir.exists()) {
                val created = networkShareDir.mkdirs()
                if (!created) {
                    Log.e("NetworkShare", "Could not create root folder, check permissions")
                }
            }

            // 1. Get File Name and Extension
            val fileName = getFileName(uri) ?: "shared_${System.currentTimeMillis()}"
            val extension = fileName.substringAfterLast('.', "").lowercase()

            val isDirectory = contentResolver.getType(uri) == "vnd.android.cursor.item/directory"
            val subFolder = when {
                isDirectory -> "Folders"

                // Apps: APK, EXE
                extension in listOf("apk", "exe") -> "Apps"

                // Audio: MP3, WAV, M4A, etc.
                extension in listOf("mp3", "wav", "m4a", "flac", "ogg") -> "Audio"

                // Video: MP4, MKV, MOV, etc.
                extension in listOf("mp4", "mkv", "mov", "avi", "webm") -> "Video"

                // Pictures: JPG, PNG, GIF, etc.
                extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> "Pictures"

                // Documents: PDF, DOCX, TXT, etc.
                extension in listOf("pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx") -> "Documents"

                // Default for everything else
                else -> "Others"
            }

            val targetDir = File(networkShareDir, subFolder)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val destFile = File(targetDir, fileName)

            // 3. Copy the file
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Update WebDAV logic
            val path = networkShareDir.absolutePath
            if (!WebDAVService.selectedPaths.contains(path)) {
                WebDAVService.selectedPaths.add(path)
                WebDAVService.savePaths(this)
            }
            WebDAVService.tempPriorityPath = path

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    modifier: Modifier = Modifier,
    isOn: Boolean,
    isPending: Boolean,
    addresses: String,
    onToggle: (Boolean) -> Unit,
    onReload: () -> Unit,
    onOpenPicker: () -> Unit,
    onOpenAllowedNetworks: () -> Unit,
    onOpenBlockedNetworks: () -> Unit,
    onThemeChange: (AppTheme) -> Unit,
    currentTheme: AppTheme,
    isDark: Boolean,
    onOpenUserGuide: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var isEditing by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val networkState = WebDAVService.networkState.value

    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

// Keep track of the text at the moment they clicked "Edit"
    var originalUsername by remember { mutableStateOf("") }
    var originalPassword by remember { mutableStateOf("") }

// Check if current text is different from what we started with
    val hasChanged = WebDAVService.username.value != originalUsername ||
            WebDAVService.password.value != originalPassword

    var showNetworkDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var themeExpanded by remember { mutableStateOf(false) }
    val themeRotation by animateFloatAsState(
        targetValue = if (themeExpanded) 90f else 0f,
        label = "themeArrow"
    )

    val bgColor = MaterialTheme.colorScheme.background

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            bgColor,
                            bgColor.copy(alpha = 0f)
                        ),
                        startY = 0f,
                        endY = 24.dp.toPx()
                    )
                )
            }
    ) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                onReload()
                scope.launch {
                    delay(1500)
                    isRefreshing = false
                }
            },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullRefreshState,
                    isRefreshing = isRefreshing,
                    color = Color(0xFF2BAED5),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        ) {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            if (isEditing) {
                                WebDAVService.username.value = originalUsername
                                WebDAVService.password.value = originalPassword
                                isEditing = false
                            }
                            focusManager.clearFocus()
                        })
                    }
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

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onOpenPicker,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_sp),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Choose Shared Paths...",
                                fontSize = 16.sp,
                                color = Color(0xFF2BAED5)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Shared Paths Addresses",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                    color = Color.Gray
                )
                Surface(
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 156.dp),
                    color = if (isOn && networkState == NetworkState.TRUSTED) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    val addressLines = addresses
                        .split("\n")
                        .filter { it.isNotBlank() }

                    val noPaths = WebDAVService.selectedPaths.isEmpty()

                    val displayLines = if (networkState != NetworkState.TRUSTED) {
                        val grouped = mutableListOf<String>()
                        var count = 0
                        for (line in addressLines) {
                            if (count >= 2) break
                            grouped.add(line)
                            if (line.startsWith("http")) count++
                        }
                        grouped
                    } else {
                        addressLines
                    }

                    when {
                        // State 1 — service is off
                        !isOn -> {
                            showNetworkDialog = false
                            Column(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .graphicsLayer(alpha = 0.5f)
                            ) {
                                Text(
                                    text = "NetworkShare is off.\nTurn on the switch to start sharing.",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    color = Color.Gray
                                )
                            }
                        }

                        // State 2 — no paths selected
                        noPaths -> {
                            showNetworkDialog = false
                            Column(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .graphicsLayer(alpha = 0.5f)
                            ) {
                                Text(
                                    text = "No folders selected.\nGo to 'Choose Shared Paths' to start.",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    color = Color.Gray
                                )
                            }
                        }

                        // State 3 — service on, no network → also trigger dialog
                        isOn && networkState == NetworkState.NO_NETWORK -> {
                            LaunchedEffect(Unit) {
                                delay(2000L)
                                showNetworkDialog = true
                            }
                            Column(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .graphicsLayer(alpha = 0.5f)
                            ) {
                                Text(
                                    text = "No network detected.\nJoin a WiFi or create a Hotspot to start sharing.",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    color = Color.Gray
                                )
                            }
                        }

                        // State 4 — service on, trusted network
                        isOn && networkState == NetworkState.TRUSTED -> {
                            showNetworkDialog = false
                            SelectionContainer {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .draggableScrollbar(listState, scope)
                                        .padding(16.dp)
                                ) {
                                    itemsIndexed(displayLines) { _, address ->
                                        val isUrl = address.startsWith("http")
                                        Text(
                                            text = address,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 14.sp,
                                            color = if (isUrl) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(
                                                bottom = if (isUrl) 16.dp else 2.dp,
                                                top = 2.dp
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // State 5 — service on, untrusted/pending
                        else -> {
                            showNetworkDialog = false
                            Column(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .graphicsLayer(alpha = 0.5f)
                            ) {
                                displayLines.forEach { address ->
                                    val isUrl = address.startsWith("http")
                                    Text(
                                        text = address,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp,
                                        color = Color.Gray,
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

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Network Security",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                    color = Color.Gray
                )
                Surface(
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth()

                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Digest Authentication",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp
                                )
                            }

                            Switch(
                                checked = WebDAVService.isAuthEnabled.value,
                                onCheckedChange = {
                                    WebDAVService.isAuthEnabled.value = it
                                    WebDAVService.savePaths(context)  // always persist immediately
                                    if (!it) {
                                        // Turning auth OFF → stop the service so it restarts without auth
                                        onToggle(false)
                                    }
                                    // Turning auth ON → flag is saved above, no service restart needed
                                },
                                enabled = true,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = if (isDark) Color.Black else Color.White,
                                    uncheckedThumbColor = if (isDark) Color.White else Color(0xFF666660),

                                    uncheckedTrackColor = if (isDark) Color(0xFF666660) else Color(
                                        0xFFEEF1F3
                                    ),

                                    uncheckedBorderColor = Color(0xFF666660)
                                )
                            )
                        }

                        AnimatedVisibility(
                            visible = WebDAVService.isAuthEnabled.value,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column {
                                Spacer(modifier = Modifier.height(4.dp))

                                // Edit button
                                Row(
                                    horizontalArrangement = Arrangement.End,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    TextButton(
                                        onClick = {
                                            if (!isEditing) {
                                                // Start Editing: Capture the current values
                                                originalUsername = WebDAVService.username.value
                                                originalPassword = WebDAVService.password.value
                                                isEditing = true
                                            } else {
                                                // Save & Close: Keep the new values and close
                                                isEditing = false
                                                focusManager.clearFocus()
                                                WebDAVService.savePaths(context)
                                            }
                                        },
                                        contentPadding = PaddingValues(
                                            horizontal = 8.dp,
                                            vertical = 4.dp
                                        )
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val label =
                                                if (isEditing && hasChanged) "Done" else if (isEditing) "Close" else "Edit"
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = Color(0xFF2BAED5)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                painter = painterResource(
                                                    id = if (isEditing && hasChanged) R.drawable.baseline_check else if (isEditing) R.drawable.baseline_close else R.drawable.baseline_edit
                                                ),
                                                contentDescription = if (isEditing) "Save" else "Edit",
                                                modifier = Modifier.size(if (isEditing) 16.dp else 24.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Username Row
                                LaunchedEffect(isEditing) {
                                    if (isEditing) {
                                        focusRequester.requestFocus()
                                        softwareKeyboardController?.show()
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Username",
                                        color = Color(0xFF2BAED5),
                                        fontSize = 16.sp,
                                        modifier = Modifier.width(90.dp)
                                    )
                                    BasicTextField(
                                        value = WebDAVService.username.value,
                                        onValueChange = { WebDAVService.username.value = it },
                                        enabled = isEditing,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                // Software Keyboard 'Check' counts as Saving
                                                isEditing = false
                                                focusManager.clearFocus()
                                                WebDAVService.savePaths(context)
                                            }
                                        ),
                                        textStyle = LocalTextStyle.current.copy(
                                            fontSize = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        cursorBrush = SolidColor(Color(0xFF2BAED5)),
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(focusRequester)
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier
                                        .offset(y = 4.dp)
                                        .padding(start = 90.dp)
                                        .padding(end = 16.dp)
                                        .padding(top = 8.dp),
                                    thickness = 0.5.dp,
                                    color = Color.Gray.copy(alpha = 0.3f)
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                // Password Row
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                ) {
                                    Text(
                                        text = "Password",
                                        color = Color(0xFF2BAED5),
                                        fontSize = 16.sp,
                                        modifier = Modifier.width(90.dp)
                                    )
                                    BasicTextField(
                                        value = WebDAVService.password.value,
                                        onValueChange = { WebDAVService.password.value = it },
                                        enabled = isEditing,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                // Software Keyboard 'Check' counts as Saving
                                                isEditing = false
                                                focusManager.clearFocus()
                                                WebDAVService.savePaths(context)
                                            }
                                        ),
                                        textStyle = LocalTextStyle.current.copy(
                                            fontSize = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        cursorBrush = SolidColor(Color(0xFF2BAED5)),
                                        singleLine = true,
                                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .padding(start = 8.dp)
                                            .pointerInput(Unit) {
                                                detectTapGestures(
                                                    onPress = {
                                                        passwordVisible = true
                                                        tryAwaitRelease() // Wait for finger to lift
                                                        passwordVisible = false
                                                    }
                                                )
                                            }
                                    ) {
                                        Icon(
                                            painter = painterResource(
                                                id = if (passwordVisible) R.drawable.baseline_visibility else R.drawable.baseline_visibility_off
                                            ),
                                            contentDescription = "Reveal password",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Manage Networks",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                    color = Color.Gray
                )
                Surface(
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = Color.DarkGray),
                                    onClick = onOpenAllowedNetworks
                                )
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_allowed_wifi),
                                contentDescription = "Allowed networks icon",
                                alpha = if (isDark) 0.85f else 1.0f,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Allowed Networks",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier
                                .offset(y = 4.dp)
                                .padding(start = 45.dp)
                                .padding(end = 8.dp)
                                .padding(top = 8.dp),
                            thickness = 0.5.dp,
                            color = Color.Gray.copy(alpha = 0.2f)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = Color.DarkGray),
                                    onClick = onOpenBlockedNetworks
                                )
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_blocked_wifi),
                                contentDescription = "Blocked networks icon",
                                alpha = if (isDark) 0.85f else 1.0f,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Blocked Networks",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp
                            )
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
                                color = if (isDark) Color(0xFF252525) else Color(0xFFFCFCFC),
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
                                                try {
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {
                                                    context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                // We use clickable here to force the custom ripple color
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
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
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(
                            color = MaterialTheme.colorScheme.background,
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(
                                color = Color.Gray,
                                bounded = true,
                                radius = 19.dp
                            ),
                            onClick = { showMenu = true }
                        )
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.baseline_dehaze),
                        contentDescription = "Menu",
                        tint = Color(0xFF666660),
                        modifier = Modifier.size(22.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    )
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = R.drawable.baseline_guide),
                                    contentDescription = null,
                                    tint = Color(0xFF2BAED5),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "User Guide",
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        onClick = {
                            showMenu = false
                            onOpenUserGuide()
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        thickness = 0.5.dp,
                        color = Color.Gray.copy(alpha = 0.2f)
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = R.drawable.baseline_color_lens),
                                    contentDescription = null,
                                    tint = Color(0xFF2BAED5),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Appearance",
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    painter = painterResource(id = R.drawable.baseline_chevron_right),
                                    contentDescription = null,
                                    tint = Color.Gray,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .graphicsLayer { rotationZ = themeRotation }
                                )
                            }
                        },
                        onClick = { themeExpanded = !themeExpanded }
                    )

// Animated theme options dropdown
                    AnimatedVisibility(
                        visible = themeExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            listOf(
                                Triple(AppTheme.LIGHT, "Light", R.drawable.baseline_light_mode),
                                Triple(AppTheme.DARK, "Dark", R.drawable.baseline_dark_mode),
                                Triple(AppTheme.SYSTEM, "System", R.drawable.baseline_system_mode)
                            ).forEach { (theme, label, icon) ->
                                val isSelected = currentTheme == theme
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 32.dp) // ← indent under App Themes
                                        ) {
                                            Icon(
                                                painter = painterResource(id = icon),
                                                contentDescription = null,
                                                tint = if (isSelected) Color(0xFF2BAED5) else Color.Gray,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = label,
                                                fontSize = 15.sp,
                                                color = if (isSelected) Color(0xFF2BAED5)
                                                else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                            // Checkmark for selected
                                            if (isSelected) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.baseline_check),
                                                    contentDescription = null,
                                                    tint = Color(0xFF2BAED5),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        onThemeChange(theme)
                                        showMenu = false
                                        themeExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } // end PullToRefreshBox
    } // end outer Box
} // end DiscoveryScreen

@Composable
fun FilePickerSection(
    onBack: () -> Unit,
    currentPath: MutableState<File?>
) {
    val context = LocalContext.current
    val activity = context as MainActivity
    var currentPath by currentPath

    val handleBack = {
        if (currentPath == null) {
            if (WebDAVService.isRunning) {
                val intent = Intent(context, WebDAVService::class.java).apply {
                    action = "REFRESH_INFO"
                }
                context.startService(intent)  // not startForegroundService
            }
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
    val scope = rememberCoroutineScope()

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
                            if (currentPath != null) {
                                currentPath = null
                                WebDAVService.scannedItems.clear()
                            }
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

                        // Skip showing segments like "/", "storage" or "emulated"
                        // only if they are just parents of the actual storage roots
                        if (file.path != "/" && file.path != "/storage" && file.path != "/storage/emulated") {
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
                    .draggableScrollbar(listState, scope)
                    .graphicsLayer(alpha = if (isLoading) 0f else 1f),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(itemsToShow, key = { it.file.absolutePath }) { folderItem ->

                    val isStorageRoot = currentPath == null
                    val isLast = folderItem == itemsToShow.last()

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
                        isLast = isLast,
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
    isLast: Boolean = false,
    onClick: () -> Unit
) {
    val isDark = LocalDarkTheme.current
    val context = LocalContext.current
    val isChecked = remember(fullPath, WebDAVService.selectedPaths.size) {
        WebDAVService.selectedPaths.contains(fullPath)
    }

    val isInherited = remember(fullPath, WebDAVService.selectedPaths.size) {
        WebDAVService.selectedPaths.any { shared ->
            fullPath.startsWith("$shared/") && fullPath != shared
        }
    }

    val inheritedColor = if (isDark) Color.Gray else Color.LightGray

    val inheritedCheck = if (isDark) Color.LightGray else Color.White

    val isPartiallyChecked = remember(fullPath, WebDAVService.selectedPaths.size) {
        !isChecked && !isInherited && WebDAVService.selectedPaths.any { shared ->
            shared.startsWith("$fullPath/") && shared != fullPath
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(
                id = if (path == "Folder") R.drawable.ic_folder else R.drawable.ic_drive
            ),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .padding(end = 16.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
            Text(
                text = name,
                modifier = Modifier.offset(y = if (isLast) 0.dp else 4.dp),
                color = if (isInherited) Color.Gray else MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp
            )
            if (!isLast) {
                HorizontalDivider(
                    modifier = Modifier
                        .offset(y = (16).dp)
                        .padding(top = 8.dp),
                    thickness = 0.5.dp,
                    color = Color.Gray.copy(alpha = 0.3f)
                )
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .padding(4.dp)
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

                        if (WebDAVService.isRunning) {
                            val intent = Intent(context, WebDAVService::class.java).apply {
                                action = "REFRESH_INFO"
                            }
                            context.startService(intent)  // not startForegroundService
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
                    } else if (isDark) {
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
    val isDark = LocalDarkTheme.current
    var buttonReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100)
        buttonReady = true
        delay(1000)
        onUnlockClick()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            tonalElevation = 4.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .widthIn(max = 360.dp) // Prevents it from getting too wide in landscape
                .fillMaxWidth(), // Tells it to fill up to that 400.dp limit
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.baseline_lock),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "App Locked",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Authenticate to manage your server",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onUnlockClick,
                    enabled = buttonReady,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2BAED5),
                        disabledContainerColor = Color(0xFF2BAED5).copy(alpha = 0.5f)
                    ),
                ) {
                    Text(
                        text = "Tap to Unlock",
                        modifier = Modifier.padding(horizontal = 8.dp), // Space inside the button
                        color = if (isDark) Color.Black else Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun NetworkListScreen(
    title: String,
    networks: List<String>,
    iconRes: Int,
    onRemove: (String) -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(32.dp).offset(x = (-8).dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.baseline_chevron_left),
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (networks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No networks here",
                            color = Color.Gray,
                            fontSize = 15.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .draggableScrollbar(listState, scope),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(networks, key = { it }) { ssid ->
                            NetworkRow(
                                ssid = ssid,
                                iconRes = iconRes,
                                isLast = ssid == networks.last(),
                                onRemove = { onRemove(ssid) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkRow(
    ssid: String,
    iconRes: Int,
    isLast: Boolean = false,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            tint = Color(0xFF2BAED5),
            contentDescription = null,
            modifier = Modifier
                .size(28.dp)
                .offset(y = (-4).dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                text = ssid,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp
            )
            if (!isLast) {
                HorizontalDivider(
                    modifier = Modifier
                        .offset(y = 4.dp)
                        .padding(top = 8.dp),
                    thickness = 0.5.dp,
                    color = Color.Gray.copy(alpha = 0.3f)
                )
            }
        }

        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .size(28.dp)
                .offset(y = (-4).dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.baseline_close),
                contentDescription = "Remove",
                tint = Color.Gray,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun UserGuideScreen(onBack: () -> Unit) {
    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(32.dp).offset(x = (-8).dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.baseline_chevron_left),
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "User Guide",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Coming Soon...",
                    color = Color.Gray,
                    fontSize = 15.sp
                )
            }
        }
    }
}

fun Modifier.draggableScrollbar(
    state: LazyListState,
    coroutineScope: CoroutineScope,
    color: Color = Color.DarkGray.copy(alpha = 0.6f)
): Modifier = this.composed {
    // Both blocks below can now see this variable
    var isPressed by remember { mutableStateOf(false) }

    this.drawWithContent {
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
    }.pointerInput(state) {
        detectDragGestures(
            onDragStart = { isPressed = true },
            onDragEnd = { isPressed = false },
            onDragCancel = { isPressed = false },
            onDrag = { change, _ ->
                change.consume()
                val totalItems = state.layoutInfo.totalItemsCount
                if (totalItems > 0) {
                    val viewportHeight = size.height
                    val touchY = change.position.y
                    val targetIndex = ((touchY / viewportHeight) * totalItems).toInt()

                    coroutineScope.launch {
                        state.scrollToItem(targetIndex.coerceIn(0, totalItems - 1))
                    }
                }
            }
        )
    }
}