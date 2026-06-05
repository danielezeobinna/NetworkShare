package com.danieleze.networkshare

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import com.danieleze.networkshare.ui.theme.AppTheme
import com.danieleze.networkshare.ui.theme.LocalDarkTheme
import com.danieleze.networkshare.ui.theme.NetworkShareTheme
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * MainActivity — owns ONLY the UI.
 *
 * It extends AppControl, which handles all background logic and lifecycle.
 * This class calls setContent and composes every screen. Nothing else.
 */
class MainActivity : AppControl() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)   // AppControl.onCreate runs first
        enableEdgeToEdge()

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
                                                if (pendingTrustSsid != null && isDiscoveryOn)
                                                    showUnknownNetworkDialog = true
                                            }

                                            LocationOffDialog(
                                                show = showLocationOffDialog,
                                                appTheme = appTheme,
                                                onDismiss = { showLocationOffDialog = false }
                                            )
                                            UnknownNetworkDialog(
                                                show = showUnknownNetworkDialog,
                                                ssid = pendingTrustSsid,
                                                appTheme = appTheme,
                                                onDismiss = { showUnknownNetworkDialog = false }
                                            )
                                            NotificationPermissionDialog(
                                                show = showNotificationDialog,
                                                appTheme = appTheme,
                                                onDismiss = { showNotificationDialog = false }
                                            )
                                            NoNetworkDialog(
                                                show = showNetworkDialog,
                                                appTheme = appTheme,
                                                onDismiss = { showNetworkDialog = false }
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
                                                        networks = NetworkTrustManager.allowedNetworks,
                                                        iconRes = R.drawable.ic_wifi,
                                                        onRemove = { ssid ->
                                                            NetworkTrustManager.remove(
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
                                                        networks = NetworkTrustManager.blockedNetworks,
                                                        iconRes = R.drawable.ic_wifi,
                                                        onRemove = { ssid ->
                                                            NetworkTrustManager.remove(
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
                                                        isOn = isDiscoveryOn,
                                                        isPending = isPending,
                                                        addresses = serverAddresses,
                                                        onToggle = { start -> handleToggle(start) },
                                                        onReload = {
                                                            if (isDiscoveryOn) {
                                                                startService(
                                                                    Intent(
                                                                        this@MainActivity,
                                                                        WebDAVService::class.java
                                                                    ).apply {
                                                                        action = "RESTART_SERVERS"
                                                                    })
                                                            }
                                                        },
                                                        onNoNetwork = { showNetworkDialog = true },
                                                        onDismissNetworkDialog = {
                                                            showNetworkDialog = false
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
                                                        currentTheme = appTheme,
                                                        onThemeChange = { theme -> saveTheme(theme) },
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
}

// ─────────────────────────────────────────────────────────────────────────────
// Screens
// ─────────────────────────────────────────────────────────────────────────────

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
    onNoNetwork: () -> Unit,
    onDismissNetworkDialog: () -> Unit,
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

    var originalUsername by remember { mutableStateOf("") }
    var originalPassword by remember { mutableStateOf("") }

    val hasChanged = WebDAVService.username.value != originalUsername ||
            WebDAVService.password.value != originalPassword

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
    ) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(bgColor, bgColor.copy(alpha = 0f)),
                            startY = 0f,
                            endY = 36.dp.toPx()
                        )
                    )
                }
        ) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    onReload()
                    scope.launch { delay(1500); isRefreshing = false }
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

                    // ── Header row ────────────────────────────────────────────────
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
                                uncheckedTrackColor = if (isDark) Color(0xFF666660) else Color(
                                    0xFFEEF1F3
                                ),
                                uncheckedBorderColor = Color(0xFF666660)
                            )
                        )
                    }

                    // ── Choose paths button ───────────────────────────────────────
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onOpenPicker,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_sp),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
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

                    // ── Address card ──────────────────────────────────────────────
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
                        color = if (isOn && networkState == NetworkState.TRUSTED)
                            MaterialTheme.colorScheme.surfaceVariant
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        val addressLines = addresses.split("\n").filter { it.isNotBlank() }
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
                        } else addressLines

                        when {
                            !isOn -> {
                                onDismissNetworkDialog()
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

                            noPaths -> {
                                onDismissNetworkDialog()
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

                            isOn && networkState == NetworkState.NO_NETWORK -> {
                                LaunchedEffect(Unit) { delay(2000L); onNoNetwork() }
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

                            isOn && networkState == NetworkState.TRUSTED -> {
                                onDismissNetworkDialog()
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

                            else -> {
                                onDismissNetworkDialog()
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

                    // ── Network Security card ─────────────────────────────────────
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
                        modifier = Modifier.fillMaxWidth(),
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
                                        WebDAVService.savePaths(context)
                                        if (!it) onToggle(false)
                                    },
                                    enabled = true,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = if (isDark) Color.Black else Color.White,
                                        uncheckedThumbColor = if (isDark) Color.White else Color(
                                            0xFF666660
                                        ),
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

                                    // Edit / Done / Close button
                                    Row(
                                        horizontalArrangement = Arrangement.End,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        TextButton(
                                            onClick = {
                                                if (!isEditing) {
                                                    originalUsername = WebDAVService.username.value
                                                    originalPassword = WebDAVService.password.value
                                                    isEditing = true
                                                } else {
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
                                                        id = if (isEditing && hasChanged) R.drawable.baseline_check
                                                        else if (isEditing) R.drawable.baseline_close
                                                        else R.drawable.baseline_edit
                                                    ),
                                                    contentDescription = if (isEditing) "Save" else "Edit",
                                                    modifier = Modifier.size(if (isEditing) 16.dp else 24.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    LaunchedEffect(isEditing) {
                                        if (isEditing) {
                                            focusRequester.requestFocus(); softwareKeyboardController?.show()
                                        }
                                    }

                                    // Username row
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
                                            keyboardActions = KeyboardActions(onDone = {
                                                isEditing =
                                                    false; focusManager.clearFocus(); WebDAVService.savePaths(
                                                context
                                            )
                                            }),
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
                                            .padding(start = 90.dp, end = 16.dp, top = 8.dp),
                                        thickness = 0.5.dp,
                                        color = Color.Gray.copy(alpha = 0.3f)
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Password row
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
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
                                            keyboardActions = KeyboardActions(onDone = {
                                                isEditing =
                                                    false; focusManager.clearFocus(); WebDAVService.savePaths(
                                                context
                                            )
                                            }),
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
                                                    detectTapGestures(onPress = {
                                                        passwordVisible =
                                                            true; tryAwaitRelease(); passwordVisible =
                                                        false
                                                    })
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

                    // ── Manage Networks card ──────────────────────────────────────
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
                        modifier = Modifier.fillMaxWidth(),
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
                                    .padding(start = 45.dp, end = 8.dp, top = 8.dp),
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
                }
            }
        }

        // ── Hamburger menu ────────────────────────────────────────────────
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
            ) {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(38.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.baseline_dehaze),
                        contentDescription = "Menu",
                        tint = Color(0xFF666660),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(color = MaterialTheme.colorScheme.surfaceVariant)
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
                    onClick = { showMenu = false; onOpenUserGuide() }
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
                AnimatedVisibility(
                    visible = themeExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column {
                        listOf(
                            Triple(AppTheme.LIGHT, "Light", R.drawable.baseline_light_mode),
                            Triple(AppTheme.DARK, "Dark", R.drawable.baseline_dark_mode),
                            Triple(
                                AppTheme.SYSTEM,
                                "System",
                                R.drawable.baseline_system_mode
                            )
                        ).forEach { (theme, label, icon) ->
                            val isSelected = currentTheme == theme
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 32.dp)
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
                                            color = if (isSelected) Color(0xFF2BAED5) else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isSelected) Icon(
                                            painter = painterResource(id = R.drawable.baseline_check),
                                            contentDescription = null,
                                            tint = Color(0xFF2BAED5),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    onThemeChange(theme); showMenu = false; themeExpanded =
                                    false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FilePickerSection(
    onBack: () -> Unit,
    currentPath: MutableState<File?>
) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var currentPath by currentPath
    var isExiting by remember { mutableStateOf(false) }

    val handleBack = {
        scope.launch {
            if (currentPath == null) {
                if (WebDAVService.isRunning) {
                    context.startService(Intent(context, WebDAVService::class.java).apply {
                        action = "REFRESH_INFO"
                    })
                }
                onBack()
            } else {
                isExiting = true; delay(220); isExiting = false
                val storages = activity.getAvailableStorages()
                currentPath =
                    if (storages.any { it.absolutePath == currentPath?.absolutePath }) null
                    else currentPath?.parentFile
                WebDAVService.scannedItems.clear()
            }
        }
    }

    val pathParts = remember(currentPath) {
        val parts = mutableListOf<File>()
        var temp = currentPath
        while (temp != null) {
            parts.add(0, temp); temp = temp.parentFile
        }
        parts
    }

    val itemsToShow = WebDAVService.scannedItems.sortedBy { it.name.lowercase() }
    val isLoading = WebDAVService.isScanning.value

    LaunchedEffect(currentPath) {
        if (currentPath == null) {
            WebDAVService.scannedItems.clear()
            WebDAVService.scannedItems.addAll(
                activity.getAvailableStorages().map { FolderItem(it, it.name, true) }
            )
        } else {
            WebDAVService.requestFolderScan(currentPath)
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) { handleBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
    ) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                val breadcrumbScrollState = rememberScrollState()

                fun formatBreadcrumbName(name: String): String =
                    if (name.length > 15) name.take(6) + "..." + name.takeLast(6) else name

                LaunchedEffect(pathParts.size) {
                    breadcrumbScrollState.animateScrollTo(
                        breadcrumbScrollState.maxValue
                    )
                }

                Spacer(modifier = Modifier.height(42.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_sp),
                            contentDescription = null,
                            modifier = Modifier
                                .size(26.dp)
                                .clickable {
                                    if (currentPath != null) {
                                        scope.launch {
                                            isExiting = true; delay(220); isExiting =
                                            false; currentPath =
                                            null; WebDAVService.scannedItems.clear()
                                        }
                                    }
                                }
                        )
                        Row(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .horizontalScroll(breadcrumbScrollState),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            pathParts.forEachIndexed { index, file ->
                                val rawName = when {
                                    file.absolutePath.endsWith("emulated/0") -> "Internal"
                                    file.absolutePath.startsWith("/storage/") && file.parentFile?.path == "/storage" ->
                                        if (file.name.contains("-")) "SD Card" else "USB"

                                    else -> file.name
                                }
                                if (file.path != "/" && file.path != "/storage" && file.path != "/storage/emulated") {
                                    Icon(
                                        painter = painterResource(id = R.drawable.baseline_chevron_right),
                                        contentDescription = null,
                                        tint = Color(0xFF666660),
                                        modifier = Modifier
                                            .size(22.dp)
                                            .padding(horizontal = 2.dp)
                                    )
                                    Text(
                                        text = formatBreadcrumbName(rawName),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (index == pathParts.size - 1) MaterialTheme.colorScheme.onSurface else Color.Gray,
                                        modifier = Modifier.clickable {
                                            scope.launch {
                                                isExiting = true; delay(220); isExiting =
                                                false; currentPath =
                                                file; WebDAVService.scannedItems.clear()
                                            }
                                        }
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
                        .background(color = MaterialTheme.colorScheme.surfaceVariant)
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
                            val isStorageRoot = remember(isExiting) { currentPath == null }
                            val isLast = folderItem == itemsToShow.last()
                            val label = remember(folderItem, isStorageRoot) {
                                when {
                                    isStorageRoot && folderItem.file.absolutePath.contains("emulated/0") -> "Internal Storage"
                                    isStorageRoot -> "SD Card (${folderItem.name})"
                                    else -> folderItem.name
                                }
                            }
                            StorageRow(
                                name = label,
                                path = if (isStorageRoot) folderItem.file.absolutePath else "Folder",
                                fullPath = folderItem.file.absolutePath,
                                isExiting = isExiting,
                                isLast = isLast,
                                onClick = {
                                    if (folderItem.hasSubFolders || isStorageRoot) {
                                        scope.launch {
                                            isExiting = true; delay(220); isExiting =
                                            false; currentPath = folderItem.file
                                        }
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "No sub-folders inside",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )
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
            ) {
                IconButton(
                    onClick = { handleBack() },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.baseline_chevron_left),
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp)
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
    isExiting: Boolean = false,
    index: Int = 0,
    onClick: () -> Unit
) {
    val isDark = LocalDarkTheme.current
    val context = LocalContext.current
    val isChecked = remember(fullPath, WebDAVService.selectedPaths.size) {
        WebDAVService.selectedPaths.contains(fullPath)
    }
    val isInherited = remember(fullPath, WebDAVService.selectedPaths.size) {
        WebDAVService.selectedPaths.any { shared -> fullPath.startsWith("$shared/") && fullPath != shared }
    }
    val inheritedColor = if (isDark) Color.Gray else Color.LightGray
    val inheritedCheck = if (isDark) Color.LightGray else Color.White
    val isPartiallyChecked = remember(fullPath, WebDAVService.selectedPaths.size) {
        !isChecked && !isInherited && WebDAVService.selectedPaths.any { shared ->
            shared.startsWith(
                "$fullPath/"
            ) && shared != fullPath
        }
    }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(index * 40L); visible = true }

    val effectiveVisible = visible && !isExiting
    val offsetX by animateFloatAsState(
        targetValue = if (effectiveVisible) 0f else 40f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "slideX"
    )
    val alpha by animateFloatAsState(
        targetValue = if (effectiveVisible) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "fade"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = offsetX.dp.toPx(); this.alpha = alpha }
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = if (path == "Folder") R.drawable.ic_folder else R.drawable.ic_drive),
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
                        .offset(y = 16.dp)
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
                    if (isInherited) inheritedColor else if (isChecked || isPartiallyChecked) Color(
                        0xFF2BAED5
                    ) else Color.Gray,
                    RoundedCornerShape(6.dp)
                )
                .background(
                    if (isInherited) inheritedColor else if (isChecked) Color(0xFF2BAED5) else Color.Transparent,
                    RoundedCornerShape(6.dp)
                )
                .clickable {
                    if (!isInherited) {
                        WebDAVService.toggleSelection(context, fullPath)
                        if (WebDAVService.isRunning) {
                            context.startService(
                                Intent(
                                    context,
                                    WebDAVService::class.java
                                ).apply { action = "REFRESH_INFO" })
                        }
                    }
                }
        ) {
            if (isChecked || isInherited) {
                Icon(
                    painter = painterResource(id = R.drawable.baseline_check),
                    contentDescription = "Selected",
                    tint = if (isInherited) inheritedCheck else if (isDark) Color.Black else Color.White,
                    modifier = Modifier
                        .size(22.dp)
                        .padding(2.dp)
                )
            } else if (isPartiallyChecked) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(Color(0xFF2BAED5), RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
fun BiometricGateScreen(onUnlockClick: () -> Unit) {
    var buttonReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(100); buttonReady = true; delay(1000); onUnlockClick() }

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
                .widthIn(max = 360.dp)
                .fillMaxWidth(),
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
                    )
                ) {
                    val localIsDark = LocalDarkTheme.current
                    Text(
                        text = "Tap to Unlock",
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = if (localIsDark) Color.Black else Color.White
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

    androidx.activity.compose.BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
    ) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(42.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
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
                            contentPadding = PaddingValues(
                                horizontal = 24.dp,
                                vertical = 32.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(networks, key = { _, ssid -> ssid }) { index, ssid ->
                                NetworkRow(
                                    ssid = ssid,
                                    iconRes = iconRes,
                                    isLast = ssid == networks.last(),
                                    index = index,
                                    onRemove = { onRemove(ssid) })
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
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.baseline_chevron_left),
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp)
                    )
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
    index: Int = 0,
    onRemove: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { delay(index * 40L); visible = true }

    val effectiveVisible = visible && !removing
    val offsetX by animateFloatAsState(
        targetValue = if (effectiveVisible) 0f else 40f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "slideX"
    )
    val alpha by animateFloatAsState(
        targetValue = if (effectiveVisible) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "fade"
    )

    var collapsed by remember { mutableStateOf(false) }
    LaunchedEffect(removing) {
        if (removing) {
            delay(240); collapsed = true; onRemove()
        }
    }

    AnimatedVisibility(visible = !collapsed, exit = shrinkVertically(animationSpec = tween(200))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX.dp.toPx(); this.alpha = alpha }
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
                    modifier = Modifier.offset(y = if (isLast) 0.dp else 4.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                )
                if (!isLast) {
                    HorizontalDivider(
                        modifier = Modifier
                            .offset(y = 16.dp)
                            .padding(top = 8.dp),
                        thickness = 0.5.dp,
                        color = Color.Gray.copy(alpha = 0.3f)
                    )
                }
            }
            IconButton(
                onClick = { removing = true },
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
}

@Composable
fun UserGuideScreen(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
    ) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(42.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
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
                    Text(text = "Coming Soon...", color = Color.Gray, fontSize = 15.sp)
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
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.baseline_chevron_left),
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dialogs
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LocationOffDialog(show: Boolean, appTheme: AppTheme, onDismiss: () -> Unit) {
    val context = LocalContext.current
    if (!show) return
    val isDark = when (appTheme) {
        AppTheme.LIGHT -> false; AppTheme.DARK -> true; AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    Dialog(
        onDismissRequest = onDismiss,
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
                ) { onDismiss() }, contentAlignment = Alignment.Center
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
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "Location Is Off",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Android requires location to be turned on to detect your Wi-Fi network name. Please enable location to continue sharing.",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        thickness = 0.5.dp,
                        color = Color.Gray.copy(alpha = 0.2f)
                    )
                    TextButton(onClick = {
                        onDismiss()
                        (context as? AppControl)?.pendingLocationCheck = true
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                        })
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Settings", color = Color(0xFF2BAED5), fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun UnknownNetworkDialog(show: Boolean, ssid: String?, appTheme: AppTheme, onDismiss: () -> Unit) {
    val context = LocalContext.current
    if (!show || ssid == null) return
    val isDark = when (appTheme) {
        AppTheme.LIGHT -> false; AppTheme.DARK -> true; AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    Dialog(
        onDismissRequest = onDismiss,
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
                ) { onDismiss() }, contentAlignment = Alignment.Center
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
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "Unknown Network Detected",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "\"$ssid\" is an unknown network. Choose whether NetworkShare should share files on this network.",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            NetworkTrustManager.allow(
                                context,
                                ssid
                            ); WebDAVService.pendingTrustSsid.value =
                            null; NetworkTrustManager.restoreSharingNotification(context); onDismiss()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Allow",
                                color = Color(0xFF2BAED5),
                                fontSize = 16.sp
                            )
                        }
                        TextButton(onClick = {
                            NetworkTrustManager.allowOnce(ssid); WebDAVService.pendingTrustSsid.value =
                            null; NetworkTrustManager.restoreSharingNotification(context); onDismiss()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Allow Once",
                                color = Color(0xFF2BAED5),
                                fontSize = 16.sp
                            )
                        }
                        TextButton(onClick = {
                            NetworkTrustManager.block(
                                context,
                                ssid
                            ); WebDAVService.pendingTrustSsid.value =
                            null; NetworkTrustManager.restoreSharingNotification(context); onDismiss()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Block",
                                color = Color(0xFF2BAED5),
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NoNetworkDialog(show: Boolean, appTheme: AppTheme, onDismiss: () -> Unit) {
    val context = LocalContext.current
    if (!show) return
    val isDark = when (appTheme) {
        AppTheme.LIGHT -> false; AppTheme.DARK -> true; AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    Dialog(
        onDismissRequest = onDismiss,
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
                ) { onDismiss() }, contentAlignment = Alignment.Center
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
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.Start) {
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
                        TextButton(onClick = {
                            onDismiss(); WebDAVService.isWaitingForHotspot = true
                            val i =
                                Intent("android.settings.TETHER_SETTINGS").apply { addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY) }
                            try {
                                context.startActivity(i)
                            } catch (_: Exception) {
                                context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                                    addFlags(
                                        Intent.FLAG_ACTIVITY_NO_HISTORY
                                    )
                                })
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Text(
                                "Hotspot",
                                color = Color(0xFF2BAED5),
                                fontSize = 16.sp
                            )
                        }
                        VerticalDivider(
                            modifier = Modifier
                                .height(20.dp)
                                .width(1.dp),
                            color = Color.Gray.copy(alpha = 0.2f)
                        )
                        TextButton(onClick = {
                            onDismiss()
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
                                context.startActivity(Intent("android.settings.panel.action.WIFI"))
                            else
                                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        }, modifier = Modifier.weight(1f)) {
                            Text(
                                "Wi-Fi",
                                color = Color(0xFF2BAED5),
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationPermissionDialog(show: Boolean, appTheme: AppTheme, onDismiss: () -> Unit) {
    val context = LocalContext.current
    if (!show) return
    val isDark = when (appTheme) {
        AppTheme.LIGHT -> false; AppTheme.DARK -> true; AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    Dialog(
        onDismissRequest = onDismiss,
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
                ) { onDismiss() }, contentAlignment = Alignment.Center
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
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.Start) {
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
                        modifier = Modifier.padding(horizontal = 8.dp),
                        thickness = 0.5.dp,
                        color = Color.Gray.copy(alpha = 0.2f)
                    )
                    TextButton(onClick = {
                        onDismiss()
                        (context as? AppControl)?.pendingNotificationCheck = true
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        })
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Settings", color = Color(0xFF2BAED5), fontSize = 18.sp)
                    }
                }
            }
        }
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