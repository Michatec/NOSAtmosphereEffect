package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockAdaptiveLayoutTest {

    private fun profile(vararg tops: Float) = SubjectProfile(tops)

    private fun fit(
        profile: SubjectProfile,
        boxTop: Float = 0.10f,
        boxHeight: Float = 0.30f,
        boxWidth: Float = 0.60f,
        centerX: Float = 0.5f
    ) = ClockAdaptiveLayout.digitFit(
        profile = profile,
        centerX = centerX,
        boxTop = boxTop,
        boxHeight = boxHeight,
        boxWidth = boxWidth
    )

    @Test
    fun `nothing in the clock's way produces no fit at all`() {
        assertNull(fit(profile(1f, 1f, 1f, 1f)))
        // Subject starts below the box: 0.10 + 0.30 = 0.40.
        assertNull(fit(profile(0.55f, 0.55f, 0.55f, 0.55f)))
    }

    @Test
    fun `a subject inside the box limits the columns it covers`() {
        // Subject on the right half only, starting at 0.25 of the screen.
        val tops = FloatArray(20) { column -> if (column >= 10) 0.25f else 1f }
        val result = fit(SubjectProfile(tops))
        assertNotNull(result)
        checkNotNull(result)

        // Left of the box is clear, right is cut to (0.25 - clearance - 0.10) / 0.30.
        assertEquals(1f, result.limitFor(0f, 0.2f), 1e-4f)
        val expected = (0.25f - ClockAdaptiveLayout.CLEARANCE - 0.10f) / 0.30f
        assertEquals(expected, result.limitFor(0.8f, 1f), 0.01f)
    }

    @Test
    fun `a digit over empty sky keeps its full height`() {
        val tops = FloatArray(20) { column -> if (column >= 10) 0.25f else 1f }
        val result = fit(SubjectProfile(tops))!!
        assertEquals(1f, ClockAdaptiveLayout.digitScale(result.limitFor(0f, 0.2f) * 100f, 100f), 0f)
    }

    @Test
    fun `a subject above the clock cannot shrink the digits past the floor`() {
        val result = fit(profile(0.02f, 0.02f, 0.02f))!!
        val scale = ClockAdaptiveLayout.digitScale(result.limitFor(0f, 1f) * 100f, 100f)
        assertEquals(AtmosphereClockPolicy.MIN_ADAPTIVE_SCALE, scale, 1e-4f)
    }

    @Test
    fun `digitScale never grows a digit beyond its natural height`() {
        assertEquals(1f, ClockAdaptiveLayout.digitScale(available = 500f, natural = 100f), 0f)
    }

    @Test
    fun `a stacked column with room keeps both rows full height`() {
        val scales = ClockAdaptiveLayout.stackedDigitScales(
            rowCount = 2,
            rowHeight = 100f,
            rowSpacing = 10f,
            available = 210f
        )
        assertEquals(listOf(1f, 1f), scales.toList())
    }

    @Test
    fun `a stacked column gives up most of the height from its bottom row`() {
        // Base 210, available 160 -> 50 to give up: 10 from the top, 40 below.
        val scales = ClockAdaptiveLayout.stackedDigitScales(
            rowCount = 2,
            rowHeight = 100f,
            rowSpacing = 10f,
            available = 160f
        )
        assertEquals(0.90f, scales[0], 1e-4f)
        assertEquals(0.60f, scales[1], 1e-4f)
        assertTrue("the bottom row must shorten more", scales[1] < scales[0])
    }

    @Test
    fun `stacked rows stay above the floor`() {
        val scales = ClockAdaptiveLayout.stackedDigitScales(
            rowCount = 2,
            rowHeight = 100f,
            rowSpacing = 10f,
            available = 0f
        )
        scales.forEach { assertTrue(it >= AtmosphereClockPolicy.MIN_ADAPTIVE_SCALE) }
    }

    @Test
    fun `an unconstrained fit is recognised as such`() {
        assertTrue(ClockDigitFit(floatArrayOf(1f, 1f)).unconstrained)
        assertTrue(!ClockDigitFit(floatArrayOf(1f, 0.5f)).unconstrained)
    }

    @Test
    fun `fits compare by their contents so states can be deduplicated`() {
        assertEquals(ClockDigitFit(floatArrayOf(1f, 0.5f)), ClockDigitFit(floatArrayOf(1f, 0.5f)))
    }

    @Test
    fun `profiles survive an encode decode round trip`() {
        val original = profile(0.25f, 1f, 0.5f)
        val decoded = SubjectProfile.decode(original.encode())!!
        assertEquals(original.subjectTops.toList(), decoded.subjectTops.toList())
    }

    @Test
    fun `malformed profiles decode to nothing`() {
        assertNull(SubjectProfile.decode(""))
        assertNull(SubjectProfile.decode("abc"))
        assertNull(SubjectProfile.decode(null))
    }

    @Test
    fun `topWithin only looks at the requested span`() {
        val profile = profile(0.1f, 0.9f, 0.9f, 0.1f)
        assertEquals(0.9f, profile.topWithin(0.3f, 0.7f), 0f)
        assertEquals(0.1f, profile.topWithin(0f, 1f), 0f)
    }
}
