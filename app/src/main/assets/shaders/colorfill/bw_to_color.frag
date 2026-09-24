#version 300 es
precision highp float;
in vec2 vTexCoord;
// Screen-locked, unaffected by the wallpaper's scroll window — the clock
// overlay is positioned against the physical screen.
in vec2 vEffectCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform float uBlurStrength; // 1.0 (Locked/B&W) -> 0.0 (Unlocked/Color)
uniform vec2 uOrigin;        // Fingerprint location (X, Y)
uniform float uAspectRatio;
uniform float uDimLevel;

// Fractal noise shapes the paint front, detached droplets, and wet rim.

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 345.45));
    p += dot(p, p + 34.345);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 5; i++) {
        v += amp * vnoise(p);
        p = p * 2.03 + 7.1;
        amp *= 0.5;
    }
    return v;
}

// Splatter droplets: direction (roughly unit), distance fraction, size fraction.
const vec2  DROP_DIR[5]  = vec2[5](
    vec2( 0.80,  0.60), vec2(-0.55,  0.84), vec2( 0.28, -0.96),
    vec2(-0.90, -0.30), vec2( 0.97,  0.05)
);
const float DROP_F[5]    = float[5](0.52, 0.66, 0.60, 0.74, 0.83);
const float DROP_SIZE[5] = float[5](0.11, 0.08, 0.13, 0.07, 0.06);

float paintCoverage(vec2 uv, vec2 origin, float aspect, float progress, out float rim) {
    rim = 0.0;
    if (progress <= 0.002) return 0.0;
    if (progress >= 0.998) return 1.0;

    // Farthest screen corner from the origin, so the pool fills the screen right
    // as the animation ends, wherever the fingerprint sits.
    float reach = 0.0;
    reach = max(reach, distance(origin, vec2(0.0,    0.0)));
    reach = max(reach, distance(origin, vec2(aspect, 0.0)));
    reach = max(reach, distance(origin, vec2(0.0,    1.0)));
    reach = max(reach, distance(origin, vec2(aspect, 1.0)));

    vec2 d = uv - origin;
    float dist = length(d);
    float ang = atan(d.y, d.x);
    vec2 circ = vec2(cos(ang), sin(ang));

    float R = progress * reach * 1.42;

    // Big lobes: radius wobbles with direction (seamless around the circle) and
    // churns slowly as the pool grows.
    float lobe = fbm(circ * 2.1 + vec2(9.0, progress * 1.2));
    // Fine viscous fingering on the rim.
    float fingers = fbm(uv * 7.5 + circ * 1.7);

    float front = R * (0.74 + 0.34 * lobe) + (fingers - 0.5) * 0.13 * reach;

    float aa = mix(0.06, 0.012, progress) * (reach + 0.25);
    float cover = 1.0 - smoothstep(front - aa, front + aa, dist);

    // Droplets emerge just ahead of the front, then the pool absorbs them.
    for (int i = 0; i < 5; i++) {
        float f = DROP_F[i];
        vec2 c = origin + DROP_DIR[i] * (f * reach);
        float appear = smoothstep(f - 0.20, f - 0.02, progress);
        float r = DROP_SIZE[i] * reach * appear;
        if (r > 0.0001) {
            float dd = length(uv - c);
            float dn = (fbm(uv * 11.0 + float(i) * 3.7) - 0.5) * 0.25;
            cover = max(cover, 1.0 - smoothstep(r * (0.6 + dn), r, dd));
        }
    }

    // Wet sheen: thin bright band hugging the advancing edge, fading as it settles.
    rim = (1.0 - smoothstep(0.0, aa * 3.5, abs(dist - front))) * cover * (1.0 - progress);

    return clamp(cover, 0.0, 1.0);
}

