#version 330

// 世界空间体素遮挡场：**只由延迟光照 pass 引入**。

// 为什么单独成文件而不是放进 adapt_lighting.glsl：那个 include 被 12 个 shader
// 共享（云/天空/实体/前向地形/G-buffer 几何阶段…），而 `uniform usamplerBuffer`
// 是独立 uniform，任何 import 它的 pipeline 都会在 SPIR-V 里声明该 uniform。
// IntermediaryShaderModule.rebind 结尾会检查所有反射到的 uniform 是否都在
// BindGroupLayout 里找到匹配，未匹配就抛 ShaderCompileException →
// "Pipeline is not valid (may contain invalid shaders?)"。实测崩溃点是云 pass
// （ReimaginedCloudRenderer.renderAfterOpaque:132），因为它的 layout 没有
// VoxelField。UBO 字段（VoxelOrigin）不受影响 — UBO 整块绑定，多余字段无害。

// 依赖 adapt_lighting.glsl 提供的 AdaptLighting UBO（VoxelOrigin / HaloLight* /
// ViewRotCol*）与 adaptLightingGGX2 / HALO_RADIANCE_GAIN，所以必须在它之后引入。

// ---- 方块光源阴影：世界空间体素遮挡场（延迟渲染专属）----

// 为什么不是屏幕空间步进（SSRT）。上一版用屏幕空间光线步进，用户实测三个
// 必然缺陷：影子随走动/视角转动而变（屏幕空间方法的固有性质，不是 bug）、
// 出现"两层"（屏幕外遮挡物缺失 + 深度重投影歧义）、每帧全量重算无法缓存。
// 世界空间体素场三条全部解决：视角无关、遮挡物不受屏幕可见性限制、数据按
// 区块增量更新可跨帧复用。

// 为什么不是 cube shadow map：32 光源 × 6 面 = 192 次地形重绘，M4 上不可行。

// 为什么载体是 texel buffer 而不是 sampler3D：MC 26.2 的 Blaze3D 完全不支持
// 3D 纹理（IntermediaryShaderModule.rebind off.434 拒绝 SPIR-V Dim3D；
// GpuDevice.verifyTextureCreationArgs off.159 拒绝 depthOrLayers>1；
// VulkanGpuTexture.<init> off.48 把 imageType 写死 VK_IMAGE_TYPE_2D）。
// texel buffer + texelFetch + 手工索引展平是被原版验证过的路径（CloudFaces）。

// 体素编码（每体素 2 个 RGBA32_UINT texel = 32 字节，见 VoxelOcclusionField.pack）：
// word[i] 位 0-29: minX(5) minY(5) minZ(5) maxX(5) maxY(5) maxZ(5)，单位 1/16 格
// word[0] 位 30: 材质半透明（玻璃/树叶/水）
// word[i] 位 31: 该槽位有效
// 八个槽位：MC 栅栏四向全连是 1 柱 + 8 条横杆（每侧上下两根）共 9 个 box，
// 4 个槽位装不下，toAabbs 返回的最大 box 往往是跨整格的横杆，栅栏就又变成
// 整格实心（实测"栅栏还是不透光"）。网格同时从 ±64 缩到 ±32 格 — 点光半径
// 只有 5.5–7 格，覆盖远超需要，省下的预算换槽位，内存反而从 32 MiB 降到 8 MiB。
uniform usamplerBuffer VoxelField;

/** 每体素占几个 texel（与 VoxelOcclusionField.TEXELS_PER_VOXEL 一致）。 */
#define ADAPT_TEXELS_PER_VOXEL 2

/** 半透明材质（玻璃/树叶/水）的单格透光率。 */
const float ADAPT_TRANSLUCENT_TRANSMIT = 0.62;

/** 解出打包的 AABB（返回 0-1 的格内归一化坐标）。 */
void adaptUnpackBox(uint word, out vec3 boxMin, out vec3 boxMax) {
    boxMin = vec3(float(word & 31u), float((word >> 5u) & 31u), float((word >> 10u) & 31u)) * 0.0625;
    boxMax = vec3(float((word >> 15u) & 31u), float((word >> 20u) & 31u), float((word >> 25u) & 31u)) * 0.0625;
}

