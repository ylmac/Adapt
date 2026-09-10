#version 330

// Adapt 自有渲染管线（Beryl 移植）统一光照 UBO。
// CPU 侧由 com.ylmac.adapt.render.AdaptLightingUniform 按 std140 写入，
// 大小 1248 字节，与下述布局逐字节对应（后置偏移随 MAX_HALO_LIGHTS 伸缩）。

layout(std140) uniform AdaptLighting {
    vec3 SunDirection;        // 0   视空间太阳方向（夜间=月球方向，Beryl celestialAngle+0.5）
    float SunIntensity;       // 12
    vec3 SunColor;            // 16  太阳/月光颜色（夜间 0.2*LightIntensity 灰色）
    float Padding0;           // 28
    vec3 AmbientColor;        // 32  环境光颜色
    float Padding1;           // 44
    float AmbientLightFactor; // 48  Beryl: (LdotUp<0.25 ? LdotUp*2+0.5 : 1.0) * 0.3
    float NightFactor;        // 52  Beryl NightMultiplier
    float MinAmbientLight;    // 56  Beryl: gamma*0.05+0.01
    float HaloLightCount;     // 60
    vec4 HaloLightPos[32];    // 64  xyz=视空间位置 w=半径
    vec4 HaloLightColor[32];  // 576 rgb=颜色 a=强度
    vec4 UpVector;            // 1088 视空间世界上方向（Beryl UpVector，地形用地平线衰减/地面反弹）
    vec4 SkyFactors0;         // 1104 x=rainStrength y=timeAngle z=moonPhase(Iris编码) w=云高度
    vec4 ViewRotCol0;         // 1120 视图旋转矩阵列0（星星/月亮世界方向用）
    vec4 ViewRotCol1;         // 1136
    vec4 ViewRotCol2;         // 1152
    mat4 ProjectionMatrix;    // 1168 视空间→裁剪空间（延迟点光阴影 SSRT 用）
    vec4 ScreenParams;        // 1232 x=宽 y=高 z=簇网格边长(屏幕方向) w=保留
    vec4 VoxelOrigin;         // 1248 xyz=体素网格原点（**相对相机**）w=网格边长
    vec4 SampleParams;        // 1264 x=帧序号 y=体素调试模式 z=DDA 近阈值(格) w=DDA 远阈值(格)
    mat4 PrevViewProjection;   // 1280 上一帧世界（相对上一帧相机）→裁剪
    vec4 CamDelta;            // 1344 xyz=上一帧相机-本帧相机 w=历史有效
    vec4 ClusterParams;       // 1360 x=深度切片数 y=每片厚度(格) z/w 保留
};                            // 1376

float adaptSaturate(float f) {
    return clamp(f, 0.0, 1.0);
}

// ---- Beryl lighting.glsl GGX（learnopengl PBR cook-torrance）----

float adaptGeometrySchlickGGX(float NdotV, float roughness) {
    float r = (roughness + 1.0);
    float k = (r * r) * 0.125;
    float num = NdotV;
    float denom = NdotV * (1.0 - k) + k;
    return num / denom;
}

float adaptGeometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
    float NdotV = max(dot(N, V), 0.0);
    float NdotL = max(dot(N, L), 0.0);
    float ggx2 = adaptGeometrySchlickGGX(NdotV, roughness);
    float ggx1 = adaptGeometrySchlickGGX(NdotL, roughness);
    return ggx1 * ggx2;
}

vec3 adaptFresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

vec3 adaptLightingGGX2(vec3 V, vec3 N, vec3 L, vec3 albedo, vec3 radiance, vec3 F0,
                       float roughness, float metallic) {
    vec3 H = normalize(V + L);
    float a = roughness * roughness;
    float a2 = a * a;
    float NdotH = max(dot(N, H), 0.0);
    float NdotH2 = NdotH * NdotH;
    float num = a2;
    float denom = (NdotH2 * (a2 - 1.0) + 1.0);
    denom = 3.14159265 * denom * denom;
    float NDF = num / denom;
    float NdotV = max(dot(N, V), 0.0);
    float NdotL = max(dot(N, L), 0.0);
    float ggx2 = adaptGeometrySchlickGGX(NdotV, roughness);
    float ggx1 = adaptGeometrySchlickGGX(NdotL, roughness);
    float G = ggx1 * ggx2;
    vec3 F = adaptFresnelSchlick(max(dot(H, V), 0.0), F0);
    vec3 kS = F;
    vec3 kD = vec3(1.0) - kS;
    kD *= 1.0 - metallic;
    vec3 numerator = NDF * G * F;
    float denominator = 4.0 * NdotV * NdotL + 0.0001;
    vec3 specular = numerator / denominator;
    specular = min(specular * NdotL, 1.0) * radiance;
    vec3 Lo = (kD * albedo * 0.31830988618) * radiance * NdotL + specular;
    return Lo;
}

