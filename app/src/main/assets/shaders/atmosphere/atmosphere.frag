#version 300 es
precision highp float;
// hashU needs 32-bit ints; ES 3.00 defaults to mediump (only 16 bits guaranteed).
precision highp int;

in vec2 vTexCoord;
in vec2 vEffectCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform sampler2D uTextureBlur;
uniform sampler2D uSubjectMask;

#define MAX_BLOBS 16
uniform vec3 uBlobColors[MAX_BLOBS];
uniform vec2 uBlobPositions[MAX_BLOBS];
uniform float uBlobSizes[MAX_BLOBS];
uniform int uBlobCount;
uniform float uAspectRatio;

uniform float uBlurStrength;
uniform float uDimLevel;
uniform float uEnableNoise;
uniform float uNoiseScale;
uniform float uNoiseStrength;

uniform float uSaturation;
uniform float uContrast;

uniform float uAtmosphereGlassEnabled;
uniform float uGlassLineCount;
uniform float uGlassLineThickness;
uniform float uScrollWindowX;
uniform float uBackgroundOnly;
uniform float uHasSubject;

uniform sampler2D uClockTexture;
// 1.0 only once a real clock face has been uploaded. Independent of the
// user's toggle: the texture starts out as unwritten storage, and sampling
// that would paint a rectangle of garbage where the clock belongs.
uniform float uClockEnabled;
uniform vec4 uClockRect;   // x, y, width, height — screen-locked UV (vEffectCoord) space
uniform float uClockOpacity;
// The clock's own depth switch, ANDed with "a subject mask exists" by the
// renderer. Deliberately not uHasSubject: that one is gated on the Glass
// effect's background-only mode, which used to make the clock's depth effect
// silently do nothing whenever Glass was off.
uniform float uClockDepth;
// 1 when the face is drawn as refracting glass (ClockStyle.liquidGlass).
uniform float uClockGlass;

const float TWO_PI = 6.28318530718;

vec3 sampleGlassSoftened(vec2 sampleUv, vec2 texel) {
    vec2 radius = vec2(texel.x * 1.15, 0.0);
    return
        texture(uTextureSharp, sampleUv).rgb * 0.58 +
        texture(uTextureSharp, clamp(sampleUv + radius, 0.0, 1.0)).rgb * 0.21 +
        texture(uTextureSharp, clamp(sampleUv - radius, 0.0, 1.0)).rgb * 0.21;
}

float sampleSubject(vec2 sampleUv) {
    vec2 stepSize = 2.0 / vec2(textureSize(uSubjectMask, 0));
    float mask = texture(uSubjectMask, sampleUv).r;
    mask = max(
        mask,
        texture(
            uSubjectMask,
            clamp(sampleUv + vec2(stepSize.x, 0.0), 0.0, 1.0)
        ).r
    );
    mask = max(
        mask,
        texture(
            uSubjectMask,
            clamp(sampleUv - vec2(stepSize.x, 0.0), 0.0, 1.0)
        ).r
    );
    mask = max(
        mask,
        texture(
            uSubjectMask,
            clamp(sampleUv + vec2(0.0, stepSize.y), 0.0, 1.0)
        ).r
    );
    mask = max(
        mask,
        texture(
            uSubjectMask,
            clamp(sampleUv - vec2(0.0, stepSize.y), 0.0, 1.0)
        ).r
    );
    return mask;
}

