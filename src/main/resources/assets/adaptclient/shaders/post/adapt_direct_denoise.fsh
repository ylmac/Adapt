#version 330 core

// 直接光降噪（MegaLights「所有光源共用一个降噪 pass」的实现）。
//
// 为什么必须有这一层：随机采样把成本从 O(灯数) 降到 O(1)，代价是噪点。
// Epic 的做法是所有灯共用一次降噪，而不是每盏灯各降一次 —— 后者成本又回到
// O(灯数)。AC Shadows 的实测里「降噪几乎占了整个 pass 的一半」，这是这条
// 路线的已知代价，换来的是成本与灯数无关。
//
// 实现：可分离双边滤波，横竖两趟（各 9 tap，等效 17×17 但只付 2×9 的代价）。
// 引导信号来自 G-buffer 的视空间位置与法线 —— 跨越几何边界不混合，
// 否则墙角会漏光。

#moj_import <adaptclient:adapt_lighting.glsl>

in vec2 texCoord;

uniform sampler2D DirectSampler;  // 上一趟的直接光（RGB=辐射）
uniform sampler2D GB0Sampler;     // xyz=视空间位置 w=coverage
uniform sampler2D GB1Sampler;     // xyz=视空间法线 w=roughness
uniform sampler2D GB4Sampler;     // xyz=世界相对位置 w=vanillaAO
uniform sampler2D HistorySampler; // 上一帧降噪结果（A 通道存历史视距）

layout(std140) uniform DenoiseConfig {
    vec4 DenoiseParams; // xy=滤波方向（1,0 或 0,1）zw=1/屏幕尺寸
    vec4 DenoiseFlags;  // x=是否做时间累积（最后一趟才做）y/z/w 保留
};

out vec4 fragColor;

void main() {
    vec4 center = texture(DirectSampler, texCoord);
    vec4 gb0 = texture(GB0Sampler, texCoord);
    // 背景像素（无几何）不参与降噪，原样透传
    if (gb0.w < 0.5) {
        fragColor = center;
        return;
    }
    vec3 centerPos = gb0.xyz;
    vec3 centerNormal = normalize(texture(GB1Sampler, texCoord).xyz);
    float centerDepth = length(centerPos);

    vec2 stepUv = DenoiseParams.xy * DenoiseParams.zw;

    // 5-tap 二项式核（1,4,6,4,1）扩展到 ±4 像素，跳步采样等效更宽的支撑
    const float weights[5] = float[](0.0625, 0.25, 0.375, 0.25, 0.0625);
    const float offsets[5] = float[](-4.0, -2.0, 0.0, 2.0, 4.0);

    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    for (int i = 0; i < 5; ++i) {
        vec2 uv = texCoord + stepUv * offsets[i];
        vec4 sampleGb0 = texture(GB0Sampler, uv);
        if (sampleGb0.w < 0.5) {
            continue;
        }
        // 几何相似度：深度差按视距归一化（远处容许更大差值），法线夹角
        float posDelta = length(sampleGb0.xyz - centerPos);
        float depthScale = max(centerDepth * 0.05, 0.05);
        float posWeight = exp(-posDelta / depthScale);
        vec3 sampleNormal = normalize(texture(GB1Sampler, uv).xyz);
        float normalWeight = pow(max(dot(sampleNormal, centerNormal), 0.0), 16.0);

        float w = weights[i] * posWeight * normalWeight;
        if (w <= 0.0) {
            continue;
        }
        sum += texture(DirectSampler, uv).rgb * w;
        weightSum += w;
    }

    // 全部邻居都被几何判据拒绝时保留中心值（宁可有噪点也不要漏光）
    vec3 spatial = weightSum > 0.0 ? sum / weightSum : center.rgb;

    if (DenoiseFlags.x < 0.5) {
        fragColor = vec4(spatial, centerDepth);
        return;
    }

    // ---- 时间累积（带重投影）----
    // 1 SPP 的空间滤波不足以压掉噪点（实测"噪点有点多，只有一盏灯时很少"
    // —— 灯多时被选中的那盏方差大）。加时间累积：把历史结果按指数移动平均
    // 混进来，等效采样数随帧数上升。
    //
    // **必须重投影**：同像素比较在相机旋转时抓不住几何变化（旋转前后深度
    // 可能接近），实测表现为"以前的画面和现在的画面堆在一起，只有站在原地
    // 很久才会收敛"。用 GB4 的世界相对位置反推上一帧的屏幕位置。
    if (CamDelta.w < 0.5) {
        // 上一帧数据无效（首帧/切世界）
        fragColor = vec4(spatial, centerDepth);
        return;
    }

    vec3 worldRel = texture(GB4Sampler, texCoord).xyz;
    // GB4 是"相对本帧相机"，加上相机位移得到"相对上一帧相机"
    vec4 prevClip = PrevViewProjection * vec4(worldRel - CamDelta.xyz, 1.0);
    if (prevClip.w <= 0.0001) {
        fragColor = vec4(spatial, centerDepth);
        return;
    }
    vec2 prevNdc = prevClip.xy / prevClip.w;
    vec2 prevUv = prevNdc * 0.5 + 0.5;
    if (any(lessThan(prevUv, vec2(0.0))) || any(greaterThan(prevUv, vec2(1.0)))) {
        // 上一帧看不到这个点（disocclusion）
        fragColor = vec4(spatial, centerDepth);
        return;
    }

    vec4 history = texture(HistorySampler, prevUv);
    float historyDepth = history.a;
    // 历史视距与本帧视距应当接近；差太多说明重投影落到了别的几何上
    float depthDiff = abs(historyDepth - centerDepth);
    float tolerance = max(centerDepth * 0.05, 0.1);
    bool valid = historyDepth > 0.0 && depthDiff < tolerance
            && all(equal(history.rgb, history.rgb)); // NaN 会让 == 为 false
    if (!valid) {
        // 深度突变或历史含 NaN（未初始化显存）→ 丢弃历史。
        // NaN 一旦进 EMA 会永久传播，实测表现为"地板莫名变绿"。
        fragColor = vec4(spatial, centerDepth);
        return;
    }
    // 0.85 历史权重 ≈ 7 帧有效窗口。重投影正确时可以更激进，但留余量
    // 防止残留拖影。
    fragColor = vec4(mix(history.rgb, spatial, 0.15), centerDepth);
}
