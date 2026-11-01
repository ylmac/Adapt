package com.ylmac.adapt.render;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.Vec3;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界空间体素遮挡场：方块光源阴影与玻璃遮挡的数据源。
 * <p>
 * <b>为什么不是屏幕空间（SSRT）。</b>上一版用屏幕空间光线步进做点光阴影，
 * 用户实测三个必然缺陷：随走动/视角转动而变（屏幕空间方法的固有性质）、
 * 出现"两层"（屏幕外遮挡物缺失 + 深度重投影歧义）、每帧全量重算无法缓存。
 * 世界空间体素场三条全部解决 —— 实测确认"非常稳定，移动也是稳定的"。
 * <p>
 * <b>为什么是 texel buffer 而不是 3D 纹理。</b>MC 26.2 的 Blaze3D 完全不支持
 * 3D 纹理与数组纹理（字节码实证）：
 * <ul>
 *   <li>{@code GpuDevice.verifyTextureCreationArgs} off.159 →
 *       {@code UnsupportedOperationException("Array or 3D textures are not yet supported")}</li>
 *   <li>{@code VulkanGpuTexture.<init>} off.48 是 {@code iconst_1}
 *       （VK_IMAGE_TYPE_2D）无分支，off.62 把 {@code extent.depth} 写死 1</li>
 *   <li>{@code IntermediaryShaderModule.rebind} off.434 拒绝 SPIR-V {@code Dim3D}</li>
 * </ul>
 * 替代是 uniform texel buffer + GLSL {@code usamplerBuffer} + {@code texelFetch}，
 * 自己做 3D→1D 索引展平。原版自己就用这条路（{@code CloudFaces} 是
 * {@code R8_SINT} + {@code isamplerBuffer}）。
 * <p>
 * <b>体素编码</b>（每体素 1 字节，{@code R8_UINT}）：
 * <pre>
 *   0        = 空气/完全透光
 *   1..254   = 不透明度（玻璃等半透明取中间值，让光穿过但衰减 → 玻璃遮挡）
 *   255      = 完全实心
 * </pre>
 * <p>
 * <b>双缓冲消闪烁。</b>上一版单缓冲 + 增量重建：原点变化时先把整个场清零，
 * 再每帧填 512 列，128²=16384 列要跑满 32 帧。清零期间大部分体素读到 0
 * （无遮挡），随后逐渐出现遮挡 —— 实测表现为"某个位置看得到某个位置看不到"
 * 的闪烁，且玩家每走过一次 16 格对齐边界就重来。
 * <p>
 * 现在改为：后台缓冲慢慢建，建满才与前台原子交换；交换前 shader 继续读旧的
 * <b>完整自洽</b>数据（原点略滞后但内容不残缺）。UBO 里的原点必须跟随
 * <b>当前生效</b>的前台缓冲，绝不能提前更新 —— 否则坐标换算会指向尚未填好的
 * 区域，等价于回到闪烁。这是 AC Shadows 探针"缓存 G-buffer、每帧只更新一小
 * 部分"的同构做法。
 */
public final class VoxelOcclusionField {

    /**
     * 网格边长（体素=方块）。64³ × 32B = 8 MiB/缓冲，覆盖玩家周围 ±32 格。
     * <p>
     * 从 ±64 缩到 ±32：点光半径只有 5.5–7 格，±64 的覆盖远超需要。省下的
     * 预算换成每体素 8 个 AABB 槽位 —— MC 栅栏四向全连是 1 柱 + 8 条横杆
     * （每侧上下两根）共 9 个 box，4 个槽位装不下，`toAabbs()` 返回的最大
     * box 往往是跨整格的横杆，于是栅栏又变成整格实心（实测"栅栏还是不透光"）。
     * 内存反而从 32 MiB 降到 8 MiB，重建量降 4 倍。
     */
    public static final int SIZE = 64;
    private static final int SIZE_MASK = SIZE - 1;
    private static final int SIZE_SHIFT = 6;   // log2(SIZE)
    private static final int VOLUME = SIZE * SIZE * SIZE;
    private static final int COLUMNS = SIZE * SIZE;
    /** 每体素 32 字节 = 2 个 RGBA32_UINT texel：八个 1/16 精度 AABB。 */
    private static final int BYTES_PER_VOXEL = 32;
    /** 每体素最多存几个 AABB。 */
    private static final int MAX_BOXES = 8;
    /** 每体素占几个 texel（GLSL 侧 texelFetch 索引要乘这个数）。 */
    public static final int TEXELS_PER_VOXEL = 2;
    private static final int BUFFER_BYTES = VOLUME * BYTES_PER_VOXEL;

