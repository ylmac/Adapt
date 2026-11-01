package com.ylmac.adapt.compose.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.AdaptFonts
import com.ylmac.adapt.compose.theme.MonetScheme
import kotlinx.coroutines.launch

/**
 * 列表旁边的拉条。平时 8dp，悬停或拖动时展开。拖动时在拇指左边标出当前位置的字。
 * 内容没有超出视口时不画。
 */
@Composable
fun Md3ScrollRail(
    state: ScrollState,
    modifier: Modifier = Modifier,
    labelAt: (Float) -> String = { "" },
) {
    if (state.maxValue <= 0) return
    var railPx by remember { mutableFloatStateOf(1f) }
    val progress = (state.value / state.maxValue.toFloat()).coerceIn(0f, 1f)
    val thumb = (railPx / (railPx + state.maxValue)).coerceIn(0.12f, 1f)
    val scope = rememberCoroutineScope()
    Rail(
        progress = progress,
        thumbFraction = thumb,
        label = labelAt(progress),
        modifier = modifier.onSizeChanged { railPx = it.height.toFloat().coerceAtLeast(1f) },
        onDrag = { dy, travel ->
            val delta = dy / travel * state.maxValue.toFloat().coerceAtLeast(1f)
            scope.launch { state.scroll { scrollBy(delta) } }
        },
    )
}

@Composable
fun Md3ScrollRail(
    state: LazyListState,
    modifier: Modifier = Modifier,
    labelAt: (Float) -> String = { "" },
) {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    if (info.totalItemsCount <= 0 || visible.isEmpty()) return
    val avg = visible.sumOf { it.size } / visible.size.toFloat()
    if (avg <= 0f) return
    val content = info.totalItemsCount * avg
    val view = info.viewportSize.height.toFloat().coerceAtLeast(1f)
    if (content <= view + 1f) return
    val range = (content - view).coerceAtLeast(1f)
    val first = visible.first()
    val scrolled = first.index * avg - first.offset
    val progress = (scrolled / range).coerceIn(0f, 1f)
    val thumb = (view / content).coerceIn(0.12f, 1f)
    val scope = rememberCoroutineScope()
    Rail(
        progress = progress,
        thumbFraction = thumb,
        label = labelAt(progress),
        modifier = modifier,
        onDrag = { dy, travel ->
            scope.launch { state.scroll { scrollBy(dy / travel * range) } }
        },
    )
}

@Composable
private fun Rail(
    progress: Float,
    thumbFraction: Float,
    label: String,
    modifier: Modifier,
    onDrag: (dy: Float, travel: Float) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (hovered || dragging) 24.dp else 8.dp, tween(200), label = "rail")
    var railPx by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    val thumbPx = (railPx * thumbFraction).coerceIn(48f, railPx.coerceAtLeast(48f))
    val travel = (railPx - thumbPx).coerceAtLeast(1f)
    val topPx = travel * progress
    val drag by rememberUpdatedState(onDrag)
    val travelNow by rememberUpdatedState(travel)
    Box(
        modifier
            .width(if (dragging && label.isNotEmpty()) 72.dp else 28.dp)
            .fillMaxHeight()
            .onSizeChanged { railPx = it.height.toFloat().coerceAtLeast(1f) }
            .hoverable(interaction)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { _, dy -> drag(dy, travelNow.coerceAtLeast(1f)) },
                )
            },
        contentAlignment = Alignment.TopEnd,
    ) {
        if (dragging && label.isNotEmpty()) {
            Box(
                Modifier
                    .offset(y = with(density) { (topPx + thumbPx / 2f).toDp() - 20.dp })
                    .padding(end = 34.dp)
                    .width(36.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MonetScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = MonetScheme.onSecondaryContainer,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = AdaptFonts.PingFang,
                )
            }
        }
        Box(
            Modifier
                .padding(end = 2.dp)
                .offset(y = with(density) { topPx.toDp() })
                .width(width)
                .height(with(density) { thumbPx.toDp() })
                .clip(RoundedCornerShape(6.dp))
                .background(MonetScheme.secondaryContainer),
        )
    }
}
