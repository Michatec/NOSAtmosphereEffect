package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockAdaptiveLayoutTest {
    /** Glyphs fill the whole face box, to keep the arithmetic readable. */
    private val fullFace = ClockFaceBox(aspect = 1f, left = 0f, top = 0f, right = 1f, bottom = 1f)

    private fun profile(vararg tops: Float) = SubjectProfile(tops)

    private fun scale(
        profile: SubjectProfile,
        boxTop: Float = 0.10f,
        boxHeight: Float = 0.30f,
        centerX: Float = 0.5f
    ) = ClockAdaptiveLayout.scale(
        profile = profile,
        centerX = centerX,
        boxTop = boxTop,
        boxHeight = boxHeight,
        face = fullFace,
        faceAspect = 1f,
        screenAspect = 0.5f
    )

    @Test
    fun `no subject keeps the full size`() {
        assertEquals(1f, scale(profile(1f, 1f, 1f, 1f)), 0f)
    }

    @Test
    fun `a subject below the clock keeps the full size`() {
        assertEquals(1f, scale(profile(1f, 0.8f, 0.8f, 1f)), 0f)
    }

    @Test
    fun `the clock shrinks until its bottom clears the subject`() {
        // Subject starts at 0.30; clock top is 0.10, so the digits may be at
        // most 0.30 - 0.10 - clearance tall.
        val result = scale(profile(0.30f, 0.30f, 0.30f, 0.30f))
        val expected = (0.30f - 0.10f - ClockAdaptiveLayout.CLEARANCE) / 0.30f
        assertEquals(expected, result, 0.002f)
        assertTrue(0.10f + 0.30f * result + ClockAdaptiveLayout.CLEARANCE <= 0.30f + 1e-4f)
    }

    @Test
    fun `a narrower clock can clear a shoulder the full-size one touched`() {
        // Shoulders high at the edges, head low in the middle strip only. At
        // full size (width 0.6 of the screen) the clock spans the shoulders;
        // shrunk, it only spans the middle, where the subject starts lower.
        val shoulders = FloatArray(20) { column -> if (column in 7..12) 0.9f else 0.32f }
        val result = scale(SubjectProfile(shoulders))
        assertTrue("expected a real shrink, got $result", result < 1f)
        assertTrue("expected more than the floor, got $result", result > 0.42f)
    }

    @Test
    fun `when even the smallest size overlaps, the clock keeps its size`() {
        // Subject reaches above the clock's own top edge: shrinking cannot help.
        assertEquals(1f, scale(profile(0.05f, 0.05f, 0.05f)), 0f)
    }

    @Test
    fun `the result never drops below the floor`() {
        val result = scale(profile(0.24f, 0.24f, 0.24f))
        assertTrue(result >= AtmosphereClockPolicy.MIN_ADAPTIVE_SCALE)
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
