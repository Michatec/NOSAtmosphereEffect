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
 * How far down each part of the clock may reach before it would touch the
 * subject, as a fraction of the clock box's own height (0 = its top edge,
 * 1 = its bottom edge).
 *
 * The face is drawn into a bitmap that is mapped onto the clock box, so these
 * fractions are exactly the face renderer's own vertical coordinates — it can
 * size each digit against them without knowing anything about the screen, the
 * photo or the mask.
 *
 * [limits] samples the box from left to right in equal strips.
 */
class ClockDigitFit(val limits: FloatArray) {
    init {
        require(limits.isNotEmpty()) { "A digit fit needs at least one column" }
    }

    /** The lowest any digit covering [left]..[right] of the box may reach. */
    fun limitFor(left: Float, right: Float): Float {
        val columns = limits.size
        val first = (left.coerceIn(0f, 1f) * columns).toInt().coerceIn(0, columns - 1)
        val last = (right.coerceIn(0f, 1f) * columns).toInt().coerceIn(first, columns - 1)
        var limit = 1f
        for (column in first..last) {
            if (limits[column] < limit) limit = limits[column]
        }
        return limit
    }

    /** True when nothing intrudes into the clock box at all. */
    val unconstrained: Boolean
        get() = limits.all { it >= 1f }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is ClockDigitFit && limits.contentEquals(other.limits)
    }

    override fun hashCode(): Int = limits.contentHashCode()
}

/**
 * One UI-style adaptive clock: the digits make room for the subject.
 *
 * The clock keeps the position and the size the user chose. What adapts is
 * each digit on its own — a digit over empty sky keeps its full height, one
 * whose column runs into a head is shortened just enough to clear it. That is
 * why this produces a per-column ceiling rather than a single scale factor:
 * shrinking the whole clock makes it small even where there was nothing in the
 * way, which is not what the phone clocks this imitates do.
 */
object ClockAdaptiveLayout {
    /** Gap kept between the digits and the subject, as a fraction of screen height. */
    const val CLEARANCE = 0.014f

    /** Strips sampled across the clock box. Four digits plus a separator fit. */
    const val COLUMNS = 24

    /**
     * Maps the subject profile into the clock box. Returns null when nothing
     * reaches into the box, so the common case costs the renderers nothing.
     *
     * @param centerX clock centre, screen fraction.
     * @param boxTop top of the clock box, screen fraction.
     * @param boxHeight height of the clock box, screen fraction.
     * @param boxWidth width of the clock box, screen fraction.
     */
    fun digitFit(
        profile: SubjectProfile,
        centerX: Float,
        boxTop: Float,
        boxHeight: Float,
        boxWidth: Float
    ): ClockDigitFit? {
        if (!boxHeight.isFinite() || boxHeight <= 0f ||
            !boxWidth.isFinite() || boxWidth <= 0f
        ) {
            return null
        }
        val boxLeft = centerX - boxWidth / 2f
        val limits = FloatArray(COLUMNS)
        var constrained = false
        for (column in 0 until COLUMNS) {
            val left = boxLeft + boxWidth * column / COLUMNS
            val right = boxLeft + boxWidth * (column + 1) / COLUMNS
            val subjectTop = profile.topWithin(left, right)
            val limit = ((subjectTop - CLEARANCE - boxTop) / boxHeight).coerceIn(0f, 1f)
            limits[column] = limit
            if (limit < 1f) constrained = true
        }
        return if (constrained) ClockDigitFit(limits) else null
    }

    /**
     * How much of the height a stacked column gives up comes out of its bottom
     * row. The row nearest the subject is the one that should visibly shorten;
     * the rows above only ease off a little, so the pair still reads as one
     * clock rather than as two unrelated numbers.
     */
    const val STACK_BOTTOM_SHARE = 0.8f

    /**
     * The vertical scale for one digit that may reach [available] pixels from
     * its top edge, at [natural] pixels tall. Never below [minScale]: a digit
     * shrunk past that is unreadable, and the depth effect (drawing the
     * subject over the clock) is the better answer at that point.
     */
    fun digitScale(
        available: Float,
        natural: Float,
        minScale: Float = AtmosphereClockPolicy.MIN_ADAPTIVE_SCALE
    ): Float {
        if (natural <= 0f || !available.isFinite()) return 1f
        return (available / natural).coerceIn(minScale.coerceIn(0.05f, 1f), 1f)
    }

    /**
     * The vertical scales for the [rowCount] rows of one stacked column that
     * together may reach [available] pixels, each [rowHeight] tall with
     * [rowSpacing] between them.
     *
     * The bottom row absorbs [STACK_BOTTOM_SHARE] of whatever has to be given
     * up and the rows above share the rest.
     */
    fun stackedDigitScales(
        rowCount: Int,
        rowHeight: Float,
        rowSpacing: Float,
        available: Float,
        minScale: Float = AtmosphereClockPolicy.MIN_ADAPTIVE_SCALE
    ): FloatArray {
        if (rowCount <= 0) return FloatArray(0)
        if (rowHeight <= 0f) return FloatArray(rowCount) { 1f }
        val baseTotal = rowHeight * rowCount + rowSpacing * (rowCount - 1)
        val deficit = (baseTotal - available).coerceAtLeast(0f)
        return FloatArray(rowCount) { row ->
            val share = when {
                rowCount == 1 -> 1f
                row == rowCount - 1 -> STACK_BOTTOM_SHARE
                else -> (1f - STACK_BOTTOM_SHARE) / (rowCount - 1)
            }
            digitScale(rowHeight - deficit * share, rowHeight, minScale)
        }
    }
}
