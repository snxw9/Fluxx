#version 450
layout(binding=0) uniform sampler2DArray atlas;
layout(location=0) in vec2 texCoord;
layout(location=1) in vec4 colour;
layout(location=2) flat in float page;
layout(location=3) flat in vec4 stroke;
layout(location=4) flat in vec4 sdf;
layout(location=0) out vec4 result;
layout(push_constant) uniform Push { mat4 transform; vec4 settings; } pc;
void main() {
    float distance = texture(atlas, vec3(texCoord, page)).r;
    // Per-glyph sdf = half stroke, softness, glyph opacity; settings = paint order, layer opacity.
    // Keep AA within the encoded spread, including heavily minified glyphs.
    float width = min(max(fwidth(distance), 1.0 / 255.0), 0.125);
    float band = width * 0.5 + sdf.y;
    float centre = 128.0 / 255.0;
    float fillCoverage = smoothstep(centre-band, centre+band, distance);
    float outer = smoothstep(centre-sdf.x-band, centre-sdf.x+band, distance);
    float inner = smoothstep(centre+sdf.x-band, centre+sdf.x+band, distance);
    float strokeCoverage = sdf.x > 0.0 ? max(0.0, outer-inner) : 0.0;
    float fa = colour.a * fillCoverage;
    float sa = stroke.a * strokeCoverage;
    // Compose in premultiplied space, then return straight colour to SRC_ALPHA blending.
    vec3 rgb = pc.settings.x > 0.5 ? stroke.rgb*sa + colour.rgb*fa*(1.0-sa)
                              : colour.rgb*fa + stroke.rgb*sa*(1.0-fa);
    float alpha = fa + sa*(1.0-fa);
    result = vec4(alpha > 0.0 ? rgb/alpha : vec3(0.0), alpha*sdf.z*pc.settings.y);
}
