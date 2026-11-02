package com.ylmac.adapt.compose.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ylmac.adapt.compose.theme.MonetScheme

/** MD3 Switch，轨道 52×32。 */
@Composable
fun Md3Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    mod: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val thumbSize by animateDpAsState(
        targetValue = if (checked) 24.dp else 16.dp,
        animationSpec = tween(150),
        label = "thumbSize",
    )
    val thumbX by animateDpAsState(
        targetValue = if (checked) (52 - 24 - 4).dp else 8.dp,
        animationSpec = tween(150),
        label = "thumbX",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(150),
        label = "checkScale",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(100),
        label = "pressScale",
    )
    val trackTarget = when {
        !enabled -> MonetScheme.onSurface.copy(alpha = 0.12f)
        checked -> MonetScheme.primary
        else -> MonetScheme.surfaceContainerHighest
    }
    val thumbTarget = when {
        !enabled -> if (checked) MonetScheme.surface else MonetScheme.onSurface.copy(alpha = 0.38f)
        checked -> MonetScheme.onPrimary
        else -> MonetScheme.outline
    }
    val trackColor by animateColorAsState(trackTarget, tween(150), label = "track")
    val thumbColor by animateColorAsState(thumbTarget, tween(150), label = "thumb")
    val outlineColor by animateColorAsState(
        if (!checked && enabled) MonetScheme.outline else MonetScheme.outline.copy(alpha = 0f),
        tween(150),
        label = "outline",
    )

    Box(
        mod
            .size(width = 52.dp, height = 32.dp)
            .scale(pressScale)
            .clip(RoundedCornerShape(16.dp))
            .background(trackColor)
            .border(2.dp, outlineColor, RoundedCornerShape(16.dp))
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = interaction,
                onClick = { onCheckedChange(!checked) },
            ),
    ) {
        Box(
            Modifier
                .offset(x = thumbX)
                .size(thumbSize)
                .align(Alignment.CenterStart)
                .clip(CircleShape)
                .background(thumbColor),
            contentAlignment = Alignment.Center,
        ) {
            Md3Icon(
                name = "check",
                size = 16.sp,
                color = MonetScheme.primary,
                mod = Modifier.scale(checkScale),
            )
        }
    }
}
