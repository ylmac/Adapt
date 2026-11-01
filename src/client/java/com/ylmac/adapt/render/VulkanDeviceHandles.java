package com.ylmac.adapt.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * 从原版 {@code FrontendGpuDevice.backend}（{@code VulkanDevice}）偷出 Vulkan 句柄，
 * 供 HumbleUI / Skiko 各自 {@code DirectContext.makeVulkan} 共用，避免反射漂移。
 */
public final class VulkanDeviceHandles {

    public static final int VK_API_VERSION_1_2 = 0x00402000;

    public final long instance;
    public final long physicalDevice;
    public final long device;
    public final long queue;
    public final int queueFamilyIndex;
    public final long instanceProcAddr;
    public final long deviceProcAddr;
    public final String backendName;
    public final String deviceName;

    private VulkanDeviceHandles(
            long instance,
            long physicalDevice,
            long device,
            long queue,
            int queueFamilyIndex,
            long instanceProcAddr,
            long deviceProcAddr,
            String backendName,
            String deviceName
    ) {
        this.instance = instance;
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.queue = queue;
        this.queueFamilyIndex = queueFamilyIndex;
        this.instanceProcAddr = instanceProcAddr;
        this.deviceProcAddr = deviceProcAddr;
        this.backendName = backendName;
        this.deviceName = deviceName;
    }

    /**
     * @return 句柄，或 {@code null}（非 Vulkan / 反射失败）
     */
    public static VulkanDeviceHandles stealOrNull() {
        try {
            Object gpuDevice = RenderSystem.getDevice();
            if (gpuDevice == null) {
                Field deviceField = RenderSystem.class.getDeclaredField("DEVICE");
                deviceField.setAccessible(true);
                gpuDevice = deviceField.get(null);
            }
            if (gpuDevice == null) return null;

            Object vulkanDevice = stealVanillaVulkanDevice(gpuDevice);
            if (vulkanDevice == null) return null;
            Class<?> vdClass = vulkanDevice.getClass();
            if (!vdClass.getName().endsWith("VulkanDevice")
                    && !vdClass.getSimpleName().equals("VulkanDevice")) {
                return null;
            }

            Object vulkanInstance = invoke(vulkanDevice, "instance");
            if (vulkanInstance == null) return null;
            Object vkInstanceObj = invoke(vulkanInstance, "vkInstance");
            if (!(vkInstanceObj instanceof VkInstance)) return null;
            long instanceHandle = ((VkInstance) vkInstanceObj).address();

            Object vkDeviceObj = invoke(vulkanDevice, "vkDevice");
            if (!(vkDeviceObj instanceof VkDevice)) return null;
            VkDevice vkDevice = (VkDevice) vkDeviceObj;
            long deviceHandle = vkDevice.address();

            VkPhysicalDevice physical = vkDevice.getPhysicalDevice();
            long physicalHandle = physical != null ? physical.address() : 0L;
            if (physicalHandle == 0) return null;

            Object graphicsQueue = invoke(vulkanDevice, "graphicsQueue");
            if (graphicsQueue == null) return null;
            Object vkQueueObj = invoke(graphicsQueue, "vkQueue");
            if (!(vkQueueObj instanceof VkQueue)) {
                vkQueueObj = getField(graphicsQueue, "vkQueue");
            }
            if (!(vkQueueObj instanceof VkQueue)) return null;
            long queueHandle = ((VkQueue) vkQueueObj).address();
            int queueFamilyIndex = extractQueueFamilyIndex(graphicsQueue);

            String backendName = "Vulkan";
            String deviceName = "GPU";
            try {
                Object info = invoke(vulkanDevice, "getDeviceInfo");
                if (info != null) {
                    Object n = invoke(info, "name");
                    Object b = invoke(info, "backendName");
                    if (n instanceof String s && !s.isEmpty()) deviceName = s;
                    if (b instanceof String s && !s.isEmpty()) backendName = s;
                }
            } catch (Throwable ignored) {
            }

            long instanceProcAddr = 0;
            long deviceProcAddr = 0;
            FunctionProvider fp = null;
            try {
                fp = VK.getFunctionProvider();
            } catch (Throwable ignored) {
            }
            if (fp != null) {
                try {
                    instanceProcAddr = fp.getFunctionAddress("vkGetInstanceProcAddr");
                } catch (Throwable ignored) {
                }
                try {
                    deviceProcAddr = fp.getFunctionAddress("vkGetDeviceProcAddr");
                } catch (Throwable ignored) {
                }
            }
            if (instanceProcAddr == 0 || deviceProcAddr == 0) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    if (instanceProcAddr == 0) {
                        ByteBuffer name = stack.ASCII("vkGetInstanceProcAddr");
                        instanceProcAddr = VK10.nvkGetInstanceProcAddr(
                                instanceHandle, MemoryUtil.memAddress(name));
                    }
                    if (deviceProcAddr == 0) {
                        ByteBuffer name = stack.ASCII("vkGetDeviceProcAddr");
                        deviceProcAddr = VK10.nvkGetInstanceProcAddr(
                                instanceHandle, MemoryUtil.memAddress(name));
                    }
                }
            }
            if (instanceProcAddr == 0 || deviceProcAddr == 0) return null;

            return new VulkanDeviceHandles(
                    instanceHandle,
                    physicalHandle,
                    deviceHandle,
                    queueHandle,
                    queueFamilyIndex,
                    instanceProcAddr,
                    deviceProcAddr,
                    backendName,
                    deviceName
            );
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object stealVanillaVulkanDevice(Object gpuDevice) {
        Object backend = getField(gpuDevice, "backend");
        if (backend != null) return backend;
        Class<?> c = gpuDevice.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField("backend");
                f.setAccessible(true);
                Object v = f.get(gpuDevice);
                if (v != null) return v;
            } catch (ReflectiveOperationException ignored) {
            }
            c = c.getSuperclass();
        }
        Object viaGetter = invoke(gpuDevice, "backend");
        if (viaGetter != null) return viaGetter;
        return invoke(gpuDevice, "getBackend");
    }

    private static int extractQueueFamilyIndex(Object queue) {
        Object via = invoke(queue, "queueFamilyIndex");
        if (via instanceof Integer i) return i;
        Object field = getField(queue, "queueFamilyIndex");
        if (field instanceof Integer i) return i;
        return 0;
    }

    private static Object invoke(Object obj, String name) {
        if (obj == null) return null;
        Method m = findMethod(obj.getClass(), name);
        if (m == null) return null;
        try {
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getField(Object obj, String name) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (ReflectiveOperationException ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name) {
        try {
            return clazz.getMethod(name);
        } catch (NoSuchMethodException ignored) {
        }
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
