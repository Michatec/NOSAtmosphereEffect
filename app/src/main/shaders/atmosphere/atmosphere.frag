#version 450

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec2 vEffectCoord;
layout(location = 0) out vec4 fragColor;

layout(set = 0, binding = 0) uniform sampler2D sharpTexture;
layout(set = 0, binding = 1) uniform sampler2D blurredTexture;
layout(set = 0, binding = 2) uniform sampler2D subjectMask;
layout(set = 0, binding = 3) uniform sampler2D clockTexture;

layout(std140, set = 0, binding = 4) uniform AtmosphereParams {
    vec4 render;
    vec4 noise;
    vec4 glass;
    vec4 viewport;
    vec4 misc;
    ivec4 blobMeta;
    vec4 blobColors[16];
    vec4 blobPositionsAndSizes[16];
    // x: centerX, y: top, z: heightFraction, w: textureAspect — all in the
    // screen-locked vEffectCoord space.
    vec4 clockRect;
    // x: opacity, y: a face has been uploaded, z: depth enabled AND a
    // subject mask exists, w: unused.
    vec4 clockMeta;
} params;

const float TWO_PI = 6.28318530718;

vec3 sampleGlassSoftened(vec2 uv, vec2 texel) {
    vec2 radius = vec2(texel.x * 1.15, 0.0);
    return
        texture(sharpTexture, uv).rgb * 0.58 +
        texture(sharpTexture, clamp(uv + radius, 0.0, 1.0)).rgb * 0.21 +
        texture(sharpTexture, clamp(uv - radius, 0.0, 1.0)).rgb * 0.21;
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

vec3 staticGlass() {
    float count = max(1.0, floor(params.glass.z + 0.5));
    float localRib = fract(clamp(vEffectCoord.x, 0.0, 0.999999) * count);
    float wave = sin(TWO_PI * localRib);
    float exponent = mix(
        1.80,
        0.25,
        clamp(params.glass.w, 0.0, 1.0)
    );
    float profile = sign(wave) * pow(abs(wave), exponent);
    float displacement =
        profile * (1.08 * max(params.viewport.y, 0.001) / count);
    vec2 glassUv = vec2(
        clamp(vTexCoord.x + displacement, 0.0, 1.0),
        vTexCoord.y
    );

    vec2 texel = 1.0 / vec2(textureSize(sharpTexture, 0));
    vec3 sharpColor = texture(sharpTexture, vTexCoord).rgb;
    vec3 refracted = texture(sharpTexture, glassUv).rgb;
    vec3 glassColor = mix(
        refracted,
        sampleGlassSoftened(glassUv, texel),
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

    float backgroundCoverage = 1.0;
    if (params.viewport.z > 0.5) {
        if (params.viewport.w > 0.5) {
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
    return mix(sharpColor, glassColor, backgroundCoverage);
}

vec3 adjustColor(vec3 color) {
    color = (color - 0.5) * max(params.glass.x, 0.0) + 0.5;
    float luminance = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(luminance), color, max(params.noise.w, 0.0));
    return clamp(color, 0.0, 1.0);
}

float randomValue(vec2 coordinate) {
    return fract(
        sin(dot(coordinate, vec2(12.9898, 78.233))) * 43758.5453
    );
}

// ------------------------------------------------------- liquid glass clock
// Drawn instead of the flat face when the style asks for glass. The face
// texture only supplies the glyph SHAPE (its alpha); everything visible is the
// wallpaper, bent at the rounded edges like a thick lens, softened inside,
// and lit along the edges facing the light. Normals come from the alpha
// gradient over a few texels, so the bevel costs no extra texture and no CPU
// work per frame.
//
// sharpTexture is the sharp photo. What the effect had already drawn here ([color])
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
        2.0 * texture(sharpTexture, sampleUv).rgb +
        texture(sharpTexture, clamp(sampleUv + vec2(frost, 0.0), 0.0, 1.0)).rgb +
        texture(sharpTexture, clamp(sampleUv - vec2(frost, 0.0), 0.0, 1.0)).rgb +
        texture(sharpTexture, clamp(sampleUv + vec2(0.0, frost), 0.0, 1.0)).rgb +
        texture(sharpTexture, clamp(sampleUv - vec2(0.0, frost), 0.0, 1.0)).rgb
    ) / 6.0;
    // Whatever the effect did to the wallpaper behind the clock applies to
    // what shows through it too.
    refracted = clamp(refracted + (color - texture(sharpTexture, vTexCoord).rgb), 0.0, 1.0);
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

void main() {
    float progress = clamp(params.render.x, 0.0, 1.0);
    float aspectRatio = max(params.render.z, 0.001);
    vec2 uv = vTexCoord;
    uv.x *= aspectRatio;

    vec3 cloudSum = vec3(0.0);
    float cloudWeight = 0.0;
    int blobCount = clamp(params.blobMeta.x, 0, 16);
    for (int index = 0; index < blobCount; ++index) {
        vec2 position = params.blobPositionsAndSizes[index].xy;
        position.x *= aspectRatio;
        float distanceToBlob = length(uv - position);
        float weight =
            params.blobPositionsAndSizes[index].z /
            (pow(distanceToBlob, 2.0) + 0.05);
        cloudSum += adjustColor(params.blobColors[index].rgb) * weight;
        cloudWeight += weight;
    }

    vec3 muddyBackground = vec3(0.0);
    if (cloudWeight > 0.0) {
        muddyBackground = cloudSum / cloudWeight;
    }

    float blurPhase = smoothstep(0.0, 0.2, progress);
    float cloudMorph = smoothstep(0.18, 0.5, progress);
    vec3 sharp = texture(sharpTexture, vTexCoord).rgb;
    if (params.glass.y > 0.5) {
        sharp = staticGlass();
    }
    vec3 frosted = texture(blurredTexture, vTexCoord).rgb;
    vec3 finalColor = mix(sharp, frosted, blurPhase);
    if (progress > 0.18) {
        finalColor = mix(finalColor, muddyBackground, cloudMorph);
    }

    float blobOpacity = smoothstep(0.15, 0.3, progress);
    if (blobOpacity > 0.01) {
        for (int index = 0; index < blobCount; ++index) {
            vec2 position = params.blobPositionsAndSizes[index].xy;
            position.x *= aspectRatio;
            float distanceToBlob = length(uv - position);
            float alpha = 1.0 - smoothstep(
                0.0,
                params.blobPositionsAndSizes[index].z,
                distanceToBlob
            );
            alpha *= blobOpacity;
            if (alpha > 0.0) {
                finalColor = mix(
                    finalColor,
                    adjustColor(params.blobColors[index].rgb),
                    alpha
                );
            }
        }
    }

    finalColor = mix(
        finalColor,
        vec3(0.0),
        clamp(params.render.y, 0.0, 1.0) * progress
    );

    if (params.noise.x > 0.5) {
        vec2 grainUv = floor(uv * params.noise.y);
        float noise = randomValue(grainUv);
        float forwardVisibility = smoothstep(0.4, 1.0, progress);
        float reverseVisibility = smoothstep(0.0, 0.4, progress);
        float visibility = mix(
            forwardVisibility,
            reverseVisibility,
            step(0.5, params.misc.x)
        );
        finalColor += vec3(noise * params.noise.z * visibility);
    }

    float drawerBlur =
        params.misc.x > 0.5 ? clamp(params.misc.y, 0.0, 1.0) : 0.0;
    finalColor = mix(finalColor, frosted, drawerBlur);

    // Clock overlay — mirrors the GLES path in
    // assets/shaders/atmosphere/atmosphere.frag; keep the two in step.
    //
    // clockMeta.y is "a face has been uploaded", not the user's toggle. The
    // engine fills unwritten optional bindings with an opaque-black 1x1
    // clear texture, so sampling before the first upload would draw a solid
    // black rectangle. The lock fade lives on the host side and arrives
    // already folded into clockMeta.x, so this shader has no policy in it.
    if (params.clockMeta.y > 0.5 && params.clockMeta.x > 0.0) {
        vec3 beforeClock = finalColor;
        float clockHeightUv = max(params.clockRect.z, 1e-5);
        float clockWidthUv =
            max(clockHeightUv * params.clockRect.w / aspectRatio, 1e-5);
        vec2 clockOrigin = vec2(
            params.clockRect.x - clockWidthUv * 0.5,
            params.clockRect.y
        );
        vec2 clockUv =
            (vEffectCoord - clockOrigin) / vec2(clockWidthUv, clockHeightUv);
        if (
            clockUv.x >= 0.0 && clockUv.x <= 1.0 &&
            clockUv.y >= 0.0 && clockUv.y <= 1.0
        ) {
            vec4 clockSample = texture(clockTexture, clockUv);
            if (params.clockMeta.w > 0.5) {
                finalColor = clockGlass(
                    finalColor,
                    clockUv,
                    clockSample,
                    vec2(clockWidthUv, clockHeightUv),
                    params.clockMeta.x,
                    // Above 1 is glass and the fraction is the frost level —
                    // see ClockOverlayState.glassMeta.
                    max(params.clockMeta.w - 1.0, 0.0)
                );
            } else {
                finalColor = mix(
                    finalColor,
                    clockSample.rgb,
                    clockSample.a * params.clockMeta.x
                );
            }
        }

        // Restores the frame as it was before the clock, so depth only
        // changes pixels the clock touched (see the GLES twin).
        if (params.clockMeta.z > 0.5) {
            float subjectCoverage =
                smoothstep(0.30, 0.72, sampleSubject(vTexCoord));
            finalColor = mix(
                finalColor,
                beforeClock,
                subjectCoverage * params.clockMeta.x
            );
        }
    }

    fragColor = vec4(finalColor, 1.0);
}
