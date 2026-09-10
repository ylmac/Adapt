#version 330 core

// G-buffer 可视化调试 pass —— 直接输出 G-buffer 内容，跳过光照。
// 用途：区分"G-buffer 没写入"（全黑/全零）与"光照算出来是黑"（G-buffer 有内容但 lit=0）。
// 显示模式由 Java 侧 DebugConfig UBO 控制：
//   1 = albedo（GB2.rgb）
//   2 = 法线（GB1.rgb * 0.5 + 0.5）
//   3 = 视空间位置（GB0.rgb * 0.05 缩放可视化）
//   4 = coverage（GB0.w，白色=几何）
//   5 = 世界相对位置（GB4.rgb，按 0.02 缩放）
//   6 = lmCoordM（GB5.xy：R=blockLight G=skyLight）

uniform sampler2D GB0Sampler;
uniform sampler2D GB1Sampler;
uniform sampler2D GB2Sampler;
uniform sampler2D GB3Sampler;
uniform sampler2D GB4Sampler;
uniform sampler2D GB5Sampler;
uniform sampler2D MainColorSampler;

layout(std140) uniform DebugConfig {
    int DebugMode;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 gb0 = texture(GB0Sampler, texCoord);
    float coverage = gb0.w;
    if (coverage < 0.5) {
        fragColor = texture(MainColorSampler, texCoord);
        return;
    }

    vec4 gb1 = texture(GB1Sampler, texCoord);
    vec4 gb2 = texture(GB2Sampler, texCoord);
    vec4 gb4 = texture(GB4Sampler, texCoord);
    vec4 gb5 = texture(GB5Sampler, texCoord);

    if (DebugMode == 1) {
        fragColor = vec4(gb2.rgb, 1.0);
    } else if (DebugMode == 2) {
        fragColor = vec4(gb1.rgb * 0.5 + 0.5, 1.0);
    } else if (DebugMode == 3) {
        fragColor = vec4(gb0.rgb * 0.05 + 0.5, 1.0);
    } else if (DebugMode == 4) {
        fragColor = vec4(vec3(coverage), 1.0);
    } else if (DebugMode == 5) {
        fragColor = vec4(fract(gb4.rgb * 0.02 + 0.5), 1.0);
    } else if (DebugMode == 6) {
        fragColor = vec4(gb5.x, gb5.y, 0.0, 1.0);
    } else {
        fragColor = texture(MainColorSampler, texCoord);
    }
}
