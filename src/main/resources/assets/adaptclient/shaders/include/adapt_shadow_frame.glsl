#version 330

// Shared shadow-caster matrix UBO. Receivers use adapt_shadow.glsl, which
// declares the same block plus the manual PCF samplers.
// 布局必须与 adapt_shadow.glsl 和 AdaptShadowMap.FRAME_SIZE(224) 逐字节一致。
layout(std140) uniform ShadowFrame {
    mat4 LightViewProjection; // 0
    mat4 ViewToWorld;         // 64
    vec4 ShadowParams;        // 128
    mat4 SkyViewProjection;   // 144 顶视正交 VP（天光可见度图）
    vec4 SkyParams;           // 208
};                            // 224