vec3 sampleStaticAtmosphereGlass() {
    float count = max(1.0, floor(uGlassLineCount + 0.5));
    float screenX = clamp(vEffectCoord.x, 0.0, 0.999999);
    float lanePosition = screenX * count;
    float localRib = fract(lanePosition);
    float wave = sin(TWO_PI * localRib);
    float profileExponent = mix(
        1.80,
        0.25,
        clamp(uGlassLineThickness, 0.0, 1.0)
    );
    float profile = sign(wave) * pow(abs(wave), profileExponent);

    float scrollWindow = uScrollWindowX <= 0.0 ? 1.0 : uScrollWindowX;
    float displacement = profile * (1.08 * scrollWindow / count);
    vec2 glassUv = vec2(
        clamp(vTexCoord.x + displacement, 0.0, 1.0),
        vTexCoord.y
    );
    vec2 texel = 1.0 / vec2(textureSize(uTextureSharp, 0));
    vec3 sharpColor = texture(uTextureSharp, vTexCoord).rgb;
    vec3 refractedColor = texture(uTextureSharp, glassUv).rgb;
    vec3 glassColor = mix(
        refractedColor,
        sampleGlassSoftened(glassUv, texel),
        0.72
    );
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

    float backgroundCoverage = 1.0;
    if (uBackgroundOnly > 0.5) {
        if (uHasSubject > 0.5) {
            float subject = max(
                sampleSubject(vTexCoord),
                sampleSubject(glassUv)
            );
            backgroundCoverage = 1.0 - smoothstep(0.30, 0.72, subject);
        } else {
            // No subject mask (extraction failed/ambiguous/pending): nothing
            // is known to protect, so don't withhold the effect from the
            // whole frame — that reads as "the whole photo is the subject".
            backgroundCoverage = 1.0;
        }
    }
    return mix(sharpColor, glassColor, backgroundCoverage);
}

vec3 adjustColor(vec3 color) {
    color = (color - 0.5) * max(uContrast, 0.0) + 0.5;
    float luminance = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(luminance), color, max(uSaturation, 0.0));
    return clamp(color, 0.0, 1.0);
}

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
vec3 clockGlass(vec3 color, vec2 clockUv, vec4 clockSample, vec2 rectSize, float opacity) {
    float body = clockSample.a;
    if (body <= 0.003) return color;
    vec2 texel = 1.0 / vec2(textureSize(uClockTexture, 0));
    const float bevel = 9.0;
    vec2 dx = vec2(texel.x * bevel, 0.0);
    vec2 dy = vec2(0.0, texel.y * bevel);
    // Alpha rises into the glyph, so this points inwards; the outward
    // surface normal tilts the opposite way.
    vec2 slope = 0.5 * vec2(
        texture(uClockTexture, clamp(clockUv + dx, 0.0, 1.0)).a -
            texture(uClockTexture, clamp(clockUv - dx, 0.0, 1.0)).a,
        texture(uClockTexture, clamp(clockUv + dy, 0.0, 1.0)).a -
            texture(uClockTexture, clamp(clockUv - dy, 0.0, 1.0)).a
    );
    float edge = clamp(length(slope) * 2.0, 0.0, 1.0);

    // Refraction: the rim pulls the image outwards, the way a thick rounded
    // edge does. Scaled by the clock's own size so it looks the same at any
    // size the user picks.
    vec2 sampleUv = clamp(vTexCoord - slope * (0.11 * rectSize.y), 0.0, 1.0);
    float frost = 0.0032;
    vec3 refracted = (
        2.0 * texture(uTextureSharp, sampleUv).rgb +
        texture(uTextureSharp, clamp(sampleUv + vec2(frost, 0.0), 0.0, 1.0)).rgb +
        texture(uTextureSharp, clamp(sampleUv - vec2(frost, 0.0), 0.0, 1.0)).rgb +
        texture(uTextureSharp, clamp(sampleUv + vec2(0.0, frost), 0.0, 1.0)).rgb +
        texture(uTextureSharp, clamp(sampleUv - vec2(0.0, frost), 0.0, 1.0)).rgb
    ) / 6.0;
    refracted = clamp(refracted + (color - texture(uTextureSharp, vTexCoord).rgb), 0.0, 1.0);

    // Light from the upper left (texture y grows downwards).
    vec3 normal = normalize(vec3(-slope * 2.4, 1.0));
    vec3 light = normalize(vec3(-0.5, -0.72, 0.48));
    float specular = pow(max(dot(normal, light), 0.0), 22.0) * edge;
    float rim = smoothstep(0.12, 0.85, edge);
    float shade = max(-dot(normal.xy, light.xy), 0.0) * edge;

    vec3 tint = clockSample.rgb / max(clockSample.a, 0.001);
    vec3 glass = refracted * 1.05 + vec3(0.035);
    // Coloured glass: the chosen colour tints what shows through, while the
    // rim and the specular stay white the way real glass reflects. At the old
    // 0.16 the colour was barely visible, so picking one looked like it did
    // nothing at all.
    glass = mix(glass, glass * tint, 0.55);
    glass += vec3(rim * 0.20 + specular * 0.9);
    glass -= vec3(shade * 0.14);
    return mix(color, clamp(glass, 0.0, 1.0), body * opacity);
}

