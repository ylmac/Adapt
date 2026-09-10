#version 330 core

// Sodium compressed terrain shadow caster — VERIFIED by ShadowSimVk.
// Same as Beryl shadow.vsh: gl_Position = LightSpaceMat * pos, then
// distortShadowClipPos (z*0.5+0.5), clamp to [0,1].
// u_ProjectionMatrix = lightProjection
// u_ModelViewMatrix  = lightView * T(camPos%2)
// position is WORLD-RELATIVE (u_RegionOffset + drawTranslation + local).

#moj_import <sodium:globals.glsl>
#moj_import <sodium:chunk_vertex.glsl>

out vec4 v_Color;
out vec2 v_TexCoord;

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

    // Beryl shadow.vsh: LightSpaceMat * position, then z*0.5+0.5.
    // **不做 Beryl 的 xy distort**：Beryl 的 receiver 也在顶点阶段 distort
    // （terrain.vsh:110），光栅线性插值误差两边抵消；Adapt 的 receiver 是延迟
    // 全屏 pass，只能逐像素精确求 distort，与这里的顶点+插值最多差 66 texel
    // ≈ 1.25 格（数值见 AdaptShadowMap.SHADOW_DISTORTION）。两边一起去掉才对齐。
    vec4 clip = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);
    vec3 ndc = clip.xyz / clip.w;
    ndc.z = ndc.z * 0.5 + 0.5;
    gl_Position = vec4(ndc, 1.0);

    v_Color = _vert_color;
    // Match Sodium's original UV math so the texture atlas behaves identically.
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord;
}
