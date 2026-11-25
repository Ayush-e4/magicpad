package app.magicpad.pad.gl

/** GLSL ES 3.00 for the finger-glow and ambient-gradient backgrounds. */
object EffectShaders {
    /** Fullscreen quad with texture coordinates. */
    const val QUAD_VERTEX = """#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /** A flat colour, used with blending to fade the glow texture. */
    const val SOLID = """#version 300 es
precision mediump float;
uniform vec4 color;
out vec4 fragColor;
void main() {
    fragColor = color;
}
"""

    /** One small quad per glow dot: [center] and [radius] are in clip space. */
    const val DOT_VERTEX = """#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
uniform vec2 center;
uniform vec2 radius;
out vec2 vLocal;
void main() {
    vLocal = aPos;
    gl_Position = vec4(center + aPos * radius, 0.0, 1.0);
}
"""

    /** A soft Gaussian dot, added on top of what is there. */
    const val DOT = """#version 300 es
precision mediump float;
in vec2 vLocal;
uniform vec3 color;
out vec4 fragColor;
void main() {
    float a = exp(-dot(vLocal, vLocal) * 4.0);
    fragColor = vec4(color * a, 1.0);
}
"""

    /** Draws a texture to the screen, dimmed. */
    const val TEXTURE = """#version 300 es
precision mediump float;
precision mediump sampler2D;
in vec2 vUv;
uniform sampler2D uTexture;
uniform float dim;
out vec4 fragColor;
void main() {
    fragColor = vec4(texture(uTexture, vUv).rgb * dim, 1.0);
}
"""

    /**
     * Four muted colours drifting around the screen, blended by inverse distance, with a
     * light vignette and a little dither so the dark gradient doesn't band.
     */
    const val AMBIENT = """#version 300 es
precision highp float;
uniform float time;
uniform vec2 resolution;
uniform float dim;
out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec2 orbit(float t, float a, float b, float px, float py) {
    return vec2(0.5 + 0.38 * sin(t * a + px), 0.5 + 0.38 * cos(t * b + py));
}

void main() {
    vec2 uv = gl_FragCoord.xy / resolution;
    float aspect = resolution.x / resolution.y;
    float t = time * 0.06;
    vec2 p[4];
    p[0] = orbit(t, 1.00, 0.70, 0.0, 1.3);
    p[1] = orbit(t, 0.63, 1.10, 2.1, 0.4);
    p[2] = orbit(t, 0.81, 0.53, 4.2, 2.9);
    p[3] = orbit(t, 0.47, 0.89, 5.3, 4.6);
    vec3 c[4];
    c[0] = vec3(0.07, 0.30, 0.38);
    c[1] = vec3(0.20, 0.14, 0.44);
    c[2] = vec3(0.36, 0.13, 0.30);
    c[3] = vec3(0.09, 0.22, 0.46);
    vec3 sum = vec3(0.0);
    float weight = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 d = uv - p[i];
        d.x *= aspect;
        float w = 1.0 / (0.04 + dot(d, d));
        sum += c[i] * w;
        weight += w;
    }
    vec3 col = sum / weight;
    vec2 v = uv - 0.5;
    col *= 1.0 - 0.45 * dot(v, v) * 2.0;
    col = col * dim + (hash(gl_FragCoord.xy + fract(time)) - 0.5) / 255.0;
    fragColor = vec4(max(col, 0.0), 1.0);
}
"""
}
