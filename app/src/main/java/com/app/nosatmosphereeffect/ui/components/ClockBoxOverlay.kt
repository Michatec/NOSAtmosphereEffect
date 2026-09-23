package com.app.nosatmosphereeffect.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.helper.ClockBoxHandle
import com.app.nosatmosphereeffect.helper.ClockBoxRect
import kotlin.math.abs

/**
 * The frame the user drags to place and size the clock.
 *
 * Replaces the size / width / height sliders: the clock is a rectangle on a
 * photo, so it is set like one — drag inside to move it, drag a corner to
 * change both dimensions, drag an edge to change one. The proposed rectangle
 * is handed back unclamped; the caller owns the limits because it is the one
 * that has to store them.
 *
 * All rectangles are fractions of this view, matching how the clock is stored
 * and how the shaders place it.
 */
@Composable
internal fun ClockBoxOverlay(
    box: ClockBoxRect,
    /** True when the clock is exactly centred, which lights the centre guide. */
    centered: Boolean,
    showHandles: Boolean,
    onBoxChange: (ClockBoxRect, ClockBoxHandle) -> Unit,
    onDragStarted: () -> Unit,
    onDragFinished: () -> Unit,
    onTap: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val touchSlopPx = with(density) { HANDLE_TOUCH_DP.dp.toPx() }
    val handleRadiusPx = with(density) { HANDLE_RADIUS_DP.dp.toPx() }
    // The gesture detector is installed once and reads everything it needs
    // through these. Keying pointerInput on the box restarted the detector on
    // every movement, so each swipe produced one small step and then had to
    // clear the touch slop all over again.
    val currentBox by rememberUpdatedState(box)
    val boxChanged by rememberUpdatedState(onBoxChange)
    val dragStarted by rememberUpdatedState(onDragStarted)
    val dragFinished by rememberUpdatedState(onDragFinished)
    val tapped by rememberUpdatedState(onTap)

    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { tapped(it) }
            }
            .pointerInput(Unit) {
                var handle = ClockBoxHandle.MOVE
                var startBox = ClockBoxRect(0f, 0f, 0f, 0f)
                var travelled = Offset.Zero
                detectDragGestures(
                    onDragStart = { position ->
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        startBox = currentBox
                        travelled = Offset.Zero
                        handle = handleAt(position, startBox, width, height, touchSlopPx)
                        dragStarted()
                    },
                    onDragEnd = { dragFinished() },
                    onDragCancel = { dragFinished() }
                ) { change, drag ->
                    change.consume()
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    if (width <= 0f || height <= 0f) return@detectDragGestures
                    // Applied to where the box was when the finger went down,
                    // not to wherever it is now: pointer events arrive faster
                    // than recomposition, and chaining deltas onto a box that
                    // has not caught up yet drops movement.
                    travelled += drag
                    boxChanged(
                        startBox.movedBy(handle, travelled.x / width, travelled.y / height),
                        handle
                    )
                }
            }
    ) {
        drawCentreGuide(currentBox, centered)
        drawBox(currentBox, showHandles, handleRadiusPx)
    }
}

/** The proposed rectangle after dragging [handle] by a fraction of the view. */
private fun ClockBoxRect.movedBy(handle: ClockBoxHandle, dx: Float, dy: Float): ClockBoxRect = when (handle) {
    ClockBoxHandle.MOVE -> ClockBoxRect(left + dx, top + dy, right + dx, bottom + dy)
    // Every resize anchors the opposite side, so the corner under the finger
    // is the one that moves.
    ClockBoxHandle.TOP_LEFT -> ClockBoxRect(left + dx, top + dy, right, bottom)
    ClockBoxHandle.TOP_RIGHT -> ClockBoxRect(left, top + dy, right + dx, bottom)
    ClockBoxHandle.BOTTOM_LEFT -> ClockBoxRect(left + dx, top, right, bottom + dy)
    ClockBoxHandle.BOTTOM_RIGHT -> ClockBoxRect(left, top, right + dx, bottom + dy)
    ClockBoxHandle.LEFT -> ClockBoxRect(left + dx, top, right, bottom)
    ClockBoxHandle.RIGHT -> ClockBoxRect(left, top, right + dx, bottom)
    ClockBoxHandle.TOP -> ClockBoxRect(left, top + dy, right, bottom)
    ClockBoxHandle.BOTTOM -> ClockBoxRect(left, top, right, bottom + dy)
}

