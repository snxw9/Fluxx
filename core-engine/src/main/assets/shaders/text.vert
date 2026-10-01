#version 450
layout(location=0) in vec2 position;
layout(location=1) in vec2 uv;
layout(location=2) in vec4 glyphColour;
layout(location=3) in float atlasLayer;
layout(location=0) out vec2 texCoord;
layout(location=1) out vec4 colour;
layout(location=2) flat out float page;
layout(push_constant) uniform Push { mat4 transform; vec4 fill; } pc;
void main() {
    gl_Position = pc.transform * vec4(position, 0.0, 1.0);
    texCoord = uv;
    colour = pc.fill * glyphColour;
    page = atlasLayer;
}
