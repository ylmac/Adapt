package com.ylmac.adapt.metal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@code id<MTLComputeCommandEncoder>} 的包装。
 * <p>
 * 用法（严格顺序，Metal 不允许乱序）：
 * <pre>
 * try (var pool = AdaptObjC.pool();
 *      var encoder = MTLComputeCommandEncoder.create(commandBuffer)) {
 *     encoder.setPipeline(pso);
 *     encoder.setAccelerationStructure(accel, 0);   // buffer(0)
 *     encoder.setBuffer(outBuffer, 0, 1);           // buffer(1)
 *     encoder.setTexture(gbuffer, 0);               // texture(0)
 *     encoder.dispatchThreads(width, height, 1, 8, 8, 1);
 * }   // close() 自动 endEncoding + release
 * </pre>
 * <p>
 * {@code create} 返回的 encoder 是 autoreleased 的，这里主动 retain 一次让它的
 * 生命周期与 Java 对象对齐（照 metallum {@code MTLCommandBuffer.makeBlitCommandEncoder}
 * 的做法）；{@link #close()} 里 endEncoding + release 配平。
 * <p>
 * <b>native access</b>：见 {@link AdaptObjC} 类注释。
 */
public final class MTLComputeCommandEncoder implements AutoCloseable {

    private static final AdaptMsg COMPUTE_COMMAND_ENCODER = AdaptMsg.of("computeCommandEncoder", ADDRESS);
    private static final AdaptMsg SET_COMPUTE_PIPELINE_STATE = AdaptMsg.ofVoid("setComputePipelineState:", ADDRESS);
    private static final AdaptMsg SET_BUFFER_OFFSET_AT_INDEX =
            AdaptMsg.ofVoid("setBuffer:offset:atIndex:", ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final AdaptMsg SET_BYTES_LENGTH_AT_INDEX =
            AdaptMsg.ofVoid("setBytes:length:atIndex:", ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final AdaptMsg SET_TEXTURE_AT_INDEX = AdaptMsg.ofVoid("setTexture:atIndex:", ADDRESS, JAVA_LONG);
    private static final AdaptMsg SET_SAMPLER_AT_INDEX = AdaptMsg.ofVoid("setSamplerState:atIndex:", ADDRESS, JAVA_LONG);
    private static final AdaptMsg SET_ACCELERATION_STRUCTURE =
            AdaptMsg.ofVoid("setAccelerationStructure:atBufferIndex:", ADDRESS, JAVA_LONG);
    private static final AdaptMsg SET_THREADGROUP_MEMORY_LENGTH =
            AdaptMsg.ofVoid("setThreadgroupMemoryLength:atIndex:", JAVA_LONG, JAVA_LONG);
    private static final AdaptMsg USE_RESOURCE = AdaptMsg.ofVoid("useResource:usage:", ADDRESS, JAVA_LONG);
    private static final AdaptMsg END_ENCODING = AdaptMsg.ofVoid("endEncoding");
    private static final AdaptMsg SET_LABEL = AdaptMsg.ofVoid("setLabel:", ADDRESS);

    /**
     * {@code dispatchThreads:threadsPerThreadgroup:} —— 两个 {@code MTLSize} 按值传参。
     * MTLSize 是 3×NSUInteger = 24 字节，超过 16 字节所以走内存（arm64 AAPCS），
     * Panama 用 {@link AdaptMsg#SIZES_LAYOUT} 自动处理；不需要也<b>不能</b>传指针。
     * 实测该调用在 critical(false) 下正常工作（见 {@link AdaptMsg} 类注释）。
     */
    private static final AdaptMsg DISPATCH_THREADS = AdaptMsg.ofVoid(
            "dispatchThreads:threadsPerThreadgroup:", AdaptMsg.SIZES_LAYOUT, AdaptMsg.SIZES_LAYOUT);
    private static final AdaptMsg DISPATCH_THREADGROUPS = AdaptMsg.ofVoid(
            "dispatchThreadgroups:threadsPerThreadgroup:", AdaptMsg.SIZES_LAYOUT, AdaptMsg.SIZES_LAYOUT);

    /** {@code MTLResourceUsageRead}。{@code useResource:usage:} 用。 */
    public static final long USAGE_READ = 1L << 0;
    /** {@code MTLResourceUsageWrite}。 */
    public static final long USAGE_WRITE = 1L << 1;

    private MemorySegment handle;

    private MTLComputeCommandEncoder(final MemorySegment handle) {
        this.handle = handle;
    }

    /**
     * 从 command buffer 建 compute encoder。commandBuffer 为 nil 或 Metal 返回 nil
     * 时返回 null——调用方必须判空（一个 command buffer 上同时只能有一个活跃
     * encoder，若上一个没 endEncoding 这里就会拿到 nil）。
     */
    public static MTLComputeCommandEncoder create(final MemorySegment commandBuffer) {
        if (AdaptObjC.isNil(commandBuffer)) {
            return null;
        }
        MemorySegment encoder = COMPUTE_COMMAND_ENCODER.sendPtr(commandBuffer);
        if (AdaptObjC.isNil(encoder)) {
            return null;
        }
        return new MTLComputeCommandEncoder(AdaptObjC.retain(encoder));
    }

    public MemorySegment handle() {
        if (AdaptObjC.isNil(handle)) {
            throw new IllegalStateException("MTLComputeCommandEncoder is closed");
        }
        return handle;
    }

    public void setLabel(final String label) {
        if (label == null || label.isEmpty()) {
            return;
        }
        MemorySegment nsLabel = AdaptObjC.nsString(label);
        SET_LABEL.send(handle(), nsLabel);
        AdaptObjC.release(nsLabel);
    }

    public void setPipeline(final MTLComputePipeline pipeline) {
        if (pipeline == null || pipeline.isClosed()) {
            return;
        }
        SET_COMPUTE_PIPELINE_STATE.send(handle(), pipeline.handle());
    }

    public void setPipelineState(final MemorySegment pipelineState) {
        SET_COMPUTE_PIPELINE_STATE.send(handle(), pipelineState);
    }

    /** 绑 {@code id<MTLBuffer>} 到 {@code [[buffer(index)]]}。buffer 传 nil 等于解绑。 */
    public void setBuffer(final MemorySegment buffer, final long offset, final long index) {
        SET_BUFFER_OFFSET_AT_INDEX.send(handle(), buffer, offset, index);
    }

    /**
     * 内联小常量到 {@code [[buffer(index)]]}（Metal 上限 4KB）。
     * 传的 {@code bytes} 必须是活着的 native 段（比如 confined arena 分配的）。
     */
    public void setBytes(final MemorySegment bytes, final long length, final long index) {
        SET_BYTES_LENGTH_AT_INDEX.send(handle(), bytes, length, index);
    }

    /** 绑 {@code id<MTLTexture>} 到 {@code [[texture(index)]]}。 */
    public void setTexture(final MemorySegment texture, final long index) {
        SET_TEXTURE_AT_INDEX.send(handle(), texture, index);
    }

    /** 绑 {@code id<MTLSamplerState>} 到 {@code [[sampler(index)]]}。 */
    public void setSamplerState(final MemorySegment sampler, final long index) {
        SET_SAMPLER_AT_INDEX.send(handle(), sampler, index);
    }

    /**
     * 绑 acceleration structure 到 {@code [[buffer(index)]]}。
     * <p>
     * MSL 侧签名是 {@code primitive_acceleration_structure accel [[buffer(0)]]}
     * —— 用 buffer slot，不是单独的 AS slot；所以同一个 index 空间和
     * {@link #setBuffer} 共享，不能撞号。
     */
    public void setAccelerationStructure(final MTLAccelerationStructure accel, final long index) {
        SET_ACCELERATION_STRUCTURE.send(handle(), accel == null ? MemorySegment.NULL : accel.handle(), index);
    }

    public void setAccelerationStructure(final MemorySegment accel, final long index) {
        SET_ACCELERATION_STRUCTURE.send(handle(), accel, index);
    }

    public void setThreadgroupMemoryLength(final long length, final long index) {
        SET_THREADGROUP_MEMORY_LENGTH.send(handle(), length, index);
    }

    /**
     * 声明 kernel 会通过 argument buffer / AS 间接访问某个资源。
     * <p>
     * acceleration structure 引用的 vertex/index buffer 不在 encoder 的直接
     * binding 里，Metal 的自动 hazard tracking 看不到它们；不 useResource 的话
     * residency 不保证，GPU 可能读到已被驱逐的页。RT 路径必须对每个几何 buffer 调。
     */
    public void useResource(final MemorySegment resource, final long usage) {
        USE_RESOURCE.send(handle(), resource, usage);
    }

    /**
     * 非对齐 dispatch（Metal 自动处理边界，kernel 里不需要手写 bounds check）。
     * 需要 {@code MTLGPUFamilyApple4}+，M4 满足。
     */
    public void dispatchThreads(final long gridWidth, final long gridHeight, final long gridDepth,
                                final long groupWidth, final long groupHeight, final long groupDepth) {
        if (gridWidth <= 0L || gridHeight <= 0L || gridDepth <= 0L) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            SegmentAllocator allocator = arena;
            MemorySegment grid = AdaptMsg.allocateSize(allocator, gridWidth, gridHeight, gridDepth);
            MemorySegment group = AdaptMsg.allocateSize(allocator,
                    Math.max(groupWidth, 1L), Math.max(groupHeight, 1L), Math.max(groupDepth, 1L));
            DISPATCH_THREADS.sendSizes(handle(), grid, group);
        }
    }

    /** 按 threadgroup 数 dispatch（grid = groups × threadsPerThreadgroup）。 */
    public void dispatchThreadgroups(final long groupsX, final long groupsY, final long groupsZ,
                                     final long groupWidth, final long groupHeight, final long groupDepth) {
        if (groupsX <= 0L || groupsY <= 0L || groupsZ <= 0L) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            SegmentAllocator allocator = arena;
            MemorySegment groups = AdaptMsg.allocateSize(allocator, groupsX, groupsY, groupsZ);
            MemorySegment group = AdaptMsg.allocateSize(allocator,
                    Math.max(groupWidth, 1L), Math.max(groupHeight, 1L), Math.max(groupDepth, 1L));
            DISPATCH_THREADGROUPS.sendSizes(handle(), groups, group);
        }
    }

    /** 显式 endEncoding。{@link #close()} 会自动调，一般不用手动。 */
    public void endEncoding() {
        if (!AdaptObjC.isNil(handle)) {
            END_ENCODING.send(handle);
        }
    }

    /** endEncoding + release，幂等。 */
    @Override
    public void close() {
        if (AdaptObjC.isNil(handle)) {
            return;
        }
        END_ENCODING.send(handle);
        AdaptObjC.release(handle);
        handle = MemorySegment.NULL;
    }
}
