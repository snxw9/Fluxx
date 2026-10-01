#version 450
layout(location = 0) in vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

layout(binding = 0) uniform sampler2D videoSampler;

layout(push_constant) uniform PushConstants {
    mat4 transform;
    float opacity;
} pc;

void main() {
    vec4 color = texture(videoSampler, fragTexCoord);
    outColor = vec4(color.rgb, color.a * pc.opacity);
}
