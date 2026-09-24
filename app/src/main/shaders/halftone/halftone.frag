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
    float body = clockSample.a;
    if (body <= 0.003) return color;
    vec2 texel = 1.0 / vec2(textureSize(clockTexture, 0));
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
            texture(clockTexture, clamp(clockUv + vec2(texel.x * 3.0, 0.0), 0.0, 1.0)).a -
                texture(clockTexture, clamp(clockUv - vec2(texel.x * 3.0, 0.0), 0.0, 1.0)).a,
            texture(clockTexture, clamp(clockUv + vec2(0.0, texel.y * 3.0), 0.0, 1.0)).a -
                texture(clockTexture, clamp(clockUv - vec2(0.0, texel.y * 3.0), 0.0, 1.0)).a
        ) +
        vec2(
            texture(clockTexture, clamp(clockUv + vec2(texel.x * 7.0, 0.0), 0.0, 1.0)).a -
                texture(clockTexture, clamp(clockUv - vec2(texel.x * 7.0, 0.0), 0.0, 1.0)).a,
            texture(clockTexture, clamp(clockUv + vec2(0.0, texel.y * 7.0), 0.0, 1.0)).a -
                texture(clockTexture, clamp(clockUv - vec2(0.0, texel.y * 7.0), 0.0, 1.0)).a
        ) +
        vec2(
            texture(clockTexture, clamp(clockUv + vec2(texel.x * EDGE, 0.0), 0.0, 1.0)).a -
                texture(clockTexture, clamp(clockUv - vec2(texel.x * EDGE, 0.0), 0.0, 1.0)).a,
            texture(clockTexture, clamp(clockUv + vec2(0.0, texel.y * EDGE), 0.0, 1.0)).a -
                texture(clockTexture, clamp(clockUv - vec2(0.0, texel.y * EDGE), 0.0, 1.0)).a
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
        texture(clockTexture, clamp(clockUv - stride * 4.0, 0.0, 1.0)).a +
        texture(clockTexture, clamp(clockUv - stride * 3.0, 0.0, 1.0)).a +
        texture(clockTexture, clamp(clockUv - stride * 2.0, 0.0, 1.0)).a +
        texture(clockTexture, clamp(clockUv - stride, 0.0, 1.0)).a +
        body +
        texture(clockTexture, clamp(clockUv + stride, 0.0, 1.0)).a +
        texture(clockTexture, clamp(clockUv + stride * 2.0, 0.0, 1.0)).a +
        texture(clockTexture, clamp(clockUv + stride * 3.0, 0.0, 1.0)).a +
        texture(clockTexture, clamp(clockUv + stride * 4.0, 0.0, 1.0)).a;
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
        2.0 * texture(wallpaperTexture, sampleUv).rgb +
        texture(wallpaperTexture, clamp(sampleUv + vec2(frost, 0.0), 0.0, 1.0)).rgb +
        texture(wallpaperTexture, clamp(sampleUv - vec2(frost, 0.0), 0.0, 1.0)).rgb +
        texture(wallpaperTexture, clamp(sampleUv + vec2(0.0, frost), 0.0, 1.0)).rgb +
        texture(wallpaperTexture, clamp(sampleUv - vec2(0.0, frost), 0.0, 1.0)).rgb
    ) / 6.0;
    // Whatever the effect did to the wallpaper behind the clock applies to
    // what shows through it too.
    refracted = clamp(refracted + (color - texture(wallpaperTexture, vTexCoord).rgb), 0.0, 1.0);
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
    return mix(color, clockSample.rgb, clockSample.a * params.clockMeta.x);
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
