package com.ylmac.adapt.compose.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.AdaptFonts
import com.ylmac.adapt.compose.theme.MonetScheme
import kotlin.math.roundToInt

/**
 * MD3 SegmentedButton：高 40、轨 surfaceContainerHighest、
 * 选中 primary 滑块动画、outline 描边。
 */
@Composable
fun Md3SegmentedButton(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    mod: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val count = options.size.coerceAtLeast(1)
    val sel = selectedIndex.coerceIn(0, count - 1)
    BoxWithConstraints(
        mod
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(MonetScheme.surfaceContainerHighest)
            .border(1.dp, MonetScheme.outline, shape),
    ) {
        val density = LocalDensity.current
        val segW = maxWidth / count
        val targetX = with(density) { (segW * sel).toPx() }
        val animX by animateFloatAsState(
            targetValue = targetX,
            animationSpec = tween(150),
            label = "segX",
        )
        Box(
            Modifier
                .offset { IntOffset(animX.roundToInt(), 0) }
                .width(segW)
                .fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(MonetScheme.primary),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val selected = i == sel
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = { onSelect(i) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (selected) MonetScheme.onPrimary else MonetScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = AdaptFonts.PingFang,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun Md3SegmentedDarkMode(
    selected: Int,
    onSelect: (Int) -> Unit,
    mod: Modifier = Modifier,
) {
    Md3SegmentedButton(
        options = listOf("跟随系统", "浅色", "深色", "主题色"),
        selectedIndex = selected,
        onSelect = onSelect,
        mod = mod,
    )
}
