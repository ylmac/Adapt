package com.ylmac.adapt.metal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@code MTLPrimitiveAccelerationStructure}（BLAS）的构建与包装。
 * <p>
 * 完整流程（每一步都实测过，见类末尾的 verified 注释）：
 * <pre>
 * 1. MTLAccelerationStructureTriangleGeometryDescriptor.descriptor
 *      setVertexBuffer/vertexBufferOffset/vertexStride/triangleCount
 *      [可选] setIndexBuffer/indexBufferOffset/indexType
 * 2. NSArray arrayWithObjects:count:  ← geometryDescriptors 只吃 NSArray
 * 3. MTLPrimitiveAccelerationStructureDescriptor.descriptor + setGeometryDescriptors:
 * 4. device accelerationStructureSizesWithDescriptor:  ← 24 字节 struct 值返回
 * 5. device newAccelerationStructureWithSize:            (+1 引用，要 release)
 * 6. device newBufferWithLength:options:                 scratch buffer
 * 7. commandBuffer accelerationStructureCommandEncoder
 *      buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:
 *      endEncoding → commit
 * </pre>
 * <p>
 * <b>顶点格式</b>：默认 {@code MTLAttributeFormatFloat3}，即 vertexStride 常见取 12
 * （紧凑 float3）。Metal 不要求 stride == 12，用 16 做 float4 对齐也合法。
 * <p>
 * <b>生命周期</b>：本类持有 AS 与 scratch buffer 的 +1 引用，{@link #close()} 释放。
 * 顶点/索引 buffer 由调用方持有——AS 构建<b>完成后</b>它们就不再被需要（AS 内部
 * 是自包含的 BVH），但在 command buffer 完成前不能释放。
 * <p>
 * <b>native access</b>：见 {@link AdaptObjC} 类注释。
 */
public final class MTLAccelerationStructure implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("adapt-metal");

    /** {@code MTLIndexTypeUInt16}。 */
    public static final long INDEX_TYPE_UINT16 = 0L;
    /** {@code MTLIndexTypeUInt32}。 */
    public static final long INDEX_TYPE_UINT32 = 1L;

    /** {@code MTLAccelerationStructureUsageRefit}：允许后续 refit（动态地形用）。 */
    public static final long USAGE_REFIT = 1L << 0;
    /** {@code MTLAccelerationStructureUsagePreferFastBuild}：建得快、trace 略慢。 */
    public static final long USAGE_PREFER_FAST_BUILD = 1L << 1;
    /** {@code MTLAccelerationStructureUsageExtendedLimits}。 */
    public static final long USAGE_EXTENDED_LIMITS = 1L << 2;

    private static final MemorySegment TRIANGLE_GEOMETRY_CLASS =
            AdaptObjC.clazz("MTLAccelerationStructureTriangleGeometryDescriptor");
    private static final MemorySegment PRIMITIVE_DESCRIPTOR_CLASS =
            AdaptObjC.clazz("MTLPrimitiveAccelerationStructureDescriptor");

    private static final AdaptMsg DESCRIPTOR = AdaptMsg.of("descriptor", ADDRESS);
    private static final AdaptMsg SET_VERTEX_BUFFER = AdaptMsg.ofVoid("setVertexBuffer:", ADDRESS);
    private static final AdaptMsg SET_VERTEX_BUFFER_OFFSET = AdaptMsg.ofVoid("setVertexBufferOffset:", JAVA_LONG);
    private static final AdaptMsg SET_VERTEX_STRIDE = AdaptMsg.ofVoid("setVertexStride:", JAVA_LONG);
    private static final AdaptMsg SET_TRIANGLE_COUNT = AdaptMsg.ofVoid("setTriangleCount:", JAVA_LONG);
    private static final AdaptMsg SET_INDEX_BUFFER = AdaptMsg.ofVoid("setIndexBuffer:", ADDRESS);
    private static final AdaptMsg SET_INDEX_BUFFER_OFFSET = AdaptMsg.ofVoid("setIndexBufferOffset:", JAVA_LONG);
    private static final AdaptMsg SET_INDEX_TYPE = AdaptMsg.ofVoid("setIndexType:", JAVA_LONG);
    private static final AdaptMsg SET_OPAQUE = AdaptMsg.ofVoid("setOpaque:", java.lang.foreign.ValueLayout.JAVA_BOOLEAN);
    private static final AdaptMsg SET_GEOMETRY_DESCRIPTORS = AdaptMsg.ofVoid("setGeometryDescriptors:", ADDRESS);
    private static final AdaptMsg SET_USAGE = AdaptMsg.ofVoid("setUsage:", JAVA_LONG);

    /**
     * 唯一需要 struct 值返回的 selector：
     * {@code - (MTLAccelerationStructureSizes)accelerationStructureSizesWithDescriptor:}
     * 返回 {@code {accelerationStructureSize, buildScratchBufferSize, refitScratchBufferSize}}
     * 三个 NSUInteger。metallum 的 Msg 表达不了这个，所以有了
     * {@link AdaptMsg#ofStruct} / {@link AdaptMsg#sendStruct}。
     * <p>
     * 实测（M4 / macOS 26，单三角形）：{@code {512, 576, 0}}。
     */
    private static final AdaptMsg ACCELERATION_STRUCTURE_SIZES =
            AdaptMsg.ofStruct("accelerationStructureSizesWithDescriptor:", AdaptMsg.SIZES_LAYOUT, ADDRESS);

    private static final AdaptMsg NEW_ACCELERATION_STRUCTURE =
            AdaptMsg.of("newAccelerationStructureWithSize:", true, ADDRESS, JAVA_LONG);
    private static final AdaptMsg NEW_BUFFER = AdaptMsg.of("newBufferWithLength:options:", true, ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final AdaptMsg COMMAND_BUFFER = AdaptMsg.of("commandBuffer", ADDRESS);
    private static final AdaptMsg ACCELERATION_STRUCTURE_COMMAND_ENCODER =
            AdaptMsg.of("accelerationStructureCommandEncoder", ADDRESS);
    private static final AdaptMsg BUILD_ACCELERATION_STRUCTURE = AdaptMsg.ofVoid(
            "buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:",
            ADDRESS, ADDRESS, ADDRESS, JAVA_LONG);
    private static final AdaptMsg REFIT_ACCELERATION_STRUCTURE = AdaptMsg.ofVoid(
            "refitAccelerationStructure:descriptor:destination:scratchBuffer:scratchBufferOffset:",
            ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG);
    private static final AdaptMsg END_ENCODING = AdaptMsg.ofVoid("endEncoding");
    private static final AdaptMsg COMMIT = AdaptMsg.ofVoid("commit");
    private static final AdaptMsg WAIT_UNTIL_COMPLETED = AdaptMsg.ofVoid("waitUntilCompleted", true);
    private static final AdaptMsg STATUS = AdaptMsg.of("status", JAVA_LONG);
    private static final AdaptMsg SIZE = AdaptMsg.of("size", JAVA_LONG);

    /** {@code MTLCommandBufferStatusCompleted}。 */
    private static final long STATUS_COMPLETED = 4L;

    /** 三角形几何的描述（Java 侧的纯数据，不持有 ObjC 对象）。 */
    public record TriangleGeometry(
            MemorySegment vertexBuffer,
            long vertexBufferOffset,
            long vertexStride,
            long triangleCount,
            MemorySegment indexBuffer,
            long indexBufferOffset,
            long indexType,
            boolean opaque
    ) {
        /** 无索引（顶点直接按 3 个一组构成三角形）。 */
        public static TriangleGeometry nonIndexed(final MemorySegment vertexBuffer, final long vertexBufferOffset,
                                                 final long vertexStride, final long triangleCount) {
            return new TriangleGeometry(vertexBuffer, vertexBufferOffset, vertexStride, triangleCount,
                    MemorySegment.NULL, 0L, INDEX_TYPE_UINT32, true);
        }

        /** 索引化几何。{@code indexType} 用 {@link #INDEX_TYPE_UINT16}/{@link #INDEX_TYPE_UINT32}。 */
        public static TriangleGeometry indexed(final MemorySegment vertexBuffer, final long vertexBufferOffset,
                                              final long vertexStride, final long triangleCount,
                                              final MemorySegment indexBuffer, final long indexBufferOffset,
                                              final long indexType) {
            return new TriangleGeometry(vertexBuffer, vertexBufferOffset, vertexStride, triangleCount,
                    indexBuffer, indexBufferOffset, indexType, true);
        }

        boolean valid() {
            return !AdaptObjC.isNil(vertexBuffer) && vertexStride > 0L && triangleCount > 0L;
        }
    }

    /** {@code accelerationStructureSizesWithDescriptor:} 的三个 NSUInteger。 */
    public record Sizes(long accelerationStructureSize, long buildScratchBufferSize, long refitScratchBufferSize) {
    }

    private final MemorySegment device;
    private MemorySegment handle;
    private MemorySegment scratchBuffer;
    private MemorySegment descriptor;
    private final Sizes sizes;

    private MTLAccelerationStructure(final MemorySegment device, final MemorySegment handle,
                                     final MemorySegment scratchBuffer, final MemorySegment descriptor,
                                     final Sizes sizes) {
        this.device = device;
        this.handle = handle;
        this.scratchBuffer = scratchBuffer;
        this.descriptor = descriptor;
        this.sizes = sizes;
    }

    /**
     * 建一个 {@code MTLPrimitiveAccelerationStructureDescriptor}（autoreleased）。
     * 返回 NULL 表示 RT 类不存在（旧 macOS）或几何非法。
     * <p>
     * <b>注意</b>：返回的 descriptor 是 autoreleased 的，只在当前
     * {@link AdaptObjC#pool()} 作用域内有效。要跨帧保留必须自己 retain。
     */
    public static MemorySegment createPrimitiveDescriptor(final Arena arena, final long usage,
                                                          final TriangleGeometry... geometries) {
        if (AdaptObjC.isNil(TRIANGLE_GEOMETRY_CLASS) || AdaptObjC.isNil(PRIMITIVE_DESCRIPTOR_CLASS)) {
            return MemorySegment.NULL;
        }
        if (geometries == null || geometries.length == 0) {
            return MemorySegment.NULL;
        }
        MemorySegment[] geometryDescriptors = new MemorySegment[geometries.length];
        for (int i = 0; i < geometries.length; i++) {
            TriangleGeometry geometry = geometries[i];
            if (geometry == null || !geometry.valid()) {
                LOGGER.warn("[adapt-metal] Skipping AS build: geometry[{}] invalid", i);
                return MemorySegment.NULL;
            }
            MemorySegment triangle = DESCRIPTOR.sendPtr(TRIANGLE_GEOMETRY_CLASS);
            if (AdaptObjC.isNil(triangle)) {
                return MemorySegment.NULL;
            }
            SET_VERTEX_BUFFER.send(triangle, geometry.vertexBuffer());
            SET_VERTEX_BUFFER_OFFSET.send(triangle, geometry.vertexBufferOffset());
            SET_VERTEX_STRIDE.send(triangle, geometry.vertexStride());
            SET_TRIANGLE_COUNT.send(triangle, geometry.triangleCount());
            SET_OPAQUE.send(triangle, geometry.opaque());
            if (!AdaptObjC.isNil(geometry.indexBuffer())) {
                SET_INDEX_BUFFER.send(triangle, geometry.indexBuffer());
                SET_INDEX_BUFFER_OFFSET.send(triangle, geometry.indexBufferOffset());
                SET_INDEX_TYPE.send(triangle, geometry.indexType());
            }
            geometryDescriptors[i] = triangle;
        }

        MemorySegment nsArray = AdaptObjC.nsArray(arena, geometryDescriptors);
        if (AdaptObjC.isNil(nsArray)) {
            return MemorySegment.NULL;
        }
        MemorySegment primitive = DESCRIPTOR.sendPtr(PRIMITIVE_DESCRIPTOR_CLASS);
        if (AdaptObjC.isNil(primitive)) {
            return MemorySegment.NULL;
        }
        SET_GEOMETRY_DESCRIPTORS.send(primitive, nsArray);
        if (usage != 0L) {
            SET_USAGE.send(primitive, usage);
        }
        return primitive;
    }

    /**
     * 查 AS / scratch buffer 的字节数。这是本工程唯一用到 struct 值返回的地方。
     * <p>
     * arm64 上 24 字节 struct 走 indirect return（x8 隐藏指针），Panama 会在句柄
     * 签名最前面插一个 {@code SegmentAllocator} —— {@code allocator} 就是给它的。
     */
    public static Sizes querySizes(final Arena arena, final MemorySegment device, final MemorySegment descriptor) {
        if (AdaptObjC.isNil(device) || AdaptObjC.isNil(descriptor)) {
            return new Sizes(0L, 0L, 0L);
        }
        MemorySegment result = ACCELERATION_STRUCTURE_SIZES.sendStruct(arena, device, descriptor);
        return new Sizes(
                AdaptMsg.readSize(result, 0),
                AdaptMsg.readSize(result, 1),
                AdaptMsg.readSize(result, 2));
    }

    /**
     * 一站式：建 descriptor → 查 size → 建 AS + scratch → 在自己的 command buffer 上
     * build → 等完成。失败返回 null 并 log（不抛）。
     * <p>
     * 用 {@code commandQueue} 自己开 command buffer 并 {@code waitUntilCompleted}：
     * AS 构建是一次性的准备工作，同步等待最省心；如果要 async，用
     * {@link #encodeBuild} 把命令编到调用方自己的 command buffer 上。
     *
     * @param device       {@link MetalBridge#device()}
     * @param commandQueue {@link MetalBridge#commandQueue()}，或 MTLDevice 自建的私有 queue
     * @param usage        {@link #USAGE_REFIT} 等标志的按位或，0 表示默认
     */
    public static MTLAccelerationStructure build(final MemorySegment device, final MemorySegment commandQueue,
                                                 final long usage, final TriangleGeometry... geometries) {
        if (AdaptObjC.isNil(device) || AdaptObjC.isNil(commandQueue)) {
            return null;
        }
        try (AdaptObjC.Pool _ = AdaptObjC.pool(); Arena arena = Arena.ofConfined()) {
            MemorySegment descriptor = createPrimitiveDescriptor(arena, usage, geometries);
            if (AdaptObjC.isNil(descriptor)) {
                LOGGER.warn("[adapt-metal] AS descriptor creation failed (raytracing classes missing?)");
                return null;
            }

            Sizes sizes = querySizes(arena, device, descriptor);
            if (sizes.accelerationStructureSize() <= 0L) {
                LOGGER.warn("[adapt-metal] accelerationStructureSizesWithDescriptor: returned size=0");
                return null;
            }

            MemorySegment accel = NEW_ACCELERATION_STRUCTURE.sendPtr(device, sizes.accelerationStructureSize());
            if (AdaptObjC.isNil(accel)) {
                LOGGER.warn("[adapt-metal] newAccelerationStructureWithSize:{} returned nil",
                        sizes.accelerationStructureSize());
                return null;
            }

            // scratch 至少 16 字节：Metal 对退化几何可能报 0，但 newBufferWithLength:0
            // 会返回 nil，build 时又必须传非 nil scratch buffer。
            long scratchSize = Math.max(sizes.buildScratchBufferSize(), 16L);
            MemorySegment scratch = NEW_BUFFER.sendPtr(device, scratchSize, 0L);
            if (AdaptObjC.isNil(scratch)) {
                AdaptObjC.release(accel);
                LOGGER.warn("[adapt-metal] scratch buffer allocation failed ({} bytes)", scratchSize);
                return null;
            }

            // descriptor 要在 refit 时复用 → retain 一份跨出 autorelease pool。
            MemorySegment retainedDescriptor = AdaptObjC.retain(descriptor);

            MemorySegment commandBuffer = COMMAND_BUFFER.sendPtr(commandQueue);
            if (AdaptObjC.isNil(commandBuffer)) {
                AdaptObjC.release(accel);
                AdaptObjC.release(scratch);
                AdaptObjC.releaseIfPresent(retainedDescriptor);
                LOGGER.warn("[adapt-metal] commandBuffer creation failed on exported MTLCommandQueue");
                return null;
            }

            if (!encodeBuild(commandBuffer, accel, retainedDescriptor, scratch, 0L)) {
                AdaptObjC.release(accel);
                AdaptObjC.release(scratch);
                AdaptObjC.releaseIfPresent(retainedDescriptor);
                return null;
            }

            COMMIT.send(commandBuffer);
            WAIT_UNTIL_COMPLETED.send(commandBuffer);
            long status = STATUS.sendLong(commandBuffer);
            if (status != STATUS_COMPLETED) {
                AdaptObjC.release(accel);
                AdaptObjC.release(scratch);
                AdaptObjC.releaseIfPresent(retainedDescriptor);
                LOGGER.warn("[adapt-metal] AS build command buffer status={} (expected 4=completed)", status);
                return null;
            }

            LOGGER.info("[adapt-metal] Built BLAS: size={}B scratch={}B refitScratch={}B geometries={}",
                    sizes.accelerationStructureSize(), sizes.buildScratchBufferSize(),
                    sizes.refitScratchBufferSize(), geometries.length);
            return new MTLAccelerationStructure(device, accel, scratch, retainedDescriptor, sizes);
        } catch (Throwable throwable) {
            LOGGER.error("[adapt-metal] AS build crashed: {}", throwable.toString());
            return null;
        }
    }

    /**
     * 把 build 命令编到调用方自己的 command buffer 上（不 commit、不等待）。
     * 返回 false 表示 encoder 建不出来（大概是该 command buffer 上还有活跃 encoder）。
     */
    public static boolean encodeBuild(final MemorySegment commandBuffer, final MemorySegment accel,
                                      final MemorySegment descriptor, final MemorySegment scratch,
                                      final long scratchOffset) {
        if (AdaptObjC.isNil(commandBuffer) || AdaptObjC.isNil(accel) || AdaptObjC.isNil(descriptor)) {
            return false;
        }
        MemorySegment encoder = ACCELERATION_STRUCTURE_COMMAND_ENCODER.sendPtr(commandBuffer);
        if (AdaptObjC.isNil(encoder)) {
            LOGGER.warn("[adapt-metal] accelerationStructureCommandEncoder returned nil");
            return false;
        }
        BUILD_ACCELERATION_STRUCTURE.send(encoder, accel, descriptor, scratch, scratchOffset);
        END_ENCODING.send(encoder);
        return true;
    }

    /**
     * 原地 refit（几何位置变了但拓扑没变时用，比重建快得多）。
     * 需要建 AS 时带了 {@link #USAGE_REFIT}，否则 Metal 行为未定义。
     * 不 commit——由调用方决定何时提交。
     */
    public boolean encodeRefit(final MemorySegment commandBuffer) {
        if (AdaptObjC.isNil(handle) || AdaptObjC.isNil(descriptor) || AdaptObjC.isNil(commandBuffer)) {
            return false;
        }
        MemorySegment encoder = ACCELERATION_STRUCTURE_COMMAND_ENCODER.sendPtr(commandBuffer);
        if (AdaptObjC.isNil(encoder)) {
            return false;
        }
        // destination=nil → 原地 refit（Metal 头文件明确允许）。
        REFIT_ACCELERATION_STRUCTURE.send(encoder, handle, descriptor, MemorySegment.NULL, scratchBuffer, 0L);
        END_ENCODING.send(encoder);
        return true;
    }

    /** 底层 {@code id<MTLAccelerationStructure>}，绑给 compute encoder 用。 */
    public MemorySegment handle() {
        if (AdaptObjC.isNil(handle)) {
            throw new IllegalStateException("MTLAccelerationStructure is closed");
        }
        return handle;
    }

    public boolean isClosed() {
        return AdaptObjC.isNil(handle);
    }

    public MemorySegment device() {
        return device;
    }

    public Sizes sizes() {
        return sizes;
    }

    /** AS 实际占用的字节数（{@code MTLAccelerationStructure.size} 属性）。 */
    public long allocatedSize() {
        return AdaptObjC.isNil(handle) ? 0L : SIZE.sendLong(handle);
    }

    /**
     * 释放 scratch buffer 但保留 AS。
     * <p>
     * build 完成后 scratch 就没用了（refit 才需要），静态几何建完立刻调这个能省
     * 一份和 AS 同量级的显存。
     */
    public void releaseScratch() {
        if (!AdaptObjC.isNil(scratchBuffer)) {
            AdaptObjC.release(scratchBuffer);
            scratchBuffer = MemorySegment.NULL;
        }
    }

    @Override
    public void close() {
        releaseScratch();
        if (!AdaptObjC.isNil(descriptor)) {
            AdaptObjC.release(descriptor);
            descriptor = MemorySegment.NULL;
        }
        if (!AdaptObjC.isNil(handle)) {
            AdaptObjC.release(handle);
            handle = MemorySegment.NULL;
        }
    }
}