    /**
     * 原点对齐步长。玩家在同一对齐格内移动不触发重建，避免每走一步就重扫
     * 200 万个方块。16 = 一个区块宽度，与 MC 的 section 粒度对齐。
     */
    private static final int ORIGIN_STEP = 16;

    /**
     * 每帧重建多少列。16384 列 / 256 = 64 帧填满一个后台缓冲；60fps 下约
     * 1 秒。期间前台仍是完整数据。旧值 1024 列 × 128 y = 每帧 13 万次
     * getBlockState，即使场已经建完、玩家没走出对齐格也会在重建途中把 CPU 打满。
     */
    private static final int COLUMNS_PER_FRAME = 256;

    /** 材质透光：实心（几何命中即完全阻断）。 */
    private static final byte MAT_SOLID = (byte) 255;
    /** 玻璃/树叶/水等半透明材质：几何是整格，但光能穿过并按路径长度衰减。 */
    private static final byte MAT_TRANSLUCENT = (byte) 96;
    /** 空气：八个 word 全 0（无有效槽位）。 */
    private static final long[] EMPTY_CELL = {0L, 0L, 0L, 0L};

    /** 前台：shader 正在读的完整数据。 */
    private static GpuBuffer frontBuffer;
    private static ByteBuffer frontStaging;
    private static int frontOriginX;
    private static int frontOriginY;
    private static int frontOriginZ;
    private static boolean frontValid;

    /** 后台：正在增量填充，填满后与前台交换。 */
    private static ByteBuffer backStaging;
    private static int backOriginX;
    private static int backOriginY;
    private static int backOriginZ;
    private static int backCursor;
    private static boolean backBuilding;

    private static ClientLevel boundLevel;
    private static int lastColumns;
    /**
     * BlockState → 打包的体素单元（低 32 位 word0、高 32 位 word1）。
     * 栅栏的 NORTH/EAST、半砖的 half、门的 facing 都是 state 属性，
     * 所以按 state 缓存仍然能区分形态，不必每格都跑 toAabbs。
     */
    private static final Map<BlockState, long[]> VOXEL_CACHE = new ConcurrentHashMap<>();
    /** -Dadapt.dumpVoxelShapes=1：每种方块打印一次真实 AABB 列表。 */
    private static final boolean DUMP_SHAPES =
            Integer.getInteger("adapt.dumpVoxelShapes", 0) != 0;
    private static final java.util.Set<net.minecraft.world.level.block.Block> DUMPED =
            ConcurrentHashMap.newKeySet();

    private VoxelOcclusionField() {
    }

    /**
     * 每帧在渲染线程调用（AdaptLightingUniform 写 UBO 时）。推进后台重建，
     * 必要时交换缓冲。返回后调用方应读 {@link #originX()} 等取<b>前台</b>原点。
     */
    public static void update(ClientLevel level, Vec3 cameraPos) {
        if (level == null || cameraPos == null) {
            return;
        }
        ensureResources();

        boolean levelChanged = level != boundLevel;
        if (levelChanged) {
            boundLevel = level;
            frontValid = false;
            backBuilding = false;
        }

        int alignedX = alignDown((int) Math.floor(cameraPos.x) - SIZE / 2);
        int alignedY = alignDown((int) Math.floor(cameraPos.y) - SIZE / 2);
        int alignedZ = alignDown((int) Math.floor(cameraPos.z) - SIZE / 2);

        // 需要新原点：前台还没建过，或玩家已走出前台覆盖的对齐格
        boolean needNewOrigin = !frontValid
                || alignedX != frontOriginX || alignedY != frontOriginY || alignedZ != frontOriginZ;

        if (needNewOrigin && !backBuilding) {
            // 启动一轮后台重建。注意**不清零**：后台会被逐列完整覆盖，清零反而
            // 会让首次（frontValid=false）交换后出现短暂的无遮挡。
            backOriginX = alignedX;
            backOriginY = alignedY;
            backOriginZ = alignedZ;
            backCursor = 0;
            backBuilding = true;
        } else if (backBuilding
                && (alignedX != backOriginX || alignedY != backOriginY || alignedZ != backOriginZ)) {
            // 重建途中玩家又走出去了：重定向到最新原点，从头开始。前台不动，
            // 所以画面不受影响，只是新数据晚一点到。
            backOriginX = alignedX;
            backOriginY = alignedY;
            backOriginZ = alignedZ;
            backCursor = 0;
        }

        lastColumns = 0;
        if (backBuilding) {
            AdaptRenderProfiler.begin(AdaptRenderProfiler.VOXEL_BUILD);
            advanceBackBuild(level);
            AdaptRenderProfiler.end(AdaptRenderProfiler.VOXEL_BUILD);
            if (backCursor >= COLUMNS) {
                swapBuffers();
            }
        }
        AdaptRenderProfiler.recordVoxel(lastColumns, backBuilding, frontValid);

        // 首帧特例：前台尚未有效时，后台还没填满也必须先上传一份，否则 shader
        // 读到未初始化内存。此时整体按"无遮挡"处理最安全（宁可漏遮挡也不要
        // 假遮挡 —— 假遮挡会在世界里出现莫名黑块）。
        if (!frontValid) {
            for (int i = 0; i < VOLUME; i++) {
                int base = i * BYTES_PER_VOXEL;
                for (int w = 0; w < BYTES_PER_VOXEL; w += 8) {
                    frontStaging.putLong(base + w, 0L);
                }
            }
            frontOriginX = alignedX;
            frontOriginY = alignedY;
            frontOriginZ = alignedZ;
            frontValid = true;
            upload();
        }
    }

