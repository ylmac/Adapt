#version 330 core

// G-buffer 几何阶段 vsh（延迟渲染）：输出与 sodium_terrain.vsh 完全相同，
// 供 G-buffer fsh 写位置/法线/albedo/fog 数据（不做光照）。
// r5 世界光照（adaptDoLighting）：
// - lmCoordM：GetLightMapCoordinates 处理后的光照贴图坐标（0-1，r5
//   commonFunctions.glsl:2-4：clamp((uv - 0.03125) * 1.06667, 0, 1)）。
//   Sodium _vert_tex_light_coord = a_LightAndData.xy / 256（0.03-0.97），
//   与 r5 的 gl_TextureMatrix[1]*gl_MultiTexCoord1 同编码，直接套公式。
// - adaptVertexColor：未线性化的顶点色（a_Color 0-1 UNORM，AO 预乘进 RGB；
//   r5 gbuffers_terrain.glsl:219 color.rgb *= glColor.rgb 直接乘，不做 pow2.2）
// - adaptFragPos / adaptWorldPos：视空间片元位置 / 世界相对位置（halo 光照、
//   导数法线、阴影接收用；adaptWorldPos 与 caster 几何同空间）
// v_Color 通道保留原版语义（alpha 恒 1，Sodium 压缩格式无 AO alpha）。

#moj_import <sodium:globals.glsl>
#moj_import <sodium:fog.glsl>
#moj_import <sodium:chunk_vertex.glsl>
#moj_import <adaptclient:adapt_lighting.glsl>

out vec4 v_Color;
out vec2 v_TexCoord;
out vec2 lmCoordM;
out vec4 adaptVertexColor;
out vec3 adaptFragPos;
out vec3 adaptWorldPos;

#ifdef USE_FOG
out vec2 v_FragDistance;
out float fadeFactor;
#endif

uniform isamplerBuffer u_SectionTimeInfo;

#ifdef VULKAN
layout(push_constant) uniform PC {
    vec3 u_RegionOffset;
    int u_CurrentTime;
    uint u_RegionID;
};
#else
uniform vec3 u_RegionOffset;
uniform int u_CurrentTime;
uniform uint u_RegionID;
#endif

uniform sampler2D u_LightTex; // The light map texture sampler

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

void main() {
    _vert_init();

    // Transform the chunk-local vertex position into world model space
    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

#ifdef USE_FOG
    v_FragDistance = getFragDistance(position);

    int chunkId = int(_draw_id);
    int chunkFade = texelFetch(u_SectionTimeInfo, int((u_RegionID * 256u) + uint(chunkId))).r;
    int fadeTime = u_CurrentTime - chunkFade;
    float elapsed = float(fadeTime);
    float fade = clamp(float(u_CurrentTime - chunkFade) * u_FadePeriodInv, 0.0, 1.0);
    fadeFactor = (chunkFade < 0) ? 1.0 : fade;
#endif

    // Transform the vertex position into model-view-projection space
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    // ---- Adapt/Reimagined r5 顶点阶段 ----
    // 顶点色原样传递（不做 pow2.2 —— r5 albedo = tex * glColor.rgb 直接乘）
    adaptVertexColor = _vert_color;
    // GetLightMapCoordinates（r5 commonFunctions.glsl:2-4）
    lmCoordM = clamp((_vert_tex_light_coord - 0.03125) * 1.06667, 0.0, 1.0);
    adaptFragPos = (u_ModelViewMatrix * vec4(position, 1.0)).xyz;
    // 世界相对位置（与 caster 几何同空间）——阴影接收用
    adaptWorldPos = position;

    v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord; // FMA for precision
}
