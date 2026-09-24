package com.app.nosatmosphereeffect.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.app.nosatmosphereeffect.helper.ClockBoxRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max

/**
 * The clock calibration preview: the wallpaper with the glass clock drawn over
 * it, exactly where the live wallpaper would put it.
 *
 * ## Why this does not reuse the wallpaper renderers
 *
 * It used to run the real effect through EffectPreviewService, which stands up
 * an OpenGL or Vulkan surface, picks a backend, uploads textures and drives a
 * render loop — for a still preview of where some digits sit. Every one of
 * those steps could leave the calibration screen showing nothing, which is
 * exactly what it did. This draws the same glass with a runtime shader on the
 * ordinary view canvas: no surface, no backend choice, no upload path, and no
 * way for the clock to be invisible while the user is positioning it.
 *
 * The maths is a port of `clockGlass` in the effect shaders, so what is shown
 * here is what the wallpaper draws. The backdrop is the plain photo rather
 * than the effect's own output: the clock is positioned against the image, and
 * the effect only changes what is *behind* the glass.
 */
@Composable
internal fun ClockGlassPreview(
    wallpaper: Bitmap?,
    /** The rendered clock face; its alpha is the glyph shape. */
    face: Bitmap?,
    /** Where the face goes, as fractions of this view. */
    box: ClockBoxRect,
    opacity: Float,
    /** How frosted the glass is, 0..1. */
    frost: Float,
    /**
     * False for a flat face, which is drawn as it is rather than as something
     * the wallpaper refracts through.
     */
    glass: Boolean,
    /** Changes whenever [face] has been redrawn in place. */
    faceRevision: Int,
    modifier: Modifier = Modifier
) {
    // Compiled by the driver, so a failure can only show up here. The preview
    // must survive it: falling back to the plain face keeps the clock visible
    // and draggable, which is the whole point of this screen.
    val shader = remember { runCatching { RuntimeShader(GLASS_AGSL) }.getOrNull() }
    val paint = remember { Paint() }
    val photo = wallpaper?.takeIf { !it.isRecycled }
    val glyphs = face?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
    // Built per bitmap rather than per frame, and sampled linearly: the face
    // is scaled down into the box, and point sampling made the digits ragged
    // where the wallpaper's own sampler makes them smooth.
    val wallpaperShader = remember(photo) {
        photo?.let {
            BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                filterMode = BitmapShader.FILTER_MODE_LINEAR
            }
        }
    }
    val faceShader = remember(glyphs) {
        glyphs?.let {
            BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                filterMode = BitmapShader.FILTER_MODE_LINEAR
            }
        }
    }
    val wallpaperMatrix = remember { Matrix() }
    val faceMatrix = remember { Matrix() }

    Canvas(modifier) {
        val viewWidth = size.width
        val viewHeight = size.height
        if (viewWidth <= 0f || viewHeight <= 0f) return@Canvas
        // Read so a redrawn face repaints even though the bitmap is the same
        // instance the renderer keeps reusing.
        @Suppress("UNUSED_EXPRESSION")
        faceRevision

        if (photo == null || wallpaperShader == null) {
            drawRect(color = Color.Black)
            return@Canvas
        }

        // Centre-crop, the way the wallpaper itself is fitted.
        val scale = max(viewWidth / photo.width, viewHeight / photo.height)
        wallpaperMatrix.setScale(scale, scale)
        wallpaperMatrix.postTranslate(
            (viewWidth - photo.width * scale) / 2f,
            (viewHeight - photo.height * scale) / 2f
        )
        wallpaperShader.setLocalMatrix(wallpaperMatrix)

        val boxLeft = box.left * viewWidth
        val boxTop = box.top * viewHeight
        val boxWidth = box.width * viewWidth
        val boxHeight = box.height * viewHeight
        val drawGlass = glass &&
            shader != null &&
            faceShader != null &&
            glyphs != null &&
            boxWidth > 1f &&
            boxHeight > 1f &&
            opacity > 0f

        if (!drawGlass) {
            paint.shader = wallpaperShader
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, viewWidth, viewHeight, paint) }
            // Either the face is solid, or the runtime shader is unavailable
            // and the clock still has to be visible to be positioned.
            if ((!glass || shader == null) &&
                glyphs != null &&
                opacity > 0f &&
                boxWidth > 1f
            ) {
                drawImage(
                    image = glyphs.asImageBitmap(),
                    dstOffset = IntOffset(boxLeft.toInt(), boxTop.toInt()),
                    dstSize = IntSize(
                        boxWidth.toInt().coerceAtLeast(1),
                        boxHeight.toInt().coerceAtLeast(1)
                    ),
                    alpha = opacity.coerceIn(0f, 1f)
                )
            }
            return@Canvas
        }
        checkNotNull(shader)
        checkNotNull(faceShader)
        checkNotNull(glyphs)

        faceMatrix.setScale(boxWidth / glyphs.width, boxHeight / glyphs.height)
        faceMatrix.postTranslate(boxLeft, boxTop)
        faceShader.setLocalMatrix(faceMatrix)

        shader.setInputShader("wallpaper", wallpaperShader)
        shader.setInputShader("face", faceShader)
        shader.setFloatUniform("boxOrigin", boxLeft, boxTop)
        shader.setFloatUniform("boxSize", boxWidth, boxHeight)
        // Measured in face-texture texels, exactly as the wallpaper shader
        // does it, so the bevel is the same width on the same clock rather
        // than a fraction of the box (which made it far softer here).
        // The wallpaper shaders work in texture UV, where x spans the width
        // and y the height, so the same UV step is fewer pixels across than
        // down. These convert each of their offsets into view pixels.
        val refraction = REFRACTION_FRACTION * boxHeight
        shader.setFloatUniform(
            "refraction",
            refraction * viewWidth / viewHeight,
            refraction
        )
        shader.setFloatUniform(
            "magnification",
            MAGNIFICATION * boxWidth,
            MAGNIFICATION * boxHeight
        )
        shader.setFloatUniform("blurMin", FROST_MIN * viewWidth, FROST_MIN * viewHeight)
        shader.setFloatUniform("blurMax", FROST_MAX * viewWidth, FROST_MAX * viewHeight)
        shader.setFloatUniform("frostLevel", frost.coerceIn(0f, 1f))
        shader.setFloatUniform("opacity", opacity.coerceIn(0f, 1f))
        paint.shader = shader
        drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, viewWidth, viewHeight, paint) }
    }
}