private fun handleAt(
    position: Offset,
    box: ClockBoxRect,
    viewWidth: Float,
    viewHeight: Float,
    slop: Float
): ClockBoxHandle {
    if (viewWidth <= 0f || viewHeight <= 0f) return ClockBoxHandle.MOVE
    val left = box.left * viewWidth
    val right = box.right * viewWidth
    val top = box.top * viewHeight
    val bottom = box.bottom * viewHeight
    val nearLeft = abs(position.x - left) <= slop
    val nearRight = abs(position.x - right) <= slop
    val nearTop = abs(position.y - top) <= slop
    val nearBottom = abs(position.y - bottom) <= slop
    val withinRows = position.y >= top - slop && position.y <= bottom + slop
    val withinColumns = position.x >= left - slop && position.x <= right + slop

    return when {
        nearLeft && nearTop -> ClockBoxHandle.TOP_LEFT
        nearRight && nearTop -> ClockBoxHandle.TOP_RIGHT
        nearLeft && nearBottom -> ClockBoxHandle.BOTTOM_LEFT
        nearRight && nearBottom -> ClockBoxHandle.BOTTOM_RIGHT
        nearLeft && withinRows -> ClockBoxHandle.LEFT
        nearRight && withinRows -> ClockBoxHandle.RIGHT
        nearTop && withinColumns -> ClockBoxHandle.TOP
        nearBottom && withinColumns -> ClockBoxHandle.BOTTOM
        else -> ClockBoxHandle.MOVE
    }
}

private fun DrawScope.drawCentreGuide(box: ClockBoxRect, centered: Boolean) {
    val x = size.width / 2f
    val color = if (centered) CENTRE_SNAPPED_COLOR else CENTRE_GUIDE_COLOR
    drawLine(
        color = color,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = if (centered) 2f else 1f
    )
    if (!centered) return
    // A short tick at the clock's own centre confirms the snap.
    val y = (box.top + box.bottom) / 2f * size.height
    drawLine(
        color = color,
        start = Offset(x - size.width * 0.04f, y),
        end = Offset(x + size.width * 0.04f, y),
        strokeWidth = 2f
    )
}

private fun DrawScope.drawBox(box: ClockBoxRect, showHandles: Boolean, handleRadius: Float) {
    val left = box.left * size.width
    val top = box.top * size.height
    val width = box.width * size.width
    val height = box.height * size.height
    if (width <= 0f || height <= 0f) return

    drawRect(
        color = BOX_COLOR,
        topLeft = Offset(left, top),
        size = Size(width, height),
        style = Stroke(width = 2f)
    )
    if (!showHandles) return

    val right = left + width
    val bottom = top + height
    val centreX = left + width / 2f
    val centreY = top + height / 2f
    listOf(
        Offset(left, top),
        Offset(right, top),
        Offset(left, bottom),
        Offset(right, bottom)
    ).forEach { corner ->
        drawCircle(color = HANDLE_FILL, radius = handleRadius, center = corner)
        drawCircle(
            color = HANDLE_BORDER,
            radius = handleRadius,
            center = corner,
            style = Stroke(width = 2f)
        )
    }
    listOf(
        Offset(centreX, top),
        Offset(centreX, bottom),
        Offset(left, centreY),
        Offset(right, centreY)
    ).forEach { edge ->
        drawCircle(color = HANDLE_FILL, radius = handleRadius * 0.62f, center = edge)
    }
}

private const val HANDLE_TOUCH_DP = 28
private const val HANDLE_RADIUS_DP = 7
private val BOX_COLOR = Color.White.copy(alpha = 0.85f)
private val HANDLE_FILL = Color.White.copy(alpha = 0.95f)
private val HANDLE_BORDER = Color.Black.copy(alpha = 0.35f)
private val CENTRE_GUIDE_COLOR = Color.White.copy(alpha = 0.28f)
private val CENTRE_SNAPPED_COLOR = Color(0xFF7FD1FF)
