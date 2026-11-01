package com.ylmac.adapt.metal;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMetalObjects;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportMetalCommandQueueInfoEXT;
import org.lwjgl.vulkan.VkExportMetalDeviceInfoEXT;
import org.lwjgl.vulkan.VkExportMetalObjectsInfoEXT;
import org.lwjgl.vulkan.VkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * 从 Minecraft 的 MoltenVK {@code VkDevice} 里导出底层 {@code MTLDevice} /
 * {@code MTLCommandQueue}，让 Adapt 能在同一个 Metal 设备上跑硬件光追。
 * <p>
 * <b>完整调用链（26.2 实证，javap 于 minecraft-clientonly-deobf-26.2.jar）</b>
 * <pre>
 * RenderSystem.getDevice()                    → com.mojang.renderpearl.api.device.GpuDevice
 *   ↓ private final GpuDeviceBackend backend  ← 必须反射，26.2 无 public getter
 * (VulkanDevice) backend                      → com.mojang.renderpearl.backend.vulkan.VulkanDevice
 *   ↓ public VkDevice vkDevice()
 *   ↓ public VulkanQueue graphicsQueue() → record，public VkQueue vkQueue()
 * vkExportMetalObjectsEXT(vkDevice, &amp;info)  → pNext 链：
 *       VkExportMetalObjectsInfoEXT (sType 1000311001)
 *         → VkExportMetalDeviceInfoEXT       (sType 1000311002) → mtlDevice
 *         → VkExportMetalCommandQueueInfoEXT (sType 1000311003) → mtlCommandQueue
 * </pre>
 * <p>
 * <b>为什么不能用 LWJGL 的 {@code EXTMetalObjects.vkExportMetalObjectsEXT}</b>：
 * 那个静态方法内部 {@code check(device.getCapabilities().vkExportMetalObjectsEXT)}，
 * 而 {@code VKCapabilitiesDevice} 只在设备创建时枚举到 {@code VK_EXT_metal_objects}
 * 才会填这个函数指针。Minecraft 创建 VkDevice 时没启这个扩展 → 字段为 0 →
 * LWJGL 直接抛 NullPointerException。但 <b>MoltenVK 的 dylib 无条件导出这个符号</b>，
 * 用 {@code VK.getFunctionProvider().getFunctionAddress("vkExportMetalObjectsEXT")}
 * 拿到裸地址、走 Panama downcall 就能正常调用（先前研究已端到端验证：导出的
 * MTLDevice 指针与 {@code MTLCreateSystemDefaultDevice()} 完全相同）。
 * <p>
 * <b>所有权（重要）</b>：导出的 {@code MTLDevice} / {@code MTLCommandQueue} 是
 * <b>unretained</b> 引用，归 MoltenVK 所有，其生命周期跟 {@code VkDevice} 绑定。
 * <b>绝对不要 release</b>；也不要在 {@code GpuDevice} 关闭后继续用。想延长生命周期
 * 只能自己 retain 一次并配对 release，本类<b>不</b>这么做（避免和 MoltenVK 的
 * 引用计数打架）。
 * <p>
 * <b>降级</b>：任何一步失败（非 macOS、后端不是 Vulkan、符号缺失、导出返回 0x0）
 * 都返回 null 并且只 log 一次。本类永不向渲染线程抛异常。
 * <p>
 * <b>native access</b>：见 {@link AdaptObjC} 的类注释——发布前需要
 * {@code --enable-native-access=ALL-UNNAMED} 或 manifest 的
 * {@code Enable-Native-Access} 条目。
 */
