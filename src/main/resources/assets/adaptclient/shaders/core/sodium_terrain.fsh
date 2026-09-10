#version 330 core

// Adapt 对 Sodium 地形管线的 shader 副本（基于 sodium-fabric 0.9.1-beta.2
// 的 blocks/block_layer_opaque.fsh）。世界光照 = Complementary Reimagined r5
// 逐字移植（adaptDoLighting，见 adapt_lighting.glsl）：
//   albedo = tex * glColor.rgb（无 pow2.2 线性化，r5 gbuffers_terrain.glsl:219）
//   finalDiffuse = pow2(directionShade * vanillaAO)
//               * (blockLighting + pow2(sceneLighting) + minLighting)
//   color.rgb *= sqrt(max(finalDiffuse, 0))   （mainLighting.glsl:611-616）
// 输出经 composite5.glsl 的 Lottes tonemap + LinearToRGB（adaptTonemapLottes）。
// sodium 的 RGSS 采样、雾（_linearFog）与 chunk 淡出原样保留。法线由解析
// 导数重建（sodium 压缩顶点格式无法线属性，与 vanilla adapt_terrain 相同）。
// halo 点光源（adaptHaloLights）在 tonemap 前额外叠加（真实点光 GGX）。

#moj_import <sodium:globals.glsl>
#moj_import <sodium:fog.glsl>
#moj_import <sodium:chunk_material.glsl>
#moj_import <adaptclient:adapt_lighting.glsl>
#moj_import <adaptclient:adapt_shadow.glsl>

in vec4 v_Color; // The interpolated vertex color
in vec2 v_TexCoord; // The interpolated block texture coordinates
in vec2 v_FragDistance; // The fragment's distance from the camera (cylindrical and spherical)
in float fadeFactor;
in vec2 lmCoordM; // Reimagined GetLightMapCoordinates 处理后的光照贴图坐标（0-1）
in vec4 adaptVertexColor; // 原版顶点色（AO 预乘 RGB，alpha 恒 1）
in vec3 adaptFragPos; // Adapt：视空间片元位置
in vec3 adaptWorldPos; // Adapt：世界相对片元位置

uniform sampler2D u_BlockTex; // The block texture

out vec4 fragColor; // The output fragment for the color framebuffer

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    // Convert our UV back up to texel coordinates and find out how far over we are from the center of each pixel
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5f;
    vec2 texelOffset = uvTexelCoords - texelCenter;

    // Move our offset closer to the texel center based on texel size on screen
    texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
    texelOffset = clamp(texelOffset, 0.0f, 1.0f);

    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(source, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

// Rotated Grid Super-Sampling
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

    const vec2 offsets[4] = vec2[](
    vec2(0.125, 0.375),
    vec2(-0.125, -0.375),
    vec2(0.375, -0.125),
    vec2(-0.375, 0.125)
    );

    vec4 rgssColor = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColor += textureLod(source, sampleUV, mipLevelExact);
    }
    rgssColor *= 0.25;

    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);

    return mix(nearestColor, rgssColor, blendFactor);
}

void main() {
    vec4 color = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    // ---- Reimagined r5 世界光照（mainLighting.glsl DoLighting）----
    // albedo = tex * glColor.rgb —— 无 pow2.2 线性化（r5 gbuffers_terrain.glsl:219；
    // Sodium a_Color 0-1 UNORM 含 AO，直接乘）
    vec3 albedo = color.rgb * adaptVertexColor.rgb;
    color.rgb = albedo;

    // 法线：Sodium 压缩顶点格式无法线属性，用解析导数重建（视空间）。
    vec3 N = normalize(cross(dFdx(adaptFragPos), dFdy(adaptFragPos)));
    if (dot(N, -normalize(adaptFragPos)) < 0.0) {
        N = -N;
    }
    // 26.2/Sodium 压缩顶点格式在屏幕空间退化时产生零向量/NaN → 随机黑面/剔面。
    // 任何非法法线都回退到视向量。
    if (any(isnan(N)) || length(N) < 0.001) {
        N = normalize(-adaptFragPos);
    } else {
        N = normalize(N);
    }

    // 阴影可见度（现有 adapt_shadow.glsl，0-1）
    float shadowVisibility = adaptShadowVisibility(adaptWorldPos, N);

    // 发光方块自发光（P6）：26.2/Sodium 无法在 shader 拿到真实 Block ID
    // （Sodium 顶点格式只有 light/material bits/section，javap 证实无 mc_Entity；
    // quad.emissive 已被消费成全亮 light 不进顶点——P6 代理核实）。因此
    // 任何基于纹理色彩/luminance 的 emission 判断都会误伤沙子/花岗岩/铁矿/
    // 金矿等亮暖色方块（用户实测），禁止使用颜色猜测。
    // 正确实现（后续）：Java 侧在 Sodium chunk build 阶段按真实 BlockState
    // （BlockBehaviour.getLightEmission/Torch/Lava/Glowstone 等）生成发光标记，
    // 经 UBO/section 标记传入 shader。当前先保持 emission=0（本体不误自发光，
    // 发光方块本体仍由周边 blocklight 与 Lottes 提亮；误伤比缺失更糟糕）。
    float emissive = 0.0;

    // vanillaAO：Sodium a_Color 的 alpha 恒 1（AO 预乘进 RGB），
    // 传入 1.0 —— 等价 r5 glColor.a=1（AO 曲线在 adaptDoLighting 内保留）。
    // entityShading=0：普通地形（r5 GBUFFERS_TERRAIN 不走 L144-146 实体分支）
    adaptDoLighting(color, lmCoordM, N, adaptWorldPos, length(adaptFragPos),
            shadowVisibility, 1.0, emissive, 0.0);

    // Adapt 扩展：光环局部点光源照射地形（真实点光 GGX，在 tonemap 前叠加；
    // albedo 用未光照的原始纹理色，与旧版叠加行为一致）
    vec3 V = -normalize(adaptFragPos);
    vec3 F0 = vec3(0.04);
    float roughness = 0.8;
    color.rgb += adaptHaloLights(V, N, adaptFragPos, albedo, F0, roughness);

    // Reimagined composite5.glsl 的 Lottes tonemap + LinearToRGB
    color.rgb = adaptTonemapLottes(color.rgb);

    // alpha 保留顶点色 alpha（透明方块/淡出）
    vec4 outColor = vec4(color.rgb, color.a * adaptVertexColor.a);
    fragColor = _linearFog(outColor, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, fadeFactor);
}