// ---------------------------------------------------------------- clock
// Wallpaper clock overlay. uClockEnabled is 1.0 only once a real face has
// been uploaded — it is NOT the user's toggle, because the texture starts
// out as unwritten storage and sampling that would paint a rectangle of
// garbage where the clock belongs. uClockRect is x, y, width, height in the
// screen-locked vEffectCoord space, so the clock stays put while the photo
// pans. uClockOpacity already has the lock/home fade folded in by the
// renderer, so both backends share one curve.
uniform sampler2D uClockTexture;
uniform float uClockEnabled;
uniform vec4 uClockRect;
uniform float uClockOpacity;
uniform float uClockDepth;
// 1 when the face is drawn as refracting glass (ClockStyle.liquidGlass).
uniform float uClockGlass;

// ------------------------------------------------------- liquid glass clock
// Drawn instead of the flat face when the style asks for glass. The face
// texture only supplies the glyph SHAPE (its alpha); everything visible is the
// wallpaper, bent at the rounded edges like a thick lens, softened inside,
// and lit along the edges facing the light. Normals come from the alpha
// gradient over a few texels, so the bevel costs no extra texture and no CPU
// work per frame.
//
// uTextureSharp is the sharp photo. What the effect had already drawn here ([color])
// is folded back in as a correction, so the glass keeps the effect's grade
// (dim, monochrome, ...) instead of punching through to the raw photo.
vec3 clockGlass(
    vec3 color,
    vec2 clockUv,
    vec4 clockSample,
    vec2 rectSize,
    float opacity,
    float frostLevel
) {
    float body = clockSample.a;
    if (body <= 0.003) return color;
    vec2 texel = 1.0 / vec2(textureSize(uClockTexture, 0));
    // Width of the rolled edge, in face texels. The face is rasterised in
    // proportion to the size it is displayed at, so this stays the same
    // fraction of a stroke whatever size the clock is set to.
    const float EDGE = 14.0;

    // ## Why the edge is measured twice, and neither is a plain derivative
    //
    // The glyph's alpha is a hard edge, so a central difference of it is a
    // *plateau*, not a ramp: it reads the same at one texel in as at ten. The
    // first version of this lit the bevel with that number and the result was
    // a flat band of constant brightness — a stroke drawn around the glyph
    // rather than a surface with any thickness to it.
    //
    // So: the difference is used only for the DIRECTION of the edge, summed
    // over three radii so it survives both broad stems and tight curves...
    vec2 gradient = (
        vec2(
            texture(uClockTexture, clamp(clockUv + vec2(texel.x * 3.0, 0.0), 0.0, 1.0)).a -
                texture(uClockTexture, clamp(clockUv - vec2(texel.x * 3.0, 0.0), 0.0, 1.0)).a,
            texture(uClockTexture, clamp(clockUv + vec2(0.0, texel.y * 3.0), 0.0, 1.0)).a -
                texture(uClockTexture, clamp(clockUv - vec2(0.0, texel.y * 3.0), 0.0, 1.0)).a
        ) +
        vec2(
            texture(uClockTexture, clamp(clockUv + vec2(texel.x * 7.0, 0.0), 0.0, 1.0)).a -
                texture(uClockTexture, clamp(clockUv - vec2(texel.x * 7.0, 0.0), 0.0, 1.0)).a,
            texture(uClockTexture, clamp(clockUv + vec2(0.0, texel.y * 7.0), 0.0, 1.0)).a -
                texture(uClockTexture, clamp(clockUv - vec2(0.0, texel.y * 7.0), 0.0, 1.0)).a
        ) +
        vec2(
            texture(uClockTexture, clamp(clockUv + vec2(texel.x * EDGE, 0.0), 0.0, 1.0)).a -
                texture(uClockTexture, clamp(clockUv - vec2(texel.x * EDGE, 0.0), 0.0, 1.0)).a,
            texture(uClockTexture, clamp(clockUv + vec2(0.0, texel.y * EDGE), 0.0, 1.0)).a -
                texture(uClockTexture, clamp(clockUv - vec2(0.0, texel.y * EDGE), 0.0, 1.0)).a
        )
    ) / 3.0;
    float gradientLength = length(gradient);
    // Alpha grows inwards, so this points into the glyph.
    vec2 inward = gradient / max(gradientLength, 1e-4);

    // ...and how far inside the edge we are comes from averaging the alpha
    // along that direction, which IS a ramp: half the samples are outside at
    // the silhouette and none of them are once we are a full EDGE in.
    vec2 stride = inward * texel * (EDGE * 0.25);
    float filled =
        texture(uClockTexture, clamp(clockUv - stride * 4.0, 0.0, 1.0)).a +
        texture(uClockTexture, clamp(clockUv - stride * 3.0, 0.0, 1.0)).a +
        texture(uClockTexture, clamp(clockUv - stride * 2.0, 0.0, 1.0)).a +
        texture(uClockTexture, clamp(clockUv - stride, 0.0, 1.0)).a +
        body +
        texture(uClockTexture, clamp(clockUv + stride, 0.0, 1.0)).a +
        texture(uClockTexture, clamp(clockUv + stride * 2.0, 0.0, 1.0)).a +
        texture(uClockTexture, clamp(clockUv + stride * 3.0, 0.0, 1.0)).a +
        texture(uClockTexture, clamp(clockUv + stride * 4.0, 0.0, 1.0)).a;
    float depth = clamp((filled / 9.0 - 0.5) * 2.0, 0.0, 1.0);
    // 1 at the silhouette, 0 where the edge has finished rolling over. Faded
    // out where the direction is unreliable — along the ridge of a stroke
    // narrower than the bevel, which is the top of the glass anyway.
    float rim = (1.0 - depth) * smoothstep(0.02, 0.18, gradientLength);

    // The profile of a quarter-round edge: vertical at the silhouette and
    // flat by the time it reaches the top. This is what a chamfer — which is
    // what a linear ramp would give — does not look like.
    float lift = sqrt(max(1.0 - rim * rim, 1e-4));
    float tilt = min(rim / lift, 5.0);
    vec3 normal = normalize(vec3(-inward * tilt * 0.55, 1.0));

    // Refraction follows the same profile, so the image bends hardest right
    // at the edge. Scaled by the clock's own size to look the same at any.
    vec2 sampleUv = clamp(vTexCoord - inward * (tilt * 0.012 * rectSize.y), 0.0, 1.0);
    float frost = mix(0.0016, 0.0130, frostLevel);
    vec3 refracted = (
        2.0 * texture(uTextureSharp, sampleUv).rgb +
        texture(uTextureSharp, clamp(sampleUv + vec2(frost, 0.0), 0.0, 1.0)).rgb +
        texture(uTextureSharp, clamp(sampleUv - vec2(frost, 0.0), 0.0, 1.0)).rgb +
        texture(uTextureSharp, clamp(sampleUv + vec2(0.0, frost), 0.0, 1.0)).rgb +
        texture(uTextureSharp, clamp(sampleUv - vec2(0.0, frost), 0.0, 1.0)).rgb
    ) / 6.0;
    // Whatever the effect did to the wallpaper behind the clock applies to
    // what shows through it too.
    refracted = clamp(refracted + (color - texture(uTextureSharp, vTexCoord).rgb), 0.0, 1.0);
    // Frosted glass scatters: the more frost, the milkier and the flatter.
    float milk = dot(refracted, vec3(0.2126, 0.7152, 0.0722));
    refracted = clamp(
        mix(refracted, mix(refracted, vec3(milk), 0.40) + vec3(0.05), frostLevel),
        0.0,
        1.0
    );

    // Light from the upper left (texture y grows downwards).
    vec3 light = normalize(vec3(-0.5, -0.72, 0.48));
    float facing = dot(normal, light);
    // A broad sheen down the lit side of the roll, a tighter glint where the
    // surface turns through the light, a bright line right on the silhouette
    // that reads as the glass's own boundary, and shade on the far side.
    float sheen = max(facing, 0.0) * rim;
    float glint = pow(max(facing, 0.0), 12.0) * rim;
    float edgeLine = smoothstep(0.80, 1.0, rim);
    float shade = max(-facing, 0.0) * rim;

    vec3 tint = clockSample.rgb / max(clockSample.a, 0.001);
    vec3 glass = refracted + vec3(0.02);
    // Coloured glass: the chosen colour tints what shows through, while the
    // highlights stay white the way real glass reflects.
    glass = mix(glass, glass * tint, 0.55);
    glass += vec3(sheen * 0.40 + glint * 0.55 + edgeLine * 0.16);
    glass -= vec3(shade * 0.30);
    return mix(color, clamp(glass, 0.0, 1.0), body * opacity);
}