/** The refraction offset at the silhouette, as a fraction of the clock's height. */
private const val REFRACTION_FRACTION = 0.030f
/** How much the glass magnifies what is behind it, at the crown of a stroke. */
private const val MAGNIFICATION = 0.060f
/** The frost blur radius in wallpaper UV, at no frost and at full frost. */
private const val FROST_MIN = 0.0012f
private const val FROST_MAX = 0.0110f

private const val GLASS_AGSL = """
uniform shader wallpaper;
uniform shader face;
uniform float2 boxOrigin;
uniform float2 boxSize;
uniform float2 refraction;
uniform float2 magnification;
uniform float2 blurMin;
uniform float2 blurMax;
uniform float frostLevel;
uniform float opacity;

float faceField(float2 coord) {
    return float(face.eval(coord).a);
}

half4 main(float2 coord) {
    float3 base = float3(wallpaper.eval(coord).rgb);
    float2 uv = (coord - boxOrigin) / max(boxSize, float2(1.0));
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        return half4(half3(base), 1.0);
    }

    // A port of clockGlass in the effect shaders; see the comments there. The
    // face texture holds a distance field, so the silhouette is reconstructed
    // here rather than sampled, and the gradient is a true surface direction.
    float4 glyph = float4(face.eval(coord));
    float field = glyph.a;
    // AGSL has no derivatives, so the field's slope per pixel is measured
    // directly — which is the same number fwidth would have returned.
    float2 gradient = 0.5 * float2(
        faceField(coord + float2(1.0, 0.0)) - faceField(coord - float2(1.0, 0.0)),
        faceField(coord + float2(0.0, 1.0)) - faceField(coord - float2(0.0, 1.0))
    );
    float softness = clamp(length(gradient), 0.0008, 0.25);
    float coverage = smoothstep(0.5 - softness, 0.5 + softness, field);
    if (coverage <= 0.002) {
        return half4(half3(base), 1.0);
    }
    float2 inward = gradient / max(length(gradient), 1e-5);

    float depth = clamp((field - 0.5) * 2.0, 0.0, 1.0);
    float shoulder = 1.0 - depth;
    float lift = sqrt(max(1.0 - shoulder * shoulder, 1e-4));
    float slope = min(shoulder / lift, 6.0);
    float3 normal = normalize(float3(-inward * slope * 0.62, 1.0));

    // The shoulder, not the slope: see the note in the effect shaders.
    float2 bend = inward * shoulder * refraction;
    float2 magnify = (uv - 0.5) * magnification * depth;
    float2 at = coord - bend - magnify;

    // Nothing is sampled for frost until there is some — see the effect
    // shaders, which take the same branch.
    float3 refracted = float3(wallpaper.eval(at).rgb);
    if (frostLevel > 0.004) {
        float2 blur = mix(blurMin, blurMax, frostLevel);
        float2 diagonal = blur * 0.7;
        refracted = (
            2.0 * refracted +
            float3(wallpaper.eval(at + float2(blur.x, 0.0)).rgb) +
            float3(wallpaper.eval(at - float2(blur.x, 0.0)).rgb) +
            float3(wallpaper.eval(at + float2(0.0, blur.y)).rgb) +
            float3(wallpaper.eval(at - float2(0.0, blur.y)).rgb) +
            float3(wallpaper.eval(at + diagonal).rgb) +
            float3(wallpaper.eval(at - diagonal).rgb) +
            float3(wallpaper.eval(at + float2(diagonal.x, -diagonal.y)).rgb) +
            float3(wallpaper.eval(at - float2(diagonal.x, -diagonal.y)).rgb)
        ) / 10.0;
    }

    float3 tint = glyph.rgb / max(field, 0.001);
    float3 glass = mix(refracted, refracted * tint, 0.55);
    if (frostLevel > 0.004) {
        float milk = dot(glass, float3(0.2126, 0.7152, 0.0722));
        glass = mix(glass, tint * (0.55 + 0.45 * milk), frostLevel * 0.92);
    }

    float3 key = normalize(float3(-0.45, -0.75, 0.48));
    float3 fill = normalize(float3(0.55, 0.62, 0.55));
    float facing = dot(normal, key);
    float turn = shoulder * shoulder * shoulder;
    float sheen = max(facing, 0.0) * turn;
    float glint = pow(max(facing, 0.0), 22.0) * shoulder;
    float bounce = max(dot(normal, fill), 0.0) * turn;
    float shade = max(-facing, 0.0) * turn;
    float boundary = smoothstep(0.80, 1.0, shoulder);
    float polish = 1.0 - 0.75 * frostLevel;

    glass = glass + float3((sheen * 0.22 + glint * 0.5 + bounce * 0.12 + boundary * 0.16) * polish);
    glass = glass - float3(shade * 0.24 * polish);
    float3 result = mix(base, clamp(glass, 0.0, 1.0), coverage * opacity);
    return half4(half3(result), 1.0);
}
"""
