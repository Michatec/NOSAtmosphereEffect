#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform float uBlurStrength; // 1.0 (Locked/B&W) -> 0.0 (Unlocked/Color)
uniform vec2 uOrigin;        // Fingerprint location (X, Y)
uniform float uAspectRatio;
uniform float uDimLevel;

void main() {
    // 1. Get original color and calculate Grayscale
    vec4 color = texture(uTextureSharp, vTexCoord);
    float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    vec3 bwColor = vec3(gray);

    // 2. Adjust coordinates for aspect ratio to make a perfect circle
    vec2 uv = vTexCoord;
    uv.x *= uAspectRatio;
    vec2 origin = uOrigin;
    origin.x *= uAspectRatio;

    float dist = distance(uv, origin);

    // 3. Create the "Water Flow" ripple distortion on the edge
    float time = uBlurStrength * 10.0;
    float distortion = sin(uv.x * 20.0 + time) * cos(uv.y * 20.0 - time) * 0.03;
    distortion += sin(uv.x * 40.0 - time) * cos(uv.y * 40.0 + time) * 0.015;

    dist += distortion; // Apply water ripple to the distance

    // 4. Calculate expansion radius
    float maxDist = 1.5; // Enough to cover corners of the screen
    float currentRadius = (1.0 - uBlurStrength) * maxDist;

    // 5. Smoothly transition between B&W and Color at the water edge
    float edge = smoothstep(currentRadius - 0.05, currentRadius + 0.05, dist);
    vec3 finalColor = mix(color.rgb, bwColor, edge);

    // 6. Apply standard lock-screen dimming
    finalColor *= mix(1.0, 1.0 - uDimLevel, uBlurStrength);

    fragColor = vec4(finalColor, color.a);
}