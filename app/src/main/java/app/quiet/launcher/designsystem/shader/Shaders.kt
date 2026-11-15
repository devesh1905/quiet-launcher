package app.quiet.launcher.designsystem.shader

/**
 * AGSL ports of previews/shaders/\*.frag (DESIGN-NOTES §5, §8.5).
 * Conventions: every shader takes `u_res` in px and flips fragCoord so the maths keeps the
 * GLSL bottom-left origin of the originals. `noise` is renamed `vnoise` (reserved in some SkSL builds).
 */
object Shaders {

    private const val COMMON = """
float hash(vec2 p){ p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float vnoise(vec2 p){
  vec2 i = floor(p); vec2 f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
             mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
float lum(vec3 c){ return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
"""

    /** Now-playing colour field: the signature moment. Luminance-capped so text on top keeps contrast. */
    const val AMBIENT = """
uniform float2 u_res;
uniform float u_time;
uniform float3 u_a0; uniform float3 u_a1; uniform float3 u_a2; uniform float3 u_a3;
uniform float3 u_b0; uniform float3 u_b1; uniform float3 u_b2; uniform float3 u_b3;
uniform float u_mix;
uniform float u_maxLum;
uniform float u_minLum;
uniform float u_light;
uniform float4 u_rect;
uniform float u_glow;
uniform float u_pool;
""" + COMMON + """
float fbm(vec2 p){
  float v = 0.0; float a = 0.5; mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
  for (int i = 0; i < 5; i++) { v += a * vnoise(p); p = m * p; a *= 0.5; }
  return v;
}
float sdBox(vec2 p, vec2 b){ vec2 d = abs(p) - b; return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0); }
vec3 field(vec3 c0, vec3 c1, vec3 c2, vec3 c3, float f, vec2 q, vec2 r){
  vec3 col = mix(c0, c1, clamp(f * f * 2.6, 0.0, 1.0));
  col = mix(col, c2, clamp(length(q) * 0.9, 0.0, 1.0) * 0.6);
  col = mix(col, c3, clamp(r.x * r.x * 1.4, 0.0, 1.0) * 0.45);
  return col;
}
half4 main(float2 fc){
  vec2 frag = vec2(fc.x, u_res.y - fc.y);
  vec2 uv = frag / u_res;
  vec2 p = (frag - 0.5 * u_res) / u_res.y * 1.7;
  float t = u_time * 0.03;
  float near = 0.0; float pool = 1.0;
  if (u_rect.z > 0.0) {
    vec2 rc = u_rect.xy + 0.5 * u_rect.zw;
    float d = sdBox(frag - rc, 0.5 * u_rect.zw) / u_res.y;
    pool = mix(1.0, exp(-max(d, 0.0) * 4.5), u_pool);
    near = exp(-max(d, 0.0) * 14.0);
    vec2 nrm = normalize(frag - rc + 0.001);
    p += nrm * near * 0.035 * sin(d * 70.0 - u_time * 0.5);
  }
  vec2 q = vec2(fbm(p + vec2(0.0, t)), fbm(p + vec2(5.2, 1.3) - t));
  vec2 r = vec2(fbm(p + 4.0 * q + vec2(1.7, 9.2) + 0.15 * t), fbm(p + 4.0 * q + vec2(8.3, 2.8) - 0.12 * t));
  float f = fbm(p + 4.0 * r);
  vec3 cs = mix(field(u_a0, u_a1, u_a2, u_a3, f, q, r), field(u_b0, u_b1, u_b2, u_b3, f, q, r), u_mix);
  vec3 lin = pow(cs, vec3(2.2));
  vec3 spill = pow(mix(u_a1, u_b1, u_mix), vec3(2.2));
  float body = smoothstep(0.28, 0.9, f);
  float s = near * u_glow;
  float vig = smoothstep(1.02, 0.18, length((uv - 0.5) * vec2(1.0, 0.8)) * 1.45);
  float amount = vig * vig * pool;
  if (u_light < 0.5) {
    vec3 baseC = lin / max(lum(lin), 1e-4) * u_maxLum;
    vec3 spillC = spill / max(lum(spill), 1e-4) * u_maxLum;
    vec3 c = baseC * body + spillC * s * 0.8;
    c *= min(1.0, u_maxLum / max(lum(c), 1e-6));
    c *= amount;
    c *= 1.0 + (hash(frag + fract(u_time * 7.0) * 113.0) - 0.5) * 0.10;
    lin = max(c - 0.00025, 0.0);
  } else {
    vec3 paper = pow(vec3(0.953, 0.945, 0.922), vec3(2.2));
    vec3 tint = mix(lin, spill, clamp(s, 0.0, 1.0) * 0.6);
    tint = tint * (lum(paper) / max(lum(tint), 0.02));
    lin = mix(paper, tint, clamp((body * 0.32 + s * 0.3) * amount, 0.0, 0.4));
    lin *= 1.0 + (hash(frag + fract(u_time * 7.0) * 113.0) - 0.5) * 0.03;
    float L = lum(lin);
    if (L < u_minLum) { lin = mix(lin, paper, (u_minLum - L) / max(lum(paper) - L, 1e-3)); }
  }
  return half4(half3(pow(clamp(lin, 0.0, 1.0), vec3(1.0 / 2.2))), 1.0);
}
"""