public final class MetalBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("adapt-metal");

    /** {@code VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT}，与 LWJGL 常量核对一致。 */
    private static final int STYPE_EXPORT_METAL_OBJECTS_INFO =
            EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT;      // 1000311001
    private static final int STYPE_EXPORT_METAL_DEVICE_INFO =
            EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_DEVICE_INFO_EXT;       // 1000311002
    private static final int STYPE_EXPORT_METAL_COMMAND_QUEUE_INFO =
            EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_COMMAND_QUEUE_INFO_EXT; // 1000311003

    /** 导出结果缓存。VkDevice 在整个游戏会话里不变，只需导一次。 */
    private static MemorySegment mtlDevice = MemorySegment.NULL;
    private static MemorySegment mtlCommandQueue = MemorySegment.NULL;
    private static VkDevice cachedVkDevice;
    private static boolean attempted;
    private static String failureReason = "not attempted";

    private MetalBridge() {
    }

    /**
     * 导出的 {@code MTLDevice}，不可用时返回 null。首次调用会做完整导出，
     * 之后走缓存。必须在渲染线程调用（要读 {@code RenderSystem.getDevice()}）。
     */
    public static MemorySegment device() {
        ensureExported();
        return AdaptObjC.isNil(mtlDevice) ? null : mtlDevice;
    }

    /**
     * 导出的 {@code MTLCommandQueue}（对应 Minecraft 的 graphics queue），
     * 不可用时返回 null。
     * <p>
     * 注意：往这个 queue 上提交自己的 command buffer 会和 Minecraft 的提交<b>交错</b>。
     * MoltenVK 把 VkQueue 提交也序列化到同一个 MTLCommandQueue 上，所以顺序是
     * 保证的，但没有隐式同步语义——跨 API 的资源依赖必须用
     * {@link MTLSharedEventBridge} 显式同步。
     */
    public static MemorySegment commandQueue() {
        ensureExported();
        return AdaptObjC.isNil(mtlCommandQueue) ? null : mtlCommandQueue;
    }

    /** 桥是否可用（Metal 设备已成功导出）。 */
    public static boolean available() {
        ensureExported();
        return !AdaptObjC.isNil(mtlDevice);
    }

    /** 失败原因（成功时为空串），只用于诊断日志/调试屏。 */
    public static String failureReason() {
        ensureExported();
        return failureReason;
    }

    /**
     * 丢弃缓存，下次调用重新导出。
     * <p>
     * {@code GpuDevice} 重建（切换窗口/后端）后必须调用——旧的 MTLDevice 指针
     * 会随旧 VkDevice 一起失效，继续用是 use-after-free。
     */
    public static void invalidate() {
        mtlDevice = MemorySegment.NULL;
        mtlCommandQueue = MemorySegment.NULL;
        cachedVkDevice = null;
        attempted = false;
        failureReason = "not attempted";
    }

    /** 导出用的 {@code VkDevice}，供 {@link MTLSharedEventBridge} 建 timeline semaphore。 */
    static VkDevice vkDevice() {
        ensureExported();
        return cachedVkDevice;
    }

    /**
     * 用显式给出的 {@code VkDevice} + {@code VkQueue} 做导出，绕过
     * {@code RenderSystem} 与反射。
     * <p>
     * 两个用途：
     * <ol>
     *   <li><b>单元测试</b>：测试进程里没有 Minecraft 的 {@code GpuDevice}，
     *       但可以自己 {@code vkCreateInstance/vkCreateDevice} 起一个 MoltenVK 设备
     *       并把它喂进来，从而真正跑到导出逻辑而不是只测降级分支。</li>
     *   <li>调用方已经握着 {@code VulkanDevice}（比如某个 mixin 的
     *       {@code @Inject} 上下文里），省掉一次反射。</li>
     * </ol>
     * 会覆盖已有缓存。返回是否成功。
     */
    public static synchronized boolean attach(final VkDevice vkDevice, final VkQueue vkQueue) {
        invalidate();
        attempted = true;
        try {
            exportFrom(vkDevice, vkQueue);
        } catch (Throwable throwable) {
            fail("attach crashed: " + throwable);
        }
        return !AdaptObjC.isNil(mtlDevice);
    }

    private static synchronized void ensureExported() {
        if (attempted) {
            return;
        }
        attempted = true;
        try {
            export();
        } catch (Throwable throwable) {
            // 兜底：export() 内部每一步都已经 catch 过了，这里只防"没想到的"
            // （LinkageError、NoSuchMethodError 之类）。绝不让它穿到渲染线程。
            failureReason = "unexpected " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            mtlDevice = MemorySegment.NULL;
            mtlCommandQueue = MemorySegment.NULL;
            LOGGER.warn("[adapt-metal] Metal bridge unavailable: {}", failureReason);
        }
    }

    private static void export() {
        if (!AdaptObjC.available()) {
            fail("ObjC runtime unavailable: " + AdaptObjC.unavailableReason());
            return;
        }

        GpuDevice gpuDevice = RenderSystem.tryGetDevice();
        if (gpuDevice == null) {
            // tryGetDevice 而不是 getDevice：后者在 DEVICE==null 时抛
            // IllegalStateException（26.2 javap 实证），我们不想在初始化早期崩。
            fail("RenderSystem device not created yet");
            return;
        }

        Object backend = readBackend(gpuDevice);
        if (backend == null) {
            fail("GpuDevice.backend not readable (see accessWidener note)");
            return;
        }
        if (!(backend instanceof com.mojang.renderpearl.backend.vulkan.VulkanDevice vulkanDevice)) {
            fail("backend is " + backend.getClass().getName() + ", not VulkanDevice");
            return;
        }

        VkDevice vkDevice = vulkanDevice.vkDevice();
        var graphicsQueue = vulkanDevice.graphicsQueue();
        exportFrom(vkDevice, graphicsQueue == null ? null : graphicsQueue.vkQueue());
    }

    /** {@link #export()} 与 {@link #attach} 的公共部分：真正调 vkExportMetalObjectsEXT。 */
    private static void exportFrom(final VkDevice vkDevice, final VkQueue vkQueue) {
        if (!AdaptObjC.available()) {
            fail("ObjC runtime unavailable: " + AdaptObjC.unavailableReason());
            return;
        }
        if (vkDevice == null || vkDevice.address() == 0L) {
            fail("VkDevice is null");
            return;
        }
        if (vkQueue == null || vkQueue.address() == 0L) {
            fail("VkQueue is null");
            return;
        }

        MethodHandle exportHandle = exportHandle();
        if (exportHandle == null) {
            fail("vkExportMetalObjectsEXT symbol not found (not MoltenVK?)");
            return;
        }

        long deviceHandle;
        long queueHandle;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // pNext 链尾 → 头：CommandQueueInfo → DeviceInfo → ObjectsInfo。
            // calloc 很关键：这些 struct 的输出字段（mtlDevice/mtlCommandQueue）必须
            // 初始为 0，才能靠"仍然是 0"判断驱动没填。
            VkExportMetalCommandQueueInfoEXT queueInfo = VkExportMetalCommandQueueInfoEXT.calloc(stack)
                    .sType(STYPE_EXPORT_METAL_COMMAND_QUEUE_INFO)
                    .queue(vkQueue);
            VkExportMetalDeviceInfoEXT deviceInfo = VkExportMetalDeviceInfoEXT.calloc(stack)
                    .sType(STYPE_EXPORT_METAL_DEVICE_INFO)
                    .pNext(queueInfo.address());
            VkExportMetalObjectsInfoEXT objectsInfo = VkExportMetalObjectsInfoEXT.calloc(stack)
                    .sType(STYPE_EXPORT_METAL_OBJECTS_INFO)
                    .pNext(deviceInfo.address());

            try {
                // 裸符号 downcall：ofVoid(ADDRESS /*VkDevice*/, ADDRESS /*pInfo*/)。
                // VkDevice 是 dispatchable handle，ABI 上就是个指针，直接传 address。
                exportHandle.invokeExact(
                        MemorySegment.ofAddress(vkDevice.address()),
                        MemorySegment.ofAddress(objectsInfo.address()));
            } catch (Throwable throwable) {
                fail("vkExportMetalObjectsEXT threw " + throwable.getClass().getSimpleName()
                        + ": " + throwable.getMessage());
                return;
            }

            deviceHandle = deviceInfo.mtlDevice();
            queueHandle = queueInfo.mtlCommandQueue();
        }

        if (deviceHandle == 0L) {
            fail("vkExportMetalObjectsEXT returned mtlDevice=0x0");
            return;
        }

        mtlDevice = MemorySegment.ofAddress(deviceHandle);
        mtlCommandQueue = queueHandle == 0L ? MemorySegment.NULL : MemorySegment.ofAddress(queueHandle);
        cachedVkDevice = vkDevice;
        failureReason = "";

        LOGGER.info("[adapt-metal] Exported MTLDevice=0x{} MTLCommandQueue=0x{} from MoltenVK VkDevice=0x{}",
                Long.toHexString(deviceHandle),
                Long.toHexString(queueHandle),
                Long.toHexString(vkDevice.address()));
        if (queueHandle == 0L) {
            LOGGER.warn("[adapt-metal] MTLCommandQueue export returned 0x0; "
                    + "RT work must use a private queue from MTLDevice.newCommandQueue");
        }
    }

    /**
     * 反射读 {@code GpuDevice.backend}。
     * <p>
     * 26.2 的 {@code GpuDevice} 把 backend 声明成 {@code private final GpuDeviceBackend}
     * 且没有任何 getter（javap 实证：字段列表里只有 backend / criticalShaderLoader /
     * profiler 三个 private final）。两条路：
     * <ol>
     *   <li>反射 + {@code setAccessible(true)}。{@code com.mojang.blaze3d.systems} 属于
     *       未命名模块（Minecraft 不是 JPMS 模块），所以 setAccessible 不会被模块系统拒。
     *       这是本方法走的路。</li>
     *   <li>accessWidener 条目
     *       {@code accessible field com/mojang/blaze3d/systems/GpuDevice backend Lcom/mojang/blaze3d/systems/GpuDeviceBackend;}
     *       ——需要新建 {@code src/main/resources/adapt.accesswidener} 并在
     *       build.gradle 的 loom 块里 {@code accessWidenerPath = file(...)} 指过去，
     *       之后就能直接 {@code gpuDevice.backend}。本项目当前<b>没有</b> accessWidener
     *       文件，所以先用反射；反射失败会走降级而不是崩。</li>
     * </ol>
     * Sodium 装了的时候还有第三条路：Sodium 给 GpuDevice 混入了
     * {@code sodium$getBackend()}（见 {@code render/GpuContextHelper} 的用法）。
     * 这里先试它，因为它不需要 setAccessible。
     */
    private static Object readBackend(final GpuDevice gpuDevice) {
        try {
            var sodiumAccessor = gpuDevice.getClass().getMethod("sodium$getBackend");
            Object backend = sodiumAccessor.invoke(gpuDevice);
            if (backend != null) {
                return backend;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Sodium 没装或版本不同 → 落到直接反射。
        }
        try {
            Field field = GpuDevice.class.getDeclaredField("backend");
            field.setAccessible(true);
            return field.get(gpuDevice);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn("[adapt-metal] Cannot read GpuDevice.backend: {}", exception.toString());
            return null;
        }
    }

    /**
     * 拿 {@code vkExportMetalObjectsEXT} 的裸函数地址并包成 downcall。
     * <p>
     * 走 {@code VK.getFunctionProvider()}（= dlsym on libMoltenVK/libvulkan）而不是
     * {@code vkGetDeviceProcAddr}：后者对未启用的扩展会正确地返回 NULL，前者只看
     * dylib 导出表，所以能拿到。已实测该地址可直接调用。
     */
    private static MethodHandle exportHandle() {
        long address;
        try {
            var provider = VK.getFunctionProvider();
            if (provider == null) {
                return null;
            }
            address = provider.getFunctionAddress("vkExportMetalObjectsEXT");
        } catch (Throwable throwable) {
            LOGGER.warn("[adapt-metal] getFunctionAddress failed: {}", throwable.toString());
            return null;
        }
        if (address == 0L) {
            return null;
        }
        try {
            return AdaptObjC.LINKER.downcallHandle(
                    MemorySegment.ofAddress(address),
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        } catch (Throwable throwable) {
            LOGGER.warn("[adapt-metal] downcallHandle for vkExportMetalObjectsEXT failed: {}", throwable.toString());
            return null;
        }
    }

    private static void fail(final String reason) {
        failureReason = reason;
        mtlDevice = MemorySegment.NULL;
        mtlCommandQueue = MemorySegment.NULL;
        LOGGER.info("[adapt-metal] Metal RT bridge unavailable: {}", reason);
    }
}