// Beryl LightingSphereGGX（entity.fsh 使用的版本，球面半径 0.042）
vec3 adaptLightingSphereGGX(vec3 V, vec3 N, vec3 L, vec3 albedo, vec3 radiance, vec3 F0,
                            float roughness, float metallic) {
    vec3 R = reflect(-V, N);
    vec3 centerToRay = (R - L);
    float radius = 0.042;
    float d = length(centerToRay);
    vec3 O = centerToRay * adaptSaturate(radius / d);
    L = L + O;
    L = normalize(L);

    vec3 H = normalize(V + L);
    float a = roughness * roughness;
    float a2 = a * a;
    float NdotH = max(dot(N, H), 0.0);
    float NdotH2 = NdotH * NdotH;
    float num = a2;
    float denom = (NdotH2 * (a2 - 1.0) + 1.0);
    denom = 3.14159265 * denom * denom;
    float NDF = num / denom;
    float NdotV = max(dot(N, V), 0.0);
    float NdotL = max(dot(N, L), 0.0);
    float ggx2 = adaptGeometrySchlickGGX(NdotV, roughness);
    float ggx1 = adaptGeometrySchlickGGX(NdotL, roughness);
    float G = ggx1 * ggx2;
    vec3 F = adaptFresnelSchlick(max(dot(H, V), 0.0), F0);
    vec3 kS = F;
    vec3 kD = vec3(1.0) - kS;
    kD *= 1.0 - metallic;
    vec3 numerator = NDF * G * F;
    float denominator = 4.0 * NdotV * NdotL + 0.0001;
    vec3 specular = numerator / denominator;
    specular = min(specular * NdotL, 1.0) * radiance;
    vec3 Lo = (kD * albedo * 0.31830988618) * radiance * NdotL + specular;
    return Lo;
}

// Beryl LightingSphereGGX2：UE4 球面光适配（把 L 朝反射向量收拢得到软高光）
vec3 adaptLightingSphereGGX2(vec3 V, vec3 N, vec3 L, vec3 albedo, vec3 radiance, vec3 F0,
                             float roughness, float metallic, int lightingType) {
    vec3 R = reflect(-V, N);
    vec3 centerToRay = (R - L);
    float radius = 0.025;
    float d = length(centerToRay);
    vec3 O = centerToRay * adaptSaturate(radius / d);
    L = L + O;
    L = normalize(L);
    float NdotL = max(dot(N, L), 0.0);
    // 叶片类几何改用上向量受光（Beryl lightingType==1 分支）
    NdotL = lightingType == 1 ? (dot(vec3(0.0, 1.0, 0.0), L) * 0.8) : NdotL;
    float NdotV = max(dot(N, V), 0.0);
    float ggx2 = adaptGeometrySchlickGGX(NdotV, roughness);
    float ggx1 = adaptGeometrySchlickGGX(NdotL, roughness);
    float G = ggx1 * ggx2;
    vec3 H = normalize(V + L);
    vec3 F = adaptFresnelSchlick(max(dot(H, V), 0.0), F0);
    vec3 kS = F;
    vec3 kD = vec3(1.0) - kS;
    kD *= 1.0 - metallic;
    float a = roughness * roughness;
    float a2 = a * a;
    float NdotH = max(dot(N, H), 0.0);
    float NdotH2 = NdotH * NdotH;
    float num = a2;
    float denom = (NdotH2 * (a2 - 1.0) + 1.0);
    denom = 3.14159265 * denom * denom;
    float NDF = num / denom;
    vec3 numerator = NDF * G * F;
    float denominator = 4.0 * NdotV * NdotL + 0.0001;
    vec3 specular = numerator / denominator;
    specular = min(specular * NdotL, 1.0) * radiance;
    vec3 Lo = (kD * albedo * 0.31830988618) * radiance * NdotL + specular;
    return Lo;
}

// ---- Adapt 扩展：光环局部点光源（Beryl 没有的部分）----

