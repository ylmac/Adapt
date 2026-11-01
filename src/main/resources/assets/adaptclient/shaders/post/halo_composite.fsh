#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D GlowSampler;
uniform sampler2D SceneSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform HaloCompositeConfig {
    float GlowStrength;
};

void main() {
    vec4 scene = texture(SceneSampler, texCoord);
    vec4 glow = texture(GlowSampler, texCoord);

    fragColor = vec4(scene.rgb + glow.rgb * glow.a * GlowStrength, scene.a);
}
