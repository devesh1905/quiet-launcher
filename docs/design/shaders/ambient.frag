// ambient.frag : the now-playing colour field (the signature moment)
// GLSL ES 1.0 (WebGL 1). Android: AGSL RuntimeShader (API 33+) or GLSL ES 3.0 in WallpaperService.
// The cover palette (4 swatches, from the Android Palette API) flows through a two-level
// domain warp. Near the artwork frame the field refracts in slow ripples, as if colour
// were liquid light spilling off the painting. Output luminance is CAPPED (u_maxLum)
// so text drawn on top always keeps its contrast budget. AMOLED first: colour floats
// on pure black and every falloff ends at exactly #000.
precision highp float;

uniform vec2  u_res;        // drawing-buffer size, px
uniform float u_time;       // seconds (frozen under reduced motion)
uniform vec3  u_a0, u_a1, u_a2, u_a3;   // current palette, sRGB 0..1
uniform vec3  u_b0, u_b1, u_b2, u_b3;   // next palette (track change)
uniform float u_mix;        // 0..1 palette crossfade, 1.2 s
uniform float u_maxLum;     // dark theme: max relative luminance (0.028 player and lock, 0.014 home)
uniform float u_minLum;     // light theme: min relative luminance (0.66)
uniform float u_light;      // 0 dark, 1 light
uniform vec4  u_rect;       // artwork frame in px, origin bottom-left (x, y, w, h); w = 0 disables
uniform float u_glow;       // 0..1 light spill around the frame
uniform float u_pool;       // 0 = colour fills the screen, 1 = colour pools around the frame and the rest is black

float hash(vec2 p){ p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noise(vec2 p){
  vec2 i = floor(p), f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
             mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p){
  float v = 0.0, a = 0.5; mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
  for (int i = 0; i < 5; i++) { v += a * noise(p); p = m * p; a *= 0.5; }
  return v;
}
float sdBox(vec2 p, vec2 b){ vec2 d = abs(p) - b; return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0); }
float lum(vec3 c){ return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

vec3 field(vec3 c0, vec3 c1, vec3 c2, vec3 c3, float f, vec2 q, vec2 r){
  vec3 col = mix(c0, c1, clamp(f * f * 2.6, 0.0, 1.0));
  col = mix(col, c2, clamp(length(q) * 0.9, 0.0, 1.0) * 0.6);
  col = mix(col, c3, clamp(r.x * r.x * 1.4, 0.0, 1.0) * 0.45);
  return col;
}

void main(){
  vec2 frag = gl_FragCoord.xy;
  vec2 uv = frag / u_res;
  vec2 p = (frag - 0.5 * u_res) / u_res.y * 1.7;
  float t = u_time * 0.03;

  // liquid refraction around the frame
  float near = 0.0, pool = 1.0;
  if (u_rect.z > 0.0) {
    vec2 rc = u_rect.xy + 0.5 * u_rect.zw;
    float d = sdBox(frag - rc, 0.5 * u_rect.zw) / u_res.y;
    pool = mix(1.0, exp(-max(d, 0.0) * 4.5), u_pool);
    near = exp(-max(d, 0.0) * 14.0);
    vec2 nrm = normalize(frag - rc + 0.001);
    p += nrm * near * 0.035 * sin(d * 70.0 - u_time * 0.5);
  }

  vec2 q = vec2(fbm(p + vec2(0.0, t)), fbm(p + vec2(5.2, 1.3) - t));
  vec2 r = vec2(fbm(p + 4.0 * q + vec2(1.7, 9.2) + 0.15 * t),
                fbm(p + 4.0 * q + vec2(8.3, 2.8) - 0.12 * t));
  float f = fbm(p + 4.0 * r);

  vec3 cs = mix(field(u_a0, u_a1, u_a2, u_a3, f, q, r),
                field(u_b0, u_b1, u_b2, u_b3, f, q, r), u_mix);
  vec3 lin = pow(cs, vec3(2.2));
  vec3 spill = pow(mix(u_a1, u_b1, u_mix), vec3(2.2));   // light spilling off the artwork

  // Hue comes from the warped palette; brightness is built separately so the cap,
  // the pooling and the vignette can never fight each other.
  float body = smoothstep(0.28, 0.9, f);                 // AMOLED: black opens between the currents
  float s = near * u_glow;
  float vig = smoothstep(1.02, 0.18, length((uv - 0.5) * vec2(1.0, 0.8)) * 1.45);
  float amount = vig * vig * pool;                       // falls off to exact black

  if (u_light < 0.5) {
    vec3 baseC = lin / max(lum(lin), 1e-4) * u_maxLum;   // field colour at the luminance cap
    vec3 spillC = spill / max(lum(spill), 1e-4) * u_maxLum;
    vec3 c = baseC * body + spillC * s * 0.8;
    c *= min(1.0, u_maxLum / max(lum(c), 1e-6));         // hard cap: text on top keeps its contrast
    c *= amount;
    // multiplicative grain: black stays exactly black, the darkest tones never shimmer
    c *= 1.0 + (hash(frag + fract(u_time * 7.0) * 113.0) - 0.5) * 0.10;
    lin = max(c - 0.00025, 0.0);                         // crush sub-visible values to true black
  } else {
    vec3 paper = pow(vec3(0.953, 0.945, 0.922), vec3(2.2));
    vec3 tint = mix(lin, spill, clamp(s, 0.0, 1.0) * 0.6);
    tint = tint * (lum(paper) / max(lum(tint), 0.02));
    lin = mix(paper, tint, clamp((body * 0.32 + s * 0.3) * amount, 0.0, 0.4));
    lin *= 1.0 + (hash(frag + fract(u_time * 7.0) * 113.0) - 0.5) * 0.03;
    float L = lum(lin);
    if (L < u_minLum) lin = mix(lin, paper, (u_minLum - L) / max(lum(paper) - L, 1e-3));
  }
  gl_FragColor = vec4(pow(clamp(lin, 0.0, 1.0), vec3(1.0 / 2.2)), 1.0);
}
