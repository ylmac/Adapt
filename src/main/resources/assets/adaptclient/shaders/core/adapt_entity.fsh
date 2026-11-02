#version 330

// Adapt 自有实体管线片元着色器（ 世界光照）。
// 与地形同一套 adaptDoLighting：
// albedo = tex * vertexColor.rgb（无 pow2.2， 同

// finalDiffuse = pow2(directionShade * vanillaAO)
// * (blockLighting + pow2(sceneLighting) + minLighting)
// color.rgb *= sqrt(max(finalDiffuse, 0))
// 实体有真实 UV2（lightmap）与 alpha 通道 AO（原版实体 AO 在 alpha）：
// lmCoord = GetLightMapCoordinates(UV2/256)、vanillaAO = vertexColor.a。
// 阴影用 adaptShadowVisibility（与地形一致，ShadowFrame UBO 已绑定）。
// 输出经 的 Lottes tonemap + LinearToRGB（adaptTonemapLottes）。
// 保留 overlay/emissive 处理（overlay 混色在光照前，
// 同语义）。

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <adaptclient:adapt_lighting.glsl>
#moj_import <adaptclient:adapt_shadow.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec3 adaptNormalVS;
in vec3 adaptFragPos;
in vec3 adaptWorldPos;
in vec2 lmCoordM;
#ifndef NO_OVERLAY
in vec4 overlayColor;
#endif
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 texColor = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (texColor.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    // ：albedo = tex * glColor.rgb（无 pow2.2 线性化，与地形一致）
    vec3 albedo = texColor.rgb * vertexColor.rgb * ColorModulator.rgb;
    texColor.rgb = albedo;
#ifndef NO_OVERLAY
    // 同语义：overlay 混色在光照前
    texColor.rgb = mix(overlayColor.rgb, texColor.rgb, overlayColor.a);
#endif

    vec3 N = normalize(adaptNormalVS);
    if (any(isnan(N)) || length(N) < 0.001) {
        N = normalize(-adaptFragPos);
    }

    // 阴影可见度（现有 adapt_shadow.glsl，0-1；实体同样接收太阳阴影）
    float shadowVisibility = adaptShadowVisibility(adaptWorldPos, N);

    // vanillaAO = 实体顶点色 alpha（原版实体 AO 在 alpha 通道， glColor.a
    // 同语义；26.2 实体 alpha=255 通常是 1.0）
    // entityShading=1：实体走 的 ENTITY_SHADOW
    // 分支（lightColor 按 NdotLM 向环境色混合 → 面向太阳的面亮、背光面暗）
    adaptDoLighting(texColor, lmCoordM, N, adaptWorldPos, length(adaptFragPos),
            shadowVisibility, vertexColor.a, 0.0, 1.0);

    // Adapt 扩展：光环局部点光源（真实点光 GGX，距离衰减，穿墙由深度自然
    // 遮挡；在 tonemap 前叠加，albedo 用未光照的原始纹理色）
    vec3 V = normalize(-adaptFragPos);
    vec3 F0 = vec3(0.04);
    float roughness = 0.8;
    texColor.rgb += adaptHaloLights(V, N, adaptFragPos, albedo, F0, roughness);

    // 的 Lottes tonemap + LinearToRGB
    texColor.rgb = adaptTonemapLottes(texColor.rgb);

    vec4 color = vec4(texColor.rgb, texColor.a);

    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart,
            FogRenderDistanceEnd, FogColor);
}
