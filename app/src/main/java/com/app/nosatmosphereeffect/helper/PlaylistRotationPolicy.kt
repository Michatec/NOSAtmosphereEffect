package com.app.nosatmosphereeffect.helper

internal enum class RotationSkipReason {
    SINGLE_MODE,
    NON_THEME_EVENT,
    THEME_UNCHANGED,
    EMPTY_PLAYLIST,
    ONLY_PLAYLIST_ITEM,
    INTERVAL_NOT_ELAPSED
}

internal data class RotationDecision(
    val shouldRotate: Boolean,
    val themeChanged: Boolean,
    val skipReason: RotationSkipReason? = null
)

internal object PlaylistRotationPolicy {
    private const val MILLIS_PER_MINUTE = 60_000L

    fun decide(
        mode: String,
        isThemeChange: Boolean,
        isNightMode: Boolean,
        activeThemeState: Int,
        playlistSize: Int,
        intervalMinutes: Long,
        lastRotationMillis: Long,
        nowMillis: Long,
        forceRotate: Boolean = false
    ): RotationDecision {
        require(
            mode == PlaylistModeManager.MODE_SINGLE ||
                mode == PlaylistModeManager.MODE_STANDARD ||
                mode == PlaylistModeManager.MODE_THEME
        ) { "Unsupported playlist mode: $mode" }
        require(playlistSize >= 0) { "Playlist size cannot be negative" }
        require(lastRotationMillis >= 0L && nowMillis >= 0L) {
            "Rotation timestamps cannot be negative"
        }

        if (mode == PlaylistModeManager.MODE_SINGLE) {
            return skipped(RotationSkipReason.SINGLE_MODE)
        }
        if (isThemeChange && mode != PlaylistModeManager.MODE_THEME) {
            return skipped(RotationSkipReason.NON_THEME_EVENT)
        }

        val nextThemeState = if (isNightMode) 1 else 0
        val themeChanged = mode == PlaylistModeManager.MODE_THEME &&
            activeThemeState != nextThemeState
        if (isThemeChange && !themeChanged) {
            return skipped(RotationSkipReason.THEME_UNCHANGED)
        }
        if (playlistSize == 0) {
            return skipped(RotationSkipReason.EMPTY_PLAYLIST, themeChanged)
        }
        // The displayed image left the playlist (deleted from a followed
        // folder): replace it now, even with one image left or mid-interval.
        if (forceRotate && !isThemeChange) {
            return RotationDecision(shouldRotate = true, themeChanged = themeChanged)
        }
        if (!themeChanged && playlistSize == 1) {
            return skipped(RotationSkipReason.ONLY_PLAYLIST_ITEM)
        }
        if (!themeChanged && !intervalElapsed(
                intervalMinutes = intervalMinutes,
                lastRotationMillis = lastRotationMillis,
                nowMillis = nowMillis
            )
        ) {
            return skipped(RotationSkipReason.INTERVAL_NOT_ELAPSED)
        }
        return RotationDecision(shouldRotate = true, themeChanged = themeChanged)
    }

    fun eligibleNames(names: List<String>, lastUsedName: String?): List<String> {
        if (names.isEmpty() || lastUsedName == null) return names
        return names.filterNot { it == lastUsedName }.ifEmpty { names }
    }

    private fun intervalElapsed(
        intervalMinutes: Long,
        lastRotationMillis: Long,
        nowMillis: Long
    ): Boolean {
        if (intervalMinutes <= 0L) return true
        if (nowMillis <= lastRotationMillis) return false

        val requiredMillis = if (intervalMinutes > Long.MAX_VALUE / MILLIS_PER_MINUTE) {
            Long.MAX_VALUE
        } else {
            intervalMinutes * MILLIS_PER_MINUTE
        }
        return nowMillis - lastRotationMillis >= requiredMillis
    }

    private fun skipped(
        reason: RotationSkipReason,
        themeChanged: Boolean = false
    ) = RotationDecision(
        shouldRotate = false,
        themeChanged = themeChanged,
        skipReason = reason
    )
}
