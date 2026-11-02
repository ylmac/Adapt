package com.ylmac.adapt.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ylmac.adapt.compose.theme.MonetScheme
import kotlin.math.roundToInt

/**
 * MD3 Slider：触摸高 44、轨高 16、拇指 4×44。
 */
@Composable
fun Md3Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    mod: Modifier = Modifier,
    stepSize: Float = 0f,
) {
    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(1f) }
    val min = valueRange.start
    val max = valueRange.endInclusive.coerceAtLeast(min + 0.0001f)
    val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)

    fun setFromX(x: Float) {
        val pad = with(density) { 8.dp.toPx() }
        val avail = (widthPx - pad * 2).coerceAtLeast(1f)
        var pos = ((x - pad) / avail).coerceIn(0f, 1f)
        var v = min + pos * (max - min)
        if (stepSize > 0f) {
            val steps = ((v - min) / stepSize).roundToInt()
            v = (min + steps * stepSize).coerceIn(min, max)
        }
        onValueChange(v)
    }

    Box(
        mod
            .fillMaxWidth()
            .height(44.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(min, max, stepSize) {
                detectTapGestures { offset -> setFromX(offset.x) }
            }
            .pointerInput(min, max, stepSize) {
                detectDragGestures { change, _ ->
                    change.consume()
                    setFromX(change.position.x)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MonetScheme.surfaceContainerHighest),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MonetScheme.primary),
            )
        }
        val thumbOffset = ((widthPx - with(density) { 4.dp.toPx() }) * frac).roundToInt()
        Box(
            Modifier
                .offset { IntOffset(thumbOffset, 0) }
                .width(4.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MonetScheme.primary),
        )
    }
}