// 对视空间片元位置 fragPos 计算所有光环点光源的 GGX 贡献。
// 距离衰减与 HaloGlowRenderer 屏幕光晕一致（smoothstep 反距离）。
// 量级：halo 颜色/强度来自设置（0-1），但 GGX 的 diffuse 项乘 1/π
// （0.318）会把辐射压到 ~0.08×albedo —— 在 Lottes tonemap 后几乎不可见。
// Beryl 的太阳光辐射是 O(1-8) 的 HDR 量（LightColor*SunIntensity），点光源
// 要"照亮"周围方块必须同样以 HDR 辐射提交；这里乘 HALO_RADIANCE_GAIN 把
// 白色光环在 2-4 格内的照亮提升到肉眼清晰可见（Lottes hdrMax=8 内不裁切）。
const float HALO_RADIANCE_GAIN = 6.0;
vec3 adaptHaloLights(vec3 V, vec3 N, vec3 fragPos, vec3 albedo, vec3 F0, float roughness) {
    vec3 total = vec3(0.0);
    int count = int(HaloLightCount);
    if (count > 32) {
        count = 32;
    }
    for (int i = 0; i < count; ++i) {
        vec3 delta = HaloLightPos[i].xyz - fragPos;
        float dist = length(delta);
        float radius = HaloLightPos[i].w;
        if (dist >= radius || dist < 0.0001) {
            continue;
        }
        vec3 L = delta / dist;
        float atten = clamp(1.0 - dist / radius, 0.0, 1.0);
        atten = atten * atten * (3.0 - 2.0 * atten);
        vec3 radiance = HaloLightColor[i].rgb * (HaloLightColor[i].a * atten) * HALO_RADIANCE_GAIN;
        total += adaptLightingGGX2(V, N, L, albedo, radiance, F0, roughness, 0.0);
    }
    return total;
}

// ---- Complementary Reimagined r5 世界光照（mainLighting.glsl DoLighting 移植）----
// 逐行移植自 Reimagined r5：lib/lighting/mainLighting.glsl、
// lib/colors/lightAndAmbientColors.glsl、lib/colors/blocklightColors.glsl、
// lib/lighting/minimumLighting.glsl、lib/util/commonFunctions.glsl。
// 通用小函数名加 adapt 前缀，避免与 import 本文件的其它 shader
// （adapt_sky.fsh / reimagined_cloud.fsh 等自带 pow2/smoothstep1/GetLuminance）重名。
//
// 常量取值（Reimagined r5 默认，见 lib/common.glsl）：
//   XLIGHT_CURVE 1.00 / XLIGHT_I 1.00 / XLIGHT_RGB 1.00（L346-350）
//   VANILLAAO_I 100（L86，AO 以原版算法输出）、CAVE_LIGHTING 100（L218）
//   DIRECTIONAL_SHADING（L360）、SIDE_SHADOWING（L355）、LIGHT_HIGHLIGHT
//   （BLOCK_REFLECT_QUALITY >= 1，L26/L511）、AMBIENT_MULT 100（L221）
//   TM_EXPOSURE 1.00 / TM_CONTRAST 1.05（L275-276）
// 因子（lib/common.glsl L694-724）：
//   noonFactor = sqrt(max(sin(timeAngle*6.28318530718), 0.0))
//   invNoonFactor = 1.0 - noonFactor
//   vsBrightness = clamp(screenBrightness, 0.0, 1.0)（由 MinAmbientLight
//   = gamma*0.05+0.01 反推：clamp((MinAmbientLight - 0.01) * 20.0, 0.0, 1.0)）
// 方向向量（视空间）：
//   sunVec = normalize(SunDirection)（CPU 已把夜间翻转到月亮方向 →
//   DoLighting 里 lightVec = sunVec 直接等价 r5 gbuffers_terrain.glsl:70-74
//   OVERWORLD 的昼夜翻转；天空因子必须用真实太阳方向，见 adaptGetSunVecView）
//   upVec = normalize(UpVector.xyz)、eastVec = normalize(ViewRotCol0.xyz)（世界
//   +X 在视图）、northVec = normalize(ViewRotCol2.xyz)（世界 +Z 在视图）

float adaptPow2(float x) {
    return x * x;
}

vec3 adaptPow2(vec3 x) {
    return x * x;
}

// clamp01 的 vec3 重载：adapt_lighting 被 adapt_sky/reimagined_cloud 等
// import，float 版由各文件自带；这里只补 vec3 版（Lottes tonemap 用）。
vec3 clamp01(vec3 v) {
    return clamp(v, 0.0, 1.0);
}

float adaptSmoothstep1(float x) {
    return x * x * (3.0 - 2.0 * x);
}

float adaptMax0(float x) {
    return max(x, 0.0);
}

float adaptMin1(float x) {
    return min(x, 1.0);
}

float adaptClamp01(float x) {
    return clamp(x, 0.0, 1.0);
}

