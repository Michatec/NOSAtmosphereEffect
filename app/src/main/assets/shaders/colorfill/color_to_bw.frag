#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform float uBlurStrength; // 0.0 (Unlocked/Color) -> 1.0 (Locked/B&W)
uniform vec2 uOrigin;
uniform float uAspectRatio;
uniform float uDimLevel;

void main() {
    vec4 color = texture(uTextureSharp, vTexCoord);
    float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    vec3 bwColor = vec3(gray);

    vec2 uv = vTexCoord;
    uv.x *= uAspectRatio;
    vec2 origin = uOrigin;
    origin.x *= uAspectRatio;

    float dist = distance(uv, origin);

    // Water Flow ripple distortion
    float time = uBlurStrength * 10.0;
    float distortion = sin(uv.x * 20.0 + time) * cos(uv.y * 20.0 - time) * 0.03;
    distortion += sin(uv.x * 40.0 - time) * cos(uv.y * 40.0 + time) * 0.015;

    dist += distortion;

    float maxDist = 1.5;
    float currentRadius = uBlurStrength * maxDist; // Radius grows as it locks

    float edge = smoothstep(currentRadius - 0.05, currentRadius + 0.05, dist);

    // Notice the mix is flipped here for the reverse effect
    vec3 finalColor = mix(bwColor, color.rgb, edge);

    finalColor *= mix(1.0, 1.0 - uDimLevel, uBlurStrength);

    fragColor = vec4(finalColor, color.a);
}