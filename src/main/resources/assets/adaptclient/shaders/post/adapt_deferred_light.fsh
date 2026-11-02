#version 330 core

// Deferred 光照 pass（全屏，MRT 两目标）。

// 目标 0 = 场景 HDR（太阳 + 环境 + albedo，线性、未 tonemap）
// 目标 1 = 局部光的随机采样结果（RIS，1-2 条阴影射线，有噪点）

// 拆两个目标是 MegaLights 范式的前提：局部光要单独降噪，而太阳/环境是
// 解析解、没有噪点，不该被降噪器糊。tonemap 统一推迟到 composite pass —
// 在非线性空间里降噪会把亮部糊成灰。

#moj_import <adaptclient:adapt_lighting.glsl>
#moj_import <adaptclient:adapt_shadow.glsl>
// 体素遮挡场 + 随机直接光。必须在 adapt_lighting 之后 — 它依赖那里的
// AdaptLighting UBO 与 adaptLightingGGX2。且**只有本 pass** 引入：uniform
// usamplerBuffer 若放进共享 include，所有 import 的 pipeline 都会声明它，
// 而它们的 BindGroupLayout 没有 VoxelField → rebind 抛 ShaderCompileException。
#moj_import <adaptclient:adapt_voxel.glsl>

in vec2 texCoord;

uniform sampler2D GB0Sampler;   // xyz=视空间位置 w=coverage
uniform sampler2D GB1Sampler;   // xyz=视空间法线 w=roughness
uniform sampler2D GB2Sampler;   // rgb=albedo a=metallic
uniform sampler2D GB3Sampler;   // rgb=雾色 a=fogFactor
uniform sampler2D GB4Sampler;   // xyz=世界相对位置 w=vanillaAO
uniform sampler2D GB5Sampler;   // xy=lmCoordM z=emissive w=顶点色 alpha
uniform sampler2D MainColorSampler;
uniform sampler2D MainDepthSampler;

layout(location = 0) out vec4 sceneHdr;
layout(location = 1) out vec4 directLight;

void main() {
    vec4 gb0 = texture(GB0Sampler, texCoord);
    float coverage = gb0.w;
    if (coverage < 0.5) {
        // 背景像素：两个目标都清零，composite 会保留主颜色快照
        sceneHdr = vec4(0.0);
        directLight = vec4(0.0);
        return;
    }

    vec3 viewPos = gb0.xyz;
    vec4 gb1 = texture(GB1Sampler, texCoord);
    vec3 N = normalize(gb1.xyz);
    float roughness = gb1.w;
    vec4 gb2 = texture(GB2Sampler, texCoord);
    vec3 albedo = gb2.rgb;
    float metallic = gb2.a;
    vec4 gb4 = texture(GB4Sampler, texCoord);
    vec3 worldPos = gb4.xyz;
    float vanillaAO = gb4.w;
    vec4 gb5 = texture(GB5Sampler, texCoord);
    float emissive = gb5.z;
    float vertexAlpha = gb5.w;

    // 体素场可视化（-Dadapt.debugVoxel=1..3）。直接输出到场景目标并跳过
    // 全部光照 — 用来实测"栅栏那一格到底存了什么"，不再靠形状数据反推。
    vec3 debugColor;
    if (adaptVoxelDebug(worldPos, debugColor)) {
        sceneHdr = vec4(debugColor, 1.0);
        directLight = vec4(0.0);
        return;
    }

    // 太阳方向影（方向 shadow map）
    float shadowVisibility = adaptShadowVisibility(worldPos, N);
    // 天光可见度：现在是**半球锥形积分**（adapt_shadow.glsl 的多环软采样），
    // 不再是顶视二值深度比较。
    
    // 旧实现是方向性的：光源被隐含设成"正上方无穷远的点"，任何遮挡物都在
    // 地面上投出一条与太阳方向无关的垂直硬轮廓 — 实测"树叶都会有一个笔直
    // 在地面上的影子 90°，太阳同时还有真实位置的影子"。曾用
    // mix(0.35, 1.0, skyVis) 压过它，那只是把硬边缘整体抬高，边缘依然硬。
    
    // 现在的锥形积分本身就没有方向性、没有硬边，可以直接当 的 lightmap.y
    // 用（它在 里的语义正是"这一点能看到多少天空"）。洞穴深处→0，
    // 露天→1，屋檐下平滑过渡。
    float skyVis = adaptSkyVisibility(worldPos);
    vec2 lightInput = vec2(0.0, skyVis);

    vec4 color = vec4(albedo, vertexAlpha);
    adaptDoLighting(color, lightInput, N, worldPos, length(viewPos),
            shadowVisibility, vanillaAO, emissive, 0.0);
    sceneHdr = vec4(color.rgb, 1.0);

    // 局部光：加权蓄水池采样 + 每条射线一次体素 AABB 相交。
    // 成本与灯数解耦；噪点交给后续统一降噪。
    vec3 V = -normalize(viewPos);
    vec3 F0 = mix(vec3(0.04), albedo, metallic);
    directLight = vec4(
            adaptHaloLightsOccluded(V, N, viewPos, worldPos, albedo, F0, roughness),
            1.0);
}
