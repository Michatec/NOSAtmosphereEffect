package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns a [ClockOverlayState] into its adaptive size factor.
 *
 * [scaleFor] answers immediately from whatever is already known — the cached
 * subject profile and a measured face — so configuring the clock never waits
 * on segmentation. When the profile is not known yet it starts computing it
 * on [worker] and calls [onResolved] once it is, so the owner can configure
 * again and pick the real size up.
 *
 * One per clock owner (per engine). The profile itself is cached process- and
 * disk-wide by [ClockSubjectLayout]; the face measurements are cheap and kept
 * here per style.
 */
class ClockAdaptiveResolver(
    context: Context,
    private val worker: ExecutorService,
    private val onResolved: () -> Unit
) {
    private val appContext = context.applicationContext
    private val computing = AtomicBoolean(false)
    private val faceBoxes = HashMap<String, ClockFaceBox>()
    @Volatile private var closed = false

    fun scaleFor(state: ClockOverlayState): Float {
        if (!state.enabled || !state.adaptive) return 1f
        val profile = ClockSubjectLayout.cached(appContext)
        if (profile == null) {
            requestProfile()
            return 1f
        }
        val face = faceBox(state) ?: return 1f
        return ClockAdaptiveLayout.scale(
            profile = profile,
            centerX = state.centerX,
            boxTop = state.renderTop,
            boxHeight = state.stretchedHeight,
            face = face,
            faceAspect = state.renderTextureAspect(face.aspect),
            screenAspect = ClockSubjectLayout.screenAspect(appContext)
        )
    }

    /** Call when the wallpaper image is replaced. */
    fun invalidate() {
        ClockSubjectLayout.invalidate()
    }

    fun close() {
        closed = true
    }

    private fun requestProfile() {
        if (closed || !computing.compareAndSet(false, true)) return
        val submitted = runCatching {
            worker.execute {
                try {
                    if (closed) return@execute
                    if (ClockSubjectLayout.compute(appContext) != null && !closed) {
                        onResolved()
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Adaptive clock layout failed", error)
                } finally {
                    computing.set(false)
                }
            }
        }
        if (submitted.isFailure) computing.set(false)
    }

    private fun faceBox(state: ClockOverlayState): ClockFaceBox? {
        val key = "${state.styleId}|${state.showSeconds}|${state.hourFormat}"
        synchronized(faceBoxes) { faceBoxes[key]?.let { return it } }
        val measured = runCatching {
            ClockFaceRenderer(appContext).apply {
                style = state.style
                showSeconds = state.showSeconds
                hourFormatOverride = state.hourFormatOverride
            }.measureFace(System.currentTimeMillis())
        }.onFailure { error ->
            Log.w(TAG, "Could not measure the clock face", error)
        }.getOrNull() ?: return null
        synchronized(faceBoxes) { faceBoxes[key] = measured }
        return measured
    }

    private companion object {
        const val TAG = "ClockAdaptiveResolver"
    }
}
