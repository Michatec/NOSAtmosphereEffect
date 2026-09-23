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
vec3 clockGlass(vec3 color, vec2 clockUv, vec4 clockSample, vec2 rectSize, float opacity) {
    float body = clockSample.a;
    if (body <= 0.003) return color;
    vec2 texel = 1.0 / vec2(textureSize(clockTexture, 0));
    const float bevel = 9.0;
    vec2 dx = vec2(texel.x * bevel, 0.0);
    vec2 dy = vec2(0.0, texel.y * bevel);
    // Alpha rises into the glyph, so this points inwards; the outward
    // surface normal tilts the opposite way.
    vec2 slope = 0.5 * vec2(
        texture(clockTexture, clamp(clockUv + dx, 0.0, 1.0)).a -
            texture(clockTexture, clamp(clockUv - dx, 0.0, 1.0)).a,
        texture(clockTexture, clamp(clockUv + dy, 0.0, 1.0)).a -
            texture(clockTexture, clamp(clockUv - dy, 0.0, 1.0)).a
    );
    float edge = clamp(length(slope) * 2.0, 0.0, 1.0);

    // Refraction: the rim pulls the image outwards, the way a thick rounded
    // edge does. Scaled by the clock's own size so it looks the same at any
    // size the user picks.
    vec2 sampleUv = clamp(vTexCoord - slope * (0.11 * rectSize.y), 0.0, 1.0);
    float frost = 0.0032;
    vec3 refracted = (
        2.0 * texture(sharpTexture, sampleUv).rgb +
        texture(sharpTexture, clamp(sampleUv + vec2(frost, 0.0), 0.0, 1.0)).rgb +
        texture(sharpTexture, clamp(sampleUv - vec2(frost, 0.0), 0.0, 1.0)).rgb +
        texture(sharpTexture, clamp(sampleUv + vec2(0.0, frost), 0.0, 1.0)).rgb +
        texture(sharpTexture, clamp(sampleUv - vec2(0.0, frost), 0.0, 1.0)).rgb
    ) / 6.0;
    refracted = clamp(refracted + (color - texture(sharpTexture, vTexCoord).rgb), 0.0, 1.0);

    // Light from the upper left (texture y grows downwards).
    vec3 normal = normalize(vec3(-slope * 2.4, 1.0));
    vec3 light = normalize(vec3(-0.5, -0.72, 0.48));
    float specular = pow(max(dot(normal, light), 0.0), 22.0) * edge;
    float rim = smoothstep(0.12, 0.85, edge);
    float shade = max(-dot(normal.xy, light.xy), 0.0) * edge;

    vec3 tint = clockSample.rgb / max(clockSample.a, 0.001);
    vec3 glass = refracted * 1.05 + vec3(0.035);
    glass = mix(glass, glass * tint, 0.16);
    glass += vec3(rim * 0.20 + specular * 0.9);
    glass -= vec3(shade * 0.14);
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
                    params.clockMeta.x
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
