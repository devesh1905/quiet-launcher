// quiet-canvas.frag : generative lock-screen art
// GLSL ES 1.0 (WebGL 1). Android: GLSL ES 3.0 in a WallpaperService (EGL), or AGSL on API 33+.
// Layered horizon bands with ink-diffused edges, fbm cloud streaks and faint flow
// contours. u_calm (0..1) comes from today's screen time: the less the phone was used,
// the higher u_calm, so fewer bands, lower turbulence and fewer contour lines.
// The bottom 22 % is a flat ground so the label stays readable (>= 4.5:1).
// Dark palette is AMOLED-first: true-black sky top and ground, colour only near the horizon.
precision highp float;

uniform vec2  u_res;
uniform float u_time;   // seconds; drift is very slow, frozen under reduced motion
uniform float u_calm;   // 0 busy day .. 1 calm day
uniform float u_light;  // 0 dusk palette, 1 paper and ink-wash palette
uniform float u_seed;   // changes once a day

float hash(vec2 p){ p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noise(vec2 p){
  vec2 i = floor(p), f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
             mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p, float oct){
  float v = 0.0, a = 0.5; mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
  for (int i = 0; i < 6; i++) {
    float w = clamp(oct - float(i), 0.0, 1.0);   // fractional octaves give smooth complexity
    v += a * noise(p) * w; p = m * p; a *= 0.5;
  }
  return v;
}

void main(){
  vec2 uv = gl_FragCoord.xy / u_res;
  float asp = u_res.x / u_res.y;
  vec2 p = vec2(uv.x * asp, uv.y);
  float t = u_time * 0.012;
  float busy = 1.0 - u_calm;
  float oct = mix(2.0, 6.0, busy);

  vec3 skyTop = mix(vec3(0.000, 0.000, 0.000), vec3(0.950, 0.940, 0.910), u_light);
  vec3 skyLow = mix(vec3(0.330, 0.200, 0.150), vec3(0.905, 0.855, 0.790), u_light);
  vec3 sunC   = mix(vec3(0.980, 0.720, 0.520), vec3(0.985, 0.960, 0.910), u_light);
  vec3 far    = mix(vec3(0.070, 0.072, 0.090), vec3(0.780, 0.790, 0.800), u_light);
  vec3 nearC  = mix(vec3(0.000, 0.000, 0.000), vec3(0.420, 0.440, 0.470), u_light);
  vec3 rimC   = mix(vec3(0.900, 0.640, 0.470), vec3(0.300, 0.320, 0.360), u_light);
  vec3 ground = mix(vec3(0.000, 0.000, 0.000), vec3(0.935, 0.925, 0.890), u_light);
  float horizon = 0.585;

  // sky: a thin warm afterglow on the horizon, rising into true black (dark) or paper (light)
  float glowH = exp(-max(uv.y - horizon, 0.0) * mix(9.0, 3.0, u_light));
  vec3 col = mix(skyTop, skyLow, glowH);
  float c = fbm(vec2(p.x * 1.3 + t * 2.0, p.y * 7.0) + u_seed, oct);
  col = mix(col, mix(skyLow * 1.25, skyTop, u_light * 0.5),
            smoothstep(0.55, 0.85, c) * (0.12 + 0.45 * busy) * smoothstep(0.6, 0.72, uv.y) * mix(glowH * 2.0, 1.0, u_light));

  // low sun, half set
  vec2 sp = p - vec2(asp * 0.68, horizon + 0.012);
  float sd = length(sp);
  col = mix(col, sunC, smoothstep(0.036, 0.032, sd));
  col += sunC * 0.22 * exp(-sd * 14.0) * (1.0 - u_light * 0.8);

  // horizon bands, each with a lit crest; the first band carries the sun's reflection
  float nb = mix(7.0, 3.0, u_calm);
  for (int i = 0; i < 8; i++) {
    float fi = float(i);
    float on = clamp(nb - fi, 0.0, 1.0);
    if (on <= 0.0) break;
    float k = fi / max(nb - 1.0, 1.0);
    float base = mix(horizon, 0.30, pow(k, 0.85));
    float amp = mix(0.008, 0.05, busy) * (0.45 + k);
    float h = base + amp * (fbm(vec2(p.x * (1.1 + fi * 0.45) + fi * 3.1 + t * (0.2 + fi * 0.1), fi * 1.7 + u_seed), oct) - 0.5) * 2.0;
    float edge = 0.0012 + 0.007 * fbm(vec2(p.x * 24.0, fi * 4.0 + uv.y * 30.0), 3.0) * (0.35 + busy);
    float m = smoothstep(h + edge, h - edge, uv.y) * on;
    // body darkens downward into black; crest catches the low light
    float depth = clamp((h - uv.y) / 0.09, 0.0, 1.0);
    vec3 bc = mix(mix(far, nearC, k), nearC, depth * (1.0 - u_light) * 0.85);
    float rim = exp(-abs(uv.y - h + 0.002) * mix(900.0, 600.0, busy)) * (1.0 - k * 0.8) * on;
    col = mix(col, bc, m);
    col += rimC * rim * mix(0.35, 0.18, u_light) * mix(1.0, 0.5, u_light);
  }
  // glitter path of the sun on the nearest water
  float path = exp(-abs(p.x - asp * 0.68) * 26.0) * smoothstep(horizon, horizon - 0.12, uv.y) * smoothstep(0.36, horizon - 0.02, uv.y);
  float glit = smoothstep(0.62, 0.8, noise(vec2(p.x * 80.0, uv.y * 420.0 + t * 30.0)));
  col += sunC * path * glit * 0.5 * (1.0 - u_light * 0.7);

  // flow contours, fewer on calm days
  float f = fbm(p * vec2(2.2, 5.0) + vec2(t, -t * 0.5) + u_seed, oct);
  float lines = mix(3.0, 16.0, busy);
  float l = abs(fract(f * lines) - 0.5);
  float lm = smoothstep(0.035, 0.0, l) * (0.04 + 0.10 * busy) * mix(1.0, 0.5, u_light)
           * smoothstep(0.24, 0.34, uv.y) * smoothstep(horizon - 0.005, horizon - 0.04, uv.y);   // water only
  col = mix(col, mix(rimC * 0.5, nearC, u_light), lm);

  // flat ground for the label
  col = mix(col, ground, smoothstep(0.27, 0.215, uv.y));

  // grain, then clamp the label zone
  // grain: multiplicative on dark (black stays black, no flicker in the deepest tones), additive on paper
  float g = hash(gl_FragCoord.xy + fract(u_time * 5.0) * 97.0) - 0.5;
  col = (u_light < 0.5) ? col * (1.0 + g * 0.08) : col + g * 0.022;
  if (uv.y < 0.22) col = (u_light < 0.5) ? vec3(0.0) : max(col, vec3(0.90));
  gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
