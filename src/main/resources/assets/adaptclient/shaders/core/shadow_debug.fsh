#version 330

// Shadow debug: visualize the shadow map depth texture directly.
// Depth value -> pseudo color so the caster's actual output is visible.

uniform sampler2D ShadowMapSampler;

out vec4 fragColor;

void main() {
    vec2 uv = gl_FragCoord.xy / vec2(512.0, 512.0); // sample center region of the 2048 map
    float d = texture(ShadowMapSampler, uv).r;
    // forward-Z: near light = small (dark), far = large (bright)
    fragColor = vec4(d, 1.0 - d, 0.0, 1.0);
}
