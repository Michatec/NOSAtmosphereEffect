package com.app.nosatmosphereeffect.helper

import kotlin.math.abs

/** Which part of the clock's box a drag grabbed. */
enum class ClockBoxHandle {
    MOVE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM;

    val resizesWidth: Boolean
        get() = this != MOVE && this != TOP && this != BOTTOM

    val resizesHeight: Boolean
        get() = this != MOVE && this != LEFT && this != RIGHT
}

/** A rectangle in screen fractions. Deliberately not Compose's Rect: this is testable. */
data class ClockBoxRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
}

/** The clock's stored placement. */
data class ClockPlacement(
    val centerX: Float,
    val top: Float,
    val height: Float,
    val widthScale: Float
)

/**
 * Turns box drags into stored placement, and back.
 *
 * ## Why this is a pure object with tests
 *
 * Two rectangles are in play — the face texture (what is stored and what the
 * shaders place) and the digits inside it (what the user sees and drags) — and
 * the conversion runs both ways on every frame of a gesture. Doing that inline
 * in the calibration screen produced a resize that also nudged the clock and a
 * move that reset its size, neither of which is visible in a code review of
 * the screen. Here each rule is one function and each one has a test.
 *
 * Every gesture is resolved against the placement as it was when the finger
 * went down, so a drag cannot accumulate rounding drift or react to its own
 * clamping.
 */
object ClockBoxPlacement {

    /** Snap to the screen centre within this fraction while moving. */
    const val CENTRE_SNAP = 0.015f

    /** The whole face texture, in screen fractions. */
    fun textureBox(
        placement: ClockPlacement,
        faceAspect: Float,
        screenAspect: Float
    ): ClockBoxRect {
        val height = placement.height
        val width = if (screenAspect > 0f && faceAspect > 0f) {
            height * faceAspect * placement.widthScale / screenAspect
        } else {
            height
        }
        return ClockBoxRect(
            left = placement.centerX - width / 2f,
            top = placement.top,
            right = placement.centerX + width / 2f,
            bottom = placement.top + height
        )
    }

    /**
     * The digits' own frame: what the calibration screen draws and drags. The
     * texture carries margin for the digit animation, so the two differ.
     */
    fun contentBox(
        placement: ClockPlacement,
        faceAspect: Float,
        content: ClockFaceBox,
        screenAspect: Float
    ): ClockBoxRect {
        val texture = textureBox(placement, faceAspect, screenAspect)
        return ClockBoxRect(
            left = texture.left + content.left * texture.width,
            top = texture.top + content.top * texture.height,
            right = texture.left + content.right * texture.width,
            bottom = texture.top + content.bottom * texture.height
        )
    }

    /**
     * The placement after dragging [handle] so the digits' frame becomes
     * [proposed], starting from [start].
     *
     * The side opposite the handle stays exactly where it was, a move keeps
     * the size to the float, and a resize never moves the clock sideways
     * unless the handle itself is horizontal.
     */
    fun apply(
        start: ClockPlacement,
        proposed: ClockBoxRect,
        handle: ClockBoxHandle,
        faceAspect: Float,
        content: ClockFaceBox,
        screenAspect: Float
    ): ClockPlacement {
        if (screenAspect <= 0f || faceAspect <= 0f) return start
        val startContent = contentBox(start, faceAspect, content, screenAspect)
        val contentWidthFraction = (content.right - content.left).coerceIn(0.05f, 1f)
        val contentHeightFraction = (content.bottom - content.top).coerceIn(0.05f, 1f)

        val wantedContentHeight =
            if (handle.resizesHeight) proposed.height else startContent.height
        val wantedContentWidth =
            if (handle.resizesWidth) proposed.width else startContent.width

        // A move must not touch the size at all, so it keeps the stored
        // numbers rather than reconstructing them from the box.
        val height = if (handle.resizesHeight) {
            AtmosphereClockPolicy.sanitizeHeight(wantedContentHeight / contentHeightFraction)
        } else {
            start.height
        }
        val widthScale = if (handle.resizesWidth) {
            AtmosphereClockPolicy.sanitizeAxisScale(
                (wantedContentWidth / contentWidthFraction) * screenAspect /
                    (height * faceAspect)
            )
        } else if (handle.resizesHeight) {
            // A vertical-only resize keeps the shape: the stored width scale
            // is relative to the height, so it needs rescaling to hold the
            // digits' width steady.
            AtmosphereClockPolicy.sanitizeAxisScale(
                start.widthScale * start.height / height
            )
        } else {
            start.widthScale
        }

        val settled = ClockPlacement(
            centerX = start.centerX,
            top = start.top,
            height = height,
            widthScale = widthScale
        )
        val settledContent = contentBox(settled, faceAspect, content, screenAspect)

        val contentLeft = when (handle) {
            ClockBoxHandle.MOVE -> proposed.left
            ClockBoxHandle.TOP_LEFT, ClockBoxHandle.BOTTOM_LEFT, ClockBoxHandle.LEFT ->
                startContent.right - settledContent.width
            ClockBoxHandle.TOP_RIGHT, ClockBoxHandle.BOTTOM_RIGHT, ClockBoxHandle.RIGHT ->
                startContent.left
            // Vertical-only handles keep the clock centred where it was.
            else -> startContent.centerX - settledContent.width / 2f
        }
        val contentTop = when (handle) {
            ClockBoxHandle.MOVE -> proposed.top
            ClockBoxHandle.TOP_LEFT, ClockBoxHandle.TOP_RIGHT, ClockBoxHandle.TOP ->
                startContent.bottom - settledContent.height
            else -> startContent.top
        }

        // Back out to the texture rectangle, which is what gets stored.
        val textureWidth = settledContent.width / contentWidthFraction
        val textureHeight = settledContent.height / contentHeightFraction
        val centerX = contentLeft - content.left * textureWidth + textureWidth / 2f
        val top = contentTop - content.top * textureHeight

        return ClockPlacement(
            centerX = AtmosphereClockPolicy.sanitizeCenterX(
                // Only a move snaps: snapping mid-resize would slide the clock
                // sideways while the user is only changing its size.
                if (handle == ClockBoxHandle.MOVE && abs(centerX - 0.5f) < CENTRE_SNAP) {
                    0.5f
                } else {
                    centerX
                }
            ),
            top = AtmosphereClockPolicy.sanitizeTop(top),
            height = height,
            widthScale = widthScale
        )
    }

    /** True when [placement] sits exactly on the screen's centre line. */
    fun isCentred(placement: ClockPlacement): Boolean = abs(placement.centerX - 0.5f) < 0.001f
}
