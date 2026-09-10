package com.ylmac.adapt.metal;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@code objc_msgSend} 的人体工学包装（对照 metallum {@code com.metallum.objc.Msg} 改写）。
 * <p>
 * 与 metallum 版本的三个差异：
 * <ol>
 *   <li><b>struct 返回值</b>（{@link #ofStruct}/{@link #sendStruct}）。
 *       {@code accelerationStructureSizesWithDescriptor:} 返回
 *       {@code MTLAccelerationStructureSizes} = 3×NSUInteger 的值类型 struct，
 *       metallum 的 Msg 只能表达 {@code ValueLayout} 返回值，完全表达不了。
 *       Panama 对 struct 返回的 downcall 会在句柄类型最前面插一个
 *       {@code SegmentAllocator} 参数（实测句柄类型：
 *       {@code (SegmentAllocator,MemorySegment,MemorySegment,MemorySegment)MemorySegment}），
 *       所以 sendStruct 必须显式传 arena。</li>
 *   <li><b>struct 传参</b>（{@link #sendSizes}）。{@code dispatchThreads:threadsPerThreadgroup:}
 *       吃两个 {@code MTLSize}（也是 3×NSUInteger 值类型），不是指针。</li>
 *   <li><b>nil 短路</b>。{@code handle} 为 null（非 macOS / 运行时不可用）时所有 send
 *       静默返回，指针返回值给 {@code MemorySegment.NULL}。metallum 会 NPE。</li>
 * </ol>
 * arm64 ABI 说明：3×8=24 字节 > 16 字节，走 indirect return（x8 隐藏指针）。
 * Panama 会自动处理，不需要手动切 {@code objc_msgSend_stret} —— 而且 arm64 上
 * {@code objc_msgSend_stret} 根本不存在（只有 x86_64 有）。已实测 24 字节 struct
 * 直接用 {@code objc_msgSend} 拿到正确的 {512, 576, 0}。
 */
public record AdaptMsg(String name, MemorySegment sel, MethodHandle handle) {

    /** {@code MTLAccelerationStructureSizes} / {@code MTLSize} 的共同布局：3 个 NSUInteger。 */
    public static final MemoryLayout SIZES_LAYOUT = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG, JAVA_LONG);

    // ── 工厂 ────────────────────────────────────────────────────────────────

    public static AdaptMsg ofVoid(final String selector, final MemoryLayout... args) {
        return ofVoid(selector, false, args);
    }

    public static AdaptMsg ofVoid(final String selector, final boolean blocking, final MemoryLayout... args) {
        return create(selector, FunctionDescriptor.ofVoid(prepend(args)), blocking);
    }

    public static AdaptMsg of(final String selector, final MemoryLayout returnLayout, final MemoryLayout... args) {
        return of(selector, false, returnLayout, args);
    }

    public static AdaptMsg of(final String selector, final boolean blocking,
                              final MemoryLayout returnLayout, final MemoryLayout... args) {
        return create(selector, FunctionDescriptor.of(returnLayout, prepend(args)), blocking);
    }

    /**
     * struct 值返回的 msgSend。{@code returnLayout} 必须是
     * {@link MemoryLayout#structLayout}，用 {@link #sendStruct} 调用。
     * <p>
     * 这是为 {@code - (MTLAccelerationStructureSizes)accelerationStructureSizesWithDescriptor:}
     * 存在的：{@code ofStruct("accelerationStructureSizesWithDescriptor:", SIZES_LAYOUT, ADDRESS)}
     * 展开成 {@code FunctionDescriptor.of(structLayout(JAVA_LONG,JAVA_LONG,JAVA_LONG), ADDRESS, ADDRESS, ADDRESS)}
     * ——前两个 ADDRESS 是 self + sel。
     * <p>
     * 用 blocking（非 critical）句柄：struct 返回需要 Panama 分配返回缓冲，
     * 而且这个调用只在建 acceleration structure 时走一次，不是热路径。
     */
    public static AdaptMsg ofStruct(final String selector, final MemoryLayout returnLayout, final MemoryLayout... args) {
        return create(selector, FunctionDescriptor.of(returnLayout, prepend(args)), true);
    }

    private static AdaptMsg create(final String selector, final FunctionDescriptor descriptor, final boolean blocking) {
        MethodHandle handle = blocking
                ? AdaptObjC.msgSend(descriptor)
                : AdaptObjC.msgSendCritical(descriptor);
        return new AdaptMsg(selector, AdaptObjC.selector(selector), handle);
    }

    /** 在参数表前插 {@code (id self, SEL _cmd)} —— ObjC 方法的两个隐藏参数。 */
    private static MemoryLayout[] prepend(final MemoryLayout[] args) {
        MemoryLayout[] full = new MemoryLayout[args.length + 2];
        full[0] = ADDRESS;
        full[1] = ADDRESS;
        System.arraycopy(args, 0, full, 2, args.length);
        return full;
    }

    /** 桥不可用（非 macOS）时为 false，所有 send 变成 no-op。 */
    public boolean usable() {
        return handle != null;
    }

    // ── void 返回 ───────────────────────────────────────────────────────────

    public void send(final MemorySegment self) {
        if (handle == null) {
            return;
        }
        try {
            handle.invokeExact(self, sel);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public void send(final MemorySegment self, final MemorySegment a) {
        if (handle == null) {
            return;
        }
        try {
            handle.invokeExact(self, sel, AdaptObjC.orNil(a));
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public void send(final MemorySegment self, final long a) {
        if (handle == null) {
            return;
        }
        try {
            handle.invokeExact(self, sel, a);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public void send(final MemorySegment self, final boolean a) {
        if (handle == null) {
            return;
        }
        try {
            handle.invokeExact(self, sel, a);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public void send(final MemorySegment self, final long a, final long b) {
        if (handle == null) {
            return;
        }
        try {
            handle.invokeExact(self, sel, a, b);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public void send(final MemorySegment self, final MemorySegment a, final long b) {
        if (handle == null) {
            return;
        }
        try {
            handle.invokeExact(self, sel, AdaptObjC.orNil(a), b);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public void send(final MemorySegment self, final MemorySegment a, final long b, final long c) {
        if (handle == null) {
            return;
        }
        try {
            handle.invokeExact(self, sel, AdaptObjC.orNil(a), b, c);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public void send(final MemorySegment self, final MemorySegment a, final MemorySegment b,
                     final MemorySegment c, final long d) {
        if (handle == null) {
            return;
        }
        try {
            handle.invokeExact(self, sel, AdaptObjC.orNil(a), AdaptObjC.orNil(b), AdaptObjC.orNil(c), d);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public void send(final MemorySegment self, final MemorySegment a, final MemorySegment b,
                     final MemorySegment c, final MemorySegment d, final long e) {
        if (handle == null) {
            return;
        }
        try {
            handle.invokeExact(self, sel, AdaptObjC.orNil(a), AdaptObjC.orNil(b),
                    AdaptObjC.orNil(c), AdaptObjC.orNil(d), e);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    // ── 指针返回 ────────────────────────────────────────────────────────────

    public MemorySegment sendPtr(final MemorySegment self) {
        if (handle == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) handle.invokeExact(self, sel);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public MemorySegment sendPtr(final MemorySegment self, final MemorySegment a) {
        if (handle == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) handle.invokeExact(self, sel, AdaptObjC.orNil(a));
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public MemorySegment sendPtr(final MemorySegment self, final MemorySegment a, final MemorySegment b) {
        if (handle == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) handle.invokeExact(self, sel, AdaptObjC.orNil(a), AdaptObjC.orNil(b));
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public MemorySegment sendPtr(final MemorySegment self, final MemorySegment a,
                                 final MemorySegment b, final MemorySegment c) {
        if (handle == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) handle.invokeExact(self, sel,
                    AdaptObjC.orNil(a), AdaptObjC.orNil(b), AdaptObjC.orNil(c));
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public MemorySegment sendPtr(final MemorySegment self, final MemorySegment a, final long b) {
        if (handle == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) handle.invokeExact(self, sel, AdaptObjC.orNil(a), b);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public MemorySegment sendPtr(final MemorySegment self, final long a) {
        if (handle == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) handle.invokeExact(self, sel, a);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public MemorySegment sendPtr(final MemorySegment self, final long a, final long b) {
        if (handle == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) handle.invokeExact(self, sel, a, b);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    // ── 标量返回 ────────────────────────────────────────────────────────────

    public long sendLong(final MemorySegment self) {
        if (handle == null) {
            return 0L;
        }
        try {
            return (long) handle.invokeExact(self, sel);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public long sendLong(final MemorySegment self, final long a) {
        if (handle == null) {
            return 0L;
        }
        try {
            return (long) handle.invokeExact(self, sel, a);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public boolean sendBool(final MemorySegment self) {
        if (handle == null) {
            return false;
        }
        try {
            return (boolean) handle.invokeExact(self, sel);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public boolean sendBool(final MemorySegment self, final long a) {
        if (handle == null) {
            return false;
        }
        try {
            return (boolean) handle.invokeExact(self, sel, a);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public boolean sendBool(final MemorySegment self, final long a, final long b) {
        if (handle == null) {
            return false;
        }
        try {
            return (boolean) handle.invokeExact(self, sel, a, b);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    public double sendDouble(final MemorySegment self) {
        if (handle == null) {
            return 0.0;
        }
        try {
            return (double) handle.invokeExact(self, sel);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    // ── struct 值返回 / 传参 ────────────────────────────────────────────────

    /**
     * struct 值返回的调用（本类存在的理由）。返回的 {@link MemorySegment} 由
     * {@code allocator} 分配、生命周期跟 arena 绑定，读法：
     * {@code seg.get(JAVA_LONG, 0/8/16)}。
     * <p>
     * 必须配 {@link #ofStruct} 建的实例。注意 Panama 把 allocator 塞在
     * self/sel <b>之前</b>，所以 invokeExact 的参数顺序是
     * {@code (allocator, self, sel, arg)}。
     */
    public MemorySegment sendStruct(final SegmentAllocator allocator, final MemorySegment self, final MemorySegment a) {
        if (handle == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) handle.invokeExact(allocator, self, sel, AdaptObjC.orNil(a));
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    /** 无参 struct 返回（例如未来的 {@code MTLSize} getter）。 */
    public MemorySegment sendStruct(final SegmentAllocator allocator, final MemorySegment self) {
        if (handle == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) handle.invokeExact(allocator, self, sel);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    /**
     * 两个 {@code MTLSize} 值传参（{@code dispatchThreads:threadsPerThreadgroup:} /
     * {@code dispatchThreadgroups:threadsPerThreadgroup:}）。
     * <p>
     * 传的 {@code MemorySegment} 必须是 {@link #SIZES_LAYOUT} 布局的 24 字节段
     * （用 {@link #allocateSize} 建），Panama 按值展开进寄存器/栈——不是传指针。
     */
    public void sendSizes(final MemorySegment self, final MemorySegment a, final MemorySegment b) {
        if (handle == null) {
            return;
        }
        try {
            handle.invokeExact(self, sel, a, b);
        } catch (Throwable throwable) {
            throw fail(throwable);
        }
    }

    /** 分配并填一个 {@code MTLSize}。 */
    public static MemorySegment allocateSize(final SegmentAllocator allocator,
                                             final long width, final long height, final long depth) {
        MemorySegment size = allocator.allocate(SIZES_LAYOUT);
        size.set(JAVA_LONG, 0L, width);
        size.set(JAVA_LONG, 8L, height);
        size.set(JAVA_LONG, 16L, depth);
        return size;
    }

    /** 读 {@link #SIZES_LAYOUT} 的第 {@code index} 个 NSUInteger（0/1/2）。 */
    public static long readSize(final MemorySegment struct, final int index) {
        return AdaptObjC.isNil(struct) ? 0L : struct.get(JAVA_LONG, index * 8L);
    }

    private RuntimeException fail(final Throwable throwable) {
        return new IllegalStateException("objc_msgSend failed: " + name, throwable);
    }
}