float adaptGetLuminance(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114)); // commonFunctions.glsl:23-25
}

// Reimagined r5 默认白天/夜晚/雨天光色与环境色
// （lightAndAmbientColors.glsl L6-26，SHADER_STYLE=4/COMPOSITE1 未定义分支）。
vec3 adaptGetLightAndAmbientColors(out vec3 lightColor, out vec3 ambientColor) {
    float timeAngle = SkyFactors0.y;
    float rainFactor = SkyFactors0.x;
    // common.glsl:716
    float noonFactor = sqrt(max(sin(timeAngle * 6.28318530718), 0.0));
    float invNoonFactor = 1.0 - noonFactor;
    float vsBrightness = clamp((MinAmbientLight - 0.01) * 20.0, 0.0, 1.0);

    // 真实太阳方向（视空间，不随 Beryl 夜间翻转）：SUN_ANGLE 0=正午 →
    // timeAngle 0.25 → 世界方向 (sin(elev), cos(elev), 0)；ViewRotCol 是
    // viewRotationMatrix（视图→世界）的三列，dot(列, worldDir) = 世界→视图
    // * worldDir = 视空间方向（与 adapt_sky.fsh computeFactors 同一推导）。
    float elevAngle = (timeAngle - 0.25) * 6.28318530718;
    vec3 worldSunDir = vec3(sin(elevAngle), cos(elevAngle), 0.0);
    vec3 sunVecView = normalize(vec3(dot(ViewRotCol0.xyz, worldSunDir),
                                     dot(ViewRotCol1.xyz, worldSunDir),
                                     dot(ViewRotCol2.xyz, worldSunDir)));

    // gbuffers_terrain.glsl:61-63（sunFactor 在 lightAndAmbientColors 里仅
    // skyColors 用，这里只算 sunVisibility）
    float SdotU = dot(sunVecView, UpVector.xyz);
    float sunVisibility = clamp(SdotU + 0.0625, 0.0, 0.125) / 0.125;
    float sunVisibility2 = sunVisibility * sunVisibility;

    // lightAndAmbientColors.glsl OVERWORLD 分支（逐字）
    vec3 noonClearLightColor = vec3(0.65, 0.55, 0.375) * 2.05;
    vec3 noonClearAmbientColor = pow(vec3(0.56, 0.62, 0.75), vec3(0.75)) * 0.85;
    // 注：noonClearAmbientColor = pow(skyColor, 0.75) * 0.85 —— skyColor 是
    // 原版天空色（ColorModulator.rgb），地形 shader 没有 ColorModulator，
    // 取 Reimagined 晴天正午的典型天空色常量近似（sunVisibility 混合后
    // 白天主导；adapt_sky.fsh 的 computeCloudColors 用 ColorModulator.rgb，
    // 这里不能用，因为 block 管线不提供该 uniform）。
    vec3 sunsetClearLightColor = pow(vec3(0.64, 0.45, 0.3), vec3(1.5 + invNoonFactor)) * 5.0;
    vec3 sunsetClearAmbientColor = noonClearAmbientColor * vec3(1.21, 0.92, 0.76) * 0.95;
    vec3 nightClearLightColor = 0.9 * vec3(0.15, 0.14, 0.20) * (0.4 + vsBrightness * 0.4);
    vec3 nightClearAmbientColor = 0.9 * vec3(0.09, 0.12, 0.17) * (1.55 + vsBrightness * 0.77);

    float noonFactorDM = noonFactor; // COMPOSITE1 未定义分支（L52）
    vec3 dayLightColor = mix(sunsetClearLightColor, noonClearLightColor, noonFactorDM);
    vec3 dayAmbientColor = mix(sunsetClearAmbientColor, noonClearAmbientColor, noonFactorDM);
    vec3 clearLightColor = mix(nightClearLightColor, dayLightColor, sunVisibility2);
    vec3 clearAmbientColor = mix(nightClearAmbientColor, dayAmbientColor, sunVisibility2);

    // 雨天：SUN_MOON_DURING_RAIN 已定义 → rainShadowVisReduce = 0（L62-72）
    vec3 dayRainLightColor = vec3(0.21, 0.16, 0.13) * 0.85 + noonFactor * vec3(0.0, 0.02, 0.06);
    vec3 dayRainAmbientColor = vec3(0.2, 0.2, 0.25) * (1.8 + 0.5 * vsBrightness);
    vec3 nightRainLightColor = vec3(0.03, 0.035, 0.05) * (0.5 + 0.5 * vsBrightness);
    vec3 nightRainAmbientColor = vec3(0.16, 0.20, 0.3) * (0.75 + 0.6 * vsBrightness);

    vec3 rainLightColor = mix(nightRainLightColor, dayRainLightColor, sunVisibility2) * 2.5;
    vec3 rainAmbientColor = mix(nightRainAmbientColor, dayRainAmbientColor * 1.0, sunVisibility2);

    lightColor = mix(clearLightColor, rainLightColor, rainFactor);
    ambientColor = mix(clearAmbientColor, rainAmbientColor, rainFactor);
    return sunVecView;
}

