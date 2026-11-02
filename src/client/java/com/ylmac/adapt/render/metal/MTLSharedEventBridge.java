package com.ylmac.adapt.render.metal;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMetalObjects;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportMetalObjectCreateInfoEXT;
import org.lwjgl.vulkan.VkExportMetalObjectsInfoEXT;
import org.lwjgl.vulkan.VkExportMetalSharedEventInfoEXT;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.LongBuffer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Vulkan timeline semaphore ↔ {@code MTLSharedEvent} 的双向同步原语。
 * <p>
 * 用途：Adapt 的 RT compute pass 直接跑在 Metal 上，而地形 G-buffer 是
 * Minecraft 通过 MoltenVK 画的。两侧提交到<b>同一个</b> MTLCommandQueue，
 * 顺序有保证，但没有内存屏障语义。共享同一个 event 之后：
 * <pre>
 * Vulkan 侧：vkQueueSubmit 的 pSignalSemaphores + VkTimelineSemaphoreSubmitInfo{value=N}
 * Metal 侧：commandBuffer encodeWaitForEvent:event value:N
 * </pre>
 * 两边看到的是同一个 64 位计数器。
 * <p>
 * <b>关键实证结论：BINARY semaphore 不导出 shared event。</b>
 * 只带 {@code VkExportMetalObjectCreateInfoEXT} 而不带
 * {@code VkSemaphoreTypeCreateInfo{semaphoreType=VK_SEMAPHORE_TYPE_TIMELINE}}
 * 时，{@code vkExportMetalObjectsEXT} 返回的 {@code mtlSharedEvent} 是
 * <b>0x0</b>（已验证）。原因：MoltenVK 只在 timeline 语义下才用
 * {@code MTLSharedEvent} 实现 semaphore；binary semaphore 走的是内部
 * {@code MVKSemaphoreEmulated}（CPU 条件变量）或 {@code MTLEvent}，都没有
 * 可导出的 shared event。所以本类<b>强制</b>建 timeline semaphore，
 * {@link #create} 不提供 binary 选项。
 * <p>
 * <b>所有权</b>：{@code VkSemaphore} 由本类创建、{@link #close} 销毁。
 * 导出的 {@code MTLSharedEvent} 是 unretained（归 MoltenVK 的
 * {@code MVKTimelineSemaphore} 所有），<b>不要 release</b>；semaphore 销毁后
 * 该指针即失效。
 * <p>
 * <b>native access</b>：见 {@link AdaptObjC} 类注释。
 */
public final class MTLSharedEventBridge implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("adapt-metal");

    private static final int STYPE_EXPORT_METAL_OBJECT_CREATE_INFO =
            EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECT_CREATE_INFO_EXT;   // 1000311000
    private static final int STYPE_EXPORT_METAL_OBJECTS_INFO =
            EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT;         // 1000311001
    private static final int STYPE_EXPORT_METAL_SHARED_EVENT_INFO =
            EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_SHARED_EVENT_INFO_EXT;    // 1000311010
    private static final int EXPORT_TYPE_SHARED_EVENT =
            EXTMetalObjects.VK_EXPORT_METAL_OBJECT_TYPE_METAL_SHARED_EVENT_BIT_EXT;  // 32

    // MTLCommandBuffer 上的跨队列同步。encode* 是纯 encoder 操作，不阻塞 → critical。
    private static final AdaptMsg ENCODE_WAIT_FOR_EVENT =
            AdaptMsg.ofVoid("encodeWaitForEvent:value:", ADDRESS, JAVA_LONG);
    private static final AdaptMsg ENCODE_SIGNAL_EVENT =
            AdaptMsg.ofVoid("encodeSignalEvent:value:", ADDRESS, JAVA_LONG);
    private static final AdaptMsg SIGNALED_VALUE = AdaptMsg.of("signaledValue", JAVA_LONG);
    private static final AdaptMsg SET_SIGNALED_VALUE = AdaptMsg.ofVoid("setSignaledValue:", JAVA_LONG);
    // waitUntilSignaledValue 会阻塞 → 普通句柄，绝不能 critical。
    private static final AdaptMsg WAIT_UNTIL_SIGNALED_VALUE =
            AdaptMsg.of("waitUntilSignaledValue:timeoutMS:", true,
                    java.lang.foreign.ValueLayout.JAVA_BOOLEAN, JAVA_LONG, JAVA_LONG);

    private final VkDevice vkDevice;
    private long semaphore;
    private MemorySegment sharedEvent;

    private MTLSharedEventBridge(final VkDevice vkDevice, final long semaphore, final MemorySegment sharedEvent) {
        this.vkDevice = vkDevice;
        this.semaphore = semaphore;
        this.sharedEvent = sharedEvent;
    }

    /**
 * 建一个 timeline semaphore 并导出它的 {@code MTLSharedEvent}。
 * 任何一步失败返回 null 并 log（不抛）。
 *
 * @param initialValue timeline 的初始计数值，一般 0
 */
    public static MTLSharedEventBridge create(final long initialValue) {
        if (!MetalBridge.available()) {
            LOGGER.info("[adapt-metal] Shared event unavailable: {}", MetalBridge.failureReason());
            return null;
        }
        VkDevice vkDevice = MetalBridge.vkDevice();
        if (vkDevice == null) {
            return null;
        }

        long semaphore;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // pNext 链：SemaphoreCreateInfo → ExportMetalObjectCreateInfo → SemaphoreTypeCreateInfo

            // 两个 pNext 都是必需的，缺任何一个都拿不到 shared event：
            // ExportMetalObjectCreateInfo 告诉 MoltenVK "这个对象将来要导出"
            // （不带的话导出时 mtlSharedEvent=0x0）；
            // SemaphoreTypeCreateInfo{TIMELINE} 决定 MoltenVK 内部用
            // MVKTimelineSemaphore(MTLSharedEvent) 而不是 binary 的
            // emulated/MTLEvent 实现。
            VkSemaphoreTypeCreateInfo typeInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO)
                    .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
                    .initialValue(initialValue);
            VkExportMetalObjectCreateInfoEXT exportInfo = VkExportMetalObjectCreateInfoEXT.calloc(stack)
                    .sType(STYPE_EXPORT_METAL_OBJECT_CREATE_INFO)
                    .exportObjectType(EXPORT_TYPE_SHARED_EVENT)
                    .pNext(typeInfo.address());
            VkSemaphoreCreateInfo createInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                    .pNext(exportInfo.address());

            LongBuffer out = stack.mallocLong(1);
            int result = VK10.vkCreateSemaphore(vkDevice, createInfo, null, out);
            if (result != VK10.VK_SUCCESS) {
                LOGGER.warn("[adapt-metal] vkCreateSemaphore(timeline+export) failed: VkResult={}", result);
                return null;
            }
            semaphore = out.get(0);
        } catch (Throwable throwable) {
            LOGGER.warn("[adapt-metal] Timeline semaphore creation crashed: {}", throwable.toString());
            return null;
        }

        MethodHandle exportHandle = exportHandle();
        if (exportHandle == null) {
            VK10.vkDestroySemaphore(vkDevice, semaphore, null);
            LOGGER.warn("[adapt-metal] vkExportMetalObjectsEXT symbol missing; cannot export shared event");
            return null;
        }

        long eventHandle;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkExportMetalSharedEventInfoEXT eventInfo = VkExportMetalSharedEventInfoEXT.calloc(stack)
                    .sType(STYPE_EXPORT_METAL_SHARED_EVENT_INFO)
                    .semaphore(semaphore);
            VkExportMetalObjectsInfoEXT objectsInfo = VkExportMetalObjectsInfoEXT.calloc(stack)
                    .sType(STYPE_EXPORT_METAL_OBJECTS_INFO)
                    .pNext(eventInfo.address());

            exportHandle.invokeExact(
                    MemorySegment.ofAddress(vkDevice.address()),
                    MemorySegment.ofAddress(objectsInfo.address()));
            eventHandle = eventInfo.mtlSharedEvent();
        } catch (Throwable throwable) {
            VK10.vkDestroySemaphore(vkDevice, semaphore, null);
            LOGGER.warn("[adapt-metal] Shared event export crashed: {}", throwable.toString());
            return null;
        }

        if (eventHandle == 0L) {
            VK10.vkDestroySemaphore(vkDevice, semaphore, null);
            // 走到这里说明 MoltenVK 版本变了行为，或者 timeline pNext 没生效。
            LOGGER.warn("[adapt-metal] mtlSharedEvent=0x0 for a TIMELINE semaphore; "
                    + "MoltenVK only backs timeline semaphores with MTLSharedEvent "
                    + "(binary semaphores always return 0x0)");
            return null;
        }

        LOGGER.info("[adapt-metal] Timeline semaphore 0x{} exported as MTLSharedEvent 0x{} (initialValue={})",
                Long.toHexString(semaphore), Long.toHexString(eventHandle), initialValue);
        return new MTLSharedEventBridge(vkDevice, semaphore, MemorySegment.ofAddress(eventHandle));
    }

    /** {@code VkSemaphore} 句柄，喂给 {@code vkQueueSubmit} 的 wait/signal 列表。 */
    public long semaphore() {
        return semaphore;
    }

    /** 导出的 {@code id<MTLSharedEvent>}。<b>不要 release。</b> */
    public MemorySegment sharedEvent() {
        return sharedEvent;
    }

    public boolean valid() {
        return semaphore != VK10.VK_NULL_HANDLE && !AdaptObjC.isNil(sharedEvent);
    }

    /**
 * 让 Metal command buffer 等到 event 计数 ≥ value 才开始执行。
 * 必须在该 command buffer 上<b>创建任何 encoder 之前</b>调用（Metal 要求）。
 */
    public void encodeWait(final MemorySegment commandBuffer, final long value) {
        if (!valid() || AdaptObjC.isNil(commandBuffer)) {
            return;
        }
        ENCODE_WAIT_FOR_EVENT.send(commandBuffer, sharedEvent, value);
    }

    /**
 * Metal command buffer 执行完成后把 event 计数置为 value。
 * 必须在 {@code commit} 之前、所有 encoder 都 endEncoding 之后调用。
 */
    public void encodeSignal(final MemorySegment commandBuffer, final long value) {
        if (!valid() || AdaptObjC.isNil(commandBuffer)) {
            return;
        }
        ENCODE_SIGNAL_EVENT.send(commandBuffer, sharedEvent, value);
    }

    /** CPU 侧读当前计数（等价于 {@code vkGetSemaphoreCounterValue}）。 */
    public long signaledValue() {
        return valid() ? SIGNALED_VALUE.sendLong(sharedEvent) : 0L;
    }

    /** CPU 侧直接推进计数（等价于 {@code vkSignalSemaphore}）。谨慎使用：会跳过 GPU。 */
    public void setSignaledValue(final long value) {
        if (valid()) {
            SET_SIGNALED_VALUE.send(sharedEvent, value);
        }
    }

    /**
 * CPU 阻塞等到计数 ≥ value 或超时。返回是否等到。
 * <b>不要在渲染线程调用</b>—会掉帧。需要 macOS 12+。
 */
    public boolean waitUntilSignaled(final long value, final long timeoutMillis) {
        if (!valid()) {
            return false;
        }
        return WAIT_UNTIL_SIGNALED_VALUE.sendBool(sharedEvent, value, timeoutMillis);
    }

    /**
 * 销毁 VkSemaphore。之后 {@link #sharedEvent} 指针失效（MoltenVK 会连带
 * release 它持有的 MTLSharedEvent），所以这里把它清成 NULL。
 */
    @Override
    public void close() {
        // MTLSharedEvent 是 unretained 的，绝不 release — 归 MVKTimelineSemaphore 所有。
        sharedEvent = MemorySegment.NULL;
        if (semaphore != VK10.VK_NULL_HANDLE && vkDevice != null) {
            VK10.vkDestroySemaphore(vkDevice, semaphore, null);
            semaphore = VK10.VK_NULL_HANDLE;
        }
    }

    /** 和 {@link MetalBridge} 里同样的裸符号 downcall（原因见那边的注释）。 */
    private static MethodHandle exportHandle() {
        if (!AdaptObjC.available()) {
            return null;
        }
        try {
            var provider = org.lwjgl.vulkan.VK.getFunctionProvider();
            if (provider == null) {
                return null;
            }
            long address = provider.getFunctionAddress("vkExportMetalObjectsEXT");
            if (address == 0L) {
                return null;
            }
            return AdaptObjC.LINKER.downcallHandle(
                    MemorySegment.ofAddress(address),
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        } catch (Throwable throwable) {
            return null;
        }
    }
}

