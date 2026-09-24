#version 450

layout(set = 0, binding = 0) uniform sampler2D wallpaperTexture;
layout(set = 0, binding = 1) uniform sampler2D subjectMask;
layout(set = 0, binding = 2) uniform sampler2D clockTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec2 vEffectCoord;
layout(location = 0) out vec4 fragColor;

layout(push_constant) uniform HalftoneParams {
    vec4 render;
    vec4 controls;
    vec4 scroll;
    // Appended after the existing vec4s so none of the offsets above shift.
    //
    // clockRect: centerX, top, widthFraction, heightFraction — all in the
    // screen-locked vEffectCoord space. The width arrives already divided by
    // the surface aspect (see the JNI), because this shader has no
    // surface-aspect field of its own.
    //
    // clockMeta: opacity (with the lock/home fade already folded in by the
    // host), "a face has been uploaded", "depth enabled AND a subject mask
    // exists", unused.
    vec4 clockRect;
    vec4 clockMeta;
} params;

// Mirrors the GLES path in assets/shaders/halftone/sharp_to_halftone.frag; keep the two in step.
//
// clockMeta.y is "a face has been uploaded", NOT the user's toggle: the
// engine fills unwritten optional bindings with an opaque-black 1x1 clear
// texture, so sampling before the first upload would paint a solid black
// rectangle where the clock belongs. The lock/home fade arrives already
// folded into clockMeta.x, so there is no policy in this shader.
// ------------------------------------------------------- liquid glass clock
// Drawn instead of the flat face when the style asks for glass. The face
// texture only supplies the glyph SHAPE (its alpha); everything visible is the
// wallpaper, bent at the rounded edges like a thick lens, softened inside,
// and lit along the edges facing the light. Normals come from the alpha
// gradient over a few texels, so the bevel costs no extra texture and no CPU
// work per frame.
//
// wallpaperTexture is the sharp photo. What the effect had already drawn here ([color])
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

    vec2 texel = 1.0 / vec2(textureSize(clockTexture, 0));
    vec2 gradient = vec2(
        texture(clockTexture, clamp(clockUv + vec2(texel.x, 0.0), 0.0, 1.0)).a -
            texture(clockTexture, clamp(clockUv - vec2(texel.x, 0.0), 0.0, 1.0)).a,
        texture(clockTexture, clamp(clockUv + vec2(0.0, texel.y), 0.0, 1.0)).a -
            texture(clockTexture, clamp(clockUv - vec2(0.0, texel.y), 0.0, 1.0)).a
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
    vec3 refracted = texture(wallpaperTexture, sampleUv).rgb;
    if (frostLevel > 0.004) {
        float blur = mix(0.0012, 0.0110, frostLevel);
        float diagonal = blur * 0.7;
        refracted = (
            2.0 * refracted +
            texture(wallpaperTexture, clamp(sampleUv + vec2(blur, 0.0), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(sampleUv - vec2(blur, 0.0), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(sampleUv + vec2(0.0, blur), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(sampleUv - vec2(0.0, blur), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(sampleUv + vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(sampleUv - vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(sampleUv + vec2(diagonal, -diagonal), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(sampleUv - vec2(diagonal, -diagonal), 0.0, 1.0)).rgb
        ) / 10.0;
    }
    // Whatever the effect did to the wallpaper behind the clock applies to
    // what shows through it too.
    refracted = clamp(refracted + (color - texture(wallpaperTexture, vTexCoord).rgb), 0.0, 1.0);

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
    if (params.clockMeta.y <= 0.5 || params.clockMeta.x <= 0.0) return color;
    vec2 clockSize = max(params.clockRect.zw, vec2(1e-5));
    vec2 clockOrigin = vec2(
        params.clockRect.x - clockSize.x * 0.5,
        params.clockRect.y
    );
    vec2 clockUv = (screenCoord - clockOrigin) / clockSize;
    if (
        clockUv.x < 0.0 || clockUv.x > 1.0 ||
        clockUv.y < 0.0 || clockUv.y > 1.0
    ) {
        return color;
    }
    vec4 clockSample = texture(clockTexture, clockUv);
    if (params.clockMeta.w > 0.5) {
        // Above 1 is glass, and the fraction is the frost level — see
        // ClockOverlayState.glassMeta.
        return clockGlass(
            color,
            clockUv,
            clockSample,
            clockSize,
            params.clockMeta.x,
            max(params.clockMeta.w - 1.0, 0.0)
        );
    }
    // A flat face: no glass, but the silhouette still comes from the field
    // rather than from an alpha the texture no longer carries.
    float flatSoftness = clamp(fwidth(clockSample.a), 0.0008, 0.25);
    float flatCoverage =
        smoothstep(0.5 - flatSoftness, 0.5 + flatSoftness, clockSample.a);
    vec3 flatColor = clockSample.rgb / max(clockSample.a, 0.001);
    return mix(color, flatColor, flatCoverage * params.clockMeta.x);
}

// Draws the sharp subject back over the clock, so the clock reads as sitting
// behind them. Fades with the clock itself, so the subject is not left
// re-sharpened over a stylised background once the clock has gone.
vec3 applyClockDepth(vec3 color, vec3 subjectColor, float subjectMask) {
    if (params.clockMeta.y <= 0.5 || params.clockMeta.z <= 0.5) return color;
    float coverage = smoothstep(0.30, 0.72, subjectMask);
    return mix(color, subjectColor, coverage * params.clockMeta.x);
}

// Raw subject coverage for the clock's depth effect.
//
// Deliberately not foregroundProtection() below: that one returns 0 whenever
// the Halftone effect's own "background only" mode is off, because it exists
// to decide where the halftone is suppressed. The clock's depth is a separate
// user setting that must work with background-only switched off, so it reads
// the mask directly.
float clockSubjectMask(vec2 uv) {
    vec2 stepSize = 2.0 / vec2(textureSize(subjectMask, 0));
    float mask = texture(subjectMask, uv).r;
    mask = max(
        mask,
        texture(subjectMask, clamp(uv + vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(subjectMask, clamp(uv - vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(subjectMask, clamp(uv + vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(subjectMask, clamp(uv - vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    return mask;
}

mat2 rotate2d(float angle) {
    float sine = sin(angle);
    float cosine = cos(angle);
    return mat2(cosine, -sine, sine, cosine);
}

float halftoneChannel(
    vec2 uv,
    float angle,
    float value,
    vec2 textureDimensions,
    float dotSize
) {
    vec2 centered = uv - 0.5;
    centered.x *= params.render.z;
    vec2 rotated = rotate2d(angle) * centered;
    vec2 grid = rotated * textureDimensions.y / dotSize;
    vec2 local = fract(grid) - 0.5;

    float distanceFromCenter = length(local);
    float radius = sqrt(value) * 0.75;
    float edge = max(0.05, 1.0 / dotSize);
    return smoothstep(
        radius + edge,
        radius - edge,
        distanceFromCenter
    );
}

float foregroundProtection(vec2 uv) {
    if (params.controls.z <= 0.5) return 0.0;
    // No subject mask: nothing is known to protect, so don't revert the
    // whole frame back to the untouched image.
    if (params.controls.w <= 0.5) return 0.0;

    vec2 stepSize = 2.0 / vec2(textureSize(subjectMask, 0));
    float mask = texture(subjectMask, uv).r;
    mask = max(
        mask,
        texture(subjectMask, clamp(uv + vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(subjectMask, clamp(uv - vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(subjectMask, clamp(uv + vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(subjectMask, clamp(uv - vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    return smoothstep(0.30, 0.72, mask);
}

void main() {
    bool reverse = params.render.w > 0.5;
    float progress = clamp(params.render.x, 0.0, 1.0);
    float effectStrength = reverse ? 1.0 - progress : progress;
    float dotSize = params.controls.x;
    bool grayscale = params.controls.y > 0.5;

    vec3 sharp = texture(wallpaperTexture, vTexCoord).rgb;
    vec2 textureDimensions = vec2(textureSize(wallpaperTexture, 0));
    vec3 halftoneOutput;

    bool dotsDisabled = reverse ? dotSize == 0.0 : dotSize < 0.1;
    if (dotsDisabled) {
        if (grayscale) {
            float luma = dot(sharp, vec3(0.299, 0.587, 0.114));
            halftoneOutput = vec3(luma);
        } else {
            halftoneOutput = sharp;
        }
    } else if (grayscale) {
        float luma = dot(sharp, vec3(0.299, 0.587, 0.114));
        float black = halftoneChannel(
            vTexCoord,
            radians(45.0),
            1.0 - luma,
            textureDimensions,
            dotSize
        );
        halftoneOutput = vec3(1.0 - black);
    } else {
        vec3 cmy = 1.0 - sharp;
        float cyan = halftoneChannel(
            vTexCoord,
            radians(15.0),
            cmy.r,
            textureDimensions,
            dotSize
        );
        float magenta = halftoneChannel(
            vTexCoord,
            radians(75.0),
            cmy.g,
            textureDimensions,
            dotSize
        );
        float yellow = halftoneChannel(
            vTexCoord,
            radians(0.0),
            cmy.b,
            textureDimensions,
            dotSize
        );
        halftoneOutput = 1.0 - vec3(cyan, magenta, yellow);
    }

    vec3 finalColor = mix(sharp, halftoneOutput, effectStrength);
    finalColor = mix(
        finalColor,
        vec3(0.0),
        params.render.y * effectStrength
    );
    finalColor = mix(
        finalColor,
        sharp,
        foregroundProtection(vTexCoord)
    );

    // Depth restores the frame exactly as the effect drew it before the
    // clock, so it only ever changes pixels the clock touched. Mixing
    // in the sharp photo instead re-sharpened the subject across the
    // whole screen during the lock/unlock transition.
    vec3 beforeClock = finalColor;
    finalColor = compositeClock(finalColor, vEffectCoord);
    finalColor = applyClockDepth(
        finalColor,
        beforeClock,
        clockSubjectMask(vTexCoord)
    );

    fragColor = vec4(finalColor, 1.0);
}
