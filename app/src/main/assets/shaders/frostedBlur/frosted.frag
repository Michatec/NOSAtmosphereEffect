#version 300 es
precision highp float;
// hashU needs 32-bit ints; ES 3.00 defaults to mediump (only 16 bits guaranteed).
precision highp int;

in vec2 vTexCoord;
// Screen-locked, unaffected by the wallpaper's scroll window — the clock
// overlay is positioned against the physical screen.
in vec2 vEffectCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform sampler2D uTextureBlur;

uniform float uAspectRatio;

uniform float uBlurStrength;
uniform float uDimLevel;
uniform float uEnableNoise;
uniform float uNoiseScale;
uniform float uNoiseStrength;

// App-drawer / recents blur, driven by wallpaper visibility. 0 = in view, 1 = hidden.
uniform float uDrawerBlur;

// Bit-mixing hash, replacing fract(sin(dot(...))) -- that idiom collapses to a repeating pattern
// at the coordinate magnitudes this grain grid produces (see #85).
// uint overflow is defined wrapping in GLSL ES.
uint hashU(uvec2 p) {
    uint h = p.x * 73856093u ^ p.y * 19349663u;
    h ^= h >> 13;
    h *= 0x85ebca6bu;
    h ^= h >> 16;
    return h;
}

float random(vec2 co) {
    return float(hashU(uvec2(co)) & 0xFFFFFFu) / float(0x1000000u);
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
    // ## What the texture holds
    //
    // Not coverage — a distance field. 0.5 sits exactly on the glyph's edge,
    // 1.0 in the middle of a stroke, 0.0 a spread outside it. Everything below
    // is geometry read straight off that: it is why the whole stroke behaves
    // like a piece of glass instead of a pane with a bevelled rim, and why the
    // edge stays sharp however far the texture is magnified. See
    // ClockGlyphAtlas for how the field is built.
    float field = clockSample.a;
    // The silhouette is reconstructed rather than sampled: one pixel's worth
    // of field either side of the crossing, whatever resolution the face was
    // rasterised at. Nothing here can show its texels.
    float softness = clamp(fwidth(field), 0.0008, 0.25);
    float coverage = smoothstep(0.5 - softness, 0.5 + softness, field);
    if (coverage <= 0.002) return color;

    vec2 texel = 1.0 / vec2(textureSize(uClockTexture, 0));
    vec2 gradient = vec2(
        texture(uClockTexture, clamp(clockUv + vec2(texel.x, 0.0), 0.0, 1.0)).a -
            texture(uClockTexture, clamp(clockUv - vec2(texel.x, 0.0), 0.0, 1.0)).a,
        texture(uClockTexture, clamp(clockUv + vec2(0.0, texel.y), 0.0, 1.0)).a -
            texture(uClockTexture, clamp(clockUv - vec2(0.0, texel.y), 0.0, 1.0)).a
    );
    // The gradient of a distance field is a unit vector pointing into the
    // shape, at every point and at every glyph size. That is the whole reason
    // for the field: a coverage mask gives this only within a texel of the
    // edge, and gives it wrong everywhere else.
    vec2 inward = gradient / max(length(gradient), 1e-5);

    // The stroke's cross-section: a half-round bar, standing vertically at the
    // silhouette and flattening out along its crown.
    float depth = clamp((field - 0.5) * 2.0, 0.0, 1.0);
    float shoulder = 1.0 - depth;
    float lift = sqrt(max(1.0 - shoulder * shoulder, 1e-4));
    float slope = min(shoulder / lift, 6.0);
    vec3 normal = normalize(vec3(-inward * slope * 0.62, 1.0));

    // Two things bend the wallpaper. The surface turns hardest at the
    // silhouette and eases off across the crown; and the glass has thickness,
    // so what is behind it is magnified about the clock's own centre.
    // Together they are what makes the whole digit read as a lens rather than
    // a pane with a bevelled rim.
    //
    // The bend follows the shoulder rather than the surface slope, which runs
    // away to a right angle at the silhouette: scaled by that, the edge of a
    // stroke would sample from further away than the stroke is wide, and
    // smear rather than refract.
    vec2 bend = inward * (shoulder * 0.030 * rectSize.y);
    vec2 magnify = (clockUv - 0.5) * rectSize * (0.060 * depth);
    vec2 sampleUv = clamp(vTexCoord - bend - magnify, 0.0, 1.0);

    // Frost scatters what comes through. Nothing is sampled for it until
    // there is some: the level is a uniform, so this branch is taken by the
    // whole draw or none of it, and a clear clock costs eight fewer fetches
    // per pixel than a frosted one.
    vec3 refracted = texture(uTextureSharp, sampleUv).rgb;
    if (frostLevel > 0.004) {
        float blur = mix(0.0012, 0.0110, frostLevel);
        float diagonal = blur * 0.7;
        refracted = (
            2.0 * refracted +
            texture(uTextureSharp, clamp(sampleUv + vec2(blur, 0.0), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(sampleUv - vec2(blur, 0.0), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(sampleUv + vec2(0.0, blur), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(sampleUv - vec2(0.0, blur), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(sampleUv + vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(sampleUv - vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(sampleUv + vec2(diagonal, -diagonal), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(sampleUv - vec2(diagonal, -diagonal), 0.0, 1.0)).rgb
        ) / 10.0;
    }
    // Whatever the effect did to the wallpaper behind the clock applies to
    // what shows through it too.
    refracted = clamp(refracted + (color - texture(uTextureSharp, vTexCoord).rgb), 0.0, 1.0);

    vec3 tint = clockSample.rgb / max(field, 0.001);
    // Coloured glass: the chosen colour tints what comes through.
    vec3 glass = mix(refracted, refracted * tint, 0.55);
    if (frostLevel > 0.004) {
        // Etched glass takes the colour it was given and lets what is behind
        // it through only as brightness. Mixing towards the wallpaper's own
        // luminance instead — which is what this did — left a white clock
        // reading as whatever tint the photo happened to have.
        float milk = dot(glass, vec3(0.2126, 0.7152, 0.0722));
        glass = mix(glass, tint * (0.55 + 0.45 * milk), frostLevel * 0.92);
    }

    // A key light from the upper left (texture y grows downwards) and a dim
    // fill from the lower right: one source alone leaves the far side of every
    // stroke dead, where real glass picks up the whole room.
    vec3 key = normalize(vec3(-0.45, -0.75, 0.48));
    vec3 fill = normalize(vec3(0.55, 0.62, 0.55));
    float facing = dot(normal, key);
    // Concentrated into the turn of the edge rather than spread across the
    // shoulder: over the whole shoulder it reads as a white band frosting the
    // inside of every stroke, which is the opposite of one piece of glass.
    float turn = shoulder * shoulder * shoulder;
    float sheen = max(facing, 0.0) * turn;
    float glint = pow(max(facing, 0.0), 22.0) * shoulder;
    float bounce = max(dot(normal, fill), 0.0) * turn;
    float shade = max(-facing, 0.0) * turn;
    // The boundary itself: a hairline just inside the silhouette, which is the
    // edge of the glass rather than an outline drawn around it.
    float boundary = smoothstep(0.80, 1.0, shoulder);
    // A frosted surface scatters its highlights away with everything else, so
    // they fade out as the frost comes up and the digit stays one even tone.
    float polish = 1.0 - 0.75 * frostLevel;

    glass += vec3((sheen * 0.22 + glint * 0.5 + bounce * 0.12 + boundary * 0.16) * polish);
    glass -= vec3(shade * 0.24 * polish);
    return mix(color, clamp(glass, 0.0, 1.0), coverage * opacity);
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
    // A flat face: no glass, but the silhouette still comes from the field
    // rather than from an alpha the texture no longer carries.
    float flatSoftness = clamp(fwidth(clockSample.a), 0.0008, 0.25);
    float flatCoverage =
        smoothstep(0.5 - flatSoftness, 0.5 + flatSoftness, clockSample.a);
    vec3 flatColor = clockSample.rgb / max(clockSample.a, 0.001);
    return mix(color, flatColor, flatCoverage * uClockOpacity);
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
    float t = clamp(uBlurStrength, 0.0, 1.0);

    vec3 sharp = textureLod(uTextureSharp, vTexCoord, t * 4.0).rgb;

    vec3 frosted = texture(uTextureBlur, vTexCoord).rgb;

    vec3 finalColor = mix(sharp, frosted, t);

    finalColor = mix(finalColor, vec3(0.0), uDimLevel * t);

    if (uEnableNoise > 0.5) {
        vec2 noiseUV = vTexCoord;
        noiseUV.x *= uAspectRatio;
        vec2 grainUV = floor(noiseUV * uNoiseScale);
        float noise = random(grainUV);
        float noiseVisibility = smoothstep(0.4, 1.0, t);
        finalColor += vec3(noise * uNoiseStrength * noiseVisibility);
    }

    // App-drawer / recents: reverse Frosted sets this to 1 when out of view, blending
    // toward the frosted image so the drawer shows a blur. In view -> 0 -> sharp.
    finalColor = mix(finalColor, frosted, clamp(uDrawerBlur, 0.0, 1.0));

    vec3 beforeClock = finalColor;
    finalColor = compositeClock(finalColor, vEffectCoord);
    finalColor = applyClockDepth(finalColor, beforeClock, vTexCoord);

    fragColor = vec4(finalColor, 1.0);
}