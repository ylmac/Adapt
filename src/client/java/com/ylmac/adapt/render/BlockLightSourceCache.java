package com.ylmac.adapt.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real BlockState light-source snapshots collected during Sodium chunk builds.
 * <p>
 * This cache intentionally never examines texture RGB/luminance/hue. A source is
 * admitted only from {@link BlockState#getLightEmission()}, while its color is
 * selected by an explicit registry-name table. Sodium worker threads publish
 * immutable section snapshots; the render thread only queries those snapshots.
 */
public final class BlockLightSourceCache {
    private static final Map<Long, List<BlockLightSource>> BY_SECTION = new ConcurrentHashMap<>();
    private static final ThreadLocal<BuildCapture> BUILD = new ThreadLocal<>();

    private BlockLightSourceCache() {
    }

    public static boolean isBuilding() {
        return BUILD.get() != null;
    }

    public static void beginBuild() {
        BUILD.set(new BuildCapture());
    }

    public static void capture(BlockState state, BlockPos pos) {
        if (state == null || pos == null) {
            return;
        }
        BuildCapture capture = BUILD.get();
        if (capture == null) {
            return;
        }
        long section = SectionPos.asLong(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        capture.touchedSections.add(section);

        int emission = state.getLightEmission();
        if (emission <= 0) {
            return;
        }
        long block = pos.asLong();
        if (capture.seenBlocks.add(block)) {
            LightProfile profile = profile(state);
            capture.sources.add(new BlockLightSource(
                    block,
                    pos.getX() + 0.5,
                    pos.getY() + 0.5,
                    pos.getZ() + 0.5,
                    emission,
                    profile.red,
                    profile.green,
                    profile.blue,
                    profile.radius,
                    profile.strength));
        }
    }

    public static void endBuild() {
        BuildCapture capture = BUILD.get();
        BUILD.remove();
        if (capture == null) {
            return;
        }
        Map<Long, List<BlockLightSource>> grouped = new ConcurrentHashMap<>();
        for (BlockLightSource source : capture.sources) {
            BlockPos pos = BlockPos.of(source.blockPos);
            long section = SectionPos.asLong(
                    SectionPos.blockToSectionCoord(pos.getX()),
                    SectionPos.blockToSectionCoord(pos.getY()),
                    SectionPos.blockToSectionCoord(pos.getZ()));
            grouped.computeIfAbsent(section, ignored -> new ArrayList<>()).add(source);
        }
        for (long section : capture.touchedSections) {
            List<BlockLightSource> sources = grouped.get(section);
            BY_SECTION.put(section, sources == null ? List.of() : List.copyOf(sources));
        }
    }

    /**
     * Adds the strongest nearby block sources to the current render-thread light list.
     * The caller reserves one UBO slot for the existing Halo3D source.
     * <p>
     * 只有「收录范围」拉到 Sodium/原版区块显示范围（getEffectiveRenderDistance
     * × 16 格）—— 玩家走远后，光源仍在 UBO 里，被它照亮的固定区域在世界坐标上
     * 保持亮着（用户需求：距光源 10 米外回看，那一格仍是亮的）。
     * 单光源的作用半径保持 profile 原值（5.5~7 格，dist>=radius 硬截止）——
     * 绝不把光照扩散覆盖到整个区块。
     */
    public static int cachedCount() {
        int total = 0;
        for (List<BlockLightSource> sectionSources : BY_SECTION.values()) {
            total += sectionSources.size();
        }
        return total;
    }

    public static int sectionCount() {
        return BY_SECTION.size();
    }

    public static void submitNearby(ClientLevel level, Vec3 camera, int limit) {
        if (level == null || camera == null) {
            AdaptRenderProfiler.recordLights(cachedCount(), 0, 0);
            return;
        }
        AdaptRenderProfiler.begin(AdaptRenderProfiler.LIGHT_COLLECT);
        // 屏幕/视距内全部灯都要进列表。limit 只是防御 cap（LightListBuffer
        // 容量），不是“取最强 N 盏”。按 section 索引收，避免扫完全图。
        int renderDistanceChunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
        int camSectionX = SectionPos.blockToSectionCoord((int) Math.floor(camera.x));
        int camSectionY = SectionPos.blockToSectionCoord((int) Math.floor(camera.y));
        int camSectionZ = SectionPos.blockToSectionCoord((int) Math.floor(camera.z));
        int cap = limit <= 0 ? Integer.MAX_VALUE : limit;
        int submitted = 0;
        for (int sx = camSectionX - renderDistanceChunks; sx <= camSectionX + renderDistanceChunks && submitted < cap; sx++) {
            for (int sz = camSectionZ - renderDistanceChunks; sz <= camSectionZ + renderDistanceChunks && submitted < cap; sz++) {
                for (int sy = camSectionY - 8; sy <= camSectionY + 8 && submitted < cap; sy++) {
                    List<BlockLightSource> sectionSources = BY_SECTION.get(SectionPos.asLong(sx, sy, sz));
                    if (sectionSources == null || sectionSources.isEmpty()) {
                        continue;
                    }
                    for (BlockLightSource source : sectionSources) {
                        AdaptLightSources.submitSource(
                                new Vec3(source.worldX, source.worldY, source.worldZ),
                                source.red, source.green, source.blue,
                                source.radius, source.strength);
                        submitted++;
                        if (submitted >= cap) {
                            break;
                        }
                    }
                }
            }
        }
        AdaptRenderProfiler.end(AdaptRenderProfiler.LIGHT_COLLECT);
        AdaptRenderProfiler.recordLights(cachedCount(), submitted, submitted);
    }

    public static void clear() {
        BY_SECTION.clear();
        BUILD.remove();
    }

    private static LightProfile profile(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = id == null ? "" : id.getPath();

        // Explicit registry-name table. No texture or color sampling is used.
        if (path.contains("soul_torch") || path.contains("soul_lantern")
                || path.contains("soul_fire") || path.contains("soul_campfire")) {
            return new LightProfile(0.18f, 0.55f, 1.0f, 5.5f, 1.0f);
        }
        if (path.contains("redstone_torch")) {
            return new LightProfile(1.0f, 0.06f, 0.02f, 4.5f, 0.9f);
        }
        if (path.contains("lava") || path.equals("fire") || path.equals("campfire")
                || path.equals("magma_block")) {
            return new LightProfile(1.0f, 0.25f, 0.045f, 7.0f, 1.2f);
        }
        if (path.contains("torch") || path.contains("lantern") || path.contains("candle")) {
            return new LightProfile(1.0f, 0.58f, 0.18f, 5.5f, 0.95f);
        }
        if (path.contains("glowstone") || path.contains("shroomlight")
                || path.contains("froglight") || path.contains("sea_lantern")) {
            return new LightProfile(1.0f, 0.82f, 0.42f, 6.5f, 1.0f);
        }
        if (path.contains("end_rod") || path.contains("jack_o_lantern")
                || path.contains("beacon")) {
            return new LightProfile(0.75f, 0.9f, 1.0f, 6.0f, 1.0f);
        }
        // Modded BlockState light sources get a neutral explicit fallback.
        return new LightProfile(1.0f, 1.0f, 1.0f, 5.0f, 0.9f);
    }

    private record LightProfile(float red, float green, float blue,
                                float radius, float strength) {
    }

    private record BlockLightSource(long blockPos, double worldX, double worldY, double worldZ,
                                    int emission, float red, float green, float blue,
                                    float radius, float strength, double distanceSq) {
        private BlockLightSource(long blockPos, double worldX, double worldY, double worldZ,
                                 int emission, float red, float green, float blue,
                                 float radius, float strength) {
            this(blockPos, worldX, worldY, worldZ, emission, red, green, blue,
                    radius, strength, Double.POSITIVE_INFINITY);
        }

        private BlockLightSource withDistanceSq(double distanceSq) {
            return new BlockLightSource(blockPos, worldX, worldY, worldZ, emission,
                    red, green, blue, radius, strength, distanceSq);
        }

        private double score() {
            return strength * (emission / 15.0) / Math.max(distanceSq, 0.25);
        }
    }

    private static final class BuildCapture {
        private final List<BlockLightSource> sources = new ArrayList<>();
        private final java.util.Set<Long> seenBlocks = ConcurrentHashMap.newKeySet();
        private final java.util.Set<Long> touchedSections = ConcurrentHashMap.newKeySet();
    }
}
