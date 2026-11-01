package com.ylmac.adapt.compose.skia

import com.mojang.blaze3d.platform.NativeImage
import com.ylmac.adapt.AdaptClient
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.lwjgl.system.MemoryUtil
import java.util.concurrent.atomic.AtomicLong

/**
 * 把 Skiko [Image] 上传为 MC [DynamicTexture]，并提供 blit 用的 Identifier。
 *
 * 像素路径：读成 RGBA_8888 UNPREMUL → memcpy 进 [NativeImage] → upload。
 */
class ComposeTexturePresent(private val name: String) : AutoCloseable {
    private var nativeImage: NativeImage? = null
    private var texture: DynamicTexture? = null
    private var textureId: Identifier? = null
    private var texW = 0
    private var texH = 0

    fun textureId(): Identifier? = textureId

    fun width(): Int = texW
    fun height(): Int = texH

    fun blitPipeline() = RenderPipelines.GUI_TEXTURED

    fun ensureSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (nativeImage != null && texW == width && texH == height) return
        closeTextureOnly()
        texW = width
        texH = height
        val img = NativeImage(width, height, true)
        nativeImage = img
        val tex = DynamicTexture({ "adapt-compose-$name" }, img)
        texture = tex
        val id = Identifier.parse("adaptclient:compose_${name}_${COUNTER.incrementAndGet()}")
        textureId = id
        Minecraft.getInstance().textureManager.register(id, tex)
    }

    fun upload(image: Image) {
        val w = image.width
        val h = image.height
        ensureSize(w, h)
        val target = nativeImage ?: return

        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val bitmap = Bitmap()
        try {
            bitmap.allocPixels(info)
            if (!image.readPixels(bitmap)) {
                AdaptClient.LOGGER.warn("[ComposeTexturePresent] readPixels failed")
                return
            }
            val pixmap = bitmap.peekPixels() ?: return
            val nbytes = (w * h * 4).toLong()
            if (pixmap.computeByteSize() < nbytes) {
                AdaptClient.LOGGER.warn(
                    "[ComposeTexturePresent] pixmap too small: {} < {}",
                    pixmap.computeByteSize(),
                    nbytes,
                )
                return
            }
            MemoryUtil.memCopy(pixmap.addr, target.pointer, nbytes)
        } finally {
            bitmap.close()
        }
        texture?.upload()
    }

    private fun closeTextureOnly() {
        val id = textureId
        val tex = texture
        if (id != null) {
            try {
                Minecraft.getInstance().textureManager.release(id)
            } catch (_: Exception) {
                try {
                    tex?.close()
                } catch (_: Exception) {
                }
            }
        } else {
            try {
                tex?.close()
            } catch (_: Exception) {
            }
        }
        try {
            nativeImage?.close()
        } catch (_: Exception) {
        }
        nativeImage = null
        texture = null
        textureId = null
        texW = 0
        texH = 0
    }

    override fun close() {
        closeTextureOnly()
    }

    companion object {
        private val COUNTER = AtomicLong()
    }
}
