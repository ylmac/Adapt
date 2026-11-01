package com.ylmac.adapt.compose.skia

import com.ylmac.adapt.AdaptClient
import com.ylmac.adapt.render.VulkanDeviceHandles
import org.jetbrains.skia.DirectContext

/**
 * Skiko（`org.jetbrains.skia`）Vulkan DirectContext。
 *
 * 与 QML 的 HumbleUI DirectContext **不是同一个对象**，但共用
 * [VulkanDeviceHandles] 偷到的原版 Vulkan 队列句柄。
 */
object ComposeSkiaContext {
    @Volatile
    private var gpu: DirectContext? = null

    @Volatile
    private var pathNote: String = "raster (not_attempted)"

    @Volatile
    private var attempted: Boolean = false

    fun gpuOrNull(): DirectContext? = gpu

    fun pathLabel(): String = pathNote

    fun isGpu(): Boolean = gpu != null

    /** 幂等：成功则 [pathLabel] = `vulkan`，失败保留 raster 说明。 */
    @JvmStatic
    @Synchronized
    fun tryInit(): DirectContext? {
        if (attempted) return gpu
        attempted = true
        val t0 = System.nanoTime()
        return try {
            val handles = VulkanDeviceHandles.stealOrNull()
            if (handles == null) {
                pathNote = "raster (fallback: no_vk_handles)"
                AdaptClient.LOGGER.warn("[ComposeSkia] Vulkan handles unavailable")
                return null
            }
            val ctx = DirectContext.makeVulkan(
                handles.instance,
                handles.physicalDevice,
                handles.device,
                handles.queue,
                handles.queueFamilyIndex,
                handles.instanceProcAddr,
                handles.deviceProcAddr,
                VulkanDeviceHandles.VK_API_VERSION_1_2,
                null,
            )
            if (ctx == null) {
                pathNote = "raster (fallback: makeVulkan_null)"
                AdaptClient.LOGGER.warn("[ComposeSkia] DirectContext.makeVulkan returned null")
                return null
            }
            gpu = ctx
            pathNote = "vulkan"
            val ms = (System.nanoTime() - t0) / 1_000_000
            AdaptClient.LOGGER.info(
                "[ComposeSkia] Vulkan DirectContext in {}ms ({} / {})",
                ms,
                handles.backendName,
                handles.deviceName,
            )
            ctx
        } catch (t: Throwable) {
            pathNote = "raster (fallback: ${t.javaClass.simpleName})"
            AdaptClient.LOGGER.warn("[ComposeSkia] Vulkan init failed: {}", t.toString())
            null
        }
    }

    fun markGpuUnavailable(reason: String) {
        if (gpu != null) return
        // tryInit 失败时已写过具体 fallback；勿覆盖
        if (attempted && pathNote.startsWith("raster (fallback")) return
        pathNote = "raster ($reason)"
        AdaptClient.LOGGER.info(
            "[ComposeSkia] GPU not used ({}); path={}",
            reason,
            pathNote,
        )
    }
}
