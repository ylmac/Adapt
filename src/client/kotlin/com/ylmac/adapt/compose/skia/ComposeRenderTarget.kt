package com.ylmac.adapt.compose.skia

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.ylmac.adapt.AdaptClient
import org.jetbrains.skia.Image

@OptIn(ExperimentalComposeUiApi::class)

/**
 * Compose 帧输出目标：持有 MC 纹理上传器，把 [ImageComposeScene.render] 的
 * Skiko [Image] 变成可 blit 的 Identifier。
 *
 * 尺寸 = GUI 逻辑像素（与 Screen.width/height 一致）；density 单独交给 scene。
 */
class ComposeRenderTarget(name: String) : AutoCloseable {
    private val present = ComposeTexturePresent(name)
    private var logicalW = 0
    private var logicalH = 0

    fun logicalWidth(): Int = logicalW
    fun logicalHeight(): Int = logicalH
    fun texturePresent(): ComposeTexturePresent = present

    fun resize(width: Int, height: Int) {
        if (width == logicalW && height == logicalH) return
        logicalW = width.coerceAtLeast(1)
        logicalH = height.coerceAtLeast(1)
        present.ensureSize(logicalW, logicalH)
        AdaptClient.LOGGER.debug(
            "[ComposeRenderTarget] resize {}x{}",
            logicalW,
            logicalH,
        )
    }

    /** 将 scene 渲染结果上传到 MC 纹理。 */
    fun presentFrame(scene: ImageComposeScene, nanoTime: Long): Boolean {
        var image: Image? = null
        return try {
            image = scene.render(nanoTime)
            present.upload(image)
            true
        } catch (t: Throwable) {
            AdaptClient.LOGGER.warn("[ComposeRenderTarget] present failed: {}", t.toString())
            false
        } finally {
            image?.close()
        }
    }

    fun densityForGuiScale(guiScale: Double): Density {
        val d = guiScale.toFloat().coerceAtLeast(0.5f)
        return Density(d, 1f)
    }

    override fun close() {
        present.close()
    }
}
