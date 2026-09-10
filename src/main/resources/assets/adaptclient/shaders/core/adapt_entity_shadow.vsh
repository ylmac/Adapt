#version 330

// Vanilla entity shadow caster (Beryl shadow/entity.vsh chain, verified by ShadowSimVk).
// Entity vertex data is MODEL-LOCAL; the renderer bakes the camera rotation and
// the (entityPos - camPos) translation into ModelViewMat (LevelRenderer.render:
// modelViewStack.mul(viewRotationMatrix); EntityRenderDispatcher.submit:
// poseStack.translate(relX, relY, relZ)), so ModelViewMat = R_cam * T_rel.
// ViewToWorld (= viewRotationMatrix.invert() = R_cam) maps the view-space
// fragment back to WORLD-RELATIVE coords (Position + T_rel) — the exact frame
// LightViewProjection (lightProjection * lightView * T(camPos%2)) expects
// (Beryl entity.vsh: posLightSpace = LightSpaceMat * position, where position
// is world-relative). Do NOT feed raw local Position into LightViewProjection.

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <adaptclient:adapt_shadow_frame.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    vec3 viewPosition = (ModelViewMat * vec4(Position, 1.0)).xyz;
    vec3 worldRelPos = (ViewToWorld * vec4(viewPosition, 1.0)).xyz;
    vec4 clip = LightViewProjection * vec4(worldRelPos, 1.0);
    vec3 ndc = clip.xyz / clip.w;
    // 不做 xy distort —— receiver 在延迟 pass 里只能逐像素求 distort，与顶点
    // 阶段 + 光栅插值差最多 1.25 格（AdaptShadowMap.SHADOW_DISTORTION）。
    ndc.z = ndc.z * 0.5 + 0.5;
    gl_Position = vec4(ndc, 1.0);
    texCoord0 = UV0;
    vertexColor = Color;
}
