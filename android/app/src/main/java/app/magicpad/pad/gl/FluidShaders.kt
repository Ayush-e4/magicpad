package app.magicpad.pad.gl

/**
 * GLSL ES 3.00 for the stable-fluids simulation (semi-Lagrangian advection, vorticity
 * confinement, Jacobi pressure solve), in the style of the well-known WebGL fluid demo.
 * Velocity is in sim texels per second; everything else is in texture coordinates.
 */
object FluidShaders {
    /** Fullscreen quad with the four neighbour coordinates precomputed. */
    const val BASE_VERTEX = """#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
uniform vec2 texelSize;
out vec2 vUv;
out vec2 vL;
out vec2 vR;
out vec2 vT;
out vec2 vB;
void main() {
    vUv = aPos * 0.5 + 0.5;
    vL = vUv - vec2(texelSize.x, 0.0);
    vR = vUv + vec2(texelSize.x, 0.0);
    vT = vUv + vec2(0.0, texelSize.y);
    vB = vUv - vec2(0.0, texelSize.y);
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private const val HEAD = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
in vec2 vL;
in vec2 vR;
in vec2 vT;
in vec2 vB;
out vec4 fragColor;
"""

    /** Adds a Gaussian blob of [color] at [point] (used for both velocity and dye). */
    const val SPLAT = HEAD + """
uniform sampler2D uTarget;
uniform float aspectRatio;
uniform vec3 color;
uniform vec2 point;
uniform float radius;
void main() {
    vec2 p = vUv - point;
    p.x *= aspectRatio;
    vec3 splat = exp(-dot(p, p) / radius) * color;
    fragColor = vec4(texture(uTarget, vUv).xyz + splat, 1.0);
}
"""

    const val ADVECTION = HEAD + """
uniform sampler2D uVelocity;
uniform sampler2D uSource;
uniform float dt;
uniform float dissipation;
uniform vec2 simTexel;
void main() {
    vec2 coord = vUv - dt * texture(uVelocity, vUv).xy * simTexel;
    fragColor = texture(uSource, coord) / (1.0 + dissipation * dt);
}
"""

    const val CURL = HEAD + """
uniform sampler2D uVelocity;
void main() {
    float L = texture(uVelocity, vL).y;
    float R = texture(uVelocity, vR).y;
    float T = texture(uVelocity, vT).x;
    float B = texture(uVelocity, vB).x;
    fragColor = vec4(0.5 * (R - L - T + B), 0.0, 0.0, 1.0);
}
"""

    const val VORTICITY = HEAD + """
uniform sampler2D uVelocity;
uniform sampler2D uCurl;
uniform float curl;
uniform float dt;
void main() {
    float L = texture(uCurl, vL).x;
    float R = texture(uCurl, vR).x;
    float T = texture(uCurl, vT).x;
    float B = texture(uCurl, vB).x;
    float C = texture(uCurl, vUv).x;
    vec2 force = 0.5 * vec2(abs(T) - abs(B), abs(R) - abs(L));
    force /= length(force) + 0.0001;
    force *= curl * C;
    force.y *= -1.0;
    vec2 v = texture(uVelocity, vUv).xy + force * dt;
    fragColor = vec4(clamp(v, -1000.0, 1000.0), 0.0, 1.0);
}
"""

    /** Divergence with free-slip walls: velocity into a wall is mirrored. */
    const val DIVERGENCE = HEAD + """
uniform sampler2D uVelocity;
void main() {
    float L = texture(uVelocity, vL).x;
    float R = texture(uVelocity, vR).x;
    float T = texture(uVelocity, vT).y;
    float B = texture(uVelocity, vB).y;
    vec2 C = texture(uVelocity, vUv).xy;
    if (vL.x < 0.0) { L = -C.x; }
    if (vR.x > 1.0) { R = -C.x; }
    if (vT.y > 1.0) { T = -C.y; }
    if (vB.y < 0.0) { B = -C.y; }
    fragColor = vec4(0.5 * (R - L + T - B), 0.0, 0.0, 1.0);
}
"""

    /** Scales the previous pressure (a warm start for the solver). */
    const val SCALE = HEAD + """
uniform sampler2D uTexture;
uniform float value;
void main() {
    fragColor = value * texture(uTexture, vUv);
}
"""

    const val PRESSURE = HEAD + """
uniform sampler2D uPressure;
uniform sampler2D uDivergence;
void main() {
    float L = texture(uPressure, vL).x;
    float R = texture(uPressure, vR).x;
    float T = texture(uPressure, vT).x;
    float B = texture(uPressure, vB).x;
    float divergence = texture(uDivergence, vUv).x;
    fragColor = vec4((L + R + B + T - divergence) * 0.25, 0.0, 0.0, 1.0);
}
"""

    const val GRADIENT_SUBTRACT = HEAD + """
uniform sampler2D uPressure;
uniform sampler2D uVelocity;
void main() {
    float L = texture(uPressure, vL).x;
    float R = texture(uPressure, vR).x;
    float T = texture(uPressure, vT).x;
    float B = texture(uPressure, vB).x;
    vec2 v = texture(uVelocity, vUv).xy - vec2(R - L, T - B);
    fragColor = vec4(v, 0.0, 1.0);
}
"""

    /**
     * Shows the dye vividly: exposure tone-mapping keeps colours saturated and lets
     * them reach full brightness. The small floor turns the last faint traces to pure
     * black, so nothing is left frozen on screen when rendering goes idle.
     */
    const val DISPLAY = HEAD + """
uniform sampler2D uTexture;
uniform float exposure;
void main() {
    vec3 c = max(texture(uTexture, vUv).rgb - 0.03, 0.0);
    fragColor = vec4(1.0 - exp(-c * exposure), 1.0);
}
"""
}
