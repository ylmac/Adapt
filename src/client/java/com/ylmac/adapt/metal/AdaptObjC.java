package com.ylmac.adapt.metal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * Objective-C 运行时的 Panama FFM 桥（对照 metallum {@code com.metallum.objc.ObjC} 改写）。
 * <p>
 * 与 metallum 的唯一结构性差异：<b>整个静态初始化被 try/catch 包住</b>。metallum 只跑在
 * macOS + Metal 后端，可以让 {@code libraryLookup} 直接抛；Adapt 是通用客户端 mod，
 * Windows/Linux 玩家加载到本类时 {@code SymbolLookup.libraryLookup} 会抛
 * {@code IllegalArgumentException} → 变成 {@code ExceptionInInitializerError} →
 * 之后每次访问都是 {@code NoClassDefFoundError}，会把渲染线程整条打断。
 * 所以这里把失败折叠成 {@link #available()} == false，所有 API 在不可用时返回 nil/0。
 * <p>
 * 路径 {@code /usr/lib/libobjc.A.dylib} 在磁盘上<b>不存在</b>（macOS 11+ 起系统库全部
 * 塞进 dyld shared cache，{@code ls} 会报 No such file），但 {@code dlopen} 仍按该路径
 * 命中缓存条目——实测 {@code objc_msgSend} 符号可正常解析。不要"修正"成真实文件路径。
 * <p>
 * <b>native access</b>：JDK 26 下所有 downcall 会打印一次 restricted-method 警告。
 * 正式发布需要 JVM 参数 {@code --enable-native-access=ALL-UNNAMED}，或在 mod JAR 的
 * {@code META-INF/MANIFEST.MF} 里加 {@code Enable-Native-Access: ALL-UNNAMED}。
 * JDK 27 起该警告会变成硬错误。
 */
public final class AdaptObjC {

    /** 所有 downcall 共用的 native linker。不可用时为 null。 */
    public static final Linker LINKER;

    public static final SymbolLookup FOUNDATION;
    public static final SymbolLookup METAL;
    public static final SymbolLookup QUARTZ_CORE;

    private static final MemorySegment MSG_SEND;
    private static final MethodHandle OBJC_GET_CLASS;
    private static final MethodHandle SEL_REGISTER_NAME;
    private static final MethodHandle POOL_PUSH;
    private static final MethodHandle POOL_POP;

    /** selector 缓存：{@code sel_registerName} 本身有全局锁，热路径每帧调用几百次不能重复付这个代价。 */
    private static final Map<String, MemorySegment> SELECTORS = new ConcurrentHashMap<>();

    private static final boolean AVAILABLE;
    private static final String UNAVAILABLE_REASON;

    static {
        Linker linker = null;
        SymbolLookup foundation = null;
        SymbolLookup metal = null;
        SymbolLookup quartz = null;
        MemorySegment msgSend = null;
        MethodHandle getClass = null;
        MethodHandle selRegister = null;
        MethodHandle poolPush = null;
        MethodHandle poolPop = null;
        boolean ok = false;
        String reason = "not initialised";
        try {
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
                reason = "not macOS (os.name=" + System.getProperty("os.name") + ")";
            } else {
                linker = Linker.nativeLinker();
                Arena global = Arena.global();
                SymbolLookup runtime = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", global);
                foundation = SymbolLookup.libraryLookup(
                        "/System/Library/Frameworks/Foundation.framework/Foundation", global);
                metal = SymbolLookup.libraryLookup(
                        "/System/Library/Frameworks/Metal.framework/Metal", global);
                quartz = SymbolLookup.libraryLookup(
                        "/System/Library/Frameworks/QuartzCore.framework/QuartzCore", global);

                msgSend = runtime.findOrThrow("objc_msgSend");
                getClass = linker.downcallHandle(runtime.findOrThrow("objc_getClass"),
                        FunctionDescriptor.of(ADDRESS, ADDRESS));
                selRegister = linker.downcallHandle(runtime.findOrThrow("sel_registerName"),
                        FunctionDescriptor.of(ADDRESS, ADDRESS));
                poolPush = linker.downcallHandle(runtime.findOrThrow("objc_autoreleasePoolPush"),
                        FunctionDescriptor.of(ADDRESS));
                poolPop = linker.downcallHandle(runtime.findOrThrow("objc_autoreleasePoolPop"),
                        FunctionDescriptor.ofVoid(ADDRESS));
                ok = true;
                reason = "";
            }
        } catch (Throwable throwable) {
            reason = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
        LINKER = linker;
        FOUNDATION = foundation;
        METAL = metal;
        QUARTZ_CORE = quartz;
        MSG_SEND = msgSend;
        OBJC_GET_CLASS = getClass;
        SEL_REGISTER_NAME = selRegister;
        POOL_PUSH = poolPush;
        POOL_POP = poolPop;
        AVAILABLE = ok;
        UNAVAILABLE_REASON = reason;
    }

    private AdaptObjC() {
    }

    /** ObjC 运行时是否可用。任何 Metal 调用前必须先查这个。 */
    public static boolean available() {
        return AVAILABLE;
    }

    /** 不可用的原因（可用时为空串），只用于日志。 */
    public static String unavailableReason() {
        return UNAVAILABLE_REASON;
    }

    public static boolean isNil(final MemorySegment segment) {
        return segment == null || segment.address() == 0L;
    }

    public static MemorySegment orNil(final MemorySegment segment) {
        return isNil(segment) ? MemorySegment.NULL : segment;
    }

    // ── objc_msgSend downcall 工厂 ──────────────────────────────────────────
    // critical(false)：跳过 Java→native 线程状态切换，代价是这段时间 GC 无法在此
    // 线程停顿。只能给"确定不阻塞"的调用用（encoder setter / dispatch）。编译
    // shader、创建 pipeline、waitUntilCompleted 必须走 msgSend() 普通句柄。

    static MethodHandle msgSend(final FunctionDescriptor descriptor) {
        return AVAILABLE ? LINKER.downcallHandle(MSG_SEND, descriptor) : null;
    }

    static MethodHandle msgSendCritical(final FunctionDescriptor descriptor) {
        return AVAILABLE ? LINKER.downcallHandle(MSG_SEND, descriptor, Linker.Option.critical(false)) : null;
    }

    /**
     * 注册/查找 selector。结果永久缓存（selector 在进程生命周期内不会失效）。
     * 不可用时返回 {@link MemorySegment#NULL}，配合 {@link AdaptMsg} 的短路检查
     * 保证不会真的发出消息。
     */
    public static MemorySegment selector(final String name) {
        if (!AVAILABLE) {
            return MemorySegment.NULL;
        }
        return SELECTORS.computeIfAbsent(name, key -> {
            try (Arena arena = Arena.ofConfined()) {
                return (MemorySegment) SEL_REGISTER_NAME.invokeExact(arena.allocateFrom(key));
            } catch (Throwable throwable) {
                throw new AssertionError("sel_registerName failed for " + key, throwable);
            }
        });
    }

    /**
     * 查 ObjC 类对象。metallum 版本在找不到时抛异常；这里返回 NULL，因为 RT 相关的
     * {@code MTLPrimitiveAccelerationStructureDescriptor} 等类在旧 macOS 上确实不存在，
     * 调用方需要能优雅降级而不是崩。
     */
    public static MemorySegment clazz(final String name) {
        if (!AVAILABLE) {
            return MemorySegment.NULL;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cls = (MemorySegment) OBJC_GET_CLASS.invokeExact(arena.allocateFrom(name));
            return orNil(cls);
        } catch (Throwable throwable) {
            return MemorySegment.NULL;
        }
    }

    public static MemorySegment autoreleasePoolPush() {
        if (!AVAILABLE) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) POOL_PUSH.invokeExact();
        } catch (Throwable throwable) {
            throw new AssertionError(throwable);
        }
    }

    public static void autoreleasePoolPop(final MemorySegment pool) {
        if (!AVAILABLE || isNil(pool)) {
            return;
        }
        try {
            POOL_POP.invokeExact(pool);
        } catch (Throwable throwable) {
            throw new AssertionError(throwable);
        }
    }

    /**
     * try-with-resources 形式的 autorelease pool。
     * <p>
     * Metal 的 {@code descriptor} / {@code commandBuffer} / {@code computeCommandEncoder}
     * 都返回 autoreleased 对象。Java 线程没有 AppKit 那种 per-frame 主循环 pool，
     * 不显式 push/pop 会让这些对象一直挂在最外层 pool 上直到进程退出 —— 每帧建一次
     * command buffer 就是每帧泄漏一个。
     */
    public static Pool pool() {
        return new Pool(autoreleasePoolPush());
    }

    /** {@link #pool()} 的句柄。close 幂等。 */
    public static final class Pool implements AutoCloseable {
        private final MemorySegment handle;
        private boolean closed;

        private Pool(final MemorySegment handle) {
            this.handle = handle;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                autoreleasePoolPop(handle);
            }
        }
    }

    // ── 引用计数 ────────────────────────────────────────────────────────────
    // 这两个用 AdaptMsg 懒初始化，避免类初始化顺序依赖（AdaptMsg 的静态字段又要用
    // AdaptObjC.selector）。retain/release 是 hot path，critical(false)。

    public static MemorySegment retain(final MemorySegment object) {
        if (isNil(object)) {
            return MemorySegment.NULL;
        }
        return Lazy.RETAIN.sendPtr(object);
    }

    public static void release(final MemorySegment object) {
        if (isNil(object)) {
            return;
        }
        Lazy.RELEASE.send(object);
    }

    /** 若非 nil 则 release。清理路径上到处要用，抽出来免得每处都写 if。 */
    public static void releaseIfPresent(final MemorySegment object) {
        if (!isNil(object)) {
            Lazy.RELEASE.send(object);
        }
    }

    // ── NSString 互转 ───────────────────────────────────────────────────────

    /** Java String → NSString。返回的对象<b>已 retain</b>（alloc/init），调用方负责 release。 */
    public static MemorySegment nsString(final String value) {
        if (!AVAILABLE) {
            return MemorySegment.NULL;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment alloc = Lazy.ALLOC.sendPtr(Lazy.NSSTRING);
            return Lazy.INIT_WITH_UTF8.sendPtr(alloc, arena.allocateFrom(value));
        }
    }

    /** NSString → Java String。nil 安全，返回空串。 */
    public static String javaString(final MemorySegment nsString) {
        if (isNil(nsString)) {
            return "";
        }
        MemorySegment utf8 = Lazy.UTF8_STRING.sendPtr(nsString);
        // reinterpret(Long.MAX_VALUE)：C 字符串长度未知，getString 自己找 NUL。
        return isNil(utf8) ? "" : utf8.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /** {@code NSError.localizedDescription}。传入 {@code Arena.allocate(ADDRESS)} 出参地址。 */
    public static String errorDescription(final MemorySegment errorOut) {
        if (isNil(errorOut)) {
            return "unknown error";
        }
        MemorySegment error = errorOut.get(ADDRESS, 0L);
        return isNil(error) ? "unknown error" : javaString(Lazy.LOCALIZED_DESCRIPTION.sendPtr(error));
    }

    /**
     * 建 {@code NSArray}（autoreleased，不需要 release）。
     * <p>
     * {@code MTLPrimitiveAccelerationStructureDescriptor.geometryDescriptors} 只接受
     * NSArray，没有别的入口，所以 RT 路径必须能从 Java 侧造 NSArray。
     */
    public static MemorySegment nsArray(final Arena arena, final MemorySegment... objects) {
        if (!AVAILABLE || objects.length == 0) {
            return MemorySegment.NULL;
        }
        MemorySegment storage = arena.allocate(ADDRESS, objects.length);
        for (int i = 0; i < objects.length; i++) {
            storage.setAtIndex(ADDRESS, i, orNil(objects[i]));
        }
        return Lazy.ARRAY_WITH_OBJECTS.sendPtr(Lazy.NSARRAY, storage, objects.length);
    }

    /**
     * 把 native 指针包成 ByteBuffer（读回 {@code MTLBuffer.contents} 用）。
     * {@code MemorySegment.ofAddress} 拿到的是零长段，必须 reinterpret 才能读写。
     */
    public static java.nio.ByteBuffer byteBufferView(final MemorySegment pointer, final long byteSize) {
        if (isNil(pointer) || byteSize < 0L) {
            throw new IllegalArgumentException("byteBufferView: pointer=" + pointer + " size=" + byteSize);
        }
        return MemorySegment.ofAddress(pointer.address()).reinterpret(byteSize).asByteBuffer();
    }

    /** 惰性持有的公共 selector / 类对象。分离出来打断 AdaptObjC ↔ AdaptMsg 的初始化环。 */
    private static final class Lazy {
        static final AdaptMsg RETAIN = AdaptMsg.of("retain", ADDRESS);
        static final AdaptMsg RELEASE = AdaptMsg.ofVoid("release");
        static final AdaptMsg ALLOC = AdaptMsg.of("alloc", ADDRESS);
        static final AdaptMsg INIT_WITH_UTF8 = AdaptMsg.of("initWithUTF8String:", ADDRESS, ADDRESS);
        static final AdaptMsg UTF8_STRING = AdaptMsg.of("UTF8String", ADDRESS);
        static final AdaptMsg LOCALIZED_DESCRIPTION = AdaptMsg.of("localizedDescription", ADDRESS);
        static final AdaptMsg ARRAY_WITH_OBJECTS = AdaptMsg.of("arrayWithObjects:count:",
                ADDRESS, ADDRESS, java.lang.foreign.ValueLayout.JAVA_LONG);
        static final MemorySegment NSSTRING = clazz("NSString");
        static final MemorySegment NSARRAY = clazz("NSArray");

        private Lazy() {
        }
    }
}

