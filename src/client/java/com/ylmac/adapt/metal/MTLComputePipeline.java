package com.ylmac.adapt.metal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@code MTLComputePipelineState} 的包装：MSL 源码 → library → function → PSO。
 * <p>
 * 光追 kernel 走 compute 而不是 render：Metal 的 {@code metal_raytracing} 头在
 * fragment shader 里也能用，但 {@code intersector<>} 需要 buffer binding 的
 * acceleration structure，用 compute encoder 的
 * {@code setAccelerationStructure:atBufferIndex:} 绑定最直接（先前研究验证过的
 * 路径就是 compute kernel）。
 * <p>
 * 生命周期：{@link #create} 返回的对象持有 <b>已 retain 的</b> PSO（Metal 的
 * {@code newXxx} 家族按 ObjC 约定返回 +1 引用），必须 {@link #close()}。
 * 中间产物 library / function 在 create 内部就 release 掉了。
 * <p>
 * <b>native access</b>：见 {@link AdaptObjC} 类注释。
 */
public final class MTLComputePipeline implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("adapt-metal");

    // blocking=true（普通 downcall，非 critical）：编译 MSL 会跑几十到几百毫秒，
    // critical(false) 期间 GC 无法在本线程停顿，会把整个 JVM 拖住。
    private static final AdaptMsg NEW_LIBRARY_WITH_SOURCE =
            AdaptMsg.of("newLibraryWithSource:options:error:", true, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final AdaptMsg NEW_FUNCTION_WITH_NAME =
            AdaptMsg.of("newFunctionWithName:", true, ADDRESS, ADDRESS);
    private static final AdaptMsg NEW_COMPUTE_PIPELINE_STATE =
            AdaptMsg.of("newComputePipelineStateWithFunction:error:", true, ADDRESS, ADDRESS, ADDRESS);

    // 这三个是纯属性读取，不阻塞 → critical。
    private static final AdaptMsg MAX_TOTAL_THREADS_PER_THREADGROUP =
            AdaptMsg.of("maxTotalThreadsPerThreadgroup", JAVA_LONG);
    private static final AdaptMsg THREAD_EXECUTION_WIDTH =
            AdaptMsg.of("threadExecutionWidth", JAVA_LONG);
    private static final AdaptMsg STATIC_THREADGROUP_MEMORY_LENGTH =
            AdaptMsg.of("staticThreadgroupMemoryLength", JAVA_LONG);

    private final String label;
    private MemorySegment handle;

    private MTLComputePipeline(final String label, final MemorySegment handle) {
        this.label = label;
        this.handle = handle;
    }

    /**
     * 编译 MSL 并建 compute PSO。任何一步失败返回 null 并 log（不抛）。
     *
     * @param device     {@link MetalBridge#device()} 拿到的 MTLDevice（不可为 nil）
     * @param mslSource  完整 MSL 源码，需要自带 {@code #include <metal_stdlib>} 等
     * @param entryPoint {@code kernel} 函数名
     */
    public static MTLComputePipeline create(final MemorySegment device, final String mslSource, final String entryPoint) {
        if (AdaptObjC.isNil(device) || mslSource == null || entryPoint == null) {
            return null;
        }
        try (AdaptObjC.Pool _ = AdaptObjC.pool(); Arena arena = Arena.ofConfined()) {
            MemorySegment errorOut = arena.allocate(ADDRESS);

            MemorySegment nsSource = AdaptObjC.nsString(mslSource);
            MemorySegment library = NEW_LIBRARY_WITH_SOURCE.sendPtr(
                    device, nsSource, MemorySegment.NULL, errorOut);
            AdaptObjC.release(nsSource);
            if (AdaptObjC.isNil(library)) {
                LOGGER.error("[adapt-metal] MSL compile failed for '{}': {}",
                        entryPoint, AdaptObjC.errorDescription(errorOut));
                return null;
            }

            MemorySegment nsEntry = AdaptObjC.nsString(entryPoint);
            MemorySegment function = NEW_FUNCTION_WITH_NAME.sendPtr(library, nsEntry);
            AdaptObjC.release(nsEntry);
            AdaptObjC.release(library);
            if (AdaptObjC.isNil(function)) {
                LOGGER.error("[adapt-metal] MSL entry point '{}' not found", entryPoint);
                return null;
            }

            // errorOut 复用：上一次成功调用没写它，但 Panama 的 arena 内存没清零保证，
            // 显式清一次，否则失败分支可能报出上一次的陈旧 NSError。
            errorOut.set(ADDRESS, 0L, MemorySegment.NULL);
            MemorySegment pipeline = NEW_COMPUTE_PIPELINE_STATE.sendPtr(device, function, errorOut);
            AdaptObjC.release(function);
            if (AdaptObjC.isNil(pipeline)) {
                LOGGER.error("[adapt-metal] newComputePipelineStateWithFunction: failed for '{}': {}",
                        entryPoint, AdaptObjC.errorDescription(errorOut));
                return null;
            }

            MTLComputePipeline result = new MTLComputePipeline(entryPoint, pipeline);
            LOGGER.info("[adapt-metal] Compute PSO '{}' ready (maxThreadsPerThreadgroup={} execWidth={})",
                    entryPoint, result.maxTotalThreadsPerThreadgroup(), result.threadExecutionWidth());
            return result;
        } catch (Throwable throwable) {
            LOGGER.error("[adapt-metal] Compute pipeline creation crashed for '{}': {}",
                    entryPoint, throwable.toString());
            return null;
        }
    }

    /** 底层 {@code id<MTLComputePipelineState>}。已关闭时抛（编程错误，不是运行时降级点）。 */
    public MemorySegment handle() {
        if (AdaptObjC.isNil(handle)) {
            throw new IllegalStateException("MTLComputePipeline '" + label + "' is closed");
        }
        return handle;
    }

    public boolean isClosed() {
        return AdaptObjC.isNil(handle);
    }

    public String label() {
        return label;
    }

    /** dispatch 时 threadgroup 大小的上限。分配 threadsPerThreadgroup 前必须查。 */
    public long maxTotalThreadsPerThreadgroup() {
        return MAX_TOTAL_THREADS_PER_THREADGROUP.sendLong(handle());
    }

    /** SIMD 宽度（Apple GPU 恒为 32）。threadgroup 宽度取它的整数倍才不浪费 lane。 */
    public long threadExecutionWidth() {
        return THREAD_EXECUTION_WIDTH.sendLong(handle());
    }

    public long staticThreadgroupMemoryLength() {
        return STATIC_THREADGROUP_MEMORY_LENGTH.sendLong(handle());
    }

    @Override
    public void close() {
        if (AdaptObjC.isNil(handle)) {
            return;
        }
        AdaptObjC.release(handle);
        handle = MemorySegment.NULL;
    }
}
