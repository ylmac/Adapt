package com.ylmac.adapt.metal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * 运行时查询导出的 MTLDevice 的能力，决定 RT 路径是否可用。
 * <p>
 * 判定门槛（{@link #raytracingSupported()}）：
 * <ul>
 *   <li>{@code supportsRaytracing} —— 有没有 {@code intersector<>} 硬件支持。
 *       Apple 从 A13/M1 起全系为 true；Intel Mac 的 AMD 卡看具体型号。</li>
 *   <li>{@code supportsFamily:MTLGPUFamilyApple9(1009)} —— <b>不作为硬门槛</b>。
 *       Apple9 = M3/M4 世代，带硬件 ray tracing 加速单元（前代是 shader 模拟，
 *       能跑但慢一个数量级）。这里只记录，供上层决定要不要开高质量档。</li>
 * </ul>
 * <p>
 * {@code supportsFamily:} 返回 {@code BOOL}（1 字节），必须用 {@code JAVA_BOOLEAN}
 * 而不是 {@code JAVA_LONG} 声明返回值 —— 后者会把 x0 高位的垃圾一起读进来，
 * 在某些调用后变成"永远 true"。
 * <p>
 * <b>native access</b>：见 {@link AdaptObjC} 类注释。
 */
public final class MetalCapabilities {

    private static final Logger LOGGER = LoggerFactory.getLogger("adapt-metal");

    /** {@code MTLGPUFamilyApple7}（M1/M2、A14+）。 */
    public static final long GPU_FAMILY_APPLE7 = 1007L;
    /** {@code MTLGPUFamilyApple8}（M2 Pro/Max、A15/A16）。 */
    public static final long GPU_FAMILY_APPLE8 = 1008L;
    /** {@code MTLGPUFamilyApple9}（M3/M4、A17+；硬件 RT 加速单元从这代开始）。 */
    public static final long GPU_FAMILY_APPLE9 = 1009L;
    /** {@code MTLGPUFamilyMetal3}。 */
    public static final long GPU_FAMILY_METAL3 = 5001L;

    private static final AdaptMsg NAME = AdaptMsg.of("name", ADDRESS);
    private static final AdaptMsg MAX_BUFFER_LENGTH = AdaptMsg.of("maxBufferLength", JAVA_LONG);
    private static final AdaptMsg RECOMMENDED_MAX_WORKING_SET_SIZE =
            AdaptMsg.of("recommendedMaxWorkingSetSize", JAVA_LONG);
    private static final AdaptMsg SUPPORTS_RAYTRACING = AdaptMsg.of("supportsRaytracing", JAVA_BOOLEAN);
    private static final AdaptMsg SUPPORTS_RAYTRACING_FROM_RENDER =
            AdaptMsg.of("supportsRaytracingFromRender", JAVA_BOOLEAN);
    private static final AdaptMsg SUPPORTS_FAMILY = AdaptMsg.of("supportsFamily:", JAVA_BOOLEAN, JAVA_LONG);
    private static final AdaptMsg HAS_UNIFIED_MEMORY = AdaptMsg.of("hasUnifiedMemory", JAVA_BOOLEAN);
    private static final AdaptMsg MAX_THREADGROUP_MEMORY_LENGTH =
            AdaptMsg.of("maxThreadgroupMemoryLength", JAVA_LONG);
    private static final AdaptMsg SUPPORTS_FUNCTION_POINTERS = AdaptMsg.of("supportsFunctionPointers", JAVA_BOOLEAN);

    /** 查询快照。所有字段在设备不可用时为安全默认值（false / 0 / ""）。 */
    public record Snapshot(
            boolean available,
            String deviceName,
            boolean supportsRaytracing,
            boolean supportsRaytracingFromRender,
            boolean supportsApple7,
            boolean supportsApple8,
            boolean supportsApple9,
            boolean hasUnifiedMemory,
            boolean supportsFunctionPointers,
            long maxBufferLength,
            long recommendedMaxWorkingSetSize,
            long maxThreadgroupMemoryLength
    ) {
        static Snapshot unavailable() {
            return new Snapshot(false, "", false, false, false, false, false, false, false, 0L, 0L, 0L);
        }

        /** 硬件光追可用（RT 路径的最低门槛）。 */
        public boolean raytracingUsable() {
            return available && supportsRaytracing;
        }

        /** 有专用 RT 加速单元（M3/M4+）；false 表示能跑但是 shader 模拟。 */
        public boolean hasHardwareRaytracingUnit() {
            return available && supportsRaytracing && supportsApple9;
        }
    }

    private static Snapshot cached;

    private MetalCapabilities() {
    }

    /** 查询（带缓存）。首次会触发 {@link MetalBridge} 的导出。 */
    public static synchronized Snapshot query() {
        if (cached != null) {
            return cached;
        }
        MemorySegment device = MetalBridge.device();
        if (device == null) {
            cached = Snapshot.unavailable();
            return cached;
        }
        try (AdaptObjC.Pool _ = AdaptObjC.pool()) {
            cached = new Snapshot(
                    true,
                    AdaptObjC.javaString(NAME.sendPtr(device)),
                    SUPPORTS_RAYTRACING.sendBool(device),
                    SUPPORTS_RAYTRACING_FROM_RENDER.sendBool(device),
                    SUPPORTS_FAMILY.sendBool(device, GPU_FAMILY_APPLE7),
                    SUPPORTS_FAMILY.sendBool(device, GPU_FAMILY_APPLE8),
                    SUPPORTS_FAMILY.sendBool(device, GPU_FAMILY_APPLE9),
                    HAS_UNIFIED_MEMORY.sendBool(device),
                    SUPPORTS_FUNCTION_POINTERS.sendBool(device),
                    MAX_BUFFER_LENGTH.sendLong(device),
                    RECOMMENDED_MAX_WORKING_SET_SIZE.sendLong(device),
                    MAX_THREADGROUP_MEMORY_LENGTH.sendLong(device));
        } catch (Throwable throwable) {
            LOGGER.warn("[adapt-metal] Capability query crashed: {}", throwable.toString());
            cached = Snapshot.unavailable();
        }
        return cached;
    }

    /** RT 路径能不能开。上层的唯一判定入口。 */
    public static boolean raytracingSupported() {
        return query().raytracingUsable();
    }

    /** 有没有硬件 RT 单元（Apple9+）。用来选质量档，不是可用性门槛。 */
    public static boolean hasHardwareRaytracingUnit() {
        return query().hasHardwareRaytracingUnit();
    }

    public static String deviceName() {
        return query().deviceName();
    }

    public static long maxBufferLength() {
        return query().maxBufferLength();
    }

    /** 丢弃缓存。设备重建后配合 {@link MetalBridge#invalidate()} 一起调。 */
    public static synchronized void invalidate() {
        cached = null;
    }

    /**
     * 打印一次能力报告。放在 mod 初始化里，让日志能直接回答"这台机器能不能开 RT"。
     */
    public static void logOnce() {
        if (LOGGED.getAndSet(true)) {
            return;
        }
        Snapshot snapshot = query();
        if (!snapshot.available()) {
            LOGGER.info("[adapt-metal] Metal RT unavailable: {}", MetalBridge.failureReason());
            return;
        }
        LOGGER.info("[adapt-metal] device='{}' raytracing={} raytracingFromRender={} "
                        + "families=[Apple7={} Apple8={} Apple9={}] unifiedMemory={} functionPointers={}",
                snapshot.deviceName(), snapshot.supportsRaytracing(), snapshot.supportsRaytracingFromRender(),
                snapshot.supportsApple7(), snapshot.supportsApple8(), snapshot.supportsApple9(),
                snapshot.hasUnifiedMemory(), snapshot.supportsFunctionPointers());
        LOGGER.info("[adapt-metal] maxBufferLength={}MiB recommendedMaxWorkingSet={}MiB maxThreadgroupMemory={}KiB",
                snapshot.maxBufferLength() >> 20,
                snapshot.recommendedMaxWorkingSetSize() >> 20,
                snapshot.maxThreadgroupMemoryLength() >> 10);
        if (snapshot.supportsRaytracing() && !snapshot.supportsApple9()) {
            LOGGER.info("[adapt-metal] No dedicated RT unit (pre-Apple9); "
                    + "intersector runs in shader — expect roughly an order of magnitude less throughput");
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();
}
