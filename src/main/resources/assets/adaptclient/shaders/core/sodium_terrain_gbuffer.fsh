#version 330 core

// G-buffer 几何阶段（延迟渲染）。
// 不做任何光照：只输出几何/材质/雾/光照贴图数据，光照全部推迟到全屏
// light pass（adapt_deferred_light.fsh）执行完整 adaptDoLighting +
// adaptShadowVisibility。Sodium 压缩顶点格式无法线属性，法线仍用解析
// 导数重建（与 sodium_terrain.fsh 相同）。

// 6 个附件（设备 maxColorAttachments=8，实测 Apple M4 / MoltenVK）：
// GB0 RGBA32F: xyz=视空间位置, w=coverage(1.0=几何)
// GB1 RGBA16F: xyz=视空间法线, w=roughness
// GB2 RGBA8: rgb=albedo(纹理×顶点色), a=metallic
// GB3 RGBA16F: rgb=雾色, a=fogFactor(雾混合系数=total_fog*FogColor.a)
// GB4 RGBA32F: xyz=世界相对位置（阴影接收 + 光照需要）, w=vanillaAO
// GB5 RGBA16F: xy=lmCoordM(block/sky light), z=emissive, w=顶点色 alpha

#moj_import <sodium:globals.glsl>
#moj_import <sodium:fog.glsl>
#moj_import <sodium:chunk_material.glsl>

in vec4 v_Color; // The interpolated vertex color
in vec2 v_TexCoord; // The interpolated block texture coordinates
in vec2 lmCoordM; // GetLightMapCoordinates 处理后的光照贴图坐标（0-1）
in vec4 adaptVertexColor; // 原版顶点色（AO 预乘 RGB，alpha 恒 1）
in vec3 adaptFragPos; // Adapt：视空间片元位置
in vec3 adaptWorldPos; // Adapt：世界相对片元位置（与 shadow caster 同空间）
#ifdef USE_FOG
in vec2 v_FragDistance; // The fragment's distance from the camera (cylindrical and spherical)
in float fadeFactor;
#endif

uniform sampler2D u_BlockTex; // The block texture

layout(location = 0) out vec4 gPosition;
layout(location = 1) out vec4 gNormal;
layout(location = 2) out vec4 gAlbedo;
layout(location = 3) out vec4 gEmissiveFog;
layout(location = 4) out vec4 gWorldPos;
layout(location = 5) out vec4 gLightmap;

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

    // albedo = tex * glColor.rgb — 无 pow2.2 线性化（与 sodium_terrain.fsh 一致）
    vec3 albedo = color.rgb * adaptVertexColor.rgb;

    // 法线：解析导数重建（视空间），非法线回退视向量（同 sodium_terrain.fsh）
    vec3 N = normalize(cross(dFdx(adaptFragPos), dFdy(adaptFragPos)));
    if (dot(N, -normalize(adaptFragPos)) < 0.0) {
        N = -N;
    }
    if (any(isnan(N)) || length(N) < 0.001) {
        N = normalize(-adaptFragPos);
    } else {
        N = normalize(N);
    }

    // 雾混合系数：与 sodium _linearFog 完全一致（fogValue * fogColor.a），
    // 由 light pass 直接 mix 使用 — 雾数据全部在几何阶段算好。
#ifdef USE_FOG
    float fogValue = max(1.0 - fadeFactor, total_fog_value(
            v_FragDistance.y, v_FragDistance.x,
            u_EnvironmentFog.x, u_EnvironmentFog.y,
            u_RenderFog.x, u_RenderFog.y));
    float fogFactor = fogValue * u_FogColor.a;
#else
    float fogFactor = 0.0;
#endif

    gPosition = vec4(adaptFragPos, 1.0);
    gNormal = vec4(N, 0.8);            // w=roughness（地形基准 0.8）
    gAlbedo = vec4(albedo, 0.0);       // a=metallic（地形 0.0）
    // rgb=雾色（light pass 用它做 mix），a=fogFactor
    gEmissiveFog = vec4(u_FogColor.rgb, fogFactor);
    // 世界相对位置：阴影接收（adaptShadowVisibility）与 光照都需要，与
    // shadow caster 几何同空间。w=vanillaAO：Sodium a_Color 的 alpha 恒 1
    // （AO 预乘进 RGB），与 sodium_terrain.fsh 传 1.0 一致。
    gWorldPos = vec4(adaptWorldPos, 1.0);
    // xy=lmCoordM（ blockLight/skyLight 语义），z=emissive（当前 0，真实
    // 发光体由 halo 光源系统提供），w=顶点色 alpha（透明方块/淡出）
    gLightmap = vec4(lmCoordM, 0.0, adaptVertexColor.a);
}
