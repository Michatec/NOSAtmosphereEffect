#version 300 es
precision highp float;

in vec2 vTexCoord;
in vec2 vEffectCoord;

out vec4 fragColor;

uniform sampler2D uTexture;
uniform sampler2D uSubjectMask;
uniform float uProgress;
uniform float uLineCount;
uniform float uLineThickness;
uniform float uTransitionStyle;
uniform float uDimLevel;
uniform float uScrollWindowX;
uniform float uBackgroundOnly;
uniform float uHasSubject;

const float TWO_PI = 6.28318530718;

vec3 sampleSoftened(vec2 uv, vec2 texel) {
    vec2 radius = vec2(texel.x * 1.15, 0.0);
    return
        texture(uTexture, uv).rgb * 0.58 +
        texture(uTexture, clamp(uv + radius, 0.0, 1.0)).rgb * 0.21 +
        texture(uTexture, clamp(uv - radius, 0.0, 1.0)).rgb * 0.21;
}

float sampleSubject(vec2 uv) {
    vec2 stepSize = 2.0 / vec2(textureSize(uSubjectMask, 0));
    float mask = texture(uSubjectMask, uv).r;
    mask = max(mask, texture(uSubjectMask, clamp(uv + vec2(stepSize.x, 0.0), 0.0, 1.0)).r);
    mask = max(mask, texture(uSubjectMask, clamp(uv - vec2(stepSize.x, 0.0), 0.0, 1.0)).r);
    mask = max(mask, texture(uSubjectMask, clamp(uv + vec2(0.0, stepSize.y), 0.0, 1.0)).r);
    mask = max(mask, texture(uSubjectMask, clamp(uv - vec2(0.0, stepSize.y), 0.0, 1.0)).r);
    return mask;
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
// The clock's own depth switch, already ANDed with "a real subject mask is
// bound" by the renderer. Deliberately independent of the effect's own
// background-only mode: the depth effect has to work whether or not the user
// has asked for subject isolation elsewhere.
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
// uTexture is the sharp photo. What the effect had already drawn here ([color])
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
        2.0 * texture(uTexture, sampleUv).rgb +
        texture(uTexture, clamp(sampleUv + vec2(frost, 0.0), 0.0, 1.0)).rgb +
        texture(uTexture, clamp(sampleUv - vec2(frost, 0.0), 0.0, 1.0)).rgb +
        texture(uTexture, clamp(sampleUv + vec2(0.0, frost), 0.0, 1.0)).rgb +
        texture(uTexture, clamp(sampleUv - vec2(0.0, frost), 0.0, 1.0)).rgb
    ) / 6.0;
    // Whatever the effect did to the wallpaper behind the clock applies to
    // what shows through it too.
    refracted = clamp(refracted + (color - texture(uTexture, vTexCoord).rgb), 0.0, 1.0);
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

// Draws the sharp subject back over the clock, which is what sells "the clock
// is behind them". Fades with the clock itself, so the subject is not left
// re-sharpened over a stylised background once the clock has gone.
vec3 applyClockDepth(vec3 color, vec3 subjectColor, float subjectMask) {
    if (uClockEnabled <= 0.5 || uClockDepth <= 0.5 || uClockOpacity <= 0.0) {
        return color;
    }
    float coverage = smoothstep(0.30, 0.72, subjectMask);
    return mix(color, subjectColor, coverage * uClockOpacity);
}

void main() {
    float count = max(1.0, floor(uLineCount + 0.5));
    float screenX = clamp(vEffectCoord.x, 0.0, 0.999999);
    float lanePosition = screenX * count;
    float laneIndex = floor(lanePosition);
    float orderFromRight = count - 1.0 - laneIndex;

    float progress = clamp(uProgress, 0.0, 1.0);
    float sequential = clamp(progress * count - orderFromRight, 0.0, 1.0);
    sequential = sequential * sequential * (3.0 - 2.0 * sequential);
    float fade = progress * progress * (3.0 - 2.0 * progress);
    float transition = mix(sequential, fade, step(0.5, uTransitionStyle));

    float localRib = fract(lanePosition);
    float phase = TWO_PI * localRib;
    float wave = sin(phase);
    float profileExponent = mix(1.80, 0.25, clamp(uLineThickness, 0.0, 1.0));
    float profile = sign(wave) * pow(abs(wave), profileExponent);

    float scrollWindow = uScrollWindowX <= 0.0 ? 1.0 : uScrollWindowX;
    float displacement = profile * (1.08 * scrollWindow / count);

    vec2 glassUv = vec2(
        clamp(vTexCoord.x + displacement, 0.0, 1.0),
        vTexCoord.y
    );
    vec2 texel = 1.0 / vec2(textureSize(uTexture, 0));
    vec3 sharpColor = texture(uTexture, vTexCoord).rgb;
    vec3 refractedColor = texture(uTexture, glassUv).rgb;
    vec3 glassColor = mix(refractedColor, sampleSoftened(glassUv, texel), 0.72);
    float ribFaceLighting = 0.016 * (2.0 * localRib - 1.0);
    glassColor += vec3(ribFaceLighting);

    float rightInnerDistance = 1.0 - localRib;
    float rightInnerGlow = 1.0 - smoothstep(
        0.0,
        0.25,
        rightInnerDistance
    );
    glassColor = clamp(
        glassColor + vec3(0.036 * rightInnerGlow),
        0.0,
        1.0
    );
    glassColor = mix(
        glassColor,
        vec3(0.0),
        clamp(uDimLevel, 0.0, 1.0)
    );

    float backgroundCoverage = 1.0;
    if (uBackgroundOnly > 0.5) {
        if (uHasSubject > 0.5) {
            float subject = max(
                sampleSubject(vTexCoord),
                sampleSubject(glassUv)
            );
            backgroundCoverage = 1.0 - smoothstep(0.30, 0.72, subject);
        } else {
            // No subject mask: nothing is known to protect, so cover the
            // whole frame rather than suppressing the effect everywhere.
            backgroundCoverage = 1.0;
        }
    }

    vec3 finalColor = mix(
        sharpColor,
        glassColor,
        transition * backgroundCoverage
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
        sampleSubject(vTexCoord)
    );

    fragColor = vec4(finalColor, 1.0);
}