    /** Generative lock art; u_calm 0 busy day .. 1 calm day. */
    const val QUIET_CANVAS = """
uniform float2 u_res;
uniform float u_time;
uniform float u_calm;
uniform float u_light;
uniform float u_seed;
""" + COMMON + """
float fbmo(vec2 p, float oct){
  float v = 0.0; float a = 0.5; mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
  for (int i = 0; i < 6; i++) {
    float w = clamp(oct - float(i), 0.0, 1.0);
    v += a * vnoise(p) * w; p = m * p; a *= 0.5;
  }
  return v;
}
half4 main(float2 fc){
  vec2 frag = vec2(fc.x, u_res.y - fc.y);
  vec2 uv = frag / u_res;
  float asp = u_res.x / u_res.y;
  vec2 p = vec2(uv.x * asp, uv.y);
  float t = u_time * 0.012;
  float busy = 1.0 - u_calm;
  float oct = mix(2.0, 6.0, busy);
  vec3 skyTop = mix(vec3(0.0), vec3(0.950, 0.940, 0.910), u_light);
  vec3 skyLow = mix(vec3(0.330, 0.200, 0.150), vec3(0.905, 0.855, 0.790), u_light);
  vec3 sunC   = mix(vec3(0.980, 0.720, 0.520), vec3(0.985, 0.960, 0.910), u_light);
  vec3 far    = mix(vec3(0.070, 0.072, 0.090), vec3(0.780, 0.790, 0.800), u_light);
  vec3 nearC  = mix(vec3(0.0), vec3(0.420, 0.440, 0.470), u_light);
  vec3 rimC   = mix(vec3(0.900, 0.640, 0.470), vec3(0.300, 0.320, 0.360), u_light);
  vec3 ground = mix(vec3(0.0), vec3(0.935, 0.925, 0.890), u_light);
  float horizon = 0.585;
  float glowH = exp(-max(uv.y - horizon, 0.0) * mix(9.0, 3.0, u_light));
  vec3 col = mix(skyTop, skyLow, glowH);
  float c = fbmo(vec2(p.x * 1.3 + t * 2.0, p.y * 7.0) + u_seed, oct);
  col = mix(col, mix(skyLow * 1.25, skyTop, u_light * 0.5),
            smoothstep(0.55, 0.85, c) * (0.12 + 0.45 * busy) * smoothstep(0.6, 0.72, uv.y) * mix(glowH * 2.0, 1.0, u_light));
  vec2 sp = p - vec2(asp * 0.68, horizon + 0.012);
  float sd = length(sp);
  col = mix(col, sunC, smoothstep(0.036, 0.032, sd));
  col += sunC * 0.22 * exp(-sd * 14.0) * (1.0 - u_light * 0.8);
  float nb = mix(7.0, 3.0, u_calm);
  for (int i = 0; i < 8; i++) {
    float fi = float(i);
    float on = clamp(nb - fi, 0.0, 1.0);
    if (on > 0.0) {
      float k = fi / max(nb - 1.0, 1.0);
      float base = mix(horizon, 0.30, pow(k, 0.85));
      float amp = mix(0.008, 0.05, busy) * (0.45 + k);
      float h = base + amp * (fbmo(vec2(p.x * (1.1 + fi * 0.45) + fi * 3.1 + t * (0.2 + fi * 0.1), fi * 1.7 + u_seed), oct) - 0.5) * 2.0;
      float edge = 0.0012 + 0.007 * fbmo(vec2(p.x * 24.0, fi * 4.0 + uv.y * 30.0), 3.0) * (0.35 + busy);
      float m = smoothstep(h + edge, h - edge, uv.y) * on;
      float depth = clamp((h - uv.y) / 0.09, 0.0, 1.0);
      vec3 bc = mix(mix(far, nearC, k), nearC, depth * (1.0 - u_light) * 0.85);
      float rim = exp(-abs(uv.y - h + 0.002) * mix(900.0, 600.0, busy)) * (1.0 - k * 0.8) * on;
      col = mix(col, bc, m);
      col += rimC * rim * mix(0.35, 0.18, u_light) * mix(1.0, 0.5, u_light);
    }
  }
  float path = exp(-abs(p.x - asp * 0.68) * 26.0) * smoothstep(horizon, horizon - 0.12, uv.y) * smoothstep(0.36, horizon - 0.02, uv.y);
  float glit = smoothstep(0.62, 0.8, vnoise(vec2(p.x * 80.0, uv.y * 420.0 + t * 30.0)));
  col += sunC * path * glit * 0.5 * (1.0 - u_light * 0.7);
  float f = fbmo(p * vec2(2.2, 5.0) + vec2(t, -t * 0.5) + u_seed, oct);
  float lines = mix(3.0, 16.0, busy);
  float l = abs(fract(f * lines) - 0.5);
  float lm = smoothstep(0.035, 0.0, l) * (0.04 + 0.10 * busy) * mix(1.0, 0.5, u_light)
           * smoothstep(0.24, 0.34, uv.y) * smoothstep(horizon - 0.005, horizon - 0.04, uv.y);
  col = mix(col, mix(rimC * 0.5, nearC, u_light), lm);
  col = mix(col, ground, smoothstep(0.27, 0.215, uv.y));
  float g = hash(frag + fract(u_time * 5.0) * 97.0) - 0.5;
  col = (u_light < 0.5) ? col * (1.0 + g * 0.08) : col + g * 0.022;
  if (uv.y < 0.22) { col = (u_light < 0.5) ? vec3(0.0) : max(col, vec3(0.90)); }
  return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

    /** Tonight's sky: real moon phase lit as a sphere, sparse stars, last twilight. */
    const val NIGHT_SKY = """
uniform float2 u_res;
uniform float u_time;
uniform float u_phase;
uniform float3 u_moon;
uniform float u_horizon;
uniform float u_dusk;
uniform float u_light;
uniform float u_seed;
""" + COMMON + """
float fbm4(vec2 p){
  float v = 0.0; float a = 0.5; mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
  for (int i = 0; i < 4; i++) { v += a * vnoise(p); p = m * p; a *= 0.5; }
  return v;
}
float stars(vec2 frag, float cell, float density, float layer){
  vec2 g = frag / cell;
  vec2 id = floor(g);
  vec2 f = fract(g) - 0.5;
  float h = hash(id + layer * 17.0 + u_seed);
  float on = step(1.0 - density, h);
  vec2 off = (vec2(hash(id + 3.1 + layer), hash(id + 7.7 + layer)) - 0.5) * 0.7;
  float b = pow(hash(id + 1.3 + layer), 3.0);
  float tw = 1.0 + 0.18 * sin(u_time * (0.25 + h) + h * 40.0);
  float px = 1.0 / cell;
  float d = length(f - off);
  return on * b * tw * smoothstep(px * (1.2 + 1.8 * b), 0.0, d);
}
half4 main(float2 fc){
  vec2 frag = vec2(fc.x, u_res.y - fc.y);
  vec2 uv = frag / u_res;
  float s = u_res.y / 915.0;
  float above = uv.y - u_horizon;
  float st = stars(frag, 19.0 * s, 0.10, 0.0) * 0.45 + stars(frag, 37.0 * s, 0.16, 1.0) * 0.8 + stars(frag, 71.0 * s, 0.22, 2.0);
  vec2 bp = vec2(uv.x * 0.9 + uv.y * 0.6, uv.y * 0.9 - uv.x * 0.5);
  float band = exp(-pow((bp.y - 0.42) * 5.0, 2.0)) * fbm4(bp * vec2(3.0, 9.0) + u_seed);
  st *= 1.0 + band * 1.5;
  float twi = u_dusk * exp(-max(above, 0.0) * 5.0);
  st *= smoothstep(0.0, 0.12, above) * (1.0 - 0.85 * twi);
  vec3 glowLow  = vec3(0.115, 0.055, 0.030);
  vec3 glowHigh = vec3(0.010, 0.020, 0.055);
  vec3 sky = mix(glowHigh, glowLow, exp(-max(above, 0.0) * 22.0)) * u_dusk * exp(-max(above, 0.0) * 4.0);
  sky += vec3(0.85, 0.88, 0.95) * st;
  vec2 m = (frag - u_moon.xy) / u_moon.z;
  float r = length(m);
  float th = u_phase * 6.28318;
  vec3 L = normalize(vec3(sin(th), 0.12, -cos(th)));
  float illum = 0.5 - 0.5 * cos(th);
  vec3 moonCol = vec3(0.0);
  float disc = 0.0;
  if (r < 1.02) {
    vec3 n = vec3(m, sqrt(max(1.0 - r * r, 0.0)));
    float lit = smoothstep(-0.035, 0.07, dot(n, L));
    float maria = smoothstep(0.48, 0.68, fbm4(m * 2.1 + vec2(11.0, 3.0)));
    float grainy = fbm4(m * 9.0 + 4.0);
    float albedo = mix(0.97, 0.62, maria) * (0.86 + 0.26 * grainy);
    float limb = pow(max(n.z, 0.0), 0.28);
    moonCol = vec3(0.93, 0.91, 0.86) * albedo * limb * lit + vec3(0.030, 0.034, 0.042) * albedo * (1.0 - lit);
    disc = smoothstep(1.0, 1.0 - 1.5 / u_moon.z, r);
  }
  float halo = exp(-max(r - 1.0, 0.0) * 3.2) * 0.07 * illum * step(1.0, r);
  vec3 col = mix(sky, moonCol, disc) + vec3(0.80, 0.82, 0.86) * halo;
  col *= step(0.0, above);
  if (u_light > 0.5) {
    vec3 paper = vec3(0.953, 0.945, 0.922);
    vec3 ink = vec3(0.078, 0.075, 0.067);
    float y = clamp(st * 1.4, 0.0, 1.0) * step(0.0, above);
    vec3 c2 = mix(paper, ink, y * 0.8);
    c2 = mix(c2, paper * 0.93, twi * 0.3);
    vec3 moonInk = mix(mix(paper, ink, 0.16), vec3(0.99, 0.985, 0.97), clamp(lum(moonCol) * 1.25, 0.0, 1.0));
    c2 = mix(c2, moonInk, disc);
    float px = 1.0 / u_moon.z;
    c2 = mix(c2, ink, smoothstep(px * 1.4, 0.0, abs(r - 1.0)) * 0.55);
    col = c2;
  }
  return half4(half3(col), 1.0);
}
"""

    /** Breathing ink ring for the mindful pause. Premultiplied alpha. */
    const val INK_BREATH = """
uniform float2 u_res;
uniform float u_time;
uniform float u_breath;
uniform float u_progress;
uniform float3 u_ink;
""" + COMMON + """
float fbm4(vec2 p){
  float v = 0.0; float a = 0.5; mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
  for (int i = 0; i < 4; i++) { v += a * vnoise(p); p = m * p; a *= 0.5; }
  return v;
}
half4 main(float2 fc){
  vec2 frag = vec2(fc.x, u_res.y - fc.y);
  vec2 p = (frag - 0.5 * u_res) / (0.5 * u_res.y);
  float r = length(p);
  float a = atan(p.y, p.x);
  float R = mix(0.60, 0.78, u_breath);
  vec2 np = vec2(cos(a), sin(a)) * 1.6 + vec2(u_time * 0.06, -u_time * 0.04);
  float n = fbm4(np + r * 2.0);
  float d = abs(r - R - (n - 0.5) * 0.07);
  float ring = exp(-d * d * 1400.0) * 0.55 + exp(-d * 34.0) * 0.10 * (0.6 + 0.4 * u_breath);
  float ang = mod(atan(p.x, p.y) + 6.28318, 6.28318) / 6.28318;
  float px = 2.0 / u_res.y;
  float track = smoothstep(px * 1.2, 0.0, abs(r - 0.95)) * 0.22;
  float arc = smoothstep(px * 1.6, 0.0, abs(r - 0.95)) * step(ang, u_progress);
  float alpha = clamp(ring + track + arc, 0.0, 1.0);
  return half4(half3(u_ink * alpha), alpha);
}
"""
}
