package com.ylmac.adapt.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * 可见光源簇列表（clustered forward rendering 的 froxel 网格）。
 * <p>
 * <b>不是截断。</b>用户硬要求：屏幕里能看见的灯必须全部实现、不能有上限。
 * 本表只回答"这个像素该测哪些灯"，不从 LightList 里删灯；采用 CSR
 * （压缩行）布局，每格容量按实际需要分配，<b>结构上不可能丢灯</b>，
 * 也不再需要溢出哨兵。
 * <p>
 * <b>为什么要加深度这一维。</b>上一版只按屏幕 XY 分 16×16 格。一个屏幕格
 * 在世界里是一条从相机延伸到渲染边界的<b>长棱锥</b>，深度方向完全不过滤 ——
 * 实测 {@code tileAvg=87 tileMax=194}，而点光半径只有 5.5–7 格，真正能照到
 * 某个像素的灯（半径球体积 × 灯密度）只有个位数。也就是说 90% 以上的候选灯
 * 是白算的：每盏都要 2 次 texelFetch 去算权重，然后被 {@code dist >= radius}
 * 丢掉。这是 GPU 侧的主要浪费。
 * <p>
 * 加上深度切片后候选数降到 ~25，且"真正在范围内"的仍是个位数 —— 于是
 * shader 可以负担<b>逐灯各做一次阴影射线</b>，不再需要共享一个标量可见性
 * （那个共享标量是"栅栏围起来里面没光"的根因：外面的灯被栅栏挡住，
 * visibility=0 把里面那盏灯的贡献也一起乘没了）。
 * <p>
 * 布局（{@code R32_UINT} texel buffer，CSR）：
 * <pre>
 *   texel 0 .. CLUSTERS      : 行指针（cluster c 的条目是 [ptr[c], ptr[c+1])）
 *   texel CLUSTERS+1 ..      : 光源索引（into LightList）
 * </pre>
 * 簇索引 {@code (slice * GRID + ty) * GRID + tx}，与 GLSL 侧一致。
 */
public final class LightTileGrid {

    /** 屏幕方向的格数（两个方向相同）。 */
    public static final int GRID = 16;
    /**
     * 深度切片数与厚度（格）。48 × 8 = 覆盖 384 格视距，超出的落进最后一片。
     * <p>
     * 用<b>均匀</b>厚度而不是指数分布：指数分布在近处很细、远处极粗，而点光
     * 半径是<b>常数</b> 5.5–7 格，远处的粗切片会把大量灯塞进同一簇。均匀 8 格
     * 与半径同量级，近远一致。
     */
    public static final int DEPTH_SLICES = 48;
    public static final float SLICE_THICKNESS = 8.0f;
    private static final int CLUSTERS = GRID * GRID * DEPTH_SLICES;
    /** 行指针占的 texel 数（CLUSTERS + 1，末尾哨兵）。 */
    private static final int HEADER = CLUSTERS + 1;
    /** body 初始容量（条目数），不够时翻倍。 */
    private static final int INITIAL_BODY = 1 << 17;

    private static GpuBuffer buffer;
    private static ByteBuffer staging;
    private static int bodyCapacity;
    /** 每盏灯的簇范围缓存：minTx,maxTx,minTy,maxTy,minSlice,maxSlice。 */
    private static int[] spans = new int[0];
    private static int[] counts = new int[CLUSTERS];
    private static int[] cursor = new int[CLUSTERS];
    private static int lastAvg;
    private static int lastMax;

    private LightTileGrid() {
    }

    public static int lastAvg() {
        return lastAvg;
    }

    public static int lastMax() {
        return lastMax;
    }

