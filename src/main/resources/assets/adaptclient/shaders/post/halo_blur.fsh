#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D MaskSampler;
uniform sampler2D MaskDepthSampler;
uniform sampler2D SceneDepthSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform HaloBlurConfig {
    vec2 Direction;
    float Radius;
    float DepthEpsilon;
};

const float BLUR_WEIGHTS[9] = float[9](
    0.016216,
    0.054054,
    0.121622,
    0.194595,
    0.227027,
    0.194595,
    0.121622,
    0.054054,
    0.016216
);

void main() {
    vec2 texel = Direction / InSize;
    float centerSceneDepth = texture(SceneDepthSampler, texCoord).r;
    float radius = min(Radius, 4.0);

    vec3 accumulatedColor = vec3(0.0);
    float accumulatedAlpha = 0.0;
    float normalization = 0.0;

    for (int offset = -4; offset <= 4; ++offset) {
        float offsetFloat = float(offset);
        if (abs(offsetFloat) > radius) {
            continue;
        }

        float weight = BLUR_WEIGHTS[offset + 4];
        vec2 sampleUV = texCoord + texel * offsetFloat;
        vec4 mask = texture(MaskSampler, sampleUV);
        float maskDepth = texture(MaskDepthSampler, sampleUV).r;

        if (mask.a > 0.0 && maskDepth + DepthEpsilon >= centerSceneDepth) {
            accumulatedColor += mask.rgb * weight;
            accumulatedAlpha += mask.a * weight;
            normalization += weight;
        }
    }

    if (normalization > 0.0) {
        accumulatedColor /= normalization;
        accumulatedAlpha /= normalization;
    }

    fragColor = vec4(accumulatedColor, accumulatedAlpha);
}
