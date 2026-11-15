# Quiet Launcher: design notes, draft 1

Concept: **the quiet gallery.** The phone is calm and almost colourless. Colour arrives only through art and music, shown like works on a wall with a small museum label (title in Fraunces italic, artist and album or year in Geist). The same label is used on the Home now-playing card, the expanded player and the lock-screen art.

Preview: `index.html` (all screens), `index.html?screen=<id>` (one frame at 1:1 on a neutral ground). Extra flags: `&theme=light`, `&lockfont=bodoni|instrument|shoulders`, `&nogl` (forces the non-WebGL fallbacks), `&open` (with `screen-home-music`, opens the player after load). Round 2 (lock screens, lock typeface, two new lock styles) is section 8. Serve the folder over HTTP (for example `python -m http.server`); it also works from `file://`.

**Primary target: AMOLED.** Dark is the default theme and it is true black (`#000000`). Black pixels are switched off, which saves power and gives unlimited contrast. Colour floats on black, and every fall-off (vignettes, pooled colour, sky gradients) ends at exactly `#000`. There are no large mid-grey fills and no full-screen raised greys; raised surfaces (the bottom sheet, the search field, a selected row) are small. **Dithering and grain in the darkest tones must stay subtle.** Strong noise near black causes visible banding and "black crush" flicker on AMOLED (pixels turning on and off from frame to frame). The shaders therefore use *multiplicative* grain in dark mode, so a black pixel stays black, and they crush sub-visible values (below about 0.00025 linear) to exact zero.

---

## 1. Colour tokens

Ratios are WCAG 2.x contrast, measured with a script (sRGB relative luminance), not by eye.

### Dark (default, AMOLED)

| Token | Value | Use | Contrast |
|---|---|---|---|
| `--bg` | `#000000` | Page, phone background | n/a |
| `--raised` | `#0F0F0E` | Bottom sheet, search field, selected row (small surfaces only) | n/a |
| `--ink` | `#EDEBE6` | Text, filled button | 17.63 on bg, 16.10 on raised |
| `--muted` | `#9A968E` | Secondary text, icons | 7.13 on bg, 6.51 on raised |
| `--edge` | `#6E6B64` | Control edges (search field, chips, outline buttons, switch track, battery and progress track) | 3.95 on bg, 3.61 on raised |
| `--line` | `#1F1E1B` | Decorative hairlines only, never a control edge | 1.26 (decorative) |
| `--faint` | `#34322D` | Future-hour dots in the day strip (decorative) | 1.64 (decorative) |
| `--mat` | `#141412` | Mat of the small now-playing card | n/a |
| `--mat-big` | `#ECE8DF` | Gallery mat of large artwork | n/a |
| `--wall` | `#000000` | Lock-screen wall (lit only by the picture light) | n/a |
| `--wood` | `#2B241D` | Picture frame | n/a |
| `--on-ink` | `#000000` | Text on an ink-filled button | 17.63 |
| `--scrim` | `rgba(0,0,0,.72)` | Behind bottom sheets | n/a |
| `--edge` on the colour field | `#8E8A82` | Controls inside the player and lock music | 3.92 at the field's luminance cap, 6.11 on black |

### Light (designed separately: paper and ink)

| Token | Value | Contrast |
|---|---|---|
| `--bg` | `#F3F1EB` | n/a |
| `--raised` | `#FBFAF6` | n/a |
| `--ink` | `#141311` | 16.44 on bg, 17.78 on raised |
| `--muted` | `#5E5A52` | 6.08 on bg, 6.57 on raised |
| `--edge` | `#858076` | 3.48 on bg, 3.76 on raised |
| `--line` | `#DDD9CF` | 1.25 (decorative) |
| `--faint` | `#CFCAC0` | 1.45 (decorative) |
| `--on-ink` | `#F3F1EB` | 16.44 on ink |
| `--edge` on the colour field | `#77726A` | 3.23 at the field's luminance floor, 4.23 on paper |

### Text over shader fields (the contrast budget)

