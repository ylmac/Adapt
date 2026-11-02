package com.ylmac.adapt.compose.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs

/**
 * CMP 1.13 / Skiko [org.jetbrains.skiko.node.RenderNode]。
 *
 * 这一层的绘制录进显示列表。滚动只重放，不再把文字、裁剪和封面重走一遍。
 * 内容真变了（开关、封面解码完、颜色）Compose 会丢掉列表重录。
 * 不降分辨率，也不把动画停在第 0 帧。
 */
fun Modifier.retainedLayer(): Modifier = this.graphicsLayer()

/**
 * 比默认更“收”的 fling：摩擦略高，减少 →Compose 后拖尾过长的怪异感。
 */
@Composable
fun rememberAdaptFlingBehavior(): FlingBehavior {
    val fallback = ScrollableDefaults.flingBehavior()
    return remember(fallback) {
        object : FlingBehavior {
            private val decay = exponentialDecay<Float>(frictionMultiplier = 1.55f)

            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                return try {
                    var velocityLeft = initialVelocity
                    var lastValue = 0f
                    AnimationState(
                        initialValue = 0f,
                        initialVelocity = initialVelocity,
                    ).animateDecay(decay) {
                        val delta = value - lastValue
                        lastValue = value
                        val consumed = scrollBy(delta)
                        if (abs(delta - consumed) > 0.5f) {
                            cancelAnimation()
                        }
                        velocityLeft = this.velocity
                    }
                    velocityLeft
                } catch (_: Throwable) {
                    with(fallback) { performFling(initialVelocity) }
                }
            }
        }
    }
}
