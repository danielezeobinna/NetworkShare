package com.danieleze.networkshare.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.danieleze.networkshare.WebDAVService
import com.danieleze.networkshare.MainActivity
import com.danieleze.networkshare.AppControlService
import com.danieleze.networkshare.NetworkManager
import com.danieleze.networkshare.ui.theme.AppTheme

@Composable
fun LocationOffDialog(
    show: Boolean,
    appTheme: AppTheme,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
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
                        onOpenSettings()
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
                            NetworkManager.allow(
                                context,
                                ssid
                            ); WebDAVService.pendingTrustSsid.value =
                            null; context.startService(Intent(context, WebDAVService::class.java).apply { action = "RESTORE_NOTIFICATION" }); onDismiss()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Allow",
                                color = Color(0xFF2BAED5),
                                fontSize = 16.sp
                            )
                        }
                        TextButton(onClick = {
                            NetworkManager.allowOnce(ssid); WebDAVService.pendingTrustSsid.value =
                            null; context.startService(Intent(context, WebDAVService::class.java).apply { action = "RESTORE_NOTIFICATION" }); onDismiss()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Allow Once",
                                color = Color(0xFF2BAED5),
                                fontSize = 16.sp
                            )
                        }
                        TextButton(onClick = {
                            NetworkManager.block(
                                context,
                                ssid
                            ); WebDAVService.pendingTrustSsid.value =
                            null; context.startService(Intent(context, WebDAVService::class.java).apply { action = "RESTORE_NOTIFICATION" }); onDismiss()
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
fun NotificationPermissionDialog(
    show: Boolean,
    appTheme: AppTheme,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
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
                        onOpenSettings()
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