#version 330

// Adapt 地形管线顶点着色器。
// 输入为原版 TERRAIN 顶点格式（Position/Color/UV0/UV2 — 无法线属性）与
// ChunkSection UBO；输出 adaptFragPos / adaptLightRaw 供 fragment 做
// GGX 光照（法线由 fragment 解析导数重建）。ModelViewMat 来自 ChunkSection
// UBO（原版 terrain.vsh 同样如此，因此不导入 dynamictransforms）。

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <adaptclient:adapt_lighting.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec3 adaptFragPos;
out vec3 adaptWorldPos;
out vec3 adaptLightRaw;
out vec2 texCoord0;

const float LIGHT_CONV = 1.0 / 256.0;

void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);

    vertexColor = Color;

    // terrain.vsh：视空间片元位置
    adaptFragPos = (ModelViewMat * vec4(pos, 1.0)).xyz;
    // 世界相对位置（regionPos - camPos）—阴影接收用（模拟器验证的坐标空间）
    adaptWorldPos = pos;
    // 避免依赖相机矩阵抵消（防止影子随视角转动）。

    // terrain.vsh：UV2 光照分解（与实体完全相同的公式）
    float lightY = UV2.y * LIGHT_CONV;
    lightY = lightY * lightY;
    float lightZ = max((lightY - 0.5) / (1.0 - 0.5), 0.0);
    lightY *= AmbientLightFactor;
    lightY = max(lightY - NightFactor, MinAmbientLight);
    float lightX = UV2.x * LIGHT_CONV;
    lightX = lightX * lightX * (1.0 - lightY);
    adaptLightRaw = vec3(lightX * 2.0, lightY, lightZ);

    texCoord0 = UV0;
}
