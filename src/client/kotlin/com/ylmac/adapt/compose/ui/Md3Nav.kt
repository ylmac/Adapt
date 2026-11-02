package com.ylmac.adapt.compose.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.AdaptFonts
import com.ylmac.adapt.compose.theme.MonetScheme

data class NavItem(val icon: String, val label: String)

/** MD3 NavigationRail：宽 80，胶囊 64×48，按下放大 1.14。 */
@Composable
fun Md3NavigationRail(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    mod: Modifier = Modifier,
    glassAlpha: Float = 0.62f,
) {
    Column(
        mod
            .width(80.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(MonetScheme.surfaceContainerHigh.copy(alpha = glassAlpha)),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items.forEachIndexed { i, item ->
            RailSlot(
                item = item,
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
            )
        }
    }
}

@Composable
private fun ColumnScope.RailSlot(
    item: NavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bounce = remember { Animatable(1f) }
    var hasAnimated by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        if (!hasAnimated) {
            hasAnimated = true
            return@LaunchedEffect
        }
        if (selected) {
            bounce.snapTo(1f)
            bounce.animateTo(1.14f, tween(110))
            bounce.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
        }
    }
    val pillAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(200),
        label = "pillA",
    )
    val pillScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.86f,
        animationSpec = tween(250),
        label = "pillS",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .scale(bounce.value)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 64.dp, height = 48.dp)
                .scale(pillScale)
                .clip(RoundedCornerShape(24.dp))
                .background(MonetScheme.primary.copy(alpha = pillAlpha)),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Md3Icon(
                name = item.icon,
                size = 22.sp,
                color = if (selected) MonetScheme.onPrimary else MonetScheme.onSurfaceVariant,
            )
            Text(
                text = item.label,
                color = if (selected) MonetScheme.onPrimary else MonetScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontFamily = AdaptFonts.PingFang,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 底部导航：高 76，pill 64×32。 */
@Composable
fun Md3BottomNav(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    mod: Modifier = Modifier,
) {
    Row(
        mod
            .fillMaxWidth()
            .height(76.dp)
            .background(MonetScheme.surfaceContainerHigh.copy(alpha = 0.75f))
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            val selected = i == selectedIndex
            Column(
                Modifier
                    .weight(1f)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { onSelect(i) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .width(64.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (selected) MonetScheme.secondaryContainer else Color.Transparent,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Md3Icon(
                        name = item.icon,
                        size = 22.sp,
                        color = if (selected) MonetScheme.onSecondaryContainer
                        else MonetScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = item.label,
                    color = if (selected) MonetScheme.onSurface else MonetScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = AdaptFonts.PingFang,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun Md3FilledIconButton(
    onClick: () -> Unit,
    icon: String,
    container: Color = MonetScheme.surfaceContainerLow,
    content: Color = MonetScheme.onSurface,
    mod: Modifier = Modifier,
) {
    Box(
        mod
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Md3Icon(name = icon, size = 22.sp, color = content)
    }
}

@Composable
fun Md3StandardIconButton(
    onClick: () -> Unit,
    icon: String,
    content: Color = MonetScheme.onSurface,
    mod: Modifier = Modifier,
) {
    Box(
        mod
            .size(40.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Md3Icon(name = icon, size = 22.sp, color = content)
    }
}