    public static void rebuild(List<AdaptLightSources.HaloSource> lights, Matrix4f projection,
                               int screenW, int screenH) {
        int lightCount = lights.size();
        if (spans.length < lightCount * 6) {
            spans = new int[Math.max(lightCount * 6, 256)];
        }
        java.util.Arrays.fill(counts, 0);

        boolean haveProjection = projection != null && screenW > 0 && screenH > 0;
        long total = 0;
        for (int i = 0; i < lightCount; i++) {
            int base = i * 6;
            if (!haveProjection || !computeSpan(lights.get(i), projection, screenW, screenH, spans, base)) {
                spans[base] = -1;   // 这盏灯不在视锥里，不占任何簇
                continue;
            }
            int cells = (spans[base + 1] - spans[base] + 1)
                    * (spans[base + 3] - spans[base + 2] + 1)
                    * (spans[base + 5] - spans[base + 4] + 1);
            total += cells;
            for (int s = spans[base + 4]; s <= spans[base + 5]; s++) {
                for (int ty = spans[base + 2]; ty <= spans[base + 3]; ty++) {
                    int rowBase = (s * GRID + ty) * GRID;
                    for (int tx = spans[base]; tx <= spans[base + 1]; tx++) {
                        counts[rowBase + tx]++;
                    }
                }
            }
        }

        ensureResources((int) Math.min(total, Integer.MAX_VALUE));

        // 前缀和 → 行指针。同时写进 staging 的 header 区。
        int running = 0;
        int max = 0;
        for (int c = 0; c < CLUSTERS; c++) {
            staging.putInt(c * 4, running);
            cursor[c] = running;
            int n = counts[c];
            if (n > max) {
                max = n;
            }
            running += n;
        }
        staging.putInt(CLUSTERS * 4, running);
        lastMax = max;
        lastAvg = (int) (total / CLUSTERS);
        AdaptRenderProfiler.recordTiles(lastAvg, lastMax);

        // 填充 body。
        int bodyByteBase = HEADER * 4;
        for (int i = 0; i < lightCount; i++) {
            int base = i * 6;
            if (spans[base] < 0) {
                continue;
            }
            for (int s = spans[base + 4]; s <= spans[base + 5]; s++) {
                for (int ty = spans[base + 2]; ty <= spans[base + 3]; ty++) {
                    int rowBase = (s * GRID + ty) * GRID;
                    for (int tx = spans[base]; tx <= spans[base + 1]; tx++) {
                        int c = rowBase + tx;
                        staging.putInt(bodyByteBase + cursor[c] * 4, i);
                        cursor[c]++;
                    }
                }
            }
        }

        int usedBytes = bodyByteBase + running * 4;
        staging.position(0);
        staging.limit(usedBytes);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), staging);
        staging.clear();
    }

    /**
     * 灯的视空间包围球 → 簇范围。返回 false 表示整颗灯在视锥外。
     * <p>
     * XY 用投影包围盒的八个角取 min/max（保守）；有角落在近裁面之后就把 XY
     * 放大到全屏（宁可多算不可漏灯）。深度直接用视空间 z —— 26.2 视空间 -Z
     * 朝前，所以距离 = -viewZ，不需要过投影。
     */
    private static boolean computeSpan(AdaptLightSources.HaloSource light, Matrix4f projection,
                                       int screenW, int screenH, int[] out, int base) {
        float vx = light.viewX();
        float vy = light.viewY();
        float vz = light.viewZ();
        float radius = light.radius();
        // 整颗灯在相机后面
        if (vz - radius >= 0.0f) {
            return false;
        }

        float nearDepth = Math.max(-(vz + radius), 0.0f);
        float farDepth = -(vz - radius);
        int minSlice = clampSlice((int) Math.floor(nearDepth / SLICE_THICKNESS));
        int maxSlice = clampSlice((int) Math.floor(farDepth / SLICE_THICKNESS));

        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        boolean crossesNear = false;
        Vector4f corner = new Vector4f();
        for (int c = 0; c < 8; c++) {
            corner.set(vx + ((c & 1) == 0 ? -radius : radius),
                    vy + ((c & 2) == 0 ? -radius : radius),
                    vz + ((c & 4) == 0 ? -radius : radius), 1.0f);
            projection.transform(corner);
            if (corner.w <= 0.0001f) {
                crossesNear = true;
                break;
            }
            float ndcX = corner.x / corner.w;
            float ndcY = corner.y / corner.w;
            minX = Math.min(minX, ndcX);
            maxX = Math.max(maxX, ndcX);
            minY = Math.min(minY, ndcY);
            maxY = Math.max(maxY, ndcY);
        }

        if (crossesNear) {
            out[base] = 0;
            out[base + 1] = GRID - 1;
            out[base + 2] = 0;
            out[base + 3] = GRID - 1;
            out[base + 4] = minSlice;
            out[base + 5] = maxSlice;
            return true;
        }

        // NDC → framebuffer 像素。**不翻转 Y**：Vulkan 的 framebuffer 原点在
        // 左上，NDC y 向下为正（y=-1 是顶部），gl_FragCoord.y 同样从顶部计。
        // 按 OpenGL 约定翻转 Y 的后果是上下半屏交换 —— 地面在下半屏，灯的
        // tile 被标到上半屏，于是"只有屏幕中间一段距离能发光"（实测）。
        int minPx = (int) Math.floor((minX * 0.5f + 0.5f) * screenW);
        int maxPx = (int) Math.ceil((maxX * 0.5f + 0.5f) * screenW);
        int minPy = (int) Math.floor((minY * 0.5f + 0.5f) * screenH);
        int maxPy = (int) Math.ceil((maxY * 0.5f + 0.5f) * screenH);
        if (maxPx < 0 || minPx >= screenW || maxPy < 0 || minPy >= screenH) {
            return false;
        }

        out[base] = clampTile(Math.max(minPx, 0) * GRID / screenW);
        out[base + 1] = clampTile(Math.min(maxPx, screenW - 1) * GRID / screenW);
        out[base + 2] = clampTile(Math.max(minPy, 0) * GRID / screenH);
        out[base + 3] = clampTile(Math.min(maxPy, screenH - 1) * GRID / screenH);
        out[base + 4] = minSlice;
        out[base + 5] = maxSlice;
        return true;
    }

    private static int clampTile(int v) {
        return Math.max(0, Math.min(GRID - 1, v));
    }

    private static int clampSlice(int v) {
        return Math.max(0, Math.min(DEPTH_SLICES - 1, v));
    }

    private static void ensureResources(int neededEntries) {
        int need = Math.max(neededEntries, 1);
        if (staging != null && bodyCapacity >= need) {
            return;
        }
        int capacity = Math.max(bodyCapacity == 0 ? INITIAL_BODY : bodyCapacity, 1);
        while (capacity < need) {
            capacity <<= 1;
        }
        bodyCapacity = capacity;
        int bytes = (HEADER + capacity) * Integer.BYTES;
        staging = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        if (buffer != null) {
            buffer.close();
        }
        buffer = RenderSystem.getDevice().createBuffer(
                () -> "Adapt light clusters",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER,
                bytes);
    }

    public static void bind(RenderPass pass) {
        if (buffer == null) {
            return;
        }
        pass.setUniform("LightTiles", buffer);
    }

    /**
     * 资源是否就绪。延迟光照 pass 的 BindGroupLayout 声明了本 uniform，
     * 未就绪时开 pass 会在 pushDescriptors 抛
     * "Missing uniform LightTiles (should be TEXEL_BUFFER)" —— 调用方必须先查。
     */
    public static boolean ready() {
        return buffer != null;
    }

    public static GpuFormat format() {
        return GpuFormat.R32_UINT;
    }

    public static void close() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
        staging = null;
        bodyCapacity = 0;
        lastAvg = 0;
        lastMax = 0;
    }
}
