package app.twistcubee.render

/**
 * GLSL ES 3.00 shaders.
 *
 * The turning layer is animated entirely on the GPU: every instance carries its cubie center, and
 * the vertex shader rotates the instances whose center lies inside the animated slab. A frame
 * during a turn therefore costs a handful of uniform updates and no buffer uploads.
 */
internal object Shaders {

    private const val ANIM = """
uniform mat4 uViewProj;
uniform mat4 uModel;
uniform mat3 uAnimRot;
uniform vec3 uAnimAxis;
uniform vec2 uAnimRange;
bool inSlab(vec3 c) {
    float d = dot(c, uAnimAxis);
    return d > uAnimRange.x && d < uAnimRange.y;
}
"""

    private const val LIGHTING = """
const vec3 KEY = vec3(-0.4082, 0.8165, 0.4082);
const vec3 FILL = vec3(0.6963, -0.1393, 0.7041);
uniform vec3 uEye;
"""

    const val BODY_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNor;
layout(location = 2) in vec3 aCenter;
$ANIM
out vec3 vNor;
out vec3 vPos;
void main() {
    vec3 p = aPos + aCenter;
    vec3 n = aNor;
    if (inSlab(aCenter)) {
        p = uAnimRot * p;
        n = uAnimRot * n;
    }
    vec4 w = uModel * vec4(p, 1.0);
    vPos = w.xyz;
    vNor = mat3(uModel) * n;
    gl_Position = uViewProj * w;
}
"""

    const val BODY_FS = """#version 300 es
precision mediump float;
$LIGHTING
uniform vec3 uColor;
in vec3 vNor;
in vec3 vPos;
out vec4 oColor;
void main() {
    vec3 n = normalize(vNor);
    vec3 v = normalize(uEye - vPos);
    float key = max(dot(n, KEY), 0.0);
    float fill = max(dot(n, FILL), 0.0);
    float hemi = 0.5 + 0.5 * n.y;
    vec3 h = normalize(KEY + v);
    float spec = pow(max(dot(n, h), 0.0), 40.0);
    float rim = 1.0 - max(dot(n, v), 0.0);
    rim *= rim * rim;
    vec3 c = uColor * (0.32 + 0.16 * hemi + 0.52 * key + 0.20 * fill)
           + vec3(0.30) * spec + vec3(0.05) * rim;
    oColor = vec4(c, 1.0);
}
"""

    // Per-sticker basis comes from instance attributes rather than indexed const arrays, which
    // some GLSL translators and older mobile drivers miscompile in vertex shaders.
    const val STICKER_VS = """#version 300 es
layout(location = 0) in vec2 aQuad;
layout(location = 1) in vec3 aCenter;   // cubie center (cube units)
layout(location = 2) in vec4 aColor;    // sticker RGB, normalized bytes
layout(location = 3) in vec3 aNormal;   // outward face normal
layout(location = 4) in vec3 aTangent;  // in-face axis; bitangent = normal x tangent
$ANIM
uniform float uLift;
uniform vec3 uHintAxis;
uniform vec2 uHintRange;
uniform float uHintAmount;
out vec3 vNor;
out vec3 vPos;
out vec3 vCol;
void main() {
    vec3 c = aCenter;
    vec3 n = aNormal;
    vec3 p = c + n * uLift + aTangent * aQuad.x + cross(n, aTangent) * aQuad.y;
    if (inSlab(c)) {
        p = uAnimRot * p;
        n = uAnimRot * n;
    }
    vec4 w = uModel * vec4(p, 1.0);
    vPos = w.xyz;
    vNor = mat3(uModel) * n;
    vec3 col = aColor.rgb;
    float hd = dot(c, uHintAxis);
    bool hinted = hd > uHintRange.x && hd < uHintRange.y;
    // Dim everything outside the hinted layer and lift the hinted stickers slightly.
    vCol = hinted ? mix(col, vec3(1.0), 0.12 * uHintAmount) : col * (1.0 - 0.55 * uHintAmount);
    gl_Position = uViewProj * w;
}
"""

    const val STICKER_FS = """#version 300 es
precision mediump float;
$LIGHTING
in vec3 vNor;
in vec3 vPos;
in vec3 vCol;
out vec4 oColor;
void main() {
    vec3 n = normalize(vNor);
    vec3 v = normalize(uEye - vPos);
    float key = max(dot(n, KEY), 0.0);
    float fill = max(dot(n, FILL), 0.0);
    vec3 h = normalize(KEY + v);
    float spec = pow(max(dot(n, h), 0.0), 72.0);
    vec3 c = vCol * (0.50 + 0.42 * key + 0.16 * fill) + vec3(0.28) * spec;
    oColor = vec4(c, 1.0);
}
"""

    const val CORE_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMvp;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

    const val CORE_FS = """#version 300 es
precision mediump float;
uniform vec3 uColor;
out vec4 oColor;
void main() {
    oColor = vec4(uColor, 1.0);
}
"""

    const val BG_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val BG_FS = """#version 300 es
precision highp float;
uniform vec3 uTop;
uniform vec3 uBottom;
uniform vec3 uGlow;
uniform vec2 uGlowPos;
uniform float uAspect;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec3 c = mix(uBottom, uTop, vUv.y);
    vec2 d = (vUv - uGlowPos) * vec2(uAspect, 1.0);
    c += uGlow * (1.0 - smoothstep(0.0, 0.85, length(d)));
    // Interleaved gradient noise dithering removes banding in the dark gradient.
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    c += (noise - 0.5) / 255.0;
    oColor = vec4(c, 1.0);
}
"""
}
