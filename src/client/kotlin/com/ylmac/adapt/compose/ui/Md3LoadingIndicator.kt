package com.ylmac.adapt.compose.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.ylmac.adapt.compose.theme.MonetScheme
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * lobeSequence [10,9,5,2,8,4,2]，填充 polar blob，3000ms 旋转 + morph。
 *
 * [running]=false 时不建 InfiniteTransition，避免挡 Compose idle skip。
 */
@Composable
fun Md3LoadingIndicator(
    mod: Modifier = Modifier,
    withContainer: Boolean = false,
    running: Boolean = true,
) {
    if (running) {
        Md3LoadingIndicatorAnimated(mod, withContainer)
    } else {
        Md3LoadingIndicatorStatic(mod, withContainer, rotation = 0f, morph = 0f)
    }
}

@Composable
private fun Md3LoadingIndicatorAnimated(mod: Modifier, withContainer: Boolean) {
    val spin = rememberInfiniteTransition(label = "loadSpin")
    val rotation by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rot",
    )
    val morph by spin.animateFloat(
        initialValue = 0f,
        targetValue = 7f,
        animationSpec = infiniteRepeatable(
            animation = tween(7 * 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "morph",
    )
    Md3LoadingIndicatorStatic(mod, withContainer, rotation, easeMorph(morph))
}

@Composable
private fun Md3LoadingIndicatorStatic(
    mod: Modifier,
    withContainer: Boolean,
    rotation: Float,
    morph: Float,
) {
    val lobes = rememberLobeSequence()
    val color = MonetScheme.primary
    val fillFrac = if (withContainer) 0.5f else 0.8f
    Box(
        mod
            .size(48.dp)
            .then(
                if (withContainer) {
                    Modifier
                        .clip(CircleShape)
                        .background(MonetScheme.surfaceContainerHighest)
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize(fillFrac)) {
            val maxR = size.minDimension * 0.5f
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val t = morph
            val idx1 = floor(t.toDouble()).toInt().mod(lobes.size)
            val idx2 = (idx1 + 1).mod(lobes.size)
            val factor = t - floor(t)
            val path = Path()
            val points = 100
            val step = (Math.PI * 2.0 / points).toFloat()
            rotate(rotation, pivot = androidx.compose.ui.geometry.Offset(cx, cy)) {
                for (i in 0..points) {
                    val angle = i * step
                    val r1 = blobRadius(lobes[idx1], angle, maxR)
                    val r2 = blobRadius(lobes[idx2], angle, maxR)
                    val r = r1 + (r2 - r1) * factor
                    val x = cx + cos(angle) * r
                    val y = cy + sin(angle) * r
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, color = color)
            }
        }
    }
}

private fun rememberLobeSequence(): IntArray = intArrayOf(10, 9, 5, 2, 8, 4, 2)

private fun blobRadius(lobes: Int, angle: Float, maxR: Float): Float {
    val baseR = maxR * 0.85f
    val varR = maxR * 0.15f
    return baseR + varR * sin(lobes * angle)
}

private fun easeMorph(raw: Float): Float {
    val seg = floor(raw.toDouble()).toInt()
    val frac = (raw - seg).coerceIn(0f, 1f)
    val hold = 800f / 1400f
    val eased = if (frac <= hold) {
        0f
    } else {
        val u = ((frac - hold) / (1f - hold)).coerceIn(0f, 1f)
        u * u * (3f - 2f * u)
    }
    return seg + eased
}
