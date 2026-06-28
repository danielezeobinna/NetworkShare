package com.danieleze.networkshare

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.danieleze.networkshare.ui.theme.AppTheme
import com.danieleze.networkshare.ui.theme.NetworkShareTheme
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import kotlinx.coroutines.CoroutineScope
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