#version 330 core

// Adapt 半透明 Sodium 地形管线顶点着色器（与 sodium_terrain.vsh 同构）。
// 半透明 pass 不写深度、依赖 alpha blend，因此不输出 derivative 法线
// （屏幕空间退化会产生 NaN 黑面）。光照在 fragment 用视向量近似法线。

#moj_import <sodium:globals.glsl>
#moj_import <sodium:fog.glsl>
#moj_import <sodium:chunk_vertex.glsl>
#moj_import <adaptclient:adapt_lighting.glsl>

out vec4 v_Color;
out vec2 v_TexCoord;
out vec2 v_FragDistance;
out float fadeFactor;
out vec4 adaptVertexLinear;
out vec3 adaptFragPos;

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

    v_FragDistance = getFragDistance(position);

    int chunkId = int(_draw_id);
    int chunkFade = texelFetch(u_SectionTimeInfo, int((u_RegionID * 256u) + uint(chunkId))).r;
    fadeFactor = (chunkFade < 0) ? 1.0 : clamp(float(u_CurrentTime - chunkFade) * u_FadePeriodInv, 0.0, 1.0);

    // Transform the vertex position into model-view-projection space
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    // ---- Adapt/Beryl 顶点阶段光照分解（半透明保留 alpha）----
    adaptVertexLinear = vec4(pow(_vert_color.rgb, vec3(2.2)), _vert_color.a);
    adaptFragPos = (u_ModelViewMatrix * vec4(position, 1.0)).xyz;

    v_Color = _vert_color;
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord; // FMA for precision
}