/**
 * 射线与格内 AABB 的 slab 相交。返回是否命中。
 * origin 是格内局部坐标（0-1），dir 已归一化，segment 是本格内剩余行程。
 */
bool adaptRayBox(vec3 origin, vec3 invDir, vec3 boxMin, vec3 boxMax, float segment) {
    vec3 t0 = (boxMin - origin) * invDir;
    vec3 t1 = (boxMax - origin) * invDir;
    vec3 tSmall = min(t0, t1);
    vec3 tBig = max(t0, t1);
    float tEnter = max(max(tSmall.x, tSmall.y), tSmall.z);
    float tExit = min(min(tBig.x, tBig.y), tBig.z);
    return tExit > max(tEnter, 0.0) && tEnter < segment;
}

/** 3D→1D 展平：必须与 VoxelOcclusionField.index 完全一致。 */
int adaptVoxelIndex(ivec3 v, int size) {
    return v.x + v.y * size + v.z * size * size;
}

/**
 * 世界空间体素 DDA 遮挡测试。返回 0（完全遮挡）到 1（无遮挡）。
 * <p>
 * voxelStart/voxelEnd 已是**体素空间坐标**（调用方用 worldRelPos - VoxelOrigin.xyz
 * 换算好；VoxelOrigin 存的是相对相机的原点，见
 * 加回相机位置才能落到网格索引 — 这里用的是 adaptWorldPos 的定义域，光源
 * 位置同样来自视空间，两者都需要换算，因此改为直接传入体素空间坐标。
 * <p>
 * 采用标准 3D DDA（Amanatides & Woo）：沿射线逐体素推进，每步只做一次
 * texelFetch，命中实心即终止。半透明体素累乘透光率，实现玻璃衰减。
 */
/**
 * 世界空间体素 DDA 遮挡测试。返回 0（完全遮挡）到 1（无遮挡）。
 * <p>
 * skipVoxel 是<b>要跳过相交测试的那一格</b>（片元自己所在的体素）。
 * 之前的做法是把起点沿法线推出 0.55 格来"躲开自己"，那是错的：地面像素的
 * 射线起点因此落在 y=0.55，而栅栏横杆的局部高度是 y=0.375..0.9375 — 射线
 * 整条都在横杆高度带里穿行，被判遮挡（实测"栅栏围一圈里面没光"，而孤立
 * 栅栏只有柱子没有横杆所以正常）。正确做法是起点贴着表面、只跳过自己那一格。
 */
float adaptVoxelOcclusion(vec3 voxelStart, vec3 voxelEnd, ivec3 skipVoxel, int stepBudget) {
    int size = int(VoxelOrigin.w);
    if (size <= 0 || stepBudget <= 0) {
        return 1.0;
    }

    vec3 delta = voxelEnd - voxelStart;
    float total = length(delta);
    if (total < 0.001) {
        return 1.0;
    }
    vec3 dir = delta / total;

    ivec3 voxel = ivec3(floor(voxelStart));
    // 变量名不用 step：那是 GLSL 内建函数名，遮蔽虽合法但易读性差。
    ivec3 advance = ivec3(sign(dir));

    // 到下一个体素边界的射线参数 t。
    // 正方向的下一个边界是 voxel+1，负方向是 voxel 本身，dir=0 的轴永不跨界。
    vec3 invDir = 1.0 / max(abs(dir), vec3(1e-6));
    vec3 boundary = vec3(voxel) + max(vec3(advance), vec3(0.0));
    vec3 tMax = abs(boundary - voxelStart) * invDir;
    vec3 tDelta = invDir;
    // dir 某轴为 0 时该轴永远不该被选中推进：否则会白耗一步（voxel 不动），
    // 而且它的 tMax 是个小值，可能抢在真正该推进的轴之前被选中，甚至触发
    // 提前 break。轴向射线（正上方/正前方）有两个零轴，浪费两步。
    if (advance.x == 0) { tMax.x = 1e30; }
    if (advance.y == 0) { tMax.y = 1e30; }
    if (advance.z == 0) { tMax.z = 1e30; }

    float transmittance = 1.0;
    // 逐格 slab 相交需要射线在格内的局部坐标与 invDir。invDir 已在上面算好
    // （tDelta = invDir），这里保留带符号版本用于 ray-AABB。
    vec3 signedInvDir = 1.0 / mix(dir, vec3(1e-6), lessThan(abs(dir), vec3(1e-6)));

    // 步数上限：3D DDA 走完长度 L 的射线需要的步数**不是 L，而是最多 L·√3**
    // — 每跨一个 x/y/z 边界算一步，对角线方向三个轴都在跨。旧代码写的是
    // int(min(total, size*1.732)) + 1 ≈ total + 1，对斜射线只给了约 1/1.73
    // 的步数 → 射线在撞到遮挡物之前就提前退出 → 判成"无遮挡"。漏的程度随
    // 方向变化（轴向够、对角线不够），正是"计算不太准确、有很多错误"的形态。
    // +2 覆盖起点所在的半个体素与零轴的取整余量。
    int maxSteps = int(total * 1.7321) + 2;
    // 防御性硬上限 + 距离分档预算（调用方按视距给，见 adaptVoxelStepBudget）。
    // 远处的局部光阴影细节在屏幕上只有几个像素，付全价是浪费 — AC Shadows
    // 的 1/4 分辨率 1 ray/pixel 是同一个取舍（他们试过全分辨率，结论是
    // "质量提升不值 4 倍成本"）。步数被截断时射线提前结束、返回当前
    // transmittance，即按"剩下的路无遮挡"处理：宁可漏遮挡不要假遮挡。
    maxSteps = min(min(maxSteps, 128), stepBudget);

    // 当前格入口处的射线参数 t（世界单位）。用于算格内局部起点。
    float tCurrent = 0.0;

    for (int i = 0; i < maxSteps; ++i) {
        // 越界即视为无遮挡（宁可漏遮挡也不要假遮挡 — 假遮挡会在世界里
        // 出现莫名黑块，比缺一点阴影糟糕得多）
        if (any(lessThan(voxel, ivec3(0))) || any(greaterThanEqual(voxel, ivec3(size)))) {
            return transmittance;
        }
        // 下一个边界的 t：决定射线在本格内的行程
        float tNext = min(min(tMax.x, tMax.y), tMax.z);
        float segment = min(tNext, total) - tCurrent;

        int texel = adaptVoxelIndex(voxel, size) * ADAPT_TEXELS_PER_VOXEL;
        uvec4 cell0 = texelFetch(VoxelField, texel);
        // 位 31 = 槽位有效。八个槽位任一命中即算命中。
        // 第一个槽位无效 = 空气，整格跳过（不必取第二个 texel）。
        // skipVoxel = 片元自己所在的格：起点贴着表面，第一次相交必然命中
        // 自己（表面就在那个 AABB 上），必须跳过，否则整个片元被判全遮挡。
        bool skip = voxel == skipVoxel;
        if ((cell0.x & 0x80000000u) != 0u && segment > 0.0 && !skip) {
            // 格内局部起点（0-1）
            vec3 localOrigin = voxelStart + dir * tCurrent - vec3(voxel);
            vec3 boxMin;
            vec3 boxMax;
            adaptUnpackBox(cell0.x, boxMin, boxMax);
            bool hit = adaptRayBox(localOrigin, signedInvDir, boxMin, boxMax, segment);
            if (!hit && (cell0.y & 0x80000000u) != 0u) {
                adaptUnpackBox(cell0.y, boxMin, boxMax);
                hit = adaptRayBox(localOrigin, signedInvDir, boxMin, boxMax, segment);
            }
            if (!hit && (cell0.z & 0x80000000u) != 0u) {
                adaptUnpackBox(cell0.z, boxMin, boxMax);
                hit = adaptRayBox(localOrigin, signedInvDir, boxMin, boxMax, segment);
            }
            if (!hit && (cell0.w & 0x80000000u) != 0u) {
                adaptUnpackBox(cell0.w, boxMin, boxMax);
                hit = adaptRayBox(localOrigin, signedInvDir, boxMin, boxMax, segment);
            }
            // 后四个槽位只在前四个都 miss 且确实存在时才取（省一次 fetch）
            if (!hit) {
                uvec4 cell1 = texelFetch(VoxelField, texel + 1);
                if ((cell1.x & 0x80000000u) != 0u) {
                    adaptUnpackBox(cell1.x, boxMin, boxMax);
                    hit = adaptRayBox(localOrigin, signedInvDir, boxMin, boxMax, segment);
                }
                if (!hit && (cell1.y & 0x80000000u) != 0u) {
                    adaptUnpackBox(cell1.y, boxMin, boxMax);
                    hit = adaptRayBox(localOrigin, signedInvDir, boxMin, boxMax, segment);
                }
                if (!hit && (cell1.z & 0x80000000u) != 0u) {
                    adaptUnpackBox(cell1.z, boxMin, boxMax);
                    hit = adaptRayBox(localOrigin, signedInvDir, boxMin, boxMax, segment);
                }
                if (!hit && (cell1.w & 0x80000000u) != 0u) {
                    adaptUnpackBox(cell1.w, boxMin, boxMax);
                    hit = adaptRayBox(localOrigin, signedInvDir, boxMin, boxMax, segment);
                }
            }
            if (hit) {
                bool translucent = (cell0.x & 0x40000000u) != 0u;
                if (!translucent) {
                    return 0.0; // 实心几何真命中 = 完全遮挡
                }
                // 半透明（树叶/玻璃/水）：**按格数累乘会分层**。
                // 0.62^N 在 N=1/2/3 时是 0.62/0.38/0.24，叶墙厚度不均就出现
                // 明显的两档暗度（实测"同一堵墙（树叶）有深浅两道"），
                // 累到 <0.02 还直接全黑。改成固定衰减一次并立即返回：
                // 树叶墙无论多厚都是同一档柔和阴影，这也更接近真实叶簇
                // （光在叶间散射，不是逐层指数衰减）。
                return transmittance * ADAPT_TRANSLUCENT_TRANSMIT;
            }
        }
        // 推进到下一个体素（选 tMax 最小的轴）。tCurrent 记录本格入口的 t，
        // 供下一格算局部起点。先判越界再推进：若下一个边界已超出射线终点，
        // 说明剩下的路都在当前体素内，遍历结束。
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            if (tMax.x > total) { break; }
            tCurrent = tMax.x;
            voxel.x += advance.x;
            tMax.x += tDelta.x;
        } else if (tMax.y < tMax.z) {
            if (tMax.y > total) { break; }
            tCurrent = tMax.y;
            voxel.y += advance.y;
            tMax.y += tDelta.y;
        } else {
            if (tMax.z > total) { break; }
            tCurrent = tMax.z;
            voxel.z += advance.z;
            tMax.z += tDelta.z;
        }
    }
    return transmittance;
}

/**
 * 带遮挡的 halo 点光照明（延迟光照 pass 用）。与 adaptHaloLights 完全同一套
 * GGX/衰减，只是每个光源额外做一次世界空间体素遮挡测试。
 * <p>
 * worldRelPos 是世界相对位置（G-buffer GB4，即 regionPos - camPos），
 * VoxelOrigin.xyz 也是相对相机的网格原点，所以 worldRelPos - VoxelOrigin.xyz
 * 直接就是体素空间坐标 — 全程不需要相机绝对坐标，也避免了远离世界原点时
 * 的 float 精度损失。
 */
// 增长式光源列表（texel buffer）。旧实现用 UBO 里的 vec4 HaloLightPos[32]，
// 定长数组 = 硬上限 32，实测表现为"显示范围内有些光没法显示"。texel buffer
// 的元素数 Blaze3D 完全不校验（maxTexelBufferElements 全 jar 0 命中），
// 容量由 LightListBuffer 按需翻倍。布局：每光源 2 条 RGBA32_FLOAT texel，
// texel 2i = xyz 视空间位置 / w 半径
// texel 2i+1 = rgb 颜色 / a 强度
uniform samplerBuffer LightList;
uniform usamplerBuffer LightTiles;

/**
 * 每像素最多对几盏灯各做一次阴影射线。
 * <p>
 * <b>为什么不再是"选 N 盏灯 + 共享一个标量可见性"。</b>上一版把颜色确定性
 * 累加（全部灯），可见性用蓄水池选 2 盏灯的加权平均<b>标量</b>，最后
 * {@code 全部灯的颜色 × 那个标量}。这在几何上是错的：栅栏外面有灯、里面
 * 也有灯时，外面那盏更亮 → 优先被采样 → 它被栅栏挡住 → visibility≈0 →
 * <b>里面那盏灯的贡献也一起被乘成 0</b>。实测"栅栏围一圈，里面没光"。
 * 遮挡是<b>每盏灯各自</b>的属性，不能共享。
 * <p>
 * 现在深度分簇（LightTileGrid 加了 48 个深度切片）把候选灯从 87–194 降到
 * 个位数，逐灯阴影变得可负担：每盏灯的颜色乘它<b>自己</b>的可见性，
 * 栅栏内外互不干扰，而且完全确定性 — 没有随机采样，就没有噪点、
 * 没有多重影子拷贝、不依赖降噪收敛。
 * <p>
 * 16 是防御上限（真实值是个位数）。超出的灯仍然贡献光照，只是不付阴影
 * 代价（按无遮挡处理）— 宁可漏遮挡也不要丢灯。
 */
#define ADAPT_MAX_SHADOW_LIGHTS 16

/**
 * 体素 DDA 的距离分档预算（返回允许的最大步数，0 = 完全跳过）。
 * <p>
 * 近处（≤SampleParams.z 格）给满 128 步；远处（≥SampleParams.w 格）返回 0
 * 直接不追；中间线性压缩到 12 步。这是你提的"远处降采样、近处恢复"，
 * 也是 AC Shadows / Lumen 档位设计的同一思路 — 每档约为上一档一半成本。
 * <p>
 * 分档只影响<b>阴影</b>，不影响光照本身：远处的灯照样照亮，只是不投影。
 * 视觉上远处的点光阴影本来就只有几个像素宽，看不出来。
 */
int adaptVoxelStepBudget(float viewDistance) {
    float near = SampleParams.z;
    float far = SampleParams.w;
    if (far <= 0.0) {
        return 128;   // 未配置：保持全质量
    }
    if (viewDistance >= far) {
        return 0;
    }
    if (viewDistance <= near) {
        return 128;
    }
    float t = (viewDistance - near) / max(far - near, 0.0001);
    return int(mix(128.0, 12.0, t));
}

/**
 * 体素场可视化（SampleParams.y，见 AdaptLightingUniform.DEBUG_VOXEL）。
 * 返回是否命中调试模式；命中时 out 参数是要直接输出的颜色。
 * <p>
 * 存在的理由：栅栏的问题前两轮都靠从 VoxelShape 数据反推，而
 * -Dadapt.dumpVoxelShapes 那条路的启动参数一直没生效。把体素场内容直接画
 * 到屏幕上，走到栅栏边一看就知道那一格存的是"柱+杆"还是"整格实心"。
 */
bool adaptVoxelDebug(vec3 worldRelPos, out vec3 debugColor) {
    int mode = int(SampleParams.y);
    if (mode <= 0) {
        return false;
    }
    int size = int(VoxelOrigin.w);
    vec3 fragVoxel = worldRelPos - VoxelOrigin.xyz;
    ivec3 voxel = ivec3(floor(fragVoxel));
    if (size <= 0 || any(lessThan(voxel, ivec3(0))) || any(greaterThanEqual(voxel, ivec3(size)))) {
        debugColor = vec3(0.0, 0.0, 0.4);   // 深蓝 = 场外
        return true;
    }
    int texel = adaptVoxelIndex(voxel, size) * ADAPT_TEXELS_PER_VOXEL;
    uvec4 cell0 = texelFetch(VoxelField, texel);
    uvec4 cell1 = texelFetch(VoxelField, texel + 1);
    uint words[8] = uint[](cell0.x, cell0.y, cell0.z, cell0.w,
                           cell1.x, cell1.y, cell1.z, cell1.w);

    if (mode == 1) {
        // 有效槽位数：黑=空气 红=1 黄=4 白=8
        int slots = 0;
        for (int i = 0; i < 8; ++i) {
            if ((words[i] & 0x80000000u) != 0u) slots++;
        }
        float t = float(slots) / 8.0;
        debugColor = slots == 0 ? vec3(0.0) : vec3(1.0, t, t * t);
        return true;
    }

    if ((words[0] & 0x80000000u) == 0u) {
        debugColor = vec3(0.0);
        return true;
    }
    vec3 boxMin;
    vec3 boxMax;
    adaptUnpackBox(words[0], boxMin, boxMax);
    // mode 2 = 第一个 AABB 的边长；mode 3 = 它的 min 角
    debugColor = mode == 2 ? (boxMax - boxMin) : boxMin;
    return true;
}

/**
 * 局部光直接照明 + 逐灯体素阴影（clustered forward，确定性）。
 * <p>
 * 候选灯来自 LightTileGrid 的深度分簇（16×16 屏幕格 × 48 个 8 格深度切片，
 * CSR 布局）。加深度这一维之前，一个屏幕格是一条从相机延伸到渲染边界的长
 * 棱锥，深度方向完全不过滤 — 实测 tileAvg=87 tileMax=194，而点光半径只有
 * 5.5–7 格，真正能照到某像素的灯只有个位数，90% 以上的候选是白算的。
 * <p>
 * 候选数降到个位数后，可以给<b>每盏灯各发一条阴影射线</b>，不再需要随机
 * 采样 + 共享标量可见性（那是"栅栏围起来里面没光"的根因，见
 * ADAPT_MAX_SHADOW_LIGHTS 的说明）。确定性 = 无噪点、无多重影子、
 * 不依赖降噪收敛。
 */
vec3 adaptHaloLightsOccluded(vec3 V, vec3 N, vec3 fragPos, vec3 worldRelPos,
                             vec3 albedo, vec3 F0, float roughness) {
    int grid = int(ScreenParams.z);
    int slices = int(ClusterParams.x);
    float sliceThickness = max(ClusterParams.y, 0.0001);
    int clusterCount = grid * grid * slices;

    int count;
    int bodyBase;
    bool clustered = false;
    if (grid > 0 && slices > 0) {
        ivec2 pixel = ivec2(gl_FragCoord.xy);
        ivec2 screen = max(ivec2(ScreenParams.xy), ivec2(1));
        int tx = clamp(pixel.x * grid / screen.x, 0, grid - 1);
        int ty = clamp(pixel.y * grid / screen.y, 0, grid - 1);
        // 26.2 视空间 -Z 朝前，所以视距 = -fragPos.z。
        int slice = clamp(int(floor(-fragPos.z / sliceThickness)), 0, slices - 1);
        int cluster = (slice * grid + ty) * grid + tx;
        // CSR：行指针相邻两项之差就是这一簇的灯数，容量按需分配，
        // 结构上不可能丢灯 — 不再需要溢出哨兵。
        int rowStart = int(texelFetch(LightTiles, cluster).r);
        int rowEnd = int(texelFetch(LightTiles, cluster + 1).r);
        count = rowEnd - rowStart;
        bodyBase = clusterCount + 1 + rowStart;
        clustered = true;
    } else {
        count = int(HaloLightCount);
        bodyBase = 0;
    }
    if (count <= 0) {
        return vec3(0.0);
    }

    // 片元的体素空间坐标
    vec3 fragVoxel = worldRelPos - VoxelOrigin.xyz;
    // 世界空间的法线（视图→世界用 ViewRotCol 三列，见 adapt_lighting.glsl 顶部）
    vec3 worldN = ViewRotCol0.xyz * N.x + ViewRotCol1.xyz * N.y + ViewRotCol2.xyz * N.z;
    // 起点只推出 1/64 格 — 只为躲开浮点误差导致的 floor 归属摇摆，
    // **不是**为了躲开自己所在的实心体素（那个由 skipVoxel 精确处理）。
    
    // 旧值 0.55 是错的：地面像素的射线起点因此落在 y=0.55，而栅栏横杆的
    // 局部高度带是 y=0.375..0.9375 — 射线整条在横杆里穿行，被判遮挡。
    // 实测"栅栏围一圈里面没光"，且孤立栅栏（只有柱子、没有横杆）正常。
    vec3 startVoxel = fragVoxel + worldN * 0.015625;
    // 片元"自己"那一格：必须从**沿法线往里推**的点算，不能直接 floor(fragVoxel)。
    
    // 表面通常正好落在体素边界上：地面方块占世界 y=-1，它的顶面在 y=0.0，
    // floor(0.0)=0 — 那是<b>上方</b>那一格。若上方那格是栅栏，skipVoxel 就
    // 指向栅栏，于是栅栏被跳过，紧贴栅栏的地面没有影子（实测"靠近栅栏的地方
    // 有时影子不会显示"）。往里推 1/64 后 floor(-0.0156) = -1，正确指向地面
    // 自己那一格。
    ivec3 selfVoxel = ivec3(floor(fragVoxel - worldN * 0.015625));

    vec3 total = vec3(0.0);
    int shadowBudget = ADAPT_MAX_SHADOW_LIGHTS;
    // 距离分档：远处的点光阴影在屏幕上只有几个像素，不该付全价 DDA。
    // 0 = 完全跳过（灯照样亮，只是不投影）。
    int stepBudget = adaptVoxelStepBudget(length(fragPos));

    for (int n = 0; n < count; ++n) {
        int i = clustered ? int(texelFetch(LightTiles, bodyBase + n).r) : n;
        vec4 lightPos = texelFetch(LightList, i * 2);

        vec3 delta = lightPos.xyz - fragPos;
        float dist = length(delta);
        float radius = lightPos.w;
        // 簇是保守包围盒，落在簇里不等于落在半径内 — 这里才是精确判据。
        if (dist >= radius || dist < 0.0001) {
            continue;
        }
        vec3 L = delta / dist;
        float NdotL = dot(N, L);
        // 背光面贡献恒 0，跳过（也省掉一条阴影射线）
        if (NdotL <= 0.0) {
            continue;
        }

        vec4 lightColor = texelFetch(LightList, i * 2 + 1);
        float atten = clamp(1.0 - dist / radius, 0.0, 1.0);
        atten = atten * atten * (3.0 - 2.0 * atten);

        // 这盏灯**自己**的可见性。预算用尽后按无遮挡处理 — 宁可漏遮挡
        // 也不要丢灯（真实候选数是个位数，预算 16 基本用不满）。
        float visibility = 1.0;
        if (shadowBudget > 0 && stepBudget > 0) {
            shadowBudget--;
            vec3 worldL = ViewRotCol0.xyz * L.x + ViewRotCol1.xyz * L.y + ViewRotCol2.xyz * L.z;
            visibility = adaptVoxelOcclusion(startVoxel, fragVoxel + worldL * dist,
                    selfVoxel, stepBudget);
            if (visibility <= 0.0) {
                continue;
            }
        }

        vec3 radiance = lightColor.rgb * (lightColor.a * atten * visibility) * HALO_RADIANCE_GAIN;
        total += adaptLightingGGX2(V, N, L, albedo, radiance, F0, roughness, 0.0);
    }
    return total;
}
