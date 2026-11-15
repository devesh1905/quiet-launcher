// night-sky.frag : "Tonight's sky" lock screen
// GLSL ES 1.0 (WebGL 1). Android: GLSL ES 3.0 in the custom lock screen (GLSurfaceView / EGL)
// or AGSL RuntimeShader on API 33+.
// The real moon phase, lit as a sphere (terminator, maria, limb darkening, faint earthshine),
// over a sparse star field and the remains of twilight at the horizon. AMOLED first: the sky
// is exact black between stars; no grain at all (stars are the only texture), and everything
// below the horizon is black so the sun arc and the label sit on nothing.
precision highp float;

uniform vec2  u_res;
uniform float u_time;     // seconds; only star twinkle (very slow) and nothing else moves
uniform float u_phase;    // 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter
uniform vec3  u_moon;     // moon centre (px, origin bottom-left) and radius (px)
uniform float u_horizon;  // horizon height as a fraction of the screen (0..1, from the bottom)
uniform float u_dusk;     // 0 deep night .. 1 just after sunset (twilight glow strength)
uniform float u_light;    // 0 night on black, 1 ink on paper
uniform float u_seed;

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

float lum3(vec3 c){ return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// One layer of point stars on a jittered grid. Returns brightness.
float stars(vec2 frag, float cell, float density, float layer){
  vec2 g = frag / cell;
  vec2 id = floor(g);
  vec2 f = fract(g) - 0.5;
  float h = hash(id + layer * 17.0 + u_seed);
  if (h < 1.0 - density) return 0.0;
  vec2 off = (vec2(hash(id + 3.1 + layer), hash(id + 7.7 + layer)) - 0.5) * 0.7;
  float b = pow(hash(id + 1.3 + layer), 3.0);               // most stars faint, a few bright
  float tw = 1.0 + 0.18 * sin(u_time * (0.25 + h) + h * 40.0);
  float px = 1.0 / cell;                                    // one pixel in cell units
  float d = length(f - off);
  return b * tw * smoothstep(px * (1.2 + 1.8 * b), 0.0, d);
}

void main(){
  vec2 frag = gl_FragCoord.xy;
  vec2 uv = frag / u_res;
  float s = u_res.y / 915.0;                                // device scale
  float above = uv.y - u_horizon;

  // ---- sky ----
  float st = stars(frag, 19.0 * s, 0.10, 0.0) * 0.45 + stars(frag, 37.0 * s, 0.16, 1.0) * 0.8 + stars(frag, 71.0 * s, 0.22, 2.0);
  // a faint band of the galaxy, barely there
  vec2 bp = vec2(uv.x * 0.9 + uv.y * 0.6, uv.y * 0.9 - uv.x * 0.5);
  float band = exp(-pow((bp.y - 0.42) * 5.0, 2.0)) * fbm(bp * vec2(3.0, 9.0) + u_seed);
  st *= 1.0 + band * 1.5;
  // stars fade into twilight and towards the horizon
  float twi = u_dusk * exp(-max(above, 0.0) * 5.0);
  st *= smoothstep(0.0, 0.12, above) * (1.0 - 0.85 * twi);

  vec3 glowLow  = vec3(0.115, 0.055, 0.030);                // last amber at the horizon
  vec3 glowHigh = vec3(0.010, 0.020, 0.055);                // blue hour above it
  vec3 sky = mix(glowHigh, glowLow, exp(-max(above, 0.0) * 22.0)) * u_dusk * exp(-max(above, 0.0) * 4.0);
  sky += vec3(0.85, 0.88, 0.95) * st;

  // ---- moon ----
  vec2 m = (frag - u_moon.xy) / u_moon.z;
  float r = length(m);
  float th = u_phase * 6.28318;
  vec3 L = normalize(vec3(sin(th), 0.12, -cos(th)));        // waxing: lit from the right (northern sky)
  float illum = 0.5 - 0.5 * cos(th);
  vec3 moonCol = vec3(0.0);
  float disc = 0.0;
  if (r < 1.02) {
    vec3 n = vec3(m, sqrt(max(1.0 - r * r, 0.0)));
    float lit = smoothstep(-0.035, 0.07, dot(n, L));
    float maria = smoothstep(0.48, 0.68, fbm(m * 2.1 + vec2(11.0, 3.0)));
    float grainy = fbm(m * 9.0 + 4.0);
    float albedo = mix(0.97, 0.62, maria) * (0.86 + 0.26 * grainy);
    float limb = pow(max(n.z, 0.0), 0.28);
    moonCol = vec3(0.93, 0.91, 0.86) * albedo * limb * lit
            + vec3(0.030, 0.034, 0.042) * albedo * (1.0 - lit);   // earthshine on the dark side
    disc = smoothstep(1.0, 1.0 - 1.5 / u_moon.z, r);
  }
  float halo = exp(-max(r - 1.0, 0.0) * 3.2) * 0.07 * illum * step(1.0, r);
  vec3 col = mix(sky, moonCol, disc) + vec3(0.80, 0.82, 0.86) * halo;

  // below the horizon: nothing
  col *= step(0.0, above);

  if (u_light > 0.5) {
    // ink on paper: stars are dots of ink, the lit moon is paper, its dark side an ink wash
    vec3 paper = vec3(0.953, 0.945, 0.922);
    vec3 ink = vec3(0.078, 0.075, 0.067);
    float y = clamp(st * 1.4, 0.0, 1.0) * step(0.0, above);
    vec3 c = mix(paper, ink, y * 0.8);
    c = mix(c, paper * 0.93, twi * 0.3);
    // engraving: a pale ink wash on the dark side, bare paper where the sun lights it, a fine outline
    vec3 moonInk = mix(mix(paper, ink, 0.16), vec3(0.99, 0.985, 0.97), clamp(lum3(moonCol) * 1.25, 0.0, 1.0));
    c = mix(c, moonInk, disc);
    float px = 1.0 / u_moon.z;
    c = mix(c, ink, smoothstep(px * 1.4, 0.0, abs(r - 1.0)) * 0.55);
    col = c;
  }
  gl_FragColor = vec4(col, 1.0);
}
