package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockBoxPlacementTest {

    /** Digits inset inside the texture, as the real faces are. */
    private val content = ClockFaceBox(
        aspect = 1.4f,
        left = 0.08f,
        top = 0.14f,
        right = 0.92f,
        bottom = 0.86f
    )
    private val faceAspect = 1.4f
    private val screenAspect = 0.46f
    private val start = ClockPlacement(
        centerX = 0.5f,
        top = 0.13f,
        height = 0.24f,
        widthScale = 1f
    )

    private fun contentOf(placement: ClockPlacement) =
        ClockBoxPlacement.contentBox(placement, faceAspect, content, screenAspect)

    private fun drag(
        handle: ClockBoxHandle,
        dx: Float = 0f,
        dy: Float = 0f,
        from: ClockPlacement = start
    ): ClockPlacement {
        val box = contentOf(from)
        val proposed = when (handle) {
            ClockBoxHandle.MOVE ->
                ClockBoxRect(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
            ClockBoxHandle.TOP_LEFT -> ClockBoxRect(box.left + dx, box.top + dy, box.right, box.bottom)
            ClockBoxHandle.TOP_RIGHT -> ClockBoxRect(box.left, box.top + dy, box.right + dx, box.bottom)
            ClockBoxHandle.BOTTOM_LEFT -> ClockBoxRect(box.left + dx, box.top, box.right, box.bottom + dy)
            ClockBoxHandle.BOTTOM_RIGHT -> ClockBoxRect(box.left, box.top, box.right + dx, box.bottom + dy)
            ClockBoxHandle.LEFT -> ClockBoxRect(box.left + dx, box.top, box.right, box.bottom)
            ClockBoxHandle.RIGHT -> ClockBoxRect(box.left, box.top, box.right + dx, box.bottom)
            ClockBoxHandle.TOP -> ClockBoxRect(box.left, box.top + dy, box.right, box.bottom)
            ClockBoxHandle.BOTTOM -> ClockBoxRect(box.left, box.top, box.right, box.bottom + dy)
        }
        return ClockBoxPlacement.apply(from, proposed, handle, faceAspect, content, screenAspect)
    }

    @Test
    fun `moving the clock does not change its size`() {
        val moved = drag(ClockBoxHandle.MOVE, dx = 0.07f, dy = 0.09f)

        assertEquals(start.height, moved.height, 0f)
        assertEquals(start.widthScale, moved.widthScale, 0f)
        val before = contentOf(start)
        val after = contentOf(moved)
        assertEquals(before.width, after.width, 1e-5f)
        assertEquals(before.height, after.height, 1e-5f)
        assertEquals(before.top + 0.09f, after.top, 1e-5f)
    }

    @Test
    fun `a move that lands near the centre snaps to it`() {
        val offCentre = start.copy(centerX = 0.40f)
        val moved = drag(ClockBoxHandle.MOVE, dx = 0.101f, from = offCentre)

        assertTrue(ClockBoxPlacement.isCentred(moved))
        assertEquals(offCentre.height, moved.height, 0f)
    }

    @Test
    fun `resizing from the bottom right leaves the top left corner alone`() {
        val resized = drag(ClockBoxHandle.BOTTOM_RIGHT, dx = 0.06f, dy = 0.05f)

        val before = contentOf(start)
        val after = contentOf(resized)
        assertEquals("left edge moved", before.left, after.left, 1e-4f)
        assertEquals("top edge moved", before.top, after.top, 1e-4f)
        assertTrue("should have grown", after.width > before.width && after.height > before.height)
    }

    @Test
    fun `resizing from the top left leaves the bottom right corner alone`() {
        val resized = drag(ClockBoxHandle.TOP_LEFT, dx = -0.05f, dy = -0.04f)

        val before = contentOf(start)
        val after = contentOf(resized)
        assertEquals("right edge moved", before.right, after.right, 1e-4f)
        assertEquals("bottom edge moved", before.bottom, after.bottom, 1e-4f)
        assertTrue("should have grown", after.width > before.width)
    }

    @Test
    fun `a horizontal resize keeps the height and the anchored edge`() {
        val resized = drag(ClockBoxHandle.RIGHT, dx = 0.05f)

        val before = contentOf(start)
        val after = contentOf(resized)
        assertEquals(start.height, resized.height, 0f)
        assertEquals(before.left, after.left, 1e-4f)
        assertEquals(before.top, after.top, 1e-4f)
        assertTrue(after.width > before.width)
    }

    @Test
    fun `a vertical resize keeps the width and the horizontal centre`() {
        val resized = drag(ClockBoxHandle.BOTTOM, dy = 0.05f)

        val before = contentOf(start)
        val after = contentOf(resized)
        assertEquals("the clock moved sideways", before.centerX, after.centerX, 1e-4f)
        assertEquals("the width changed", before.width, after.width, 1e-3f)
        assertEquals(before.top, after.top, 1e-4f)
        assertTrue(after.height > before.height)
    }

    @Test
    fun `a resize never snaps the centre sideways`() {
        // Starting just outside the snap zone: growing must not pull it in.
        val offCentre = start.copy(centerX = 0.5f + ClockBoxPlacement.CENTRE_SNAP * 1.2f)
        val resized = drag(ClockBoxHandle.BOTTOM, dy = 0.04f, from = offCentre)

        assertEquals(offCentre.centerX, resized.centerX, 1e-4f)
    }

    @Test
    fun `the same drag applied twice gives the same result`() {
        // Every frame resolves against the gesture's start, so a drag cannot
        // drift as it is re-applied.
        val once = drag(ClockBoxHandle.BOTTOM_RIGHT, dx = 0.03f, dy = 0.02f)
        val twice = drag(ClockBoxHandle.BOTTOM_RIGHT, dx = 0.03f, dy = 0.02f)

        assertEquals(once.centerX, twice.centerX, 0f)
        assertEquals(once.top, twice.top, 0f)
        assertEquals(once.height, twice.height, 0f)
        assertEquals(once.widthScale, twice.widthScale, 0f)
    }

    @Test
    fun `a zero drag changes nothing`() {
        ClockBoxHandle.entries.forEach { handle ->
            val settled = drag(handle)
            assertEquals("$handle moved the clock", start.centerX, settled.centerX, 1e-4f)
            assertEquals("$handle moved the clock", start.top, settled.top, 1e-4f)
            assertEquals("$handle resized the clock", start.height, settled.height, 1e-4f)
            assertEquals("$handle reshaped the clock", start.widthScale, settled.widthScale, 1e-3f)
        }
    }

    @Test
    fun `shrinking past the limit stops at the limit instead of inverting`() {
        val tiny = drag(ClockBoxHandle.BOTTOM_RIGHT, dx = -1f, dy = -1f)

        assertEquals(AtmosphereClockPolicy.MIN_HEIGHT, tiny.height, 1e-5f)
        assertTrue(tiny.widthScale >= AtmosphereClockPolicy.MIN_AXIS_SCALE)
        val after = contentOf(tiny)
        assertTrue("box inverted", after.width > 0f && after.height > 0f)
    }
}
