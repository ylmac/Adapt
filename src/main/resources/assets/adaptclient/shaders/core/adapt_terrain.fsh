#version 330

// Adapt 地形管线片元着色器。
// 光照公式（线性空间）：
// color1 = LightingSphereGGX2(..., radiance = LightColor*0.6 * horizonSaturate)
// color.rgb = (color1 * shadow * light.z) + albedo * (light.y*Ambient + light.x*暖光)
// + radiance * 0.1 * NdotU * albedo * shadow * light.z (地面反弹项)
// 注意：地形法线不在原版顶点格式里， 用自定义扩展顶点格式携带 Normal；
// 26.2 原版 TERRAIN 格式无法追加属性（section 缓存已烘焙），因此这里用
// 解析导数 dFdx/dFdy 从视空间深度重建法线（无此路径，属 Adapt 适配）。
// 输出经 final.fsh 的 aces + pow(1/2.2)，与实体一致。

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <adaptclient:adapt_lighting.glsl>
#moj_import <adaptclient:adapt_shadow.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec3 adaptFragPos;
in vec3 adaptWorldPos;
in vec3 adaptLightRaw;
in vec2 texCoord0;

out vec4 fragColor;

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5;
    vec2 texelOffset = uvTexelCoords - texelCenter;
    texelOffset = (texelOffset - 0.5) * pixelSize / texelScreenSize + 0.5;
    texelOffset = clamp(texelOffset, 0.0, 1.0);
    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(source, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

// Rotated Grid Super-Sampling（原版 terrain.fsh 原样保留）
vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
    float minPixelSize = min(pixelSize.x, pixelSize.y);
    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);
    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);
    float effectiveDerivative = sqrt(minDerivative * maxDerivative);
    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));
    float mipLevelLow = floor(mipLevelExact);
    float mipLevelHigh = mipLevelLow + 1.0;
    float mipBlend = fract(mipLevelExact);
    const vec2 offsets[4] = vec2[](
    vec2(0.125, 0.375),
    vec2(-0.125, -0.375),
    vec2(0.375, -0.125),
    vec2(-0.375, 0.125)
    );
    vec4 rgssColorLow = vec4(0.0);
    vec4 rgssColorHigh = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColorLow += textureLod(source, sampleUV, mipLevelLow);
        rgssColorHigh += textureLod(source, sampleUV, mipLevelHigh);
    }
    rgssColorLow *= 0.25;
    rgssColorHigh *= 0.25;
    vec4 rgssColor = mix(rgssColorLow, rgssColorHigh, mipBlend);
    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
    return mix(nearestColor, rgssColor, blendFactor);
}

void main() {
    // 原版采样路径原样保留（RGSS / nearest 由全局设置决定）
    vec4 texColor = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0 / TextureSize)
            : sampleNearest(Sampler0, texCoord0, 1.0 / TextureSize));
#ifdef ALPHA_CUTOUT
    if (texColor.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    // chunk 淡出（原版语义原样保留，绕过光照）
    vec3 albedoSRGB = texColor.rgb * vertexColor.rgb;
    vec4 color = mix(FogColor * vec4(1.0, 1.0, 1.0, texColor.a),
            vec4(albedoSRGB, texColor.a * vertexColor.a), ChunkVisibility);

    vec3 N = normalize(cross(dFdx(adaptFragPos), dFdy(adaptFragPos)));
    // 面向相机的法线翻回正方向（cross 方向不保证一致）
    if (dot(N, -normalize(adaptFragPos)) < 0.0) {
        N = -N;
    }
    // 26.2 chunk 顶点格式无法线属性，derivative 重建在屏幕空间退化时
    // 会产生零向量/NaN → 随机黑面/剔面。任何非法法线都回退到视向量，
    // 保证光照连续、面不会被错误剔除。
    if (any(isnan(N)) || length(N) < 0.001) {
        N = normalize(-adaptFragPos);
    } else {
        N = normalize(N);
    }

    vec3 V = normalize(-adaptFragPos);
    vec3 F0 = vec3(0.04);
    float roughness = 0.8;

    // terrain.fsh：线性空间 albedo
    vec3 albedo = pow(albedoSRGB, vec3(2.2));

    // radiance = LightColor * 0.6，随太阳贴近地平线衰减
    vec3 radiance = SunColor * SunIntensity * 0.6;
    radiance *= adaptSaturate((dot(UpVector.xyz, SunDirection) - 0.04) * 50.0);

    vec3 color1 = adaptLightingSphereGGX2(V, N, SunDirection, albedo, radiance, F0,
            roughness, 0.0, 0);
    float shadowVisibility = adaptShadowVisibility(adaptWorldPos, N);
    vec3 lit = (color1 * shadowVisibility * adaptLightRaw.z)
            + albedo * (adaptLightRaw.y * AmbientColor
                    + adaptLightRaw.x * vec3(1.0, 0.7, 0.5));

    // 地面反弹项：NdotU 上表面受光 +10%
    float NdotU = max(dot(N, UpVector.xyz), 0.0);
    lit += radiance * 0.1 * NdotU * albedo * adaptLightRaw.z;

    // Adapt 扩展：光环局部点光源照射地形（真实点光 GGX）
    lit += adaptHaloLights(V, N, adaptFragPos, albedo, F0, roughness);

    vec3 finalRgb = adaptFinalize(lit);
    fragColor = vec4(mix(FogColor.rgb * vec3(1.0, 1.0, 1.0),
            finalRgb, ChunkVisibility), texColor.a * vertexColor.a);

    fragColor = apply_fog(fragColor, sphericalVertexDistance, cylindricalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart,
            FogRenderDistanceEnd, FogColor);
}
