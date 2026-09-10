#version 330

// Vanilla terrain shadow caster (Beryl shadow.vsh chain, verified by ShadowSimVk).
// pos is world-relative (Position + chunk offset); LightViewProjection (with
// T(camPos%2)) -> light clip; distort z.

#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <adaptclient:adapt_shadow_frame.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    vec3 worldRelPos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    vec4 clip = LightViewProjection * vec4(worldRelPos, 1.0);
    vec3 ndc = clip.xyz / clip.w;
    // 不做 xy distort —— receiver 在延迟 pass 里只能逐像素求 distort，与顶点
    // 阶段 + 光栅插值差最多 1.25 格（AdaptShadowMap.SHADOW_DISTORTION）。
    ndc.z = ndc.z * 0.5 + 0.5;
    gl_Position = vec4(ndc, 1.0);
    texCoord0 = UV0;
    vertexColor = Color;
}
