package com.danieleze.networkshare.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieleze.networkshare.FolderItem
import com.danieleze.networkshare.R
import com.danieleze.networkshare.ScrollableListWithDraggableScrollbar
import com.danieleze.networkshare.ui.theme.LocalDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun FilePickerSection(
    onBack: () -> Unit,
    onClearItems: () -> Unit,
    currentPath: MutableState<File?>,
    items: List<FolderItem>,
    isLoading: Boolean,
    availableStorages: List<File>,
    selectedPaths: List<String>,
    onFolderScanRequest: (File?) -> Unit,
    onRefreshServer: () -> Unit,
    onToggleSelection: (String) -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var currentPath by currentPath
    var isExiting by remember { mutableStateOf(false) }

    val handleBack = {
        scope.launch {
            if (currentPath == null) {
                onRefreshServer()
                onBack()
            } else {
                isExiting = true; delay(220); isExiting = false
                currentPath =
                    if (availableStorages.any { it.absolutePath == currentPath?.absolutePath }) null
                    else currentPath?.parentFile
                onClearItems()
            }
        }
    }

    val pathParts = remember(currentPath) {
        val parts = mutableListOf<File>()
        var temp = currentPath
        while (temp != null) { parts.add(0, temp); temp = temp.parentFile }
        parts
    }

    val itemsToShow = items.sortedBy { it.name.lowercase() }

    LaunchedEffect(currentPath) {
        onFolderScanRequest(currentPath)
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
                                            null; onClearItems()
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
                                            if (file.absolutePath != currentPath?.absolutePath) {
                                                scope.launch {
                                                    isExiting = true; delay(220); isExiting =
                                                    false; currentPath =
                                                    file; onClearItems()
                                                }
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
                    ScrollableListWithDraggableScrollbar(
                        state = listState,
                        coroutineScope = scope,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(alpha = if (isLoading) 0f else 1f),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp)
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
                                isExiting = isExiting,
                                isLast = isLast,
                                isChecked = selectedPaths.contains(folderItem.file.absolutePath),
                                isInherited = selectedPaths.any { shared ->
                                    folderItem.file.absolutePath.startsWith("$shared/") &&
                                            folderItem.file.absolutePath != shared
                                },
                                isPartiallyChecked = !selectedPaths.contains(folderItem.file.absolutePath) &&
                                        !selectedPaths.any { shared ->
                                            folderItem.file.absolutePath.startsWith("$shared/") &&
                                                    folderItem.file.absolutePath != shared
                                        } &&
                                        selectedPaths.any { shared ->
                                            shared.startsWith("${folderItem.file.absolutePath}/") &&
                                                    shared != folderItem.file.absolutePath
                                        },
                                onClick = {
                                    if (folderItem.hasSubFolders || isStorageRoot) {
                                        scope.launch {
                                            isExiting = true; delay(220); isExiting = false
                                            currentPath = folderItem.file
                                        }
                                    } else {
                                        Toast.makeText(context, "No sub-folders inside", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onToggleSelection = { onToggleSelection(folderItem.file.absolutePath) }
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
    isLast: Boolean = false,
    isExiting: Boolean = false,
    index: Int = 0,
    isChecked: Boolean,
    isInherited: Boolean,
    isPartiallyChecked: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    val isDark = LocalDarkTheme.current

    val inheritedColor = if (isDark) Color.Gray else Color.LightGray
    val inheritedCheck = if (isDark) Color.LightGray else Color.White


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
                        onToggleSelection()
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