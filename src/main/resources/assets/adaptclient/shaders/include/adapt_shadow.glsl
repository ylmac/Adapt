#version 330

// Native Vulkan directional shadow receiver — VERIFIED by the offscreen
// Vulkan simulator (shadow-sim/ShadowSimVk.java) against Beryl's chain.
//
// Verified facts:
//  - LightViewProjection = lightProjection * lightView * T(camPos % 2)
//  - Input position is WORLD-RELATIVE (regionPos - camPos), same as the caster
//    geometry -> the shadow is fixed on world geometry (no view-rotation math).
//  - Depth is forward-Z after the distort remap (z*0.5+0.5): near light = small,
//    far = large. Caster: clear 1.0, depthFunc LESS_OR_EQUAL.
//  - 26.2's Vulkan textures are NOT y-flipped (unlike VulkanMod): the receiver
//    samples with uv = ndc.xy*0.5+0.5 DIRECTLY (no `y = -y`).
//  - Lit when compareDepth <= storedDepth (forward-Z, LESS compare semantics).

layout(std140) uniform ShadowFrame {
    mat4 LightViewProjection; // world-relative + T(camPos%2) -> light clip
    mat4 ViewToWorld;         // unused (kept for layout compatibility)
    vec4 ShadowParams;        // x=texel size, y=深度/格, z=texel 的世界尺度(格), w=distortion(恒0)
    mat4 SkyViewProjection;   // 144 顶视正交 VP（天光可见度图）
    vec4 SkyParams;           // 208 x=texel size, y=depth bias, z=正交半边长(格), w=深度/格
};

uniform sampler2D ShadowMapSampler;
uniform sampler2D SkyOcclusionSampler;

/**
 * 天光可见度（"这一点能看到多少天空"），原版 lightmap sky 通道的自建替代。
 * <p>
 * <b>为什么不能是二值的顶视深度比较。</b>上一版对同一个 texel 做
 * {@code fragDepth <= stored + bias ? 1 : 0}，3×3 求平均。那是**方向性**的
 * 遮挡测试：光源被隐含地设成"正上方无穷远的点"，于是任何遮挡物都在地面上
 * 投出一条与太阳方向无关的、垂直向下的硬轮廓。实测正是"树叶以及不知道
 * 什么，都会有一个笔直在地面上的影子 90°……太阳也会产生真的位置的影子，
 * 还会多一个这个奇怪位置的影子"。3×3 的平均只有 1 个 texel 的核，
 * 换算到世界尺度是 0.25 格，完全糊不掉边缘。
 * <p>
 * <b>正确语义是半球可见度（AO/天光锥），不是方向影。</b>天空是面积光源，
 * 覆盖整个上半球。一个点的天光可见度取决于**它周围一大片**头顶有没有东西，
 * 而且随距离平滑衰减 —— 屋檐外一格就该几乎全亮，不该有硬边。
 * <p>
 * 实现：在顶视图上按世界尺度做多环锥形采样（半径 0.5→6 格），每个样本比较
 * "头顶遮挡物比我高多少"，用 smoothstep 把它变成 0-1 的软遮挡（高出 0 格
 * 不遮，高出 4 格才算全遮），再按 1/(1+r) 的余弦式权重加权平均。等效于对
 * 半球做一次粗糙的可见度积分，天生没有方向性，也天生没有硬边。
 * <p>
 * 返回 0（洞穴深处）到 1（完全露天）。边界外返回 1.0 = 露天（错判成露天
 * 只是局部偏亮，错判成全遮会让远处地形整片变黑）。
 */
float adaptSkyVisibility(vec3 worldRelPos) {
    vec4 clip = SkyViewProjection * vec4(worldRelPos, 1.0);
    if (clip.w <= 0.0) {
        return 1.0;
    }
    vec3 projected = clip.xyz / clip.w;

    // 与方向影一致：**不 distort**（caster 的 SD 已经是 0，见
    // sodium_shadow.vsh 与 AdaptShadowMap.SHADOW_DISTORTION）。
    vec2 uv = projected.xy * 0.5 + 0.5;
    if (uv.x <= 0.0 || uv.x >= 1.0 || uv.y <= 0.0 || uv.y >= 1.0) {
        return 1.0;
    }
    // caster 对 z 做了 z*0.5+0.5 重映射，receiver 同样处理才能比较。
    float fragDepth = projected.z * 0.5 + 0.5;
    if (fragDepth <= 0.0 || fragDepth >= 1.0) {
        return 1.0;
    }

    float depthPerBlock = SkyParams.w;      // 一格世界高度 = 多少深度单位
    float halfExtent = max(SkyParams.z, 1.0); // 正交半边长（格）
    // 世界一格对应的 UV 步长。不 distort 后是恒定比例，邻域采样精确落在
    // 指定的世界距离上（distort 时这里只能局部线性近似）。
    float uvPerBlock = 0.5 / halfExtent;
    float bias = SkyParams.y;

    // 三环锥形采样：半径 0.5 / 2 / 5 格，每环 6 个方向（黄金角错开，避免
    // 与方块网格共振产生条纹）。总 19 个 tap，覆盖 ±5 格的世界邻域。
    const int RINGS = 3;
    const float RADII[RINGS] = float[](0.5, 2.0, 5.0);
    // 权重按余弦加权半球积分近似：近处样本代表天顶方向（贡献大），
    // 远处代表接近地平的方向（贡献小）。
    const float RING_WEIGHT[RINGS] = float[](1.0, 0.55, 0.25);

    // 中心样本（权重最高，代表天顶）
    float stored = texture(SkyOcclusionSampler, uv).r;
    // stored 是该列最靠天空的方块深度（forward-Z，小=更靠上）。
    // (fragDepth - stored) / depthPerBlock = 遮挡物比我高多少格。
    float heightAbove = (fragDepth - stored - bias) / depthPerBlock;
    // 软遮挡：高出 0 格不算遮（自己就是最高的），高出 4 格算全遮。
    // 这条 smoothstep 是"没有硬边"的关键 —— 二值比较在这里产生硬轮廓。
    float occ = smoothstep(0.0, 4.0, heightAbove);
    float total = 1.5 * occ;
    float weightSum = 1.5;

    for (int ring = 0; ring < RINGS; ++ring) {
        float radius = RADII[ring] * uvPerBlock;
        float w = RING_WEIGHT[ring];
        for (int i = 0; i < 6; ++i) {
            // 每环旋转 0.4 弧度，避免各环样本在同一条射线上
            float angle = (float(i) + float(ring) * 0.4) * 1.0471975512;
            vec2 offset = vec2(cos(angle), sin(angle)) * radius;
            vec2 sampleUv = uv + offset;
            if (any(lessThanEqual(sampleUv, vec2(0.0)))
                    || any(greaterThanEqual(sampleUv, vec2(1.0)))) {
                // 图外按露天算（与整体边界策略一致）
                weightSum += w;
                continue;
            }
            float s = texture(SkyOcclusionSampler, sampleUv).r;
            float h = (fragDepth - s - bias) / depthPerBlock;
            total += w * smoothstep(0.0, 4.0, h);
            weightSum += w;
        }
    }

    return clamp(1.0 - total / weightSum, 0.0, 1.0);
}

float adaptShadowVisibility(vec3 worldRelPos, vec3 normalView) {
    vec4 lightClip = LightViewProjection * vec4(worldRelPos, 1.0);
    if (lightClip.w <= 0.0) {
        return 1.0;
    }

    vec3 projected = lightClip.xyz / lightClip.w;
    // **不做 Beryl 的 distort**（ShadowParams.w 恒 0）。
    //
    // Beryl 的 receiver 之所以能 distort，是因为它在 terrain.vsh:110 的
    // **顶点阶段**做 —— 光栅器的线性插值误差 caster/receiver 两边都有、正好
    // 抵消。Adapt 是延迟管线，receiver 是全屏 pass，只能逐像素精确求 distort，
    // 而 caster 仍是逐顶点 + 线性插值。离线数值（DistortSim，16 格 quad）：
    //   中点ndc 0.042 → caster 插值 0.23810，receiver 精确 0.30303
    //                  差 66 texel ≈ 1.25 格
    //   中点ndc 0.442 → 差 1 texel ≈ 0.52 格
    // x/(|x|·0.9+0.1) 是强非线性，屏幕中心（玩家脚下）错位最大 —— 实测
    // "太阳影子有点错位"。逐 quad 的顶点阶段在延迟管线里不存在，这个误差
    // 原理上无法消除，所以两边一起不 distort：恒等变换，误差 0。
    // 代价是每格从 distort 的中心 10× 放大降到均匀 5.33 texel/格。
    projected.z = projected.z * 0.5 + 0.5;

    // NO y flip: 26.2 Vulkan texture v matches image v (top = 0).
    vec2 shadowUv = projected.xy * 0.5 + 0.5;
    float projZ = projected.z;
    if (shadowUv.x <= 0.0 || shadowUv.x >= 1.0
            || shadowUv.y <= 0.0 || shadowUv.y >= 1.0
            || projZ <= 0.0 || projZ >= 1.0) {
        return 1.0;
    }

    vec3 lightDirection = normalize(SunDirection);
    // bias 全部用**世界格**表达，再乘 depthPerBlock 换成深度单位。
    //
    // 旧式是 Beryl 的 `1e-4 + 0.002*dist²`：换算过后常量项只有 0.05 格
    // （挡不住自阴影），dist² 项在屏幕边缘是 2.05 格（影子从投影物脚下脱开
    // ——错位的第二个来源）。dist² 本来是给 distort 的可变 texel 足迹擦屁股，
    // distort 去掉后它是纯误差，必须一起删。
    //
    // 现在：常量 0.05 格打底 + 斜率项。斜率项的物理含义是"一个 texel 宽的
    // 斜面，两端的高度差"= texelWorldSize × tan(θ)，θ 是面与光的夹角。
    // tan = sqrt(1-c²)/c，c=NdotL；c 很小时 tan 爆炸，所以 clamp 到 4 倍
    // texel（掠射角的自阴影交给 PCF 的软边吃掉，比让 bias 爆炸好）。
    float depthPerBlock = ShadowParams.y;
    float texelWorld = ShadowParams.z;
    float NdotL = clamp(dot(normalize(normalView), lightDirection), 0.0, 1.0);
    float tanTheta = min(sqrt(max(1.0 - NdotL * NdotL, 0.0)) / max(NdotL, 0.05), 4.0);
    float biasBlocks = 0.05 + texelWorld * (0.5 + tanTheta);
    float compareDepth = projZ - biasBlocks * depthPerBlock;
    float texel = ShadowParams.x;

    // 3×3 PCF，间距 1 texel。4096² 下一个 texel = 0.09375 格，所以整个核
    // 只有 0.28 格宽 —— 影子边缘锐利。（2048² 时是 0.56 格，实测"好糊"，
    // 那是分辨率不足而不是核太大：distort 去掉后每格只剩 5.33 texel。）
    float visibility = 0.0;
    // Forward-Z: a fragment is LIT when its depth is not beyond the nearest
    // occluder: compareDepth <= storedDepth (stored smaller = closer to light).
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            float storedDepth = texture(ShadowMapSampler,
                    shadowUv + vec2(float(x), float(y)) * texel).r;
            visibility += compareDepth <= storedDepth ? 1.0 : 0.0;
        }
    }
    return visibility / 9.0;
}