vec3 compositeClock(vec3 color, vec2 screenCoord) {
    if (uClockEnabled <= 0.5 || uClockOpacity <= 0.0) return color;
    vec2 clockUv = (screenCoord - uClockRect.xy) / max(uClockRect.zw, vec2(1e-5));
    if (clockUv.x < 0.0 || clockUv.x > 1.0 ||
        clockUv.y < 0.0 || clockUv.y > 1.0) {
        return color;
    }
    vec4 clockSample = texture(uClockTexture, clockUv);
    if (uClockGlass > 0.5) {
        // Above 1 is glass, and the fraction is the frost level — see
        // ClockOverlayState.glassMeta.
        return clockGlass(
            color,
            clockUv,
            clockSample,
            uClockRect.zw,
            uClockOpacity,
            max(uClockGlass - 1.0, 0.0)
        );
    }
    return mix(color, clockSample.rgb, clockSample.a * uClockOpacity);
}

// Subject mask for the clock's depth effect. These effects have no
// "background only" mode of their own, so this binding exists purely for the
// clock; uClockDepth is 0 whenever no real mask is bound, and the sampler is
// then never read.
uniform sampler2D uClockSubjectMask;

float clockSubjectCoverage(vec2 uv) {
    vec2 stepSize = 2.0 / vec2(textureSize(uClockSubjectMask, 0));
    float mask = texture(uClockSubjectMask, uv).r;
    mask = max(mask, texture(uClockSubjectMask, clamp(uv + vec2(stepSize.x, 0.0), 0.0, 1.0)).r);
    mask = max(mask, texture(uClockSubjectMask, clamp(uv - vec2(stepSize.x, 0.0), 0.0, 1.0)).r);
    mask = max(mask, texture(uClockSubjectMask, clamp(uv + vec2(0.0, stepSize.y), 0.0, 1.0)).r);
    mask = max(mask, texture(uClockSubjectMask, clamp(uv - vec2(0.0, stepSize.y), 0.0, 1.0)).r);
    return smoothstep(0.30, 0.72, mask);
}

