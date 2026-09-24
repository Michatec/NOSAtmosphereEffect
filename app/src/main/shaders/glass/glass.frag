#version 450

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec2 vEffectCoord;
layout(location = 0) out vec4 fragColor;

layout(set = 0, binding = 0) uniform sampler2D sourceTexture;
layout(set = 0, binding = 1) uniform sampler2D subjectMask;
layout(set = 0, binding = 2) uniform sampler2D clockTexture;

layout(push_constant) uniform GlassParams {
    vec4 transition;
    vec4 viewport;
    vec4 mask;
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

// Mirrors the GLES path in assets/shaders/glass/glass.frag; keep the two in step.
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
// sourceTexture is the sharp photo. What the effect had already drawn here ([color])
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

    // Frost scatters what comes through: a wider disc as it rises, and a
    // milkier, flatter transmission with it.
    float blur = mix(0.0012, 0.0110, frostLevel);
    float diagonal = blur * 0.7;
    vec3 refracted = (
        2.0 * texture(sourceTexture, sampleUv).rgb +
        texture(sourceTexture, clamp(sampleUv + vec2(blur, 0.0), 0.0, 1.0)).rgb +
        texture(sourceTexture, clamp(sampleUv - vec2(blur, 0.0), 0.0, 1.0)).rgb +
        texture(sourceTexture, clamp(sampleUv + vec2(0.0, blur), 0.0, 1.0)).rgb +
        texture(sourceTexture, clamp(sampleUv - vec2(0.0, blur), 0.0, 1.0)).rgb +
        texture(sourceTexture, clamp(sampleUv + vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
        texture(sourceTexture, clamp(sampleUv - vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
        texture(sourceTexture, clamp(sampleUv + vec2(diagonal, -diagonal), 0.0, 1.0)).rgb +
        texture(sourceTexture, clamp(sampleUv - vec2(diagonal, -diagonal), 0.0, 1.0)).rgb
    ) / 10.0;
    // Whatever the effect did to the wallpaper behind the clock applies to
    // what shows through it too.
    refracted = clamp(refracted + (color - texture(sourceTexture, vTexCoord).rgb), 0.0, 1.0);
    float milk = dot(refracted, vec3(0.2126, 0.7152, 0.0722));
    vec3 etched = mix(vec3(milk), vec3(1.0), 0.45);
    refracted = mix(refracted, etched, frostLevel * 0.90);

    // A key light from the upper left (texture y grows downwards) and a dim
    // fill from the lower right: one source alone leaves the far side of every
    // stroke dead, where real glass picks up the whole room.
    vec3 key = normalize(vec3(-0.45, -0.75, 0.48));
    vec3 fill = normalize(vec3(0.55, 0.62, 0.55));
    float facing = dot(normal, key);
    float sheen = max(facing, 0.0) * shoulder;
    float glint = pow(max(facing, 0.0), 22.0);
    float bounce = max(dot(normal, fill), 0.0) * shoulder;
    float shade = max(-facing, 0.0) * shoulder;
    // The boundary itself: a bright hairline just inside the silhouette, which
    // is the edge of the glass rather than an outline drawn around it.
    float boundary = smoothstep(0.55, 1.0, shoulder);

    vec3 tint = clockSample.rgb / max(field, 0.001);
    vec3 glass = refracted;
    // Coloured glass: the chosen colour tints what comes through, while the
    // highlights stay white the way a reflection does.
    glass = mix(glass, glass * tint, 0.55);
    glass += vec3(sheen * 0.18 + glint * 0.55 + bounce * 0.10 + boundary * 0.14);
    glass -= vec3(shade * 0.22);
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

const float TWO_PI = 6.28318530718;

vec3 sampleSoftened(vec2 uv, vec2 texel) {
    vec2 radius = vec2(texel.x * 1.15, 0.0);
    return
        texture(sourceTexture, uv).rgb * 0.58 +
        texture(sourceTexture, clamp(uv + radius, 0.0, 1.0)).rgb * 0.21 +
        texture(sourceTexture, clamp(uv - radius, 0.0, 1.0)).rgb * 0.21;
}

float sampleSubject(vec2 uv) {
    vec2 stepSize = 2.0 / vec2(textureSize(subjectMask, 0));
    float value = texture(subjectMask, uv).r;
    value = max(
        value,
        texture(subjectMask, clamp(uv + vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    value = max(
        value,
        texture(subjectMask, clamp(uv - vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    value = max(
        value,
        texture(subjectMask, clamp(uv + vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    value = max(
        value,
        texture(subjectMask, clamp(uv - vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    return value;
}

void main() {
    float progress = clamp(params.transition.x, 0.0, 1.0);
    float count = max(1.0, floor(params.transition.y + 0.5));
    float thickness = clamp(params.transition.z, 0.0, 1.0);
    float style = params.transition.w;

    float screenX = clamp(vEffectCoord.x, 0.0, 0.999999);
    float lanePosition = screenX * count;
    float laneIndex = floor(lanePosition);
    float orderFromRight = count - 1.0 - laneIndex;

    float sequential = clamp(progress * count - orderFromRight, 0.0, 1.0);
    sequential = sequential * sequential * (3.0 - 2.0 * sequential);
    float fade = progress * progress * (3.0 - 2.0 * progress);
    float transitionAmount = mix(sequential, fade, step(0.5, style));

    float localRib = fract(lanePosition);
    float wave = sin(TWO_PI * localRib);
    float profileExponent = mix(1.80, 0.25, thickness);
    float profile = sign(wave) * pow(abs(wave), profileExponent);
    float displacement = profile * (1.08 * max(params.viewport.y, 0.001) / count);

    vec2 glassUv = vec2(
        clamp(vTexCoord.x + displacement, 0.0, 1.0),
        vTexCoord.y
    );
    vec2 texel = 1.0 / vec2(textureSize(sourceTexture, 0));
    vec3 sharpColor = texture(sourceTexture, vTexCoord).rgb;
    vec3 refractedColor = texture(sourceTexture, glassUv).rgb;
    vec3 glassColor = mix(
        refractedColor,
        sampleSoftened(glassUv, texel),
        0.72
    );
    glassColor += vec3(0.016 * (2.0 * localRib - 1.0));

    float rightInnerGlow =
        1.0 - smoothstep(0.0, 0.25, 1.0 - localRib);
    glassColor = clamp(
        glassColor + vec3(0.036 * rightInnerGlow),
        0.0,
        1.0
    );
    glassColor = mix(
        glassColor,
        vec3(0.0),
        clamp(params.viewport.z, 0.0, 1.0)
    );

    float backgroundCoverage = 1.0;
    if (params.mask.x > 0.5) {
        if (params.mask.y > 0.5) {
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
        transitionAmount * backgroundCoverage
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
