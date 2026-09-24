#version 450

layout(set = 0, binding = 0) uniform sampler2D wallpaperTexture;
layout(set = 0, binding = 1) uniform sampler2D clockTexture;
layout(set = 0, binding = 2) uniform sampler2D clockSubjectMask;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec2 vEffectCoord;
layout(location = 0) out vec4 fragColor;

layout(push_constant) uniform ColorFillParams {
    vec4 render;
    vec4 position;
    // Appended after the existing vec4s so none of the offsets above shift.
    //
    // clockRect: centerX, top, widthFraction, heightFraction — all in the
    // screen-locked vEffectCoord space. Unlike the Atmosphere shader, which
    // is handed the face's own texture aspect and divides by the surface
    // aspect here, the width arrives already divided: these effects have no
    // surface-aspect field in their push constants, and the JNI knows the
    // surface aspect anyway, so doing the division there costs nothing and
    // keeps the shader free of geometry it would otherwise need a new
    // parameter to compute.
    //
    // clockMeta: opacity (with the lock/home fade already folded in by the
    // host), "a face has been uploaded", "depth enabled AND a subject mask
    // exists", unused.
    vec4 clockRect;
    vec4 clockMeta;
} params;

// Mirrors the GLES path in assets/shaders/colorfill/bw_to_color.frag; keep the two in step.
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

    // Frost scatters what comes through: a wider disc as it rises, and a
    // milkier, flatter transmission with it.
    float blur = mix(0.0012, 0.0110, frostLevel);
    float diagonal = blur * 0.7;
    vec3 refracted = (
        2.0 * texture(wallpaperTexture, sampleUv).rgb +
        texture(wallpaperTexture, clamp(sampleUv + vec2(blur, 0.0), 0.0, 1.0)).rgb +
        texture(wallpaperTexture, clamp(sampleUv - vec2(blur, 0.0), 0.0, 1.0)).rgb +
        texture(wallpaperTexture, clamp(sampleUv + vec2(0.0, blur), 0.0, 1.0)).rgb +
        texture(wallpaperTexture, clamp(sampleUv - vec2(0.0, blur), 0.0, 1.0)).rgb +
        texture(wallpaperTexture, clamp(sampleUv + vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
        texture(wallpaperTexture, clamp(sampleUv - vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
        texture(wallpaperTexture, clamp(sampleUv + vec2(diagonal, -diagonal), 0.0, 1.0)).rgb +
        texture(wallpaperTexture, clamp(sampleUv - vec2(diagonal, -diagonal), 0.0, 1.0)).rgb
    ) / 10.0;
    // Whatever the effect did to the wallpaper behind the clock applies to
    // what shows through it too.
    refracted = clamp(refracted + (color - texture(wallpaperTexture, vTexCoord).rgb), 0.0, 1.0);
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

// Subject mask for the clock's depth effect. These effects have no "background
// only" mode of their own, so this binding exists purely for the clock;
// clockMeta.z is 0 whenever no real mask has been uploaded, and the sampler is
// then never read. Optional binding, so until the first upload it holds the
// engine's 1x1 clear texture.
float clockSubjectCoverage(vec2 uv) {
    vec2 stepSize = 2.0 / vec2(textureSize(clockSubjectMask, 0));
    float mask = texture(clockSubjectMask, uv).r;
    mask = max(
        mask,
        texture(clockSubjectMask, clamp(uv + vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(clockSubjectMask, clamp(uv - vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(clockSubjectMask, clamp(uv + vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(clockSubjectMask, clamp(uv - vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    return smoothstep(0.30, 0.72, mask);
}

// Draws the subject back over the clock, so the clock reads as sitting behind
// them.
//
// subjectColor is the frame as it looked BEFORE the clock was composited, not
// the untouched photo. Atmosphere, Glass and Halftone use the sharp photo
// because their backgrounds are blurred or stylised, so a sharp subject reads
// as depth. Here it would read as a cut-out instead: a full-colour subject
// over Colour Fill's monochrome end, or a photographic subject over Sketch's
// line art. Re-drawing what the effect already produced keeps the subject
// looking like the rest of the frame, which is what sells the occlusion.
// Mirrors the GLES path in assets/shaders/colorfill/bw_to_color.frag; keep the two in step.
vec3 applyClockDepth(vec3 color, vec3 subjectColor, vec2 maskUv) {
    if (params.clockMeta.y <= 0.5 || params.clockMeta.z <= 0.5) return color;
    return mix(
        color,
        subjectColor,
        clockSubjectCoverage(maskUv) * params.clockMeta.x
    );
}

float hash(vec2 point) {
    point = fract(point * vec2(123.34, 345.45));
    point += dot(point, point + 34.345);
    return fract(point.x * point.y);
}

float valueNoise(vec2 point) {
    vec2 cell = floor(point);
    vec2 local = fract(point);
    local = local * local * (3.0 - 2.0 * local);
    float a = hash(cell);
    float b = hash(cell + vec2(1.0, 0.0));
    float c = hash(cell + vec2(0.0, 1.0));
    float d = hash(cell + vec2(1.0, 1.0));
    return mix(mix(a, b, local.x), mix(c, d, local.x), local.y);
}

float fbm(vec2 point) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int octave = 0; octave < 5; octave++) {
        value += amplitude * valueNoise(point);
        point = point * 2.03 + 7.1;
        amplitude *= 0.5;
    }
    return value;
}

const vec2 DROP_DIRECTION[5] = vec2[5](
    vec2(0.80, 0.60),
    vec2(-0.55, 0.84),
    vec2(0.28, -0.96),
    vec2(-0.90, -0.30),
    vec2(0.97, 0.05)
);
const float DROP_DISTANCE[5] = float[5](0.52, 0.66, 0.60, 0.74, 0.83);
const float DROP_SIZE[5] = float[5](0.11, 0.08, 0.13, 0.07, 0.06);

float paintCoverage(
    vec2 uv,
    vec2 origin,
    float aspect,
    float progress,
    out float rim
) {
    rim = 0.0;
    if (progress <= 0.002) return 0.0;
    if (progress >= 0.998) return 1.0;

    float reach = 0.0;
    reach = max(reach, distance(origin, vec2(0.0, 0.0)));
    reach = max(reach, distance(origin, vec2(aspect, 0.0)));
    reach = max(reach, distance(origin, vec2(0.0, 1.0)));
    reach = max(reach, distance(origin, vec2(aspect, 1.0)));

    vec2 delta = uv - origin;
    float distanceFromOrigin = length(delta);
    float angle = atan(delta.y, delta.x);
    vec2 circle = vec2(cos(angle), sin(angle));
    float radius = progress * reach * 1.42;
    float lobe = fbm(circle * 2.1 + vec2(9.0, progress * 1.2));
    float fingers = fbm(uv * 7.5 + circle * 1.7);
    float front = radius * (0.74 + 0.34 * lobe) +
        (fingers - 0.5) * 0.13 * reach;
    float antialiasWidth = mix(0.06, 0.012, progress) * (reach + 0.25);
    float coverage = 1.0 - smoothstep(
        front - antialiasWidth,
        front + antialiasWidth,
        distanceFromOrigin
    );

    for (int drop = 0; drop < 5; drop++) {
        float distanceFraction = DROP_DISTANCE[drop];
        vec2 center = origin + DROP_DIRECTION[drop] * (distanceFraction * reach);
        float appear = smoothstep(
            distanceFraction - 0.20,
            distanceFraction - 0.02,
            progress
        );
        float dropRadius = DROP_SIZE[drop] * reach * appear;
        if (dropRadius > 0.0001) {
            float dropDistance = length(uv - center);
            float noise = (fbm(uv * 11.0 + float(drop) * 3.7) - 0.5) * 0.25;
            coverage = max(
                coverage,
                1.0 - smoothstep(
                    dropRadius * (0.6 + noise),
                    dropRadius,
                    dropDistance
                )
            );
        }
    }

    rim = (
        1.0 - smoothstep(
            0.0,
            antialiasWidth * 3.5,
            abs(distanceFromOrigin - front)
        )
    ) * coverage * (1.0 - progress);
    return clamp(coverage, 0.0, 1.0);
}

void main() {
    vec4 color = texture(wallpaperTexture, vTexCoord);
    float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    vec3 monochrome = vec3(gray);

    float aspect = params.render.z;
    vec2 uv = vTexCoord;
    uv.x *= aspect;
    vec2 origin = params.position.xy;
    origin.x *= aspect;

    bool reverse = params.render.w > 0.5;
    float fillProgress = reverse ? params.render.x : 1.0 - params.render.x;
    float rim;
    float coverage = paintCoverage(
        uv,
        origin,
        aspect,
        fillProgress,
        rim
    );

    vec3 startColor = reverse ? color.rgb : monochrome;
    vec3 endColor = reverse ? monochrome : color.rgb;
    vec3 finalColor = mix(startColor, endColor, coverage);
    finalColor += rim * (reverse ? 0.06 : 0.10);
    finalColor *= mix(1.0, 1.0 - params.render.y, params.render.x);
    vec3 beforeClock = finalColor;
    finalColor = compositeClock(finalColor, vEffectCoord);
    finalColor = applyClockDepth(finalColor, beforeClock, vTexCoord);

    fragColor = vec4(finalColor, color.a);
}
