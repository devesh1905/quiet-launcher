// ink-breath.frag : breathing ring for the mindful-launch pause (optional, subtle)
// GLSL ES 1.0 (WebGL 1). Android: AGSL RuntimeShader via ShaderBrush on API 33+.
// A ring of ink that swells on the in-breath and settles on the out-breath, its edge
// diffusing as if dropped in water. A crisp hairline arc around it shows the 15 s pause.
// Output is premultiplied alpha over the page; the centre stays empty for the number.
precision highp float;

uniform vec2  u_res;
uniform float u_time;      // seconds
uniform float u_breath;    // 0 exhaled .. 1 inhaled (eased sine, 4 s in, 4 s out)
uniform float u_progress;  // 0..1 of the pause
uniform vec3  u_ink;       // theme ink, sRGB

float hash(vec2 p){ p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noise(vec2 p){
  vec2 i = floor(p), f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
             mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p){
  float v = 0.0, a = 0.5; mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
  for (int i = 0; i < 4; i++) { v += a * noise(p); p = m * p; a *= 0.5; }
  return v;
}

void main(){
  vec2 p = (gl_FragCoord.xy - 0.5 * u_res) / (0.5 * u_res.y);
  float r = length(p);
  float a = atan(p.y, p.x);

  // ink ring: radius breathes, edge wobbles with slow noise
  float R = mix(0.60, 0.78, u_breath);
  vec2 np = vec2(cos(a), sin(a)) * 1.6 + vec2(u_time * 0.06, -u_time * 0.04);
  float n = fbm(np + r * 2.0);
  float d = abs(r - R - (n - 0.5) * 0.07);
  float ring = exp(-d * d * 1400.0) * 0.55 + exp(-d * 34.0) * 0.10 * (0.6 + 0.4 * u_breath);

  // pause progress: hairline track and arc, clockwise from the top
  float ang = mod(atan(p.x, p.y) + 6.28318, 6.28318) / 6.28318;
  float px = 2.0 / u_res.y;                               // one device pixel in p units
  float track = smoothstep(px * 1.2, 0.0, abs(r - 0.95)) * 0.22;
  float arc = smoothstep(px * 1.6, 0.0, abs(r - 0.95)) * step(ang, u_progress);

  float alpha = clamp(ring + track + arc, 0.0, 1.0);
  gl_FragColor = vec4(u_ink * alpha, alpha);
}
