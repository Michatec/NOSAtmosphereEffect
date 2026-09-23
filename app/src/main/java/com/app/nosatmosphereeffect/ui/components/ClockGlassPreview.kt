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
import androidx.compose.ui.geometry.Rect
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
    box: Rect,
    opacity: Float,
    /** Changes whenever [face] has been redrawn in place. */
    faceRevision: Int,
    modifier: Modifier = Modifier
) {
    // Compiled by the driver, so a failure can only show up here. The preview
    // must survive it: falling back to the plain face keeps the clock visible
    // and draggable, which is the whole point of this screen.
    val shader = remember { runCatching { RuntimeShader(GLASS_AGSL) }.getOrNull() }
    val paint = remember { Paint() }

    Canvas(modifier) {
        val viewWidth = size.width
        val viewHeight = size.height
        if (viewWidth <= 0f || viewHeight <= 0f) return@Canvas
        // Read so a redrawn face repaints even though the bitmap is the same
        // instance the renderer keeps reusing.
        @Suppress("UNUSED_EXPRESSION")
        faceRevision

        val photo = wallpaper?.takeIf { !it.isRecycled }
        if (photo == null) {
            drawRect(color = Color.Black)
            return@Canvas
        }

        val wallpaperShader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        // Centre-crop, the way the wallpaper itself is fitted.
        val scale = max(viewWidth / photo.width, viewHeight / photo.height)
        wallpaperShader.setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate(
                    (viewWidth - photo.width * scale) / 2f,
                    (viewHeight - photo.height * scale) / 2f
                )
            }
        )

        val glyphs = face?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
        if (shader == null) {
            paint.shader = wallpaperShader
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, viewWidth, viewHeight, paint) }
            if (glyphs != null && opacity > 0f) {
                drawImage(
                    image = glyphs.asImageBitmap(),
                    dstOffset = IntOffset(
                        (box.left * viewWidth).toInt(),
                        (box.top * viewHeight).toInt()
                    ),
                    dstSize = IntSize(
                        (box.width * viewWidth).toInt().coerceAtLeast(1),
                        (box.height * viewHeight).toInt().coerceAtLeast(1)
                    ),
                    alpha = opacity.coerceIn(0f, 1f)
                )
            }
            return@Canvas
        }
        val boxLeft = box.left * viewWidth
        val boxTop = box.top * viewHeight
        val boxWidth = box.width * viewWidth
        val boxHeight = box.height * viewHeight
        if (glyphs == null || boxWidth <= 1f || boxHeight <= 1f || opacity <= 0f) {
            paint.shader = wallpaperShader
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, viewWidth, viewHeight, paint) }
            return@Canvas
        }

        val faceShader = BitmapShader(glyphs, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        faceShader.setLocalMatrix(
            Matrix().apply {
                setScale(boxWidth / glyphs.width, boxHeight / glyphs.height)
                postTranslate(boxLeft, boxTop)
            }
        )

        shader.setInputShader("wallpaper", wallpaperShader)
        shader.setInputShader("face", faceShader)
        shader.setFloatUniform("boxOrigin", boxLeft, boxTop)
        shader.setFloatUniform("boxSize", boxWidth, boxHeight)
        // The bevel and the refraction scale with the clock, so a big clock is
        // not a small one with thin edges.
        shader.setFloatUniform("bevel", max(2f, boxHeight * BEVEL_FRACTION))
        shader.setFloatUniform("refraction", boxHeight * REFRACTION_FRACTION)
        shader.setFloatUniform("frost", max(1f, viewHeight * FROST_FRACTION))
        shader.setFloatUniform("opacity", opacity.coerceIn(0f, 1f))
        paint.shader = shader
        drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, viewWidth, viewHeight, paint) }
    }
}

/** Fractions of the clock's height / the screen height; see the effect shaders. */
private const val BEVEL_FRACTION = 0.035f
private const val REFRACTION_FRACTION = 0.11f
private const val FROST_FRACTION = 0.0032f

private const val GLASS_AGSL = """
uniform shader wallpaper;
uniform shader face;
uniform float2 boxOrigin;
uniform float2 boxSize;
uniform float bevel;
uniform float refraction;
uniform float frost;
uniform float opacity;

half4 main(float2 coord) {
    float3 base = float3(wallpaper.eval(coord).rgb);
    float2 uv = (coord - boxOrigin) / max(boxSize, float2(1.0));
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        return half4(half3(base), 1.0);
    }
    float4 glyph = float4(face.eval(coord));
    float body = glyph.a;
    if (body <= 0.003) {
        return half4(half3(base), 1.0);
    }

    // Alpha rises into the glyph, so this points inwards; the outward surface
    // normal tilts the opposite way.
    float2 slope = 0.5 * float2(
        float(face.eval(coord + float2(bevel, 0.0)).a) -
            float(face.eval(coord - float2(bevel, 0.0)).a),
        float(face.eval(coord + float2(0.0, bevel)).a) -
            float(face.eval(coord - float2(0.0, bevel)).a)
    );
    float edge = clamp(length(slope) * 2.0, 0.0, 1.0);

    float2 at = coord - slope * refraction;
    float3 refracted = (
        2.0 * float3(wallpaper.eval(at).rgb) +
        float3(wallpaper.eval(at + float2(frost, 0.0)).rgb) +
        float3(wallpaper.eval(at - float2(frost, 0.0)).rgb) +
        float3(wallpaper.eval(at + float2(0.0, frost)).rgb) +
        float3(wallpaper.eval(at - float2(0.0, frost)).rgb)
    ) / 6.0;

    // Light from the upper left (y grows downwards).
    float3 normal = normalize(float3(-slope * 2.4, 1.0));
    float3 light = normalize(float3(-0.5, -0.72, 0.48));
    float specular = pow(max(dot(normal, light), 0.0), 22.0) * edge;
    float rim = smoothstep(0.12, 0.85, edge);
    float shade = max(-dot(normal.xy, light.xy), 0.0) * edge;

    float3 tint = glyph.rgb / max(glyph.a, 0.001);
    float3 glass = refracted * 1.05 + float3(0.035);
    glass = mix(glass, glass * tint, 0.16);
    glass = glass + float3(rim * 0.20 + specular * 0.9);
    glass = glass - float3(shade * 0.14);
    float3 result = mix(base, clamp(glass, 0.0, 1.0), body * opacity);
    return half4(half3(result), 1.0);
}
"""
