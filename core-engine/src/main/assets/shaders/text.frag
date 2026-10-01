#version 450
layout(binding=0) uniform sampler2D atlas;
layout(location=0) in vec2 texCoord;
layout(location=1) in vec4 colour;
layout(location=0) out vec4 result;
void main() {
    float distance = texture(atlas, texCoord).r;
    float width = max(fwidth(distance), 1.0 / 255.0);
    float coverage = smoothstep(128.0 / 255.0 - width * 0.5,
                              128.0 / 255.0 + width * 0.5, distance);
    result = vec4(colour.rgb, colour.a * coverage);
}
