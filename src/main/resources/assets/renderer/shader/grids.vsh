#version 300 es

layout (location = 0) in vec3 i_pos;

uniform mat4 u_projection;

out vec4 vertexColor;

void main() {
  gl_Position = u_projection * vec4(i_pos, 1.0f);
  vertexColor = vec4(0.0 / 255.0f, 0.0 / 255.0f, 0.0 / 255.0f, 255.0 / 255.0f);
}
