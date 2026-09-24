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
    vec3 refracted = texture(sharpTexture, sampleUv).rgb;
    if (frostLevel > 0.004) {
        float blur = mix(0.0012, 0.0110, frostLevel);
        float diagonal = blur * 0.7;
        refracted = (
            2.0 * refracted +
            texture(sharpTexture, clamp(sampleUv + vec2(blur, 0.0), 0.0, 1.0)).rgb +
            texture(sharpTexture, clamp(sampleUv - vec2(blur, 0.0), 0.0, 1.0)).rgb +
            texture(sharpTexture, clamp(sampleUv + vec2(0.0, blur), 0.0, 1.0)).rgb +
            texture(sharpTexture, clamp(sampleUv - vec2(0.0, blur), 0.0, 1.0)).rgb +
            texture(sharpTexture, clamp(sampleUv + vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
            texture(sharpTexture, clamp(sampleUv - vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
            texture(sharpTexture, clamp(sampleUv + vec2(diagonal, -diagonal), 0.0, 1.0)).rgb +
            texture(sharpTexture, clamp(sampleUv - vec2(diagonal, -diagonal), 0.0, 1.0)).rgb
        ) / 10.0;
    }
    // Whatever the effect did to the wallpaper behind the clock applies to
    // what shows through it too.
    refracted = clamp(refracted + (color - texture(sharpTexture, vTexCoord).rgb), 0.0, 1.0);

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
                // A flat face: the silhouette still comes from the field.
                float flatSoftness = clamp(fwidth(clockSample.a), 0.0008, 0.25);
                finalColor = mix(
                    finalColor,
                    clockSample.rgb / max(clockSample.a, 0.001),
                    smoothstep(0.5 - flatSoftness, 0.5 + flatSoftness, clockSample.a) *
                        params.clockMeta.x
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
