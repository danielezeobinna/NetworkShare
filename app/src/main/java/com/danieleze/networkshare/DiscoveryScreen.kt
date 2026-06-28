package com.danieleze.networkshare

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieleze.networkshare.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                        val noPaths = FileManager.selectedPaths.isEmpty()
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