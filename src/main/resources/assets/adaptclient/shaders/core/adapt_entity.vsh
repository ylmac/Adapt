#version 330

// Adapt 自有实体管线顶点着色器（Complementary Reimagined r5 光照移植）。
// 顶点属性为原版 ENTITY 格式不变；输出在 vanilla 接口之上附加
// adaptNormalVS / adaptFragPos / adaptWorldPos / lmCoordM 供 fragment 做
// r5 DoLighting 光照。
// - lmCoordM：GetLightMapCoordinates（r5 commonFunctions.glsl:2-4），
//   UV2 是原版 0-240 lightmap 坐标，/256 后与 r5 的 gl_MultiTexCoord1 同编码。
// - vertexColor = Color（sRGB，含 tint；alpha 是原版实体 AO，fragment 里
//   作为 vanillaAO 传入 —— r5 glColor.a 同语义）。
// 光照不再在顶点阶段用 Light0/Light1 调制（那是原版渲染，本项目弃用）。

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <adaptclient:adapt_lighting.glsl>
#moj_import <adaptclient:adapt_shadow_frame.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

#ifndef NO_OVERLAY
uniform sampler2D Sampler1;
#endif

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec3 adaptNormalVS;
out vec3 adaptFragPos;
out vec3 adaptWorldPos;
out vec2 lmCoordM;
#ifndef NO_OVERLAY
out vec4 overlayColor;
#endif
out vec2 texCoord0;

const float LIGHT_CONV = 1.0 / 256.0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);

    // albedo 原样传递（r5 实体与地形同：纹理色 * glColor.rgb，无 pow2.2）
    vertexColor = Color;

    // 视空间法线与片元位置
    adaptNormalVS = (ModelViewMat * vec4(Normal, 0.0)).xyz;
    adaptFragPos = (ModelViewMat * vec4(Position, 1.0)).xyz;
    // 阴影接收：ModelViewMat = R_cam * T_rel（相机旋转 bake 进全局 modelView
    // stack，实体相对平移在 poseStack），ViewToWorld = R_cam⁻¹ 把它还原成
    // 世界相对坐标（Position + T_rel）——正是 LightViewProjection 期望的帧。
    // 注意 Position 是模型局部坐标，不能直接乘 LightViewProjection。
    adaptWorldPos = (ViewToWorld * vec4(adaptFragPos, 1.0)).xyz;

    // GetLightMapCoordinates（r5 commonFunctions.glsl:2-4）
    lmCoordM = clamp((UV2 * LIGHT_CONV - 0.03125) * 1.06667, 0.0, 1.0);

#ifndef NO_OVERLAY
    overlayColor = texelFetch(Sampler1, UV1, 0);
#endif

    texCoord0 = UV0;

#ifdef APPLY_TEXTURE_MATRIX
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
}
