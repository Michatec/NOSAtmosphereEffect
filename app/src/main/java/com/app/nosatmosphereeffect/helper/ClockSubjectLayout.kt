package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * The subject profile of the active wallpaper, for the adaptive clock.
 *
 * Computed from exactly the image the renderers draw (same fit, crop and
 * scroll window) so the profile lines up with what is on screen, then reduced
 * to [SubjectProfile.subjectTops] and cached in preferences keyed by the
 * wallpaper's identity. Every engine and every process start after the first
 * reads the cached row of numbers instead of segmenting again.
 *
 * Independent of the renderers' own masks on purpose: those live on the GPU
 * side of six effects on two backends, while this needs a CPU-side answer in
 * one place, before any renderer has drawn.
 */
object ClockSubjectLayout {
    private const val TAG = "ClockSubjectLayout"
    private const val PREFS = "clock_subject_layout"
    private const val KEY_SIGNATURE = "signature"
    private const val KEY_PROFILE = "profile"
    private const val COLUMNS = 64
    /** Mask values above this count as subject. */
    private const val SUBJECT_THRESHOLD = 0.5f
    /** Ignore specks: a column needs this many subject rows to count. */
    private const val MIN_RUN_ROWS = 3
    private const val SEGMENTATION_TIMEOUT_S = 30L

    private val lock = Any()
    @Volatile private var memorySignature: String? = null
    @Volatile private var memoryProfile: SubjectProfile? = null
    /** Signatures that produced no usable mask this process; not retried. */
    private val failedSignatures = HashSet<String>()

    /**
     * The profile for the current wallpaper if it is already known, without
     * doing any work. Cheap enough for any thread.
     */
    fun cached(context: Context): SubjectProfile? {
        val signature = signature(context) ?: return null
        if (signature == memorySignature) return memoryProfile
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getString(KEY_SIGNATURE, null) != signature) return null
        val profile = SubjectProfile.decode(preferences.getString(KEY_PROFILE, null))
        memorySignature = signature
        memoryProfile = profile
        return profile
    }

    /**
     * Returns the profile, segmenting the wallpaper first if needed. Blocks
     * for the duration of an inference, so call only from a background
     * thread. Returns null when there is no wallpaper, no model, or the
     * segmentation failed; a failure is remembered for the process so the
     * same photo is not retried on every configuration change.
     */
    fun compute(context: Context): SubjectProfile? {
        val appContext = context.applicationContext
        cached(appContext)?.let { return it }
        val signature = signature(appContext) ?: return null
        synchronized(lock) {
            if (signature in failedSignatures) return null
        }

        val (width, height) = analysisSize(appContext)
        val image = try {
            WallpaperFitHelper.buildForAnalysis(appContext, width, height)
        } catch (error: Exception) {
            Log.w(TAG, "Could not load the wallpaper for subject analysis", error)
            return null
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory for subject analysis", error)
            return null
        }

        val mask = try {
            segment(appContext, image.bitmap)
        } finally {
            if (!image.bitmap.isRecycled) image.bitmap.recycle()
        }
        if (mask == null) {
            synchronized(lock) { failedSignatures += signature }
            return null
        }
        val profile = try {
            profileOf(mask, image.windowX)
        } finally {
            if (!mask.isRecycled) mask.recycle()
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SIGNATURE, signature)
            .putString(KEY_PROFILE, profile.encode())
            .apply()
        memorySignature = signature
        memoryProfile = profile
        return profile
    }

    /** Call when the wallpaper image changes. */
    fun invalidate() {
        memorySignature = null
        memoryProfile = null
        synchronized(lock) { failedSignatures.clear() }
    }

    /** Portrait screen size, halved: segmentation downsizes anyway. */
    fun screenAspect(context: Context): Float {
        val metrics = context.resources.displayMetrics
        val shortSide = min(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val longSide = max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        return shortSide.toFloat() / longSide.toFloat()
    }

    private fun analysisSize(context: Context): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val shortSide = min(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(2)
        val longSide = max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(2)
        return (shortSide / 2) to (longSide / 2)
    }

    private fun signature(context: Context): String? {
        val (width, height) = analysisSize(context)
        if (!java.io.File(context.filesDir, WallpaperFitHelper.ACTIVE_WALLPAPER_FILE).isFile) {
            return null
        }
        return WallpaperFitHelper.renderKey(context, width, height).toString()
    }

    private fun segment(context: Context, bitmap: Bitmap): Bitmap? {
        val done = CountDownLatch(1)
        var result: Bitmap? = null
        val extractor = SubjectMaskExtractor(context) { _, mask ->
            result = mask
            done.countDown()
        }
        return try {
            extractor.extract(bitmap, 1L)
            if (!done.await(SEGMENTATION_TIMEOUT_S, TimeUnit.SECONDS)) {
                Log.w(TAG, "Subject analysis timed out")
            }
            result
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            result?.recycle()
            null
        } finally {
            extractor.close()
        }
    }

    /**
     * Reduces a mask to per-column subject tops in screen space. The mask
     * covers the whole render image; with scrolling on, only the centred
     * [windowX] fraction of its width is on screen (the lock screen sits at
     * the middle page).
     */
    internal fun profileOf(mask: Bitmap, windowX: Float): SubjectProfile {
        val width = mask.width
        val height = mask.height
        val pixels = IntArray(width * height)
        mask.getPixels(pixels, 0, width, 0, 0, width, height)
        val window = windowX.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(1f) ?: 1f
        val offset = (1f - window) / 2f
        val threshold = (SUBJECT_THRESHOLD * 255f).toInt()
        val tops = FloatArray(COLUMNS) { 1f }
        for (column in 0 until COLUMNS) {
            val screenLeft = column.toFloat() / COLUMNS
            val screenRight = (column + 1).toFloat() / COLUMNS
            val x0 = ((offset + screenLeft * window) * width).toInt().coerceIn(0, width - 1)
            val x1 = ((offset + screenRight * window) * width).toInt().coerceIn(x0 + 1, width)
            var run = 0
            for (y in 0 until height) {
                var subjectHere = false
                val rowStart = y * width
                for (x in x0 until x1) {
                    if ((pixels[rowStart + x] shr 16 and 0xFF) > threshold) {
                        subjectHere = true
                        break
                    }
                }
                if (subjectHere) {
                    run++
                    if (run >= MIN_RUN_ROWS) {
                        tops[column] = (y - MIN_RUN_ROWS + 1).toFloat() / height
                        break
                    }
                } else {
                    run = 0
                }
            }
        }
        return SubjectProfile(tops)
    }
}