The ambient shader is **luminance-capped in the shader itself**, so text on top can never fail:

| Surface | Cap (relative luminance) | `--muted` | `--ink` | edge token |
|---|---|---|---|---|
| Player, lock music (dark) | max 0.028 | 4.57 | 10.9 | 3.92 (`#8E8A82`) |
| Home with music (dark) | max 0.014 | 5.57 | 13.8 | 3.09 (`--edge`) |
| Any field (light) | min 0.66 | 4.64 | over 4.5 | 3.23 (`#77726A`) |
| Quiet canvas label zone | flat `#000` (dark), luminance 0.79 or more (light) | 7.13 / 5.50 | | |

No accent colour exists in the chrome. Colour comes only from the artwork and, through the Palette API, from the ambient field.

## 2. Type

Two families, three weights in total: **Fraunces** (variable; weight 300, roman and italic; `opsz` 48 to 144, `SOFT` 60 to 100) and **Geist** (400 and 500). No monospace anywhere. Every time and duration uses `font-variant-numeric: tabular-nums`.

| Role | Face | px (preview) | sp (Compose) | Line height |
|---|---|---|---|---|
| Home clock | Fraunces 300, opsz 144, SOFT 70, tracking -0.035em | 118 | 118 sp (fixed: use `TextUnit` with `fontScale` capped at 1.15) | 0.86 |
| Countdown, blocked time | Fraunces 300 | 132 to 136 | 132 to 136 | 0.9 |
| Big numbers (usage) | Fraunces 300, opsz 144, SOFT 60 | 44 (Home), 64 (Screen time) | 44 / 64 | 1.0 |
| Screen titles | Fraunces 300 | 40 | 40 | 1.05 |
| Questions, intentions | Fraunces italic 300, SOFT 100 | 30 to 42 | 30 to 42 | 1.08 to 1.2 |
| Museum label title | Fraunces italic 300 | 32 (player), 21 to 24 (card, lock) | same | 1.1 to 1.15 |
| Favourite app names | Geist 400 | 26 | 26 | 1.2, 50 dp rows |
| List rows (All apps) | Geist 400 | 20 (children 18) | 20 / 18 | 50 dp / 46 dp rows |
| Body, settings rows | Geist 400 | 17 | 17 | 56 dp rows |
| Secondary | Geist 400 | 14 to 16 | 14 to 16 | 1.5 |
| Smallest (axis labels, rail) | Geist 400 or 500 | 12 | 12 | n/a |

Mapping: 1 CSS px in a 412 px frame is 1 dp on a 412 dp device, and type sizes in px map 1:1 to sp. Fraunces axes in Compose: `FontVariation.Settings(FontVariation.weight(300), FontVariation.Setting("opsz", 144f), FontVariation.Setting("SOFT", 70f))` with a downloaded or bundled variable font (`Font(resId, variationSettings = ...)`, API 26+).

## 3. Spacing, radii, layout

- Grid: 4 dp base; the rhythm used is 8, 12, 16, 24, 32, 40, 44, 56.
- Side gutter: 32 dp on Home and interstitials, 24 dp on tool surfaces and lists, 40 dp in the player.
- Hit targets: 44 dp minimum, most are 48 to 56. Switches (46 x 28 visible) carry an invisible 46 x 46 hit area. The A to Z rail is 44 dp wide.
- Radii: phone 38 (frame only); search field 14; chips and outline buttons fully rounded (24 / 28); bottom sheet 26 top corners; switches 14. Artwork, mats and frames are square: art is never rounded.
- Frames: small card mat 3 dp around a 58 dp cover (64 dp total); player mat 15 dp around 300 dp (332 total). Both are about 4.7% of the frame, so the morph scales one into the other without the mat changing proportion.
- Day strip: 18 columns (6:00 to 24:00), 3 dp bars, full height 44 dp = 30 min or more in that hour. Past hours with no use are a 7 dp hairline stub, future hours a 3 dp faint dot, the current hour is ink and labelled "now".

## 4. Motion

