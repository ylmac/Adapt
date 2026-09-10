package com.ylmac.adapt.render;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.humbleui.skija.DirectContext;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import com.ylmac.adapt.QplayerModClient;

public final class GpuContextHelper {

    public static String backendName = "Unknown";
    public static String deviceName = "Unknown";
    public static String gpuStatus = "not_attempted";

    private static DirectContext cachedContext = null;
    private static boolean isGL = false;

    private GpuContextHelper() {}

    public static DirectContext getOrCreateContext() {
        if (cachedContext != null) return cachedContext;
        // 先尝试 Vulkan，再试 OpenGL
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
            // 确保当前线程有 GL 上下文
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
                QplayerModClient.LOGGER.info("[GpuContext] GL DirectContext created in {}ms", ms);
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
            QplayerModClient.LOGGER.warn("[GpuContext] GL failed: {}", e.toString());
            gpuStatus = "fail_gl_" + e.getClass().getSimpleName();
            return null;
        }
    }

    public static DirectContext tryCreateVulkanContext() {
        if (cachedContext != null && !isGL) return cachedContext;
        gpuStatus = "attempting_vulkan";
        long t0 = System.nanoTime();

        try {
            Field deviceField = RenderSystem.class.getDeclaredField("DEVICE");
            deviceField.setAccessible(true);
            Object gpuDevice = deviceField.get(null);
            if (gpuDevice == null) {
                gpuStatus = "fail_vk_device_null";
                return null;
            }

            Method getBackend = findMethod(gpuDevice.getClass(), "sodium$getBackend");
            if (getBackend == null) {
                gpuStatus = "fail_vk_no_sodium_backend";
                return null;
            }
            Object backend = getBackend.invoke(gpuDevice);
            if (backend == null) {
                gpuStatus = "fail_vk_backend_null";
                return null;
            }
            Class<?> bc = backend.getClass();

            Method instanceM = findMethod(bc, "instance");
            if (instanceM == null) {
                gpuStatus = "fail_vk_no_instance_method";
                return null;
            }
            Object vulkanInstance = instanceM.invoke(backend);
            if (vulkanInstance == null) {
                gpuStatus = "fail_vk_instance_null";
                return null;
            }

            Method vkInstanceM = findMethod(vulkanInstance.getClass(), "vkInstance");
            if (vkInstanceM == null) {
                gpuStatus = "fail_vk_no_vkInstance_method";
                return null;
            }
            Object vkInstanceObj = vkInstanceM.invoke(vulkanInstance);
            if (vkInstanceObj == null) {
                gpuStatus = "fail_vk_vkInstance_null";
                return null;
            }
            long instanceHandle = extractHandle(vkInstanceObj);
            if (instanceHandle == 0) {
                gpuStatus = "fail_vk_instance_handle";
                return null;
            }

            long physicalHandle = 0;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer pCount = stack.mallocInt(1);
                int err = VK10.vkEnumeratePhysicalDevices((org.lwjgl.vulkan.VkInstance) vkInstanceObj, pCount, null);
                if (err == VK10.VK_SUCCESS && pCount.get(0) > 0) {
                    int count = pCount.get(0);
                    PointerBuffer pDevices = stack.mallocPointer(count);
                    err = VK10.vkEnumeratePhysicalDevices((org.lwjgl.vulkan.VkInstance) vkInstanceObj, pCount, pDevices);
                    if (err == VK10.VK_SUCCESS && count > 0) {
                        physicalHandle = pDevices.get(0);
                    }
                }
            }
            if (physicalHandle == 0) {
                gpuStatus = "fail_vk_physical_device";
                return null;
            }

            Object vkDeviceObj = null;
            Method vkDeviceM = findMethod(bc, "vkDevice");
            if (vkDeviceM != null) vkDeviceObj = vkDeviceM.invoke(backend);
            if (vkDeviceObj == null) {
                try {
                    Field f = bc.getDeclaredField("vkDevice");
                    f.setAccessible(true);
                    vkDeviceObj = f.get(backend);
                } catch (NoSuchFieldException ignored) {}
            }
            if (vkDeviceObj == null) {
                gpuStatus = "fail_vk_no_vkDevice";
                return null;
            }
            long deviceHandle = extractHandle(vkDeviceObj);
            if (deviceHandle == 0) {
                gpuStatus = "fail_vk_device_handle";
                return null;
            }

            Object vulkanQueue = null;
            Method queueM = findMethod(bc, "graphicsQueue");
            if (queueM != null) vulkanQueue = queueM.invoke(backend);
            if (vulkanQueue == null) {
                try {
                    Field f = bc.getDeclaredField("graphicsQueue");
                    f.setAccessible(true);
                    vulkanQueue = f.get(backend);
                } catch (NoSuchFieldException ignored) {}
            }
            if (vulkanQueue == null) {
                gpuStatus = "fail_vk_queue_null";
                return null;
            }
            long queueHandle = extractHandle(vulkanQueue);
            if (queueHandle == 0) queueHandle = extractNestedHandle(vulkanQueue, "vkQueue");
            if (queueHandle == 0) queueHandle = extractNestedHandle(vulkanQueue, "queue");
            if (queueHandle == 0) {
                gpuStatus = "fail_vk_queue_handle";
                return null;
            }
            int queueFamilyIndex = extractQueueFamilyIndex(vulkanQueue);

            try {
                Method getInfo = findMethod(bc, "getDeviceInfo");
                if (getInfo != null) {
                    Object info = getInfo.invoke(backend);
                    if (info != null) {
                        try { deviceName = (String) info.getClass().getMethod("name").invoke(info); } catch (Exception ignored) {}
                        try { backendName = (String) info.getClass().getMethod("backendName").invoke(info); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            if (backendName.equals("Unknown")) backendName = "Vulkan";
            if (deviceName.equals("Unknown")) deviceName = "GPU";

            long instanceProcAddr = 0;
            long deviceProcAddr = 0;

            FunctionProvider fp = null;
            try { fp = VK.getFunctionProvider(); } catch (Throwable ignored) {}

            if (fp != null) {
                try { instanceProcAddr = fp.getFunctionAddress("vkGetInstanceProcAddr"); } catch (Throwable ignored) {}
                try { deviceProcAddr = fp.getFunctionAddress("vkGetDeviceProcAddr"); } catch (Throwable ignored) {}
            }

            if (instanceProcAddr == 0 || deviceProcAddr == 0) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    if (instanceProcAddr == 0) {
                        ByteBuffer name = stack.ASCII("vkGetInstanceProcAddr");
                        instanceProcAddr = VK10.nvkGetInstanceProcAddr(instanceHandle, MemoryUtil.memAddress(name));
                    }
                    if (deviceProcAddr == 0) {
                        ByteBuffer name = stack.ASCII("vkGetDeviceProcAddr");
                        deviceProcAddr = VK10.nvkGetInstanceProcAddr(instanceHandle, MemoryUtil.memAddress(name));
                    }
                }
            }

            if (instanceProcAddr == 0 || deviceProcAddr == 0) {
                gpuStatus = "fail_vk_proc_addr";
                return null;
            }

            final int VK_API_VERSION_1_2 = 0x00402000;
            DirectContext ctx = DirectContext.makeVulkan(
                    instanceHandle, physicalHandle, deviceHandle, queueHandle,
                    queueFamilyIndex, instanceProcAddr, deviceProcAddr, VK_API_VERSION_1_2);

            if (ctx != null) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                QplayerModClient.LOGGER.info("[GpuContext] Vulkan DirectContext created in {}ms", ms);
                gpuStatus = "ok_vulkan";
                cachedContext = ctx;
                isGL = false;
            } else {
                gpuStatus = "fail_vk_makeVulkan_null";
            }
            return ctx;
        } catch (Throwable e) {
            QplayerModClient.LOGGER.warn("[GpuContext] Vulkan FAILED: {}", e.toString());
            gpuStatus = "fail_vk_" + e.getClass().getSimpleName();
            return null;
        }
    }

    private static int extractQueueFamilyIndex(Object queue) {
        Class<?> cls = queue.getClass();
        for (String name : new String[]{"queueFamilyIndex", "familyIndex", "family"}) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                if (f.getType() == int.class) return f.getInt(queue);
            } catch (Exception ignored) {}
        }
        for (String name : new String[]{"queueFamilyIndex", "familyIndex", "family"}) {
            Method m = findMethod(cls, name);
            if (m != null && m.getReturnType() == int.class) {
                try { return (int) m.invoke(queue); } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private static long extractNestedHandle(Object obj, String methodName) {
        try {
            Method m = findMethod(obj.getClass(), methodName);
            if (m == null) return 0;
            Object inner = m.invoke(obj);
            if (inner == null) return 0;
            return extractHandle(inner);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long extractHandle(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Long) return (Long) obj;

        Class<?> cls = obj.getClass();
        Method addr = findMethod(cls, "address");
        if (addr != null) {
            try {
                Object result = addr.invoke(obj);
                if (result instanceof Long) return (Long) result;
            } catch (Exception ignored) {}
        }

        if (obj instanceof java.util.function.LongSupplier) {
            return ((java.util.function.LongSupplier) obj).getAsLong();
        }

        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == long.class
                    && !m.getDeclaringClass().equals(Object.class)) {
                try {
                    long val = (long) m.invoke(obj);
                    if (val != 0) return val;
                } catch (Exception ignored) {}
            }
        }

        for (Field f : cls.getDeclaredFields()) {
            if (f.getType() == long.class) {
                f.setAccessible(true);
                try {
                    long val = f.getLong(obj);
                    if (val != 0) return val;
                } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private static Method findMethod(Class<?> clazz, String name) {
        try { return clazz.getMethod(name); } catch (NoSuchMethodException e) {}
        try { return clazz.getDeclaredMethod(name); } catch (NoSuchMethodException e) {}
        return null;
    }
}
