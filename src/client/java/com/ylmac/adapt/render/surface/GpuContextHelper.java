package com.ylmac.adapt.render.surface;

import com.mojang.blaze3d.systems.RenderSystem;
import com.ylmac.adapt.AdaptClient;
import io.github.humbleui.skija.DirectContext;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Skija DirectContext bootstrap.
 * <p>
 * 26.3：Vulkan 句柄一律从原版 {@code FrontendGpuDevice.backend}
 * （{@code VulkanDevice}）反射偷出来 — 不再走 Sodium 的
 * {@code sodium$getBackend}。原版没开 Vulkan 时才回退 GL。
 */
public final class GpuContextHelper {

    public static String backendName = "Unknown";
    public static String deviceName = "Unknown";
    public static String gpuStatus = "not_attempted";

    private static DirectContext cachedContext = null;
    private static boolean isGL = false;

    private GpuContextHelper() {}

    public static DirectContext getOrCreateContext() {
        if (cachedContext != null) return cachedContext;
        cachedContext = tryCreateVulkanContext();
        if (cachedContext != null) {
            isGL = false;
            return cachedContext;
        }
        cachedContext = tryCreateGLContext();
        if (cachedContext != null) {
            isGL = true;
        }
        return cachedContext;
    }

    public static boolean isGL() { return isGL; }

    public static DirectContext tryCreateGLContext() {
        if (cachedContext != null && isGL) return cachedContext;
        gpuStatus = "attempting_gl";
        long t0 = System.nanoTime();
        try {
            if (!RenderSystem.isOnRenderThread()) {
                gpuStatus = "fail_gl_not_render_thread";
                return null;
            }
            GLCapabilities caps = GL.getCapabilities();
            if (caps == null) {
                gpuStatus = "fail_gl_no_caps";
                return null;
            }
            DirectContext ctx = DirectContext.makeGL();
            if (ctx != null) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                AdaptClient.LOGGER.info("[GpuContext] GL DirectContext created in {}ms", ms);
                gpuStatus = "ok_gl";
                backendName = "OpenGL";
                deviceName = GL11.glGetString(GL11.GL_RENDERER);
                cachedContext = ctx;
                isGL = true;
            } else {
                gpuStatus = "fail_gl_makeGL_null";
            }
            return ctx;
        } catch (Throwable e) {
            AdaptClient.LOGGER.warn("[GpuContext] GL failed: {}", e.toString());
            gpuStatus = "fail_gl_" + e.getClass().getSimpleName();
            return null;
        }
    }

    public static DirectContext tryCreateVulkanContext() {
        if (cachedContext != null && !isGL) return cachedContext;
        gpuStatus = "attempting_vulkan";
        long t0 = System.nanoTime();

        try {
            VulkanDeviceHandles handles = VulkanDeviceHandles.stealOrNull();
            if (handles == null) {
                gpuStatus = "fail_vk_no_vanilla_backend";
                return null;
            }
            backendName = handles.backendName;
            deviceName = handles.deviceName;

            DirectContext ctx = DirectContext.makeVulkan(
                    handles.instance,
                    handles.physicalDevice,
                    handles.device,
                    handles.queue,
                    handles.queueFamilyIndex,
                    handles.instanceProcAddr,
                    handles.deviceProcAddr,
                    VulkanDeviceHandles.VK_API_VERSION_1_2);

            if (ctx != null) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                AdaptClient.LOGGER.info(
                        "[GpuContext] Vulkan DirectContext from vanilla FrontendGpuDevice in {}ms ({} / {})",
                        ms, backendName, deviceName);
                gpuStatus = "ok_vulkan_vanilla";
                cachedContext = ctx;
                isGL = false;
            } else {
                gpuStatus = "fail_vk_makeVulkan_null";
            }
            return ctx;
        } catch (Throwable e) {
            AdaptClient.LOGGER.warn("[GpuContext] Vulkan FAILED: {}", e.toString());
            gpuStatus = "fail_vk_" + e.getClass().getSimpleName();
            return null;
        }
    }
}