One signature moment (now playing). Everything else is calm. Every animation respects `prefers-reduced-motion` (Android: `Settings.Global.ANIMATOR_DURATION_SCALE == 0` or the "Remove animations" setting): springs become 200 ms crossfades, drift stops, shaders render one still frame.

| Animation | Spec | Reduced motion | Compose |
|---|---|---|---|
| **Card to player morph (open)** | Shared element, cover frame from 64 dp card to 332 dp frame. Spring dampingRatio 0.72, stiffness 380 (unit mass), settles in 450 ms, overshoot about 2.6%. Mat colour crossfades `--mat` to `--mat-big` in 260 ms ease-out. Backdrop fades in over 320 ms `cubic-bezier(.2,.7,.2,1)`. Label, progress and controls fade and rise 8 dp, 240 ms, 140 ms delay. | 200 ms opacity crossfade of the whole player | `SharedTransitionLayout` + `Modifier.sharedBounds(rememberSharedContentState("cover-$id"), animatedVisibilityScope, boundsTransform = { _, _ -> spring(0.72f, 380f) })`; content with `fadeIn(tween(240, 140)) + slideInVertically { 8.dp }` |
| **Player to card (close)** | Same element flown back at full size (lay out at hero size, play the flight in reverse). Critically damped spring, dampingRatio 1.0, stiffness 700, about 430 ms, no overshoot. Content fades out in 120 ms first. Close by: tapping outside the art and controls, the chevron, Back, or Esc. | 200 ms crossfade | same `sharedBounds`, `spring(1f, 700f)`; `BackHandler` closes |
| **Track change (art)** | New cover crossfades in over 600 ms while scaling 1.035 to 1, `cubic-bezier(.2,.7,.2,1)`. Text swaps: out 160 ms, in 300 ms. | Opacity only, 200 ms | `AnimatedContent(track, transitionSpec = { (fadeIn(tween(600)) + scaleIn(initialScale = 1.035f)) togetherWith fadeOut(tween(600)) })` |
| **Track change (colour field)** | Palette uniforms crossfade over 1200 ms, ease-in-out (quadratic). | Same crossfade (colour change is information, not decoration) | `animateFloatAsState(target, tween(1200, easing = FastOutSlowInEasing))` driving `u_mix` |
| **Ambient drift** | Domain warp time scale 0.03 per second (one visible "breath" every 30 s or so); ripples near the frame travel at 0.5 rad/s. | Frozen (one frame) | `withInfiniteAnimationFrameMillis` feeding `u_time`, only while the player is composed and the screen on |
| **Mindful pause** | 15 s linear progress arc. Ink ring breathes 4 s in, 4 s out, eased sine (`cubic-bezier(.45,0,.55,1)`). Countdown number steps each second. Time chips become active when the arc completes (300 ms). | Arc still fills (it is information); breathing stops; label "Breathe slowly" | `animateFloatAsState(1f, tween(15000, easing = LinearEasing))`; `rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = EaseInOutSine), RepeatMode.Reverse))` |
| **Hold to end early** | Fill grows left to right over 1500 ms linear while pressed; release rewinds in 200 ms ease-out. | Unchanged (it is the control's feedback) | `Modifier.pointerInput { detectTapGestures(onPress = { ... tryAwaitRelease() }) }` + `Animatable.animateTo(1f, tween(1500, easing = LinearEasing))` |
| **Bottom sheet in** | Slides up 40 dp, 320 ms `cubic-bezier(.2,.7,.2,1)`. No fade, so it is never see-through at rest. | Appears instantly | `ModalBottomSheet` with a custom `tween(320)` |
| **Theme change** | Colours crossfade 240 ms. | Instant | `animateColorAsState(tween(240))` |
| **Quiet canvas** | Bands drift at time scale 0.012 per second; `u_calm` eases toward its target (about 1 s). | One still frame | Live wallpaper frame loop; see section 5 |

Lists, Screen time and Settings have no decorative motion: ripple on rows only.

## 5. Shaders

Sources: `shaders/ambient.frag`, `shaders/quiet-canvas.frag`, `shaders/ink-breath.frag`, `shaders/quad.vert` (GLSL ES 1.0 for WebGL 1). `shaders/shaders.js` inlines them so the page works from `file://`; it is generated from the `.frag` files and should be regenerated after editing them.

Runner rules (`app.js`, `glLayer`): device pixel ratio capped at 1.5; rendering pauses when the canvas is off-screen (`IntersectionObserver`) and when the tab is hidden (`visibilitychange`); under reduced motion it renders one still frame and re-renders only when a uniform changes; without WebGL (or with `?nogl`) a static fallback stays in place.

### ambient.frag: the now-playing colour field (signature)

- **What:** the cover's four Palette swatches flow through a two-level domain warp (fbm, 5 octaves). Near the artwork frame the field refracts in slow ripples and the cover's vibrant swatch spills off the frame like light. Hue comes from the warped palette; brightness is built separately (flow body, frame spill, pooling, vignette), so it can be hard-capped.
- **Uniforms:** `u_res`, `u_time`, `u_a0..u_a3` (current palette, sRGB), `u_b0..u_b3` (next palette), `u_mix` (0..1, 1.2 s crossfade), `u_maxLum` (dark cap: 0.028 player and lock, 0.014 Home), `u_minLum` (light floor 0.66), `u_light`, `u_rect` (frame rect in px, bottom-left origin; 0 width disables), `u_glow` (spill: 1.0 player, 0.8 lock, 0.6 Home), `u_pool` (0 = full screen, 1 = colour pools around the frame and the rest is black: 0.55 player, 0.7 lock, 1.0 Home).
- **AMOLED:** vignette and pooling fall to exact black; multiplicative grain (±5%) so black stays black; values under 0.00025 linear crushed to 0.
- **Cost:** about 15 fbm octave evaluations per pixel. At 1.5x DPR on a 412 x 915 frame that is fine on a mid-range GPU; on Android render it at **half resolution** and upscale (it is soft by nature), at 30 fps.
- **Android:** API 33+: AGSL `RuntimeShader` (GLSL ES 1.0 ports almost line for line: `vec3` becomes `half3`/`float3`, `gl_FragCoord` becomes the `main(float2 fragCoord)` argument), drawn with `Modifier.drawBehind { drawRect(ShaderBrush(shader)) }` or on a `graphicsLayer` with `renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()`. Palette from `androidx.palette.graphics.Palette.from(bitmap).generate()` (darkMuted, vibrant, muted, lightVibrant). Frame rect from `onGloballyPositioned`.
- **Fallback (API below 33, no GPU budget, battery saver):** the cover bitmap scaled to 32 px, blurred (`RenderEffect.createBlurEffect(80f, 80f, Shader.TileMode.CLAMP)` on API 31–32, a pre-blurred bitmap below 31), darkened to the same luminance cap, masked with a radial fall-off to black, crossfaded on track change. The preview shows this with `?nogl`.

### quiet-canvas.frag: generative lock-screen art

- **What:** a dusk horizon on black: thin afterglow at the horizon, a half-set sun, 3 to 7 bands with ink-diffused edges and lit crests, a glitter path on the nearest water, fbm cloud streaks and faint flow contours on the water. The bottom 22% is flat ground for the label (exact `#000` in dark).
- **`u_calm` (0..1):** derived from today's screen time (`calm = clamp(1 - minutes / 240, 0, 1)` is a reasonable start). Higher calm means fewer bands (7 to 3), fewer octaves (6 to 2, fractional so it changes smoothly), smaller crest amplitude, fewer cloud streaks and fewer contours (16 to 3). The gallery buttons show 25 min, 1h 12m and 3h 40m days.
- **Uniforms:** `u_res`, `u_time`, `u_calm`, `u_light`, `u_seed` (change once a day).
- **Cost:** up to about 50 noise lookups per pixel on a busy day, about half on a calm day. The lock screen is mostly static: redraw at 10 to 15 fps, or render one frame per minute.
- **Android:** a `WallpaperService` with its own EGL context running the GLSL ES 3.0 port (`#version 300 es`, `out vec4`), `WallpaperManager.FLAG_LOCK`. Pause in `onVisibilityChanged(false)`; render a still frame when the screen is off or in battery saver. AGSL is an option for a preview inside the app.
- **Fallback:** a pre-rendered bitmap for the day (render the shader once offscreen at midnight and set it with `WallpaperManager.setBitmap(bitmap, null, true, FLAG_LOCK)`). The preview's `?nogl` path draws a simpler Canvas 2D version.

### ink-breath.frag: mindful-pause ring (optional, subtle)

- **What:** a ring of ink that swells on the in-breath and settles on the out-breath, its edge wobbling as if diffusing in water, inside a hairline arc that fills over the 15 s pause. Premultiplied alpha over the page; the centre stays clear for the number.
- **Uniforms:** `u_res`, `u_time`, `u_breath` (0..1, eased sine, 8 s cycle), `u_progress` (0..1), `u_ink` (theme ink).
- **Cost:** 4 octaves on a 240 x 240 dp area: trivial.
- **Android:** AGSL via `ShaderBrush` in a `Canvas` composable on API 33+; below that, the SVG-equivalent fallback (an `Arc` for progress and a stroked circle scaled by the breath value) which the preview also contains.

## 6. Placeholders and assets

All covers (Harbour Light, Small Hours, Paper Boats), the daily painting ("Northern shore, evening") and every artist, album and painter name are invented and drawn in code (`art.js`: SVG turbulence, displacement-mapped "paint", brush-streak alpha, canvas weave, grain). The gallery page says so; the phone UI does not. Icons are drawn inline in the Lucide stroke style (1.5 stroke, round caps). Fonts load from Google Fonts in the preview; ship them bundled in the app (OFL).

## 7. Self-audit against the audit.md tells

| Tell | Found | Fix |
|---|---|---|
| Eyebrow and tracked-caps labels | None. Group titles are sentence case ("Home", "Focus"); the player's "Playing on this phone" is a plain muted sentence, not caps. | n/a |
| Mono as costume | None. No monospace; numbers use tabular figures in Fraunces or Geist. | n/a |
| Template punctuation | Searched all files for em/en dashes, arrows and middle dots: none. The compressed usage line reads "1h 12m of 2h today, 18 pickups", not "1h 12m · 18 pickups". | n/a |
| Card inside a card | Bottom sheet rows are hairline-separated text, not filled rows. | n/a |
| Equal icon-above-heading tiles | None. Pickup stats are a plain 2 x 2 of numbers and words. | n/a |
| Side stripe | **Found:** the inline folder had a 1 px left rule beside its children. | Removed; children are indented 20 dp only. |
| Accent past 5% | No chrome accent at all. | n/a |
| More than 2 families / 3 weights | Fraunces + Geist; weights 300, 400, 500. | n/a |
| The echo | "22% less than last week" appears once (Screen time). The 1h 12m figure appears on Home, Screen time and as the quiet canvas reason, each doing different work. | Kept on purpose. |
| Radial mesh glow hero | None as decoration. Radial fall-offs exist only inside the painting (varnish vignette) and as the AMOLED fall-off mask of the no-WebGL blur. | n/a |
| Contrast | **Found:** muted text at the first field cap (0.03) measured 4.45:1; edges on the field measured 2.47:1. | Cap lowered to 0.028 (4.57:1); on-field edge tokens added (3.92:1 dark, 3.23:1 light). |
| 44 px targets | **Found:** switches were 46 x 28. | Invisible 46 x 46 hit area; rail widened to 44. A probe script found no other control under 44 x 44. |
| Readable at rest | **Found:** the sheet's entry fade could leave it see-through if animations were throttled; art crossfades waited on `requestAnimationFrame`. | Sheet slides only; class swaps use timers; morph has a safety reset. |
| Sideways scroll at 320 px | **Found:** scaled phones kept their layout width. | Frames are absolutely positioned and scaled; page width is 320 at 320. |
| Reduced motion | Springs become 200 ms crossfades, drift and caret blink stop, shaders render a still frame. Progress arcs and the hold fill still move, because they carry information. | n/a |

Not yet done: axe and Hallmark sloplint runs on the hosted page, and a check on a real AMOLED phone (especially grain visibility and black crush near the vignette edge).

---

## 8. Round 2: our own lock screen

### 8.1 How it works on Android

The lock screens are now **our own Activity drawn over the keyguard**, not a wallpaper:

- `setShowWhenLocked(true)` and `setTurnScreenOn(true)` on the Activity. It is launched on `ACTION_SCREEN_ON`; that broadcast cannot be declared in the manifest, so the receiver is registered by the existing foreground service.
- Background-activity-start limits apply on Android 10+. The exemption path is the `SYSTEM_ALERT_WINDOW` permission the floating reminder already needs. Verify on the target phone.
- Swipe up calls `KeyguardManager.requestDismissKeyguard(activity, callback)`, and the system shows its own PIN, pattern or biometric prompt. We never see or handle the credential.
- Because we draw the whole screen, **we draw the clock too**, in the lock typeface (8.2). Every lock frame now shows our clock.

**Static-wallpaper fallback.** When the custom lock screen is off (or the user's OEM blocks it), each lock style falls back to a plain lock wallpaper (`WallpaperManager.setBitmap(bitmap, null, true, FLAG_LOCK)`, or the live wallpaper for Quiet canvas). That wallpaper draws **no clock** and keeps the **top 30% clear** for the system clock. The museum label may stay at the bottom. The interactive player and the typeset poster have no wallpaper form; they fall back to Daily masterpiece.

### 8.2 Lock typeface

Home keeps Fraunces + Geist. Lock screens use **one display face + Geist**: two families per screen at most, and Fraunces never appears on a lock screen. The face drives the clock, the label titles and the poster. Geist carries dates, artist lines and controls. A switcher on the gallery page applies the face to all six lock frames (`data-lockfont` on `<html>`; tokens `--lk-font`, `--lk-weight`, `--lk-ital`, `--lk-clock`, `--lk-poster`).

| Candidate | Clock / poster size | Weight | Verdict |
|---|---|---|---|
| **Bodoni Moda** (OFL, variable `opsz` 6–96, true italic) | 96 / 252 px | 400 | **Recommended, default** |
| Instrument Serif (OFL, one weight + italic) | 108 / 280 px | 400 | Close second |
| Big Shoulders Display (OFL) | 120 / 290 px | 300 | The poster alternative |

**Why Bodoni Moda:**

- **It contrasts with Fraunces in the right way.** Fraunces is soft, warm and low in contrast (`SOFT` 70–100). Bodoni is a Didone: vertical stress, hairline serifs, very high stroke contrast. The lock screen feels like a different room of the same gallery. Exhibition posters and museum wall signage use Didones for exactly this cold, precise voice.
- **It suits AMOLED.** On true black, the heavy stems glow and the hairlines become fine threads of light. At 96 px and above (clock) or 252 px (poster) the hairlines are 2 px or more on a 420 ppi screen, so they never shimmer.
- **It has a real optical-size axis.** The clock uses `opsz` 96 (thinnest hairlines). Label titles at 21–24 px can use a lower `opsz` in Compose (`FontVariation.Setting("opsz", 24f)`) so their hairlines stay sturdy.
- **It has a true italic** for the museum-label titles, so the label language from Home carries over unchanged.

**Why not the others:**

- **Instrument Serif** is beautiful and narrower, but it has a single weight with no optical sizes. It has also become a signature of 2024–25 web templates (the Portfolio project used it), which risks reading as fashion rather than gallery.
- **Big Shoulders Display** makes the strongest poster and reads best at a glance. It has no italic, so labels go upright, and its condensed industrial voice pulls away from "the quiet gallery". Keep it as a user option for people who want maximum clock legibility.

**Bodoni Moda size floors:** never below 21 px (hairlines), never on controls or body text. Bodoni Moda figures are proportional; on device, enable `fontFeatureSettings = "tnum"` if the cut supports it. Otherwise set each clock digit in a fixed-width box so 16:41 to 16:42 does not shift sideways.

### 8.3 New screens and states

| Frame | What it is |
|---|---|
| `screen-lock-player` | **Lock-screen player** (interactive). True black, our clock (Bodoni 96), a 300 dp framed cover, gallery label (Bodoni italic title, Geist artist and album), hairline progress with elapsed and remaining, prev / play-pause / next (56 / 68 / 56 dp), and "Swipe up to unlock". The ambient field is more generous than Home: `pool` 0.32 (spreads wide), `glow` 1.3 (stronger spill). The luminance cap stays 0.028, because text sits on it. |
| `screen-lock-player`, **paused** | The art steps back and the clock takes over: the clock scales 1.55x and drops 34 dp; the frame scales to 0.56 and dims (`brightness .5, saturate .55`); the label slides to stay flush with the smaller frame; progress fades out; controls rise 38 dp. The field's cap eases from 0.028 to 0.011, so the room darkens too. Toggle with play/pause (or the gallery's "Pause or play"). |
| `screen-lock-music` | The earlier small-frame variant, now with our clock and the unlock affordance. |
| `screen-lock-art`, `screen-lock-canvas` | Unchanged art; now with our clock in the lock face, labels in the lock face, and the unlock affordance. |
| `screen-lock-sky` | **Tonight's sky.** The real moon phase for the date, over today's sun arc. For 14 Nov 2026 the screen reads "Waxing crescent, 24% lit. Full on 24 November. The sun set at 16:13; twilight ends at 16:50." The arc runs from sunrise to sunset over a hairline horizon, and a hollow dot below it marks the sun now (after sunset). Location is a placeholder (London). It carries real information and has nothing to refresh or chase. |
| `screen-lock-type` | **Typeset.** The type is the art: the hour solid, the minutes in 1.2 px outline, both at poster size; the date in Geist; "One thing today" with the user's own line (the focus intention); a museum label giving the time in words ("Sixteen forty-two") and the face it is set in. |

**Unlock affordance:** a 32 x 2 dp hairline above the words "Swipe up to unlock" (muted Geist 13). It is a 56 dp tall button, so tapping it does the same as swiping. There is no arrow glued to the text. The hairline nudges up 5 dp twice when the screen wakes, then stays still.

**Label contrast on the new screens:** the sky shader forces everything below the horizon to exact black, so the label and the sun arc sit on `#000` (muted text 7.13:1). The poster is plain black. The lock player uses the same capped field as the player (muted 4.57:1 at the cap, controls on `#8E8A82` at 3.92:1).

### 8.4 Motion (new)

| Animation | Spec | Reduced motion | Compose |
|---|---|---|---|
| Play to paused (lock player) | All moving parts animate `transform` over 620 ms with `cubic-bezier(.34,1.22,.64,1)` (a slight overshoot, about spring dampingRatio 0.8, stiffness 300). Art dimming: `filter` 600 ms. Progress fade 400 ms. Field cap eases at 5% per frame (about 1 s). | Instant layout change; the cap jumps | One `updateTransition(paused)` driving `animateFloat` for clock scale, frame scale and offset, with `spring(0.8f, 300f)`; `ColorMatrix` for the dim; `animateFloatAsState` for `u_maxLum` |
| Swipe up to unlock | Content follows the finger at 0.5x, opacity `1 + dy/420` (min 0.2). Past 110 dp: content lifts 90 dp and fades (320 ms, `cubic-bezier(.2,.7,.2,1)`), then `requestDismissKeyguard`. Short of it: settles back in 320 ms. | Same thresholds, no follow animation | `Modifier.draggable(Orientation.Vertical)` + `Animatable` offset; on threshold `animateTo` then dismiss |
| Unlock hairline nudge | 5 dp up and back, 1.6 s, `cubic-bezier(.45,0,.55,1)`, twice, starting 0.9 s after wake | None | `LaunchedEffect(screenOn) { repeat(2) { animate... } }` |
| Star twinkle (sky) | Each star's brightness varies by ±18% at 0.25–1.25 rad/s, phase-shifted per star | Frozen | Shader `u_time` |

### 8.5 New shader: `night-sky.frag`

- **What:** a sparse star field (three jittered-grid layers; most stars faint, a few bright), a barely-there galactic band, the last twilight at the horizon (amber low, blue hour above, scaled by `u_dusk`), and the moon lit as a sphere. The moon has a terminator from the real phase, fbm maria, limb darkening and faint earthshine on the dark side. Below the horizon is exact black. There is no grain: on AMOLED the stars are the only texture. The light theme draws the same sky as ink on paper (ink-dot stars; the moon as bare paper with an ink-wash dark side and a fine engraved outline).
- **Uniforms:** `u_res`; `u_time` (twinkle only); `u_phase` (0 new, 0.5 full; mean synodic month 29.530589 d from the 2000-01-06 18:14 UTC new moon, accurate to about half a day, enough for a phase name and percentage); `u_moon` (centre px and radius px, measured from the layout slot); `u_horizon` (screen fraction, measured from the sun-arc horizon line); `u_dusk` (`clamp(1 + sunAltitude / 12, 0, 1)`); `u_light`; `u_seed`.
- **Cost:** three star lookups per pixel plus 8 noise octaves only inside the moon disc, so it is cheap. Twinkle needs at most 10 fps; at rest the custom lock screen can render one frame a minute, because the sky barely changes.
- **Android:** AGSL `RuntimeShader` in the lock Activity on API 33+ (`ShaderBrush` in a full-screen `Canvas`), or GLSL ES 3.0 in a `GLSurfaceView`. Phase, sunrise, sunset and civil twilight (-6 deg) are computed on device. The preview uses an approximate solar-position formula and a mean synodic month; on device a small astronomy library (a SunCalc port, or Meeus' algorithms) is fine. Location comes from coarse location or a city typed in Settings; nothing goes to the network.
- **Fallback (below API 33, battery saver, reduced motion):** a still bitmap rendered once per evening. The preview's `?nogl` path shows the simple Canvas form: random stars, the moon as a dark disc with a lit half and an ellipse for the terminator.
- **Static-wallpaper form:** a still render with no clock, top 30% clear, and the label at the bottom.

### 8.6 Typeset poster on Android

No shader. In Compose the hour is `Text` at 252 sp with `fontFeatureSettings = "lnum, tnum"`. The outlined minutes use `TextStyle(drawStyle = Stroke(width = 1.2.dp.toPx()))` (Compose 1.5+). The "time in words" label comes from a small formatter (the preview's `words()` in `app.js`). Recompose once a minute on `ACTION_TIME_TICK`.

### 8.7 Self-audit, round 2

| Check | Result |
|---|---|
| Families per lock screen | Probe: every lock frame renders exactly two (lock face + Geist). No Fraunces on lock screens. |
| Weights | Bodoni 400 or Instrument 400 or Big Shoulders 300, plus Geist 400 and 500: three per screen at most. |
| 44 px targets | Probe across all 18 frames: none under 44 x 44 (the unlock affordance is 180 x 56). |
| Template punctuation | "Swipe up to unlock" has no glued arrow; no dashes, arrows or middle dots added. |
| Contrast | Labels on the sky and poster sit on exact black (7.13:1 muted); the lock player reuses the capped field (4.57:1 muted, 3.92:1 control edges). |
| Readable at rest | The paused state, poster and sky are fully drawn without animation. The unlock nudge runs twice and stops. |
| Sideways scroll at 320 px | None (page width 320 at 320). |
| Bug found and fixed | A gallery "Pause or play" text button had no icon; `setPlaying` threw on it and the paused class never applied. It now skips buttons without an icon. |
