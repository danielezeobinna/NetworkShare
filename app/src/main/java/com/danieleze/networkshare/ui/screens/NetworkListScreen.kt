package com.danieleze.networkshare.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieleze.networkshare.R
import com.danieleze.networkshare.draggableScrollbar
import kotlinx.coroutines.delay

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