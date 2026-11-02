package com.ylmac.adapt.render.profile;

/**
 * Lightweight per-frame timing profiler.
 * begin/end 记录各段耗时；周期性日志已按需求移除
 * （模糊性能分析完成，不再需要 [FrameProfiler] 输出）。
 * 若需再次分析，在 tick 里重新加回 report 即可。
 */
public final class FrameProfiler {

    private static final int SECTION_COUNT = 12;
    private static final String[] NAMES = new String[SECTION_COUNT];
    private static final long[] START = new long[SECTION_COUNT];
    private static final long[] ACCUM = new long[SECTION_COUNT];
    private static boolean initialized;

    public static final int
        TICK_ANIM      = 0,
        PUMP           = 1,
        COMPOSITE      = 2,
        OVERLAY        = 3,
        DAWN_LYRIC     = 4,
        FLUID_BG       = 5,
        EDGE_BLUR      = 6,
        PRESENT        = 7,
        BLUR_CAPTURE   = 8,
        BLUR_FILTER    = 9,
        BLUR_DRAW      = 10,
        BLUR_MISC      = 11;

    private static synchronized void ensureInit() {
        if (initialized) return;
        initialized = true;
        NAMES[TICK_ANIM]  = "tickAnimations";
        NAMES[PUMP]       = "playerPump";
        NAMES[COMPOSITE]  = "composite";
        NAMES[OVERLAY]    = "overlay";
        NAMES[DAWN_LYRIC] = "drawLyricOverlay";
        NAMES[FLUID_BG]   = "fluidBg";
        NAMES[EDGE_BLUR]  = "edgeBlur";
        NAMES[PRESENT]    = "present";
        NAMES[BLUR_CAPTURE] = "blurCapture";
        NAMES[BLUR_FILTER]  = "blurFilter";
        NAMES[BLUR_DRAW]    = "blurDraw";
        NAMES[BLUR_MISC]    = "blurMisc";
    }

    public static void begin(int idx) {
        ensureInit();
        if (idx >= 0 && idx < SECTION_COUNT) START[idx] = System.nanoTime();
    }

    public static void end(int idx) {
        if (idx >= 0 && idx < SECTION_COUNT && START[idx] != 0) {
            ACCUM[idx] += System.nanoTime() - START[idx];
            START[idx] = 0;
        }
    }

    public static void tick() {
        // 周期性性能日志已移除，不再累计输出
    }
}