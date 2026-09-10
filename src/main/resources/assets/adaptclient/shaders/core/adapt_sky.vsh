#version 330

// Adapt 天空盒管线顶点着色器（SKY 管线，POSITION 格式）。
// 传递视空间位置供 fragment 做天空渐变/星星/日月/Reim 云 raymarch。

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;

out vec3 adaptViewPos;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;

void main() {
    adaptViewPos = (ModelViewMat * vec4(Position, 1.0)).xyz;
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
