package com.ylmac.adapt.compose.skia

import com.ylmac.adapt.AdaptClient
import com.ylmac.adapt.render.GpuSharedTexture
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import java.util.concurrent.atomic.AtomicLong

/**
 * Compose → 共享 [GpuSharedTexture] 的零拷贝 present。
 *
 * Skia GPU 表面固定预乘 alpha，blit 走 [RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA]
 *（与 [com.ylmac.adapt.render.MinecraftSurfaceBackend] 一致）。
 */
class ComposeGpuSurface(
    private val name: String,
    private val context: DirectContext,
) : AutoCloseable {
    private var shared: GpuSharedTexture? = null
    private var backendRT: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var textureId: Identifier? = null
    private var texW = 0
    private var texH = 0

    fun textureId(): Identifier? = textureId
    fun width(): Int = texW
    fun height(): Int = texH
    fun blitPipeline() = RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA

    fun canvas(): Canvas? = surface?.canvas

    fun ensureSize(width: Int, height: Int) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (surface != null && texW == w && texH == h) return
        rebuild(w, h)
    }

    fun flush(syncCpu: Boolean = false) {
        val surf = surface ?: return
        context.flushAndSubmit(surf, syncCpu)
    }

    private fun rebuild(w: Int, h: Int) {
        disposeSurfaceOnly()
        texW = w
        texH = h
        try {
            val tex = GpuSharedTexture(w, h)
            shared = tex
            val vkImage = tex.vkImage
            val rt = BackendRenderTarget.makeVulkan(
                w,
                h,
                vkImage,
                VK_IMAGE_TILING_OPTIMAL,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_FLAGS,
                1,
                1,
            )
            backendRT = rt
            val surf = Surface.makeFromBackendRenderTarget(
                context,
                rt,
                SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.RGBA_8888,
                ColorSpace.sRGB,
            ) ?: throw IllegalStateException("Surface.makeFromBackendRenderTarget returned null")
            surface = surf

            val id = Identifier.parse("adaptclient:compose_gpu_${name}_${COUNTER.incrementAndGet()}")
            textureId = id
            Minecraft.getInstance().textureManager.register(id, tex)
            AdaptClient.LOGGER.info(
                "[ComposeGpuSurface] Vulkan RT {}x{} id={}",
                w,
                h,
                id,
            )
        } catch (t: Throwable) {
            disposeSurfaceOnly()
            throw t
        }
    }

    private fun disposeSurfaceOnly() {
        val id = textureId
        if (id != null) {
            try {
                Minecraft.getInstance().textureManager.release(id)
            } catch (_: Exception) {
            }
        }
        try {
            surface?.close()
        } catch (_: Exception) {
        }
        try {
            backendRT?.close()
        } catch (_: Exception) {
        }
        try {
            shared?.close()
        } catch (_: Exception) {
        }
        surface = null
        backendRT = null
        shared = null
        textureId = null
        texW = 0
        texH = 0
    }

    override fun close() {
        disposeSurfaceOnly()
    }

    companion object {
        private val COUNTER = AtomicLong()

        private const val VK_FORMAT_R8G8B8A8_UNORM = 37
        private const val VK_IMAGE_TILING_OPTIMAL = 0
        private const val VK_IMAGE_LAYOUT_UNDEFINED = 0
        // COLOR_ATTACHMENT | TRANSFER_SRC | TRANSFER_DST | SAMPLED
        private const val VK_IMAGE_USAGE_FLAGS = 0x10 or 0x04 or 0x02 or 0x01
    }
}