// Reimagined r5 DoLighting 的 OVERWORLD 核心（mainLighting.glsl 逐行移植）。
// 输入：
//   color      inout：albedo（sRGB 纹理色，不做 pow2.2 线性化 —— 与原版一致，
//              r5 gbuffers_terrain.glsl:219 color.rgb *= glColor.rgb 直接乘）
//   skyVis     0-1 **天光可见度**（Adapt 自建，非原版 lightmap）。
//              替代 r5 的 lightmap.y：语义等价（这一点能看到多少天空），但
//              来源是我们自己的天光遮蔽图/阴影几何，与原版光照数据完全无关。
//              原版 lightmap 是整数光照等级 + Sodium 平滑插值的产物，属于
//              "原版处理光照的部分"，按项目规则必须置空 —— 实测清零后
//              skyLightShadowMult=pow2(pow2(y²))=0 会把太阳、环境光、方向
//              阴影一起乘死，所以不能简单清零，必须换成自建数据源。
//   blockVis   0-1 方块光可见度：**恒 0**。方块光完全由 Adapt 的真实点光源
//              系统提供（BlockLightSourceCache → HaloLightPos/Color UBO →
//              adaptHaloLightsOccluded，含屏幕空间遮挡阴影），不走 lightmap
//              的"整体提亮"语义。保留形参是为了与 r5 的公式结构对齐。
//   normalM    视空间法线（normalized）
//   worldRelPos 世界相对位置（阴影接收用）
//   lViewPos   视空间距离
//   shadowMult 0-1 阴影可见度（adaptShadowVisibility 结果）
//   vanillaAO  原版 AO（Sodium a_Color 无 alpha 通道 → 恒 1.0；实体用
//              glColor.a，r5 L555-591 VANILLAAO_I=100 原版算法）
//   emission   自发光（0.0；实体的发光实体留 0 —— r5 的 IPBR emission 依赖
//              mc_Entity 材质图，26.2 不可用，注释 L360 的 emission 衰减照抄）
// 输出：color.rgb *= finalDiffuse（在 sRGB 空间直接乘，与 r5 L616 相同）。
// entityShading 1=实体/方块实体（补 r5 mainLighting.glsl:144-146 的
//   ENTITY_SHADOW 分支：按 NdotLM 把直射光色向环境色混合、NdotLM 下限抬到
//   0.25 —— "面向光源亮、背光暗"的方向对比机制），0=普通地形（r5 的
//   GBUFFERS_TERRAIN 不走该分支）。
void adaptDoLighting(inout vec4 color, vec2 lightmap, vec3 normalM,
                     vec3 worldRelPos, float lViewPos, float shadowMult,
                     float vanillaAO, float emission, float entityShading) {
    vec3 lightColor = vec3(1.0);
    vec3 ambientColor = vec3(1.0);
    vec3 sunVecView = adaptGetLightAndAmbientColors(lightColor, ambientColor);

    float timeAngle = SkyFactors0.y;
    float rainFactor = SkyFactors0.x;
    float noonFactor = sqrt(max(sin(timeAngle * 6.28318530718), 0.0));
    float invNoonFactor = 1.0 - noonFactor;
    float vsBrightness = clamp((MinAmbientLight - 0.01) * 20.0, 0.0, 1.0);

    // gbuffers_terrain.glsl:61-67（sunVisibility/shadowTime 用真实太阳方向）
    float SdotU = dot(sunVecView, UpVector.xyz);
    float sunVisibility = clamp(SdotU + 0.0625, 0.0, 0.125) / 0.125;
    float sunVisibility2 = sunVisibility * sunVisibility;
    float shadowTimeVar1 = abs(sunVisibility - 0.5) * 2.0;
    float shadowTime = shadowTimeVar1 * shadowTimeVar1;
    shadowTime *= shadowTime;

    // mainLighting.glsl L66-78
    vec3 upVec = normalize(UpVector.xyz);
    vec3 northVec = normalize(ViewRotCol2.xyz); // 世界 +Z 在视图（r5 vsh L453）
    vec3 eastVec = normalize(ViewRotCol0.xyz);  // 世界 +X 在视图（r5 vsh L452）
    float NdotU = dot(normalM, upVec);
    float NdotUmax0 = adaptMax0(NdotU);
    float absNdotN = abs(dot(normalM, northVec));
    float absNdotE = abs(dot(normalM, eastVec));
    // CPU SunDirection 已把夜间翻转到月亮方向（Beryl 语义），等价 r5
    // gbuffers_terrain.glsl:70-74 OVERWORLD 的 lightVec 昼夜翻转，直接使用
    vec3 lightVec = normalize(SunDirection);
    float NdotL = dot(normalM, lightVec);

    float lightmapY2 = adaptPow2(lightmap.y);
    float lightmapYM = adaptSmoothstep1(lightmap.y);
    float ambientMult = 1.0;

    // OVERWORLD 天光遮蔽（L84-88）
    float skyLightShadowMult = adaptPow2(adaptPow2(lightmapY2));

    // Shadows（L118-339 的简化版：无 shadow map 采样，用既有
    // adaptShadowVisibility；NdotLM 侧光项照抄 SIDE_SHADOWING L136）
    float NdotLmax0 = adaptMax0(NdotL);
    float NdotLM = NdotLmax0 * 0.9999;
    NdotLM = adaptMax0(NdotL + 0.4) * 0.714; // SIDE_SHADOWING（L136）

    // r5 mainLighting.glsl:144-146 移植（ENTITY_SHADOW <= 1 时 GBUFFERS_ENTITIES
    // 与 GBUFFERS_BLOCK 走；r5 HIGH+ 档 ENTITY_SHADOW=1）。Adapt 的实体管线
    // 不画进 shadow map（实体阴影由地形遮挡近似），等价 r5 ENTITY_SHADOW=-1 的
    // GBUFFERS_ENTITIES 补偿分支——两者都要把 lightColor 按 NdotLM 向环境色
    // 混合、把 NdotLM 下限抬到 0.25，否则背光面只剩 ambient 垫底、实体被光源
    // 照亮时呈"原版 lightmap 整体提亮"而非方向光。
    if (entityShading > 0.5) {
        lightColor = mix(lightColor * 0.75, ambientColor,
                0.5 * adaptPow2(adaptPow2(1.0 - NdotLM))); // L145
        NdotLM = NdotLM * 0.75 + 0.25; // L146
    }

    vec3 shadowMultM = vec3(shadowMult);
    if (shadowMultM.r > 0.00001) {
        shadowMultM *= adaptMax0(NdotLM * shadowTime); // L339
    }
    // 直射光只由方向 shadow map 决定，**不再乘天光遮蔽**。
    //
    // 旧式 shadowMultM * skyLightShadowMult 在延迟管线里是错的：这里的
    // lightmap.y 不是原版 lightmap，而是 adaptSkyVisibility ——
    // **顶视**正交深度图。它产生的是与太阳方向无关的第二道竖直轮廓影
    // （实测"一个非常暗的和一个浅的"里那层浅的），而 skyVis 的 8 次方
    // （pow2∘pow2∘pow2）把顶视图的深度自阴影误差放大成条纹
    // （实测"太阳影子几乎呈条纹状"）。
    //
    // 正确分工：太阳的遮挡是 shadow map 的职责；天光可见度只该衰减
    // ambient（洞穴靠 ambientMult 变暗，见下方 L444 的 ambientMult）。
    vec3 shadowLightMult = shadowMultM;

    // Blocklight（L349-371；XLIGHT_CURVE=1.00、XLIGHT_I=1.00 默认）
    float lightmapXMSteep = adaptPow2(adaptPow2(lightmap.x * lightmap.x))
            * (2.8 - 0.6 * vsBrightness + 1.0); // XLIGHT_CURVE 1.00
    float lightmapXMCalm = lightmap.x * (2.8 + 0.6 * vsBrightness - 1.0);
    float lightmapXM = pow(lightmapXMSteep + lightmapXMCalm, 2.25);

    float daylightFactor = lightmapYM * (1.0 - rainFactor) * sunVisibility; // invRainFactor
    emission *= 1.0 - 0.25 * daylightFactor; // L360

    // blocklightColors.glsl:1（XLIGHT_RGB=1.00）
    vec3 blocklightCol = vec3(0.1775, 0.104, 0.077);
    vec3 blockLighting = lightmapXM * blocklightCol; // L371

    // L438 + minimumLighting.glsl:1-17（CAVE_LIGHTING=100 分支，nightVision=0）
    vec3 minLighting = vec3(0.005625 + vsBrightness * 0.043) * vec3(0.45, 0.475, 0.6)
            * (1.0 - lightmapYM);

    // Lighting Tweaks OVERWORLD（L442-476，isEyeInWater=0 分支）
    ambientMult = mix(lightmapYM, adaptPow2(lightmapYM) * lightmapYM, rainFactor); // L444

    // r5 L440：shadowMultFloat = GetLuminance(shadowMult) —— 用含天光遮蔽的
    // shadowLightMult（r5 的 shadowMult 在 L293 里已乘 skyLightShadowMult）
    float shadowMultFloat = adaptMin1(adaptGetLuminance(shadowLightMult));
    float lxFactor = (sunVisibility2 * 0.4 + (0.6 - 0.6 * adaptPow2(invNoonFactor)))
            * (6.0 - 5.0 * rainFactor); // L466
    lxFactor *= lightmapY2 + lightmapY2 * 2.0 * adaptPow2(shadowMultFloat); // L467
    lxFactor = adaptMax0(lxFactor - emission * 1000000.0); // L468
    blockLighting *= pow(lightmapXM / 60.0 + 0.001, 0.09 * lxFactor); // L469
    blockLighting *= 1.0; // XLIGHT_I 1.00（L545）

    // L472-475：isEyeInWater=0 分支的雨/夜"近处更亮"距离微调（26.2 无
    // isEyeInWater uniform，地形/实体视为地面渲染，该分支条件成立）
    float rainLF = 0.1 * rainFactor;
    float lightFogTweaks = 1.0 + adaptMax0(96.0 - lViewPos)
            * (0.002 * (1.0 - sunVisibility2) + 0.0104 * rainLF) - rainLF;
    ambientMult *= lightFogTweaks; // L474
    lightColor *= lightFogTweaks;  // L475

    // Directional Shading（L498-535，DIRECTIONAL_SHADING + OVERWORLD）
    float absNdotE2 = adaptPow2(absNdotE);
    float NdotUM = 0.75 + NdotU * 0.25; // L505
    float NdotNM = 1.0 + 0.075 * absNdotN; // L509
    float NdotEM = 1.0 - 0.1 * absNdotE2; // L510
    float directionShade = NdotUM * NdotEM * NdotNM; // L511
    lightColor *= 1.0 + absNdotE2 * 0.75; // L514

    // L527-533：PERPENDICULAR_TWEAKS（SUN_ANGLE=-1 且 SHADER_STYLE=1 时
    // 定义，common.glsl:455）+ SIDE_SHADOWING 的假反弹光（subsurfaceMode=0）
    ambientColor = mix(ambientColor, lightColor,
            (0.05 + 0.03 * 0.0) * absNdotN * lightmapY2); // L529
    lightColor *= 1.0 + adaptMax0(1.0 - 0.0) * pow(noonFactor, 20.0)
            * (adaptPow2(absNdotN) * 0.8 - absNdotE2 * 0.2); // L532

    // Scene Lighting（L538）
    vec3 sceneLighting = lightColor * shadowLightMult + ambientColor * ambientMult;
    float dotSceneLighting = dot(sceneLighting, sceneLighting); // L539

    // Vanilla AO（L554-591，VANILLAAO_I=100 → 无额外曲线，直接原版值）
    // （subsurfaceMode=0、noVanillaAO=false、GBUFFERS_TERRAIN 分支 L565-584：
    //  vanillaAO = min1(vanillaAO + 0.08);
    //  vanillaAO = pow(pow1_5(vanillaAO),
    //      1.0 + dotSceneLighting * 0.02 + NdotUmax0 * (0.15 + 0.25 * pow2(noonFactor * pow2(lightmapY2))));
    //  vanillaAO = vanillaAO * 0.9 + 0.1;
    //  vanillaAO = pow(vanillaAO, 1.0); // VANILLAAO_I=100
    //  保留该曲线 —— Sodium 的 a_Color 是预乘 AO 后的 0-1 值，alpha 恒 1，
    //  r5 里 glColor.a 是 AO 本身（1.0 表示无 AO），此处直接代入 1.0。
    //  地形：vanillaAO=1.0（AO 已 bake 进顶点色 RGB，见 vsh 注释）。
    //  实体：传入真实 glColor.a（原版实体 AO 在 alpha 通道）。
    float vanillaAOM = adaptMin1(vanillaAO + 0.08);
    vanillaAOM = pow(vanillaAOM - vanillaAOM * adaptPow2(1.0 - vanillaAOM), // pow1_5 等价
            1.0 + dotSceneLighting * 0.02 + NdotUmax0 * (0.15 + 0.25 * adaptPow2(noonFactor * adaptPow2(lightmapY2))));
    vanillaAOM = vanillaAOM * 0.9 + 0.1;

    // Light Highlight（L593-609，LIGHT_HIGHLIGHT 已定义但 GGX 需要材质
    // smoothness —— 26.2 无材质图，smoothnessG=0 时 highlight=0，跳过）

    // Mix Colors（L611-618，全管线核心）
    vec3 finalDiffuse = adaptPow2(directionShade * vanillaAOM)
            * (blockLighting + adaptPow2(sceneLighting) + minLighting)
            + adaptPow2(emission);
    finalDiffuse = sqrt(max(finalDiffuse, vec3(0.0))); // L613
    color.rgb *= finalDiffuse; // L616
    // L617 lightHighlight = 0（无 PBR 高光）；L618 darknessLightFactor = 0
}

