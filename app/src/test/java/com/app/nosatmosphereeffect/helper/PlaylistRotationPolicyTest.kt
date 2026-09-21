package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRotationPolicyTest {
    @Test
    fun `single mode never rotates`() {
        val decision = decide(mode = PlaylistModeManager.MODE_SINGLE, playlistSize = 5)

        assertFalse(decision.shouldRotate)
        assertEquals(RotationSkipReason.SINGLE_MODE, decision.skipReason)
    }

    @Test
    fun `standard playlists ignore theme-change events`() {
        val decision = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            isThemeChange = true,
            playlistSize = 5
        )

        assertFalse(decision.shouldRotate)
        assertEquals(RotationSkipReason.NON_THEME_EVENT, decision.skipReason)
    }

    @Test
    fun `theme event rotates a single image when the active theme changed`() {
        val decision = decide(
            mode = PlaylistModeManager.MODE_THEME,
            isThemeChange = true,
            isNightMode = true,
            activeThemeState = 0,
            playlistSize = 1,
            intervalMinutes = Long.MAX_VALUE,
            lastRotationMillis = NOW
        )

        assertTrue(decision.shouldRotate)
        assertTrue(decision.themeChanged)
        assertNull(decision.skipReason)
    }

    @Test
    fun `duplicate theme event is ignored`() {
        val decision = decide(
            mode = PlaylistModeManager.MODE_THEME,
            isThemeChange = true,
            isNightMode = true,
            activeThemeState = 1,
            playlistSize = 3
        )

        assertFalse(decision.shouldRotate)
        assertEquals(RotationSkipReason.THEME_UNCHANGED, decision.skipReason)
    }

    @Test
    fun `periodic rotation needs at least two images`() {
        val decision = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            playlistSize = 1
        )

        assertFalse(decision.shouldRotate)
        assertEquals(RotationSkipReason.ONLY_PLAYLIST_ITEM, decision.skipReason)
    }

    @Test
    fun `rotation occurs exactly at the configured interval boundary`() {
        val beforeBoundary = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            playlistSize = 2,
            intervalMinutes = 10,
            lastRotationMillis = NOW - 10 * MINUTE + 1
        )
        val atBoundary = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            playlistSize = 2,
            intervalMinutes = 10,
            lastRotationMillis = NOW - 10 * MINUTE
        )

        assertEquals(RotationSkipReason.INTERVAL_NOT_ELAPSED, beforeBoundary.skipReason)
        assertTrue(atBoundary.shouldRotate)
    }

    @Test
    fun `future timestamps and overflowing intervals do not trigger early rotation`() {
        val futureTimestamp = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            playlistSize = 2,
            intervalMinutes = 1,
            lastRotationMillis = NOW + MINUTE
        )
        val overflowingInterval = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            playlistSize = 2,
            intervalMinutes = Long.MAX_VALUE,
            lastRotationMillis = 1
        )

        assertEquals(RotationSkipReason.INTERVAL_NOT_ELAPSED, futureTimestamp.skipReason)
        assertEquals(RotationSkipReason.INTERVAL_NOT_ELAPSED, overflowingInterval.skipReason)
    }

    @Test
    fun `zero and negative intervals disable throttling`() {
        assertTrue(
            decide(
                mode = PlaylistModeManager.MODE_STANDARD,
                playlistSize = 2,
                intervalMinutes = 0,
                lastRotationMillis = NOW
            ).shouldRotate
        )
        assertTrue(
            decide(
                mode = PlaylistModeManager.MODE_STANDARD,
                playlistSize = 2,
                intervalMinutes = -5,
                lastRotationMillis = NOW
            ).shouldRotate
        )
    }

    @Test
    fun `candidate selection avoids the last image and falls back safely`() {
        assertEquals(
            listOf("wallpaper_1.jpg", "wallpaper_3.jpg"),
            PlaylistRotationPolicy.eligibleNames(
                listOf("wallpaper_1.jpg", "wallpaper_2.jpg", "wallpaper_3.jpg"),
                "wallpaper_2.jpg"
            )
        )
        assertEquals(
            listOf("wallpaper_1.jpg"),
            PlaylistRotationPolicy.eligibleNames(
                listOf("wallpaper_1.jpg"),
                "wallpaper_1.jpg"
            )
        )
    }

    @Test
    fun `invalid policy inputs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            decide(mode = "corrupt", playlistSize = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decide(mode = PlaylistModeManager.MODE_STANDARD, playlistSize = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decide(
                mode = PlaylistModeManager.MODE_STANDARD,
                playlistSize = 2,
                lastRotationMillis = -1
            )
        }
    }

    @Test
    fun `a deleted displayed image forces rotation before the interval elapses`() {
        val decision = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            playlistSize = 1,
            intervalMinutes = 60,
            lastRotationMillis = NOW - MINUTE,
            forceRotate = true
        )

        assertTrue(decision.shouldRotate)
    }

    @Test
    fun `forced rotation still needs an image to rotate to`() {
        val decision = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            playlistSize = 0,
            forceRotate = true
        )

        assertFalse(decision.shouldRotate)
        assertEquals(RotationSkipReason.EMPTY_PLAYLIST, decision.skipReason)
    }

    private fun decide(
        mode: String,
        isThemeChange: Boolean = false,
        isNightMode: Boolean = false,
        activeThemeState: Int = -1,
        playlistSize: Int,
        intervalMinutes: Long = 0,
        lastRotationMillis: Long = 0,
        forceRotate: Boolean = false
    ): RotationDecision {
        return PlaylistRotationPolicy.decide(
            mode = mode,
            isThemeChange = isThemeChange,
            isNightMode = isNightMode,
            activeThemeState = activeThemeState,
            playlistSize = playlistSize,
            intervalMinutes = intervalMinutes,
            lastRotationMillis = lastRotationMillis,
            nowMillis = NOW,
            forceRotate = forceRotate
        )
    }

    private companion object {
        const val MINUTE = 60_000L
        const val NOW = 10_000_000L
    }
}
