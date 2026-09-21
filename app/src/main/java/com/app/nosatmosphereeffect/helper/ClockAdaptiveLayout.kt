package com.app.nosatmosphereeffect.helper

/**
 * Where the photo's subject starts, column by column, in screen space.
 *
 * [subjectTops] holds, for each of its equal-width vertical strips across the
 * screen, the highest point (0 = top, 1 = bottom) that belongs to the
 * subject; 1 means the strip has no subject at all. That is all the adaptive
 * clock needs to know about a mask, and it is small enough to cache per
 * wallpaper and hand to every engine.
 */
class SubjectProfile(val subjectTops: FloatArray) {
    init {
        require(subjectTops.isNotEmpty()) { "A subject profile needs at least one column" }
    }

    /** Highest subject point across the horizontal span [left, right]. */
    fun topWithin(left: Float, right: Float): Float {
        val columns = subjectTops.size
        val first = (left.coerceIn(0f, 1f) * columns).toInt().coerceIn(0, columns - 1)
        val last = (right.coerceIn(0f, 1f) * columns).toInt().coerceIn(0, columns - 1)
        var top = 1f
        for (column in first..last) {
            if (subjectTops[column] < top) top = subjectTops[column]
        }
        return top
    }

    fun encode(): String = subjectTops.joinToString(",") { "%.4f".format(java.util.Locale.ROOT, it) }

    companion object {
        fun decode(encoded: String?): SubjectProfile? {
            if (encoded.isNullOrBlank()) return null
            val values = encoded.split(',').mapNotNull { it.toFloatOrNull() }
            if (values.isEmpty() || values.any { !it.isFinite() }) return null
            return SubjectProfile(values.map { it.coerceIn(0f, 1f) }.toFloatArray())
        }
    }
}

/**
 * One UI-style adaptive clock sizing: the largest size, up to the user's own,
 * at which the digits stay clear of the subject.
 *
 * The clock keeps its top edge and horizontal centre and only shrinks, so it
 * never appears to jump — it makes room. Width follows height (the face keeps
 * its proportions), which is why this is a search rather than a formula: a
 * smaller clock is also narrower, and a narrower clock may clear a shoulder
 * that a wider one would have touched.
 */
object ClockAdaptiveLayout {
    /** Gap kept between the digits and the subject, as a fraction of screen height. */
    const val CLEARANCE = 0.014f

    private const val SEARCH_STEPS = 12

    /**
     * Returns the size factor to apply to the clock, in
     * [minScale]..1. Returns 1 when the clock already fits, and also when even
     * [minScale] would still overlap: a clock shrunk to almost nothing that
     * still covers a face helps no one, so it keeps its size and the depth
     * effect (if enabled) draws the subject over it instead.
     *
     * @param centerX clock centre, screen fraction.
     * @param boxTop top of the clock box, screen fraction.
     * @param boxHeight the box height before adapting, screen fraction.
     * @param face where the digits sit inside the box.
     * @param faceAspect width/height of the box in pixels (with the user's
     *   width/height stretch already applied).
     * @param screenAspect width/height of the screen.
     */
    fun scale(
        profile: SubjectProfile,
        centerX: Float,
        boxTop: Float,
        boxHeight: Float,
        face: ClockFaceBox,
        faceAspect: Float,
        screenAspect: Float,
        minScale: Float = AtmosphereClockPolicy.MIN_ADAPTIVE_SCALE
    ): Float {
        if (!boxHeight.isFinite() || boxHeight <= 0f ||
            !faceAspect.isFinite() || faceAspect <= 0f ||
            !screenAspect.isFinite() || screenAspect <= 0f
        ) {
            return 1f
        }
        val floor = minScale.coerceIn(0.05f, 1f)
        if (fits(1f, profile, centerX, boxTop, boxHeight, face, faceAspect, screenAspect)) {
            return 1f
        }
        if (!fits(floor, profile, centerX, boxTop, boxHeight, face, faceAspect, screenAspect)) {
            return 1f
        }
        // fits() is monotonic in scale: shrinking lowers the glyph bottom and
        // narrows the span (which can only raise the subject's top within it).
        var low = floor
        var high = 1f
        repeat(SEARCH_STEPS) {
            val mid = (low + high) / 2f
            if (fits(mid, profile, centerX, boxTop, boxHeight, face, faceAspect, screenAspect)) {
                low = mid
            } else {
                high = mid
            }
        }
        return low
    }

    private fun fits(
        scale: Float,
        profile: SubjectProfile,
        centerX: Float,
        boxTop: Float,
        boxHeight: Float,
        face: ClockFaceBox,
        faceAspect: Float,
        screenAspect: Float
    ): Boolean {
        val height = boxHeight * scale
        val width = height * faceAspect / screenAspect
        val boxLeft = centerX - width / 2f
        val glyphLeft = boxLeft + width * face.left
        val glyphRight = boxLeft + width * face.right
        val glyphBottom = boxTop + height * face.bottom
        val subjectTop = profile.topWithin(glyphLeft, glyphRight)
        return glyphBottom + CLEARANCE <= subjectTop
    }
}