void main() {
    float t = uBlurStrength;
    vec2 uv = vTexCoord;
    uv.x *= uAspectRatio;

    vec3 cloudSum = vec3(0.0);
    float cloudWeight = 0.0;

    for(int i = 0; i < MAX_BLOBS; i++) {
        if (i >= uBlobCount) break;
        vec2 pos = uBlobPositions[i];
        pos.x *= uAspectRatio;
        float dist = length(uv - pos);

        float w = uBlobSizes[i] / (pow(dist, 2.0) + 0.05);
        cloudSum += adjustColor(uBlobColors[i]) * w;
        cloudWeight += w;
    }

    vec3 muddyBackground = vec3(0.0);
    if (cloudWeight > 0.0) {
        muddyBackground = cloudSum / cloudWeight;
    }

    float blurPhase = smoothstep(0.0, 0.2, t);
    float cloudMorph = smoothstep(0.18, 0.5, t);

    vec3 sharp = texture(uTextureSharp, vTexCoord).rgb;
    if (uAtmosphereGlassEnabled > 0.5) {
        sharp = sampleStaticAtmosphereGlass();
    }
    vec3 frosted = texture(uTextureBlur, vTexCoord).rgb;

    vec3 currentBg = mix(sharp, frosted, blurPhase);

    if (t > 0.18) {
        currentBg = mix(currentBg, muddyBackground, cloudMorph);
    }

    vec3 finalColor = currentBg;

    float blobOpacity = smoothstep(0.15, 0.3, t);

    if (blobOpacity > 0.01 && uBlobCount > 0) {
        for(int i = 0; i < MAX_BLOBS; i++) {
            if (i >= uBlobCount) break;

            vec2 pos = uBlobPositions[i];
            pos.x *= uAspectRatio;

            vec2 delta = uv - pos;
            float dist = length(delta);
            float radius = uBlobSizes[i];

            float effectiveRadius = radius;
            float alpha = 1.0 - smoothstep(0.0, effectiveRadius, dist);

            alpha *= blobOpacity;

            if (alpha > 0.0) {
                finalColor = mix(finalColor, adjustColor(uBlobColors[i]), alpha);
            }
        }
    }

    finalColor = mix(finalColor, vec3(0.0), uDimLevel * t);

    if (uEnableNoise > 0.5) {
        vec2 grainUV = floor(uv * uNoiseScale);
        float noise = random(grainUV);
        float noiseVisibility = smoothstep(0.4, 1.0, t);
        finalColor += vec3(noise * uNoiseStrength * noiseVisibility);
    }

    // Clock overlay. Composited after everything else so the effect never
    // washes it out, then — when depth is on — the sharp subject is drawn
    // back over the top, which is what sells "the clock is behind them".
    if (uClockEnabled > 0.5 && uClockOpacity > 0.0) {
        vec3 beforeClock = finalColor;
        vec2 clockUv = (vEffectCoord - uClockRect.xy) / max(uClockRect.zw, vec2(1e-5));
        if (
            clockUv.x >= 0.0 && clockUv.x <= 1.0 &&
            clockUv.y >= 0.0 && clockUv.y <= 1.0
        ) {
            vec4 clockSample = texture(uClockTexture, clockUv);
            if (uClockGlass > 0.5) {
                finalColor = clockGlass(
                    finalColor, clockUv, clockSample, uClockRect.zw, uClockOpacity
                );
            } else {
                finalColor = mix(
                    finalColor,
                    clockSample.rgb,
                    clockSample.a * uClockOpacity
                );
            }
        }

        if (uClockDepth > 0.5) {
            float subjectCoverage = smoothstep(0.30, 0.72, sampleSubject(vTexCoord));
            // Restores the frame as it was before the clock, so depth only
            // changes pixels the clock touched. It used to mix in the sharp
            // photo, which re-sharpened the subject across the whole screen
            // while the clock faded during the unlock.
            finalColor = mix(finalColor, beforeClock, subjectCoverage * uClockOpacity);
        }
    }

    fragColor = vec4(finalColor, 1.0);
}
