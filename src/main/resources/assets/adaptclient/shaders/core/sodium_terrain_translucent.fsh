#version 330 core

// Adapt 半透明 Sodium 地形片元着色器。
// 半透明 pass 保留 alpha blend、不写深度，因此不使用 derivative 法线
// （避免 NaN 黑面/剔面）；光照用视向量近似法线 + 顶点光照分解，保留
// 半透明方块（水/玻璃/树叶）的正常外观。

#moj_import <sodium:globals.glsl>
#moj_import <sodium:fog.glsl>
#moj_import <sodium:chunk_material.glsl>
#moj_import <adaptclient:adapt_lighting.glsl>

in vec4 v_Color; // The interpolated vertex color
in vec2 v_TexCoord; // The interpolated block texture coordinates
in vec2 v_FragDistance; // The fragment's distance from the camera (cylindrical and spherical)
in float fadeFactor;
in vec4 adaptVertexLinear; // Adapt/Beryl：pow(2.2) 线性化顶点色
in vec3 adaptFragPos; // Adapt：视空间片元位置

uniform sampler2D u_BlockTex; // The block texture

out vec4 fragColor; // The output fragment for the color framebuffer

void main() {
    vec4 color = texture(u_BlockTex, v_TexCoord);

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    // ---- Beryl 风格光照（半透明：视向量近似法线，稳定无 NaN）----
    vec3 albedo = pow(color.rgb, vec3(2.2)) * adaptVertexLinear.rgb;
    vec3 V = -normalize(adaptFragPos);
    // 半透明面不重建法线：用视向量作为 N 的近似（diffuse 项稳定），
    // 避免 derivative 在屏幕空间退化产生随机黑面/剔面。
    vec3 N = normalize(V);

    vec3 F0 = vec3(0.04);
    float roughness = 0.8;

    // Beryl: radiance = LightColor * 0.6，随太阳贴近地平线衰减
    vec3 radiance = SunColor * SunIntensity * 0.6;
    radiance *= adaptSaturate((dot(UpVector.xyz, SunDirection) - 0.04) * 50.0);

    vec3 color1 = adaptLightingSphereGGX2(V, N, SunDirection, albedo, radiance, F0,
            roughness, 0.0, 0);
    vec3 lit = (color1 * 0.5)
            + albedo * (0.5 * AmbientColor
                    + 0.3 * vec3(1.0, 0.7, 0.5));

    // Adapt 扩展：光环局部点光源
    lit += adaptHaloLights(V, N, adaptFragPos, albedo, F0, roughness);

    vec3 finalRgb = adaptFinalize(lit);

    // alpha 保留顶点色 alpha（半透明方块/水）
    vec4 outColor = vec4(finalRgb, color.a * adaptVertexLinear.a);
    fragColor = _linearFog(outColor, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, fadeFactor);
}