    /** 后台增量重建：每帧 COLUMNS_PER_FRAME 列，顺序推进到填满。 */
    private static void advanceBackBuild(ClientLevel level) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int end = Math.min(backCursor + COLUMNS_PER_FRAME, COLUMNS);
        for (int column = backCursor; column < end; column++) {
            int lx = column & SIZE_MASK;
            int lz = column >> SIZE_SHIFT;
            int worldX = backOriginX + lx;
            int worldZ = backOriginZ + lz;
            for (int ly = 0; ly < SIZE; ly++) {
                cursor.set(worldX, backOriginY + ly, worldZ);
                long[] cell = classify(level, cursor);
                int base = index(lx, ly, lz) * BYTES_PER_VOXEL;
                for (int w = 0; w < cell.length; w++) {
                    backStaging.putLong(base + w * 8, cell[w]);
                }
            }
        }
        lastColumns = end - backCursor;
        backCursor = end;
    }

    /** 后台填满：交换 staging 引用，把新数据整体上传，原点同步生效。 */
    private static void swapBuffers() {
        ByteBuffer tmp = frontStaging;
        frontStaging = backStaging;
        backStaging = tmp;
        frontOriginX = backOriginX;
        frontOriginY = backOriginY;
        frontOriginZ = backOriginZ;
        frontValid = true;
        backBuilding = false;
        upload();
    }

    /**
     * 方块 → 四通道体素值。只用 BlockState 的几何/光照语义，不看纹理颜色
     * （项目规则：禁止用颜色判断）。
     * <p>
     * <b>为什么存真实 AABB 而不是覆盖率/标量。</b>标量与"三轴覆盖率"都是把
     * 形状压成概率，射线仍然是"命中整格再乘一个系数" —— 用户实测两版都
     * 表现为"依旧被当作整个方块处理"。现代引擎（UE/Anvil）的局部光阴影是
     * 对真实几何做 ray-AABB 相交，不是对格子做概率衰减。
     * <p>
     * 这里存每个体素最多 2 个 1/16 精度 AABB（占满 8 字节，RG32_UINT），
     * shader 在 DDA 命中格子后对这些 AABB 做精确 slab 相交测试：
     * <pre>
     *   石头     1 个 AABB (0,0,0)-(16,16,16)   任何射线都真命中
     *   下半砖   1 个       (0,0,0)-(16,8,16)   上半空间射线穿过不挡
     *   门(北)   1 个       (0,0,0)-(16,16,3)   垂直穿命中、侧滑不命中
     *   栅栏     2 个       柱 + 主连接臂        贴边射线真的穿过去
     *   地毯     1 个       (0,0,0)-(16,1,16)   只有近地射线被挡
     * </pre>
     * 2 个 AABB 装不下的（栅栏四向全连、复杂楼梯）取包围盒的**最紧子集**：
     * 前两个 box 按体积降序保留，其余并进第二个 box 的包围盒。
     */
    private static long[] classify(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return EMPTY_CELL;
        }
        // 发光体本身不该挡自己的光（火把/岩浆），否则光源位置永远被判为被遮挡
        if (state.getLightEmission() > 0) {
            return EMPTY_CELL;
        }
        long[] cached = VOXEL_CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        long[] packed = classifyUncached(level, state, pos);
        VOXEL_CACHE.put(state, packed);
        return packed;
    }

    private static long[] classifyUncached(ClientLevel level, BlockState state, BlockPos pos) {
        // <b>形状来源：occlusionShape 优先，空则退可见形状（getShape）。绝不用碰撞箱。</b>
        // <p>
        // 26.2 jar 离线实测（三种形状逐块对比，见下）：
        // <pre>
        //   oak_fence       occl=true   OCCLUSION 5x (6,0,6→10,16,10)(0,6,7→6,15,9)
        //                                          (7,6,0→9,15,6)(7,6,10→9,15,16)(10,6,7→16,15,9)
        //                               VISUAL    3x (0,0,6→16,16,10)(6,0,0→10,16,6)(6,0,10→10,16,16)
        //                               COLLISION 3x (0,0,6→16,24,10) …
        //   oak_fence_gate  occl=true   OCCLUSION 2x (0,5,7→2,16,9)(14,5,7→16,16,9)
        //                               COLLISION 1x (0,0,6→16,24,10)  ← 整格宽的墙
        //   iron_bars       occl=false  OCCLUSION EMPTY
        //                               VISUAL    3x (0,0,7→16,16,9)(7,0,0→9,16,7)(7,0,9→9,16,16)
        //   snow            occl=true   OCCLUSION 1x (0,0,0→16,2,16)   COLLISION EMPTY
        //   chest           occl=true   OCCLUSION 1x (1,0,1→15,14,15)
        // </pre>
        // 三点结论：
        // <ol>
        //   <li>碰撞箱是"玩家能不能穿过"的语义。栅栏/栅栏门被刻意加宽成整格、
        //       抬到 24/16 高（防跳过）。拿它当遮挡体，任何横穿的射线都命中 →
        //       栅栏围起来的地面永远没光。太阳走光栅化 shadow map（几何是真实
        //       模型）所以正常，只有走体素的方块光错 —— 与实测完全一致。</li>
        //   <li>可见形状（getShape，即 outline）对栅栏是**整格高的板**
        //       （makeShapes 的 extBottom=0，arm 从 y=0 拉到 y=extHeight），
        //       仍然会把栅栏内侧封死。</li>
        //   <li>occlusionShape 是 MC 自己做光传播用的几何：栅栏是柱 + 四根
        //       y=6..15 的细杆，槽位刚好 5 个。语义与我们要算的东西完全一致。</li>
        // </ol>
        // occlusionShape 的唯一缺口是 {@code canOcclude=false} 时被 initCache
        // 写成 {@code Shapes.empty()}（字节码实证）—— 玻璃/树叶/门/梯子/铁栏杆/
        // 玻璃板/活板门/火把/竹子全在此列。这些退回可见形状：它们的 outline
        // 就是真实模型包络（门 3 厚、铁栏杆 2 厚、火把 4×10×4），没有防跳加宽。
        VoxelShape occlusion = state.getOcclusionShape();
        VoxelShape shape = occlusion.isEmpty() ? state.getShape(level, pos) : occlusion;
        if (shape.isEmpty()) {
            // 水/冰这类无形状但阻挡天光的：按整格半透明处理。
            if (!state.propagatesSkylightDown()) {
                return pack(new int[][]{{0, 0, 0, 16, 16, 16}}, true);
            }
            return EMPTY_CELL;
        }

        // <b>不投影的方块：草、花、藤蔓、锁链、甘蔗、小麦……</b>
        // <p>
        // 它们的 canOcclude=false 所以 occlusionShape 空，退到 getShape 后拿到
        // 的是**十字/交叉平面的包围盒**（26.2 jar 实测）：
        // <pre>
        //   short_grass  VISUAL 1x (2,0,2→14,13,14)     ← 12×13×12 的实心块
        //   tall_grass   VISUAL 1x (0,0,0→16,16,16)     ← 整格实心
        //   sugar_cane   VISUAL 1x (2,0,2→14,16,14)
        //   vine         VISUAL 4x (0,0,0→1,16,16)…     ← 四面贴墙
        //   chain        VISUAL EMPTY                    ← 连形状都没有
        // </pre>
        // 草的渲染模型是两片对角交叉的平面（cross.json），面积几乎为零；把它
        // 当成 12×13×12 的实心 AABB，脚下一片草就能把点光全挡住 —— 实测
        // "草和植物、树叶，方块光对它们的处理都有问题"。
        // <p>
        // MC 自己的判据是 {@code getLightDampening()}：草/花/锁链/藤蔓全是 0
        // （光完全不衰减），树叶/水/冰是 1（衰减一级），实心方块是 15。这是
        // 原版<b>几何真值之外</b>的材质语义，不是"用颜色判断"。
        // <p>
        // dampening=0 且不是整格几何 → 直接不参与遮挡（EMPTY_CELL）。锁链本来
        // 就 VISUAL EMPTY，草/花/甘蔗从此不再投出方块状黑影。
        if (state.getLightDampening() <= 0) {
            return EMPTY_CELL;
        }

        java.util.List<net.minecraft.world.phys.AABB> boxes = shape.toAabbs();
        if (boxes.isEmpty()) {
            return EMPTY_CELL;
        }
        // 一次性诊断：把每种方块的真实 AABB 数量/坐标打出来。
        // 走 System.out 而不是 slf4j —— MC 的日志格式不打印 logger 名，
        // 之前用 LoggerFactory 打的行在日志里无法定位；stdout 会带
        // "[STDOUT]" 前缀，一定搜得到。
        if (DUMP_SHAPES && DUMPED.add(state.getBlock())) {
            StringBuilder sb = new StringBuilder(200);
            sb.append("[AdaptVoxel] ").append(state.getBlock())
                    .append(" boxes=").append(boxes.size())
                    .append(" canOcclude=").append(state.canOcclude())
                    .append(" skylight=").append(state.propagatesSkylightDown());
            int shown = 0;
            for (net.minecraft.world.phys.AABB b : boxes) {
                if (shown++ >= MAX_BOXES + 2) {
                    sb.append(" ...");
                    break;
                }
                sb.append(String.format(" [%.3f,%.3f,%.3f→%.3f,%.3f,%.3f]",
                        b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
            }
            System.out.println(sb);
        }

        // 竹子/花草这类有随机 XZ 偏移的方块：形状要跟着偏移，否则影子与
        // 渲染出的模型错位（用户实测"竹子之类的影子效果非常不好"）。
        Vec3 offset = state.hasOffsetFunction() ? state.getOffset(pos) : Vec3.ZERO;

        // 体积降序：保留最大的 MAX_BOXES 个，**溢出的直接丢弃**。
        // 旧实现把溢出 box 并进最后一槽的包围盒 —— toAabbs() 是从离散网格
        // 反推的，栅栏能返回 5-6 个 box，那个包围盒会覆盖一大片，等于又变成
        // 整格实心（用户实测"太阳能透栅栏、方块光不能"）。丢弃只会漏一点
        // 遮挡，比假遮挡好得多，与本项目"宁可漏不可假"的原则一致。
        boxes = new java.util.ArrayList<>(boxes);
        boxes.sort((a, b) -> Double.compare(volumeOf(b), volumeOf(a)));

        int kept = Math.min(boxes.size(), MAX_BOXES);
        int[][] quantized = new int[kept][];
        for (int i = 0; i < kept; i++) {
            quantized[i] = quantize(boxes.get(i).move(offset));
        }

        int[] first = quantized[0];
        boolean fullCube = first[0] == 0 && first[1] == 0 && first[2] == 0
                && first[3] == 16 && first[4] == 16 && first[5] == 16;
        // 材质透光：几何是整格但光能穿过（玻璃/树叶/冰/水）。
        // <p>
        // 判据用 {@code getLightDampening() < 15}（MC 自己的光衰减语义，
        // 26.2 实测：树叶/水/冰=1，石头/草方块/浮冰=15）而不是
        // {@code !canOcclude()} —— 后者对树叶成立，但对<b>门/地毯/活板门</b>
        // 也成立，而它们是实心材质 + 薄几何（薄由 AABB 表达），当成玻璃会漏光。
        // 上面的 dampening<=0 分支已经把草/花/锁链整体剔除，这里剩下的
        // dampening 1..14 就是真正的半透明材质。
        boolean translucent = fullCube && state.getLightDampening() < 15;
        return pack(quantized, translucent);
    }

    /** AABB → 1/16 格量化整数（0..16），向外取整保证不漏挡。 */
    private static int[] quantize(net.minecraft.world.phys.AABB box) {
        return new int[]{
                clamp16((int) Math.floor(box.minX * 16.0)),
                clamp16((int) Math.floor(box.minY * 16.0)),
                clamp16((int) Math.floor(box.minZ * 16.0)),
                clamp16((int) Math.ceil(box.maxX * 16.0)),
                clamp16((int) Math.ceil(box.maxY * 16.0)),
                clamp16((int) Math.ceil(box.maxZ * 16.0))
        };
    }

    private static int clamp16(int v) {
        return Math.max(0, Math.min(16, v));
    }

    private static double volumeOf(net.minecraft.world.phys.AABB box) {
        return box.getXsize() * box.getYsize() * box.getZsize();
    }

    /**
     * 打包成八个 32 位 word（GLSL 侧两个 uvec4）：
     * <pre>
     *   word[i] 位 0-29 : minX(5) minY(5) minZ(5) maxX(5) maxY(5) maxZ(5)
     *   word[0] 位 30   : 材质半透明（0=实心，1=玻璃/树叶/水）
     *   word[i] 位 31   : 该槽位有效
     * </pre>
     * 每个坐标 0..16 占 5 位。材质只有两档，1 位足够 —— 半透明的衰减系数
     * 由 shader 侧常量给出，不需要逐体素存强度。
     */
    private static long[] pack(int[][] boxes, boolean translucent) {
        int[] words = new int[MAX_BOXES];
        for (int i = 0; i < boxes.length && i < MAX_BOXES; i++) {
            int[] b = boxes[i];
            // 退化 box（min==max）不占槽位
            if (b[3] <= b[0] && b[4] <= b[1] && b[5] <= b[2]) {
                continue;
            }
            words[i] = packBox(b[0], b[1], b[2], b[3], b[4], b[5]) | (1 << 31);
        }
        if (translucent) {
            words[0] |= 1 << 30;
        }
        long[] cell = new long[MAX_BOXES / 2];
        for (int i = 0; i < cell.length; i++) {
            cell[i] = (words[i * 2] & 0xFFFFFFFFL)
                    | ((words[i * 2 + 1] & 0xFFFFFFFFL) << 32);
        }
        return cell;
    }

    private static int packBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        return (x0 & 31) | ((y0 & 31) << 5) | ((z0 & 31) << 10)
                | ((x1 & 31) << 15) | ((y1 & 31) << 20) | ((z1 & 31) << 25);
    }

    private static int alignDown(int value) {
        return Math.floorDiv(value, ORIGIN_STEP) * ORIGIN_STEP;
    }

    /** 3D→1D 展平（texel 索引，不含每体素字节数）：与 GLSL adaptVoxelIndex 一致。 */
    private static int index(int x, int y, int z) {
        return x + y * SIZE + z * SIZE * SIZE;
    }

    private static void ensureResources() {
        if (frontStaging == null) {
            frontStaging = ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.nativeOrder());
            backStaging = ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.nativeOrder());
        }
        if (frontBuffer == null) {
            frontBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Adapt voxel occlusion field",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER,
                    BUFFER_BYTES);
        }
    }

    /** 只在交换（或首帧）时上传，不再每帧传 8 MiB。 */
    private static void upload() {
        frontStaging.clear();
        frontStaging.limit(BUFFER_BYTES);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(frontBuffer.slice(), frontStaging);
    }

    /**
     * 绑定到光照 pass。必须用 {@code setUniform(String, GpuBuffer)} 整 buffer
     * 绑定——texel buffer 不支持 slice（{@code VulkanRenderPass.pushDescriptors}
     * off.173 抛 "Uniform texel buffers do not support a slice of a buffer,
     * must be entire buffer"）。
     */
    public static void bind(RenderPass pass) {
        if (frontBuffer == null) {
            return;
        }
        pass.setUniform("VoxelField", frontBuffer);
    }

    /** 前台原点（当前生效的数据对应的原点），供 shader 换算体素索引。 */
    public static int originX() {
        return frontOriginX;
    }

    public static int originY() {
        return frontOriginY;
    }

    public static int originZ() {
        return frontOriginZ;
    }

    public static boolean ready() {
        return frontBuffer != null && frontValid;
    }

    public static boolean building() {
        return backBuilding;
    }

    public static int lastColumns() {
        return lastColumns;
    }

    public static void close() {
        if (frontBuffer != null) {
            frontBuffer.close();
            frontBuffer = null;
        }
        frontStaging = null;
        backStaging = null;
        frontValid = false;
        backBuilding = false;
        boundLevel = null;
        lastColumns = 0;
        VOXEL_CACHE.clear();
    }
}
