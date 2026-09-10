#version 330 core

// Sodium shadow caster fragment — depth is the authoritative output.
// ALPHA_CUTOUT discards transparent texels (Beryl shadow.fsh: alpha < 0.1).

#ifdef ALPHA_CUTOUT
in vec4 v_Color;
in vec2 v_TexCoord;
uniform sampler2D u_BlockTex;
#endif

out vec4 fragColor;

void main() {
#ifdef ALPHA_CUTOUT
    if ((texture(u_BlockTex, v_TexCoord) * v_Color).a < 0.1) {
        discard;
    }
#endif
    fragColor = vec4(1.0);
}
