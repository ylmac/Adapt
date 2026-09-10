#version 330 core

// 统一合成 pass：HDR 场景 + 降噪后的直接光 → 单次 tonemap → 雾 → 主颜色。
//
// 为什么要单独一趟：旧管线在光照 pass 里当场 tonemap，导致直接光的降噪
// 只能在 tonemap 之后做（非线性空间里滤波会把亮部糊成灰）。现代管线
// （Lumen / MegaLights）统一在线性 HDR 空间累加所有光照，最后一次
// tonemap —— 各 pass 各自 tonemap 会让色彩空间不一致。

#moj_import <adaptclient:adapt_lighting.glsl>

in vec2 texCoord;

uniform sampler2D SceneSampler;     // 太阳 + 环境的 HDR 场景色（线性）
uniform sampler2D DirectSampler;    // 降噪后的局部光（线性 HDR）
uniform sampler2D GB0Sampler;       // xyz=视空间位置 w=coverage
uniform sampler2D GB3Sampler;       // rgb=雾色 a=fogFactor
uniform sampler2D MainColorSampler; // 主颜色快照（sky/实体已画）
uniform sampler2D MainDepthSampler; // 主深度快照（反转 Z）

out vec4 fragColor;

void main() {
    vec4 gb0 = texture(GB0Sampler, texCoord);
    if (gb0.w < 0.5) {
        // 背景像素（sky/实体已由各自管线写入主颜色）：原样保留
        fragColor = texture(MainColorSampler, texCoord);
        return;
    }
    vec3 viewPos = gb0.xyz;

    // 实体遮挡检查：G-buffer 只装地形，实体在 executeSolid 里画进主颜色 +
    // 主深度，时序上在 G-buffer 之后、本 pass 之前。若只看 coverage，
    // 实体像素处会拿它背后的地形数据覆盖掉已画好的实体。
    vec4 gbClip = ProjectionMatrix * vec4(viewPos, 1.0);
    if (gbClip.w > 0.0001) {
        float gbNdcDepth = gbClip.z / gbClip.w;
        float mainDepth = texture(MainDepthSampler, texCoord).r;
        // 反转 Z：mainDepth 明显大于地形深度 = 有更近的几何
        if (mainDepth > gbNdcDepth + 1e-5) {
            fragColor = texture(MainColorSampler, texCoord);
            return;
        }
    }

    vec3 scene = texture(SceneSampler, texCoord).rgb;
    vec3 direct = texture(DirectSampler, texCoord).rgb;
    vec4 gb3 = texture(GB3Sampler, texCoord);

    // 体素调试模式：场景目标里装的是原始诊断色，不能 tonemap 也不能混雾，
    // 否则读出来的数值不是写进去的那个（Lottes 是强非线性）。
    if (int(SampleParams.y) > 0) {
        fragColor = vec4(scene, 1.0);
        return;
    }

    // 线性空间累加，然后一次 tonemap（Reimagined composite5 的 Lottes）
    vec3 tone = adaptTonemapLottes(scene + direct);

    // alpha=1.0：主颜色在帧开始被清成 alpha=0，不透明地形被 G-buffer 接管后
    // 不再写主颜色 alpha，这里必须显式写 1.0，否则 swapchain alpha 合成会把
    // 这些像素显示为透明。
    fragColor = vec4(mix(tone, gb3.rgb, gb3.a), 1.0);
}
