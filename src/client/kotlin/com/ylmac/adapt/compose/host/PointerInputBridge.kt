package com.ylmac.adapt.compose.host

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.scene.ComposeScene

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

/**
 * 鼠标坐标 → Compose pointer。调用方负责换成 **scene 像素**
 *（GUI 逻辑坐标 × [AdaptComposeSession.pixelScale]）。
 */
object PointerInputBridge {
    fun send(
        scene: ComposeScene,
        type: PointerEventType,
        x: Double,
        y: Double,
        scrollX: Float = 0f,
        scrollY: Float = 0f,
        timeMillis: Long = System.currentTimeMillis(),
    ) {
        val pos = Offset(x.toFloat(), y.toFloat())
        if (type == PointerEventType.Scroll) {
            scene.sendPointerEvent(
                eventType = type,
                position = pos,
                scrollDelta = Offset(scrollX, scrollY),
                timeMillis = timeMillis,
            )
        } else {
            scene.sendPointerEvent(
                eventType = type,
                position = pos,
                timeMillis = timeMillis,
            )
        }
    }

    fun press(scene: ComposeScene, x: Double, y: Double) =
        send(scene, PointerEventType.Press, x, y)

    fun release(scene: ComposeScene, x: Double, y: Double) =
        send(scene, PointerEventType.Release, x, y)

    fun move(scene: ComposeScene, x: Double, y: Double) =
        send(scene, PointerEventType.Move, x, y)

    fun scroll(scene: ComposeScene, x: Double, y: Double, h: Double, v: Double) =
        // 触控板会连发很小或很大的增量。按比例换算后再封顶，避免一滑到底。
        send(
            scene,
            PointerEventType.Scroll,
            x,
            y,
            wheelPx(h),
            -wheelPx(v),
        )

    // --- ImageComposeScene raster fallback ---

    fun send(
        scene: ImageComposeScene,
        type: PointerEventType,
        x: Double,
        y: Double,
        scrollX: Float = 0f,
        scrollY: Float = 0f,
        timeMillis: Long = System.currentTimeMillis(),
    ) {
        val pos = Offset(x.toFloat(), y.toFloat())
        if (type == PointerEventType.Scroll) {
            scene.sendPointerEvent(
                eventType = type,
                position = pos,
                scrollDelta = Offset(scrollX, scrollY),
                timeMillis = timeMillis,
            )
        } else {
            scene.sendPointerEvent(
                eventType = type,
                position = pos,
                timeMillis = timeMillis,
            )
        }
    }

    fun press(scene: ImageComposeScene, x: Double, y: Double) =
        send(scene, PointerEventType.Press, x, y)

    fun release(scene: ImageComposeScene, x: Double, y: Double) =
        send(scene, PointerEventType.Release, x, y)

    fun move(scene: ImageComposeScene, x: Double, y: Double) =
        send(scene, PointerEventType.Move, x, y)

    fun scroll(scene: ImageComposeScene, x: Double, y: Double, h: Double, v: Double) =
        send(
            scene,
            PointerEventType.Scroll,
            x,
            y,
            wheelPx(h),
            -wheelPx(v),
        )

    /**
 * 鼠标一格大约 20 像素。不足一格的是触控板，按比例走；单次最多 32，
 * 免得一串大增量把列表甩出去。Y 的方向在调用处取反。
 */
    private fun wheelPx(raw: Double): Float {
        if (raw == 0.0) return 0f
        val magnitude = kotlin.math.abs(raw)
        val px = if (magnitude < 1.0) {
            raw * 12.0
        } else {
            kotlin.math.sign(raw) * (20.0 + (magnitude - 1.0) * 3.0).coerceAtMost(32.0)
        }
        return px.toFloat()
    }
}
