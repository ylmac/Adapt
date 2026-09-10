package com.ylmac.adapt.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 延迟渲染诊断：每 3 秒一条汇总，不刷每帧。
 * <p>
 * 用户问的是"烘焙完了为什么还卡、资源浪费到哪里"。所以日志必须能把
 * CPU 段（光源收集 / 体素重建 / 阴影地形重绘 / 光照 pass 提交）和 GPU
 * 侧会吃掉的规模（live 光源数、体素是否还在建）分开写出来。
 */
public final class AdaptRenderProfiler {

    private static final Logger LOG = LoggerFactory.getLogger("adapt-perf");
    private static final long REPORT_INTERVAL_NS = 3_000_000_000L;

    public static final int LIGHT_COLLECT = 0;
    public static final int VOXEL_BUILD = 1;
    public static final int SHADOW_TERRAIN = 2;
    public static final int LIGHTING_PASS = 3;
    public static final int SHADOW_ENTITY = 4;
    public static final int SECTION_COUNT = 5;

    private static final String[] NAMES = {
            "lightCollect", "voxelBuild", "shadowTerrain", "lightingPass", "shadowEntity"
    };
    private static final long[] START = new long[SECTION_COUNT];
    private static final long[] ACCUM = new long[SECTION_COUNT];
    private static final int[] HITS = new int[SECTION_COUNT];

    private static long windowStart = System.nanoTime();
    private static int frames;
    private static int lightsLive;
    private static int lightsCached;
    private static int lightsSubmitted;
    private static int tileLightsAvg;
    private static int tileLightsMax;
    private static int voxelColumnsThisFrame;
    private static boolean voxelBuilding;
    private static boolean voxelFrontValid;

    private AdaptRenderProfiler() {
    }

    public static void begin(int section) {
        if (section >= 0 && section < SECTION_COUNT) {
            START[section] = System.nanoTime();
        }
    }

    public static void end(int section) {
        if (section >= 0 && section < SECTION_COUNT && START[section] != 0) {
            ACCUM[section] += System.nanoTime() - START[section];
            HITS[section]++;
            START[section] = 0;
        }
    }

    public static void recordLights(int cached, int submitted, int live) {
        lightsCached = cached;
        lightsSubmitted = submitted;
        lightsLive = live;
    }

    public static void recordTiles(int avg, int max) {
        tileLightsAvg = avg;
        tileLightsMax = max;
    }

    public static void recordVoxel(int columnsThisFrame, boolean building, boolean frontValid) {
        voxelColumnsThisFrame = columnsThisFrame;
        voxelBuilding = building;
        voxelFrontValid = frontValid;
    }

    public static void tickFrame() {
        frames++;
        long now = System.nanoTime();
        if (now - windowStart < REPORT_INTERVAL_NS) {
            return;
        }
        double seconds = (now - windowStart) / 1_000_000_000.0;
        double fps = frames / Math.max(seconds, 1e-6);
        StringBuilder sb = new StringBuilder(256);
        sb.append(String.format(
                "fps=%.1f lights cached=%d submitted=%d live=%d tileAvg=%d tileMax=%d voxel cols/f=%d building=%s front=%s",
                fps, lightsCached, lightsSubmitted, lightsLive, tileLightsAvg, tileLightsMax,
                voxelColumnsThisFrame, voxelBuilding, voxelFrontValid));
        for (int i = 0; i < SECTION_COUNT; i++) {
            double ms = frames == 0 ? 0.0 : (ACCUM[i] / 1_000_000.0) / frames;
            sb.append(String.format(" %s=%.2fms", NAMES[i], ms));
            ACCUM[i] = 0;
            HITS[i] = 0;
        }
        LOG.info("{}", sb);
        frames = 0;
        windowStart = now;
    }
}
