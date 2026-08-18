package com.danieleze.networkshare.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun ScrollableListWithDraggableScrollbar(
    state: LazyListState,
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier,
    color: Color = Color.DarkGray.copy(alpha = 0.6f),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val touchStripWidth = 32.dp

    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
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
                },
            contentPadding = contentPadding,
            content = content
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(touchStripWidth)
                .fillMaxHeight()
                .pointerInput(state) {
                    detectDragGestures(
                        onDragStart = { isPressed = true },
                        onDragEnd = { isPressed = false },
                        onDragCancel = { isPressed = false },
                        onDrag = { change, _ ->
                            change.consume()
                            val totalItems = state.layoutInfo.totalItemsCount
                            if (totalItems > 0) {
                                val targetIndex =
                                    ((change.position.y / size.height) * totalItems).toInt()
                                coroutineScope.launch {
                                    state.scrollToItem(
                                        targetIndex.coerceIn(0, totalItems - 1)
                                    )
                                }
                            }
                        }
                    )
                }
        )
    }
}