// Draws the subject back over the clock, so the clock reads as sitting behind
// them.
//
// [subjectColor] is the frame as it looked BEFORE the clock was composited,
// not the untouched photo. Atmosphere, Glass and Halftone use the sharp photo
// because their backgrounds are blurred or stylised, so a sharp subject reads
// as depth. Here it would read as a cut-out instead: a full-colour subject
// over Colour Fill's monochrome end, or a photographic subject over Sketch's
// line art. Re-drawing what the effect had already produced keeps the subject
// looking exactly like the rest of the frame, which is what actually sells
// the occlusion.
vec3 applyClockDepth(vec3 color, vec3 subjectColor, vec2 maskUv) {
    if (uClockEnabled <= 0.5 || uClockDepth <= 0.5 || uClockOpacity <= 0.0) {
        return color;
    }
    return mix(
        color,
        subjectColor,
        clockSubjectCoverage(maskUv) * uClockOpacity
    );
}

void main() {
    vec4 color = texture(uTextureSharp, vTexCoord);
    float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    vec3 bwColor = vec3(gray);

    vec2 uv = vTexCoord;
    uv.x *= uAspectRatio;
    vec2 origin = uOrigin;
    origin.x *= uAspectRatio;

    float progress = 1.0 - uBlurStrength;

    float rim;
    float cover = paintCoverage(uv, origin, uAspectRatio, progress, rim);

    vec3 finalColor = mix(bwColor, color.rgb, cover);
    finalColor += rim * 0.10;

    finalColor *= mix(1.0, 1.0 - uDimLevel, uBlurStrength);

    vec3 beforeClock = finalColor;
    finalColor = compositeClock(finalColor, vEffectCoord);
    finalColor = applyClockDepth(finalColor, beforeClock, vTexCoord);

    fragColor = vec4(finalColor, color.a);
}