// ---- Complementary Reimagined r5 输出变换（composite5.glsl DoCompTonemap）----
// 逐行移植 composite5.glsl:36-87（Lottes tonemap + LinearToRGB）。
// 参数：TM_EXPOSURE=1.00、TM_CONTRAST=1.05、TM_WHITE_PATH=1.00、
// TM_DARK_DESATURATION=0.25（common.glsl:275-280 默认）。
// 去掉了依赖场景的 darkLift（需 sceneColor，单 pass 不可用）与
// DoBSLColorSaturation（后处理阶段做，T_SATURATION=1.00 时无效果）。
void adaptLottesLinearToRGB(vec3 colorIn, out vec3 colorOut) {
    vec3 color = 1.00 * colorIn; // TM_EXPOSURE

    vec3 a = vec3(1.05); // TM_CONTRAST
    vec3 d = vec3(1.0);
    vec3 hdrMax = vec3(8.0);
    vec3 midIn = vec3(0.25);
    vec3 midOut = vec3(0.25);

    vec3 a_d = a * d;
    vec3 hdrMaxA = pow(hdrMax, a);
    vec3 hdrMaxAD = pow(hdrMax, a_d);
    vec3 midInA = pow(midIn, a);
    vec3 midInAD = pow(midIn, a_d);
    vec3 HM1 = hdrMaxA * midOut;
    vec3 HM2 = hdrMaxAD - midInAD;
    vec3 b = (-midInA + HM1) / (HM2 * midOut);
    vec3 c = (hdrMaxAD * midInA - HM1 * midInAD) / (HM2 * midOut);
    vec3 colorM = pow(color, a) / (pow(color, a_d) * b + c);

    // LinearToRGB（composite5.glsl:31-34）
    const vec3 k = vec3(0.055);
    colorM = mix((vec3(1.0) + k) * pow(colorM, vec3(1.0 / 2.4)) - k, 12.92 * colorM,
            lessThan(colorM, vec3(0.0031308)));

    // composite5.glsl:66-71 的 darkLift 需要 composite 场景色（初始亮度），
    // 单 pass 地形/实体 shader 无此输入且 TM_CONTRAST=1.05 时
    // max0(0.55 - abs(1.05 - TM_CONTRAST)) / 0.55 = 0.927 仍部分生效；
    // 为保持逐字公式，用输入亮度近似（tonemap 前），其余照抄。
    float initialLuminance = adaptGetLuminance(colorIn);
    const float darkLiftStart = 0.1;
    const float darkLiftMix = 0.75;
    float darkLift = smoothstep(darkLiftStart, 0.0, initialLuminance);
    vec3 smoothColor = pow(colorIn, vec3(1.0 / 2.2));
    colorM = mix(colorM, smoothColor, darkLift * darkLiftMix * 0.927); // max0(0.55 - abs(1.05-1.05))/0.55

    colorM = clamp01(colorM);
    colorOut = colorM;
}

vec3 adaptTonemapLottes(vec3 linear) {
    vec3 outColor;
    adaptLottesLinearToRGB(linear, outColor);
    return outColor;
}

// ---- Beryl final.fsh 输出变换（保留：adapt_terrain.fsh 与
// sodium_terrain_translucent.fsh 仍使用，不在本次改造范围）----

vec3 adaptAces(vec3 x) {
    const float a = 2.1;
    const float b = 0.15;
    const float c = 2.03;
    const float d = 0.8;
    const float e = 0.25;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

vec3 adaptFinalize(vec3 litLinear) {
    return pow(adaptAces(litLinear), vec3(1.0 / 2.2));
}
