package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.format.DateFormat
import androidx.core.graphics.createBitmap
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The selectable clock faces.
 *
 * Every style is built from a typeface family that ships with Android
 * itself (no bundled font files, no downloadable-fonts dependency), so the
 * same code produces the same result in the F-Droid and Play builds and
 * nothing here needs a licence audit. [familyName] values are the standard
 * aliases declared in /system/etc/fonts.xml on every Android device; if a
 * device happens not to have one, Typeface.create falls back to the default
 * sans-serif rather than failing.
 */
enum class ClockStyle(
    val id: String,
    val label: String,
    val description: String,
    private val familyName: String,
    private val weight: Int,
    /** Extra tracking as a fraction of the text size. */
    val letterSpacingEm: Float,
    /** True when hours and minutes are drawn on separate rows. */
    val stacked: Boolean,
    /** Alpha applied to the ":" separator, 0..1. */
    val separatorAlpha: Float,
    /**
     * How far the glyph outline is stretched vertically, about its baseline.
     *
     * The clock is sized on screen by its *height* fraction, with width
     * following from the bitmap's aspect — so stretching here does not make
     * the clock occupy more screen, it makes the digits tall and narrow
     * inside the same height budget, which is what reads as a display clock
     * rather than a caption. It composes with the size slider instead of
     * competing with it.
     *
     * Tuned per style: a thin face carries more stretch gracefully than a
     * 900-weight one, where the stems thicken visually as they lengthen.
     */
    val verticalStretch: Float,
    /**
     * How far the glyph outline is condensed horizontally, 1.0 = untouched.
     *
     * Applied through Paint.textScaleX, so it narrows the advance as well as
     * the outline — the layout measures with it set, and the slots shrink to
     * match. That matters: condensing only the drawing would leave the digits
     * rattling around inside slots sized for the wide form.
     *
     * Paired with [verticalStretch] rather than used alone. Tall-and-narrow is
     * what reads as a display clock; tall-and-wide just reads as large.
     */
    val horizontalScale: Float = 1f,
    /**
     * Drawn as refracting glass rather than solid colour: the shader bends,
     * softens and highlights the wallpaper through the glyph shapes (see
     * `compositeClock` in the effect shaders). The face bitmap only supplies
     * the shape, so it is drawn without a drop shadow — a shadow would read
     * as frosted glass outside the digits.
     *
     * Every face is glass. The flag stays because the shaders still carry it,
     * and a flat face would only have to clear it.
     */
    val liquidGlass: Boolean = true
) {
    /** Hours and minutes side by side. */
    LIQUID_GLASS(
        id = "liquid_glass",
        label = "Glass",
        description = "Glass digits in one row",
        familyName = "sans-serif-black",
        weight = 900,
        letterSpacingEm = -0.03f,
        stacked = false,
        separatorAlpha = 0.85f,
        verticalStretch = 1.45f,
        horizontalScale = 0.98f
    ),

    /**
     * Hours above minutes: two rows of two, which is how a clock gets truly
     * large on a phone. Freed from fitting "00:00" across the width, each row
     * is roughly twice the size.
     */
    LIQUID_GLASS_STACKED(
        id = "liquid_glass_stacked",
        label = "Glass Stacked",
        description = "Glass digits, hours above minutes",
        familyName = "sans-serif-black",
        weight = 900,
        letterSpacingEm = -0.04f,
        stacked = true,
        separatorAlpha = 0f,
        verticalStretch = 1.48f,
        horizontalScale = 0.98f
    );

    fun typeface(): Typeface {
        return try {
            Typeface.create(
                Typeface.create(familyName, Typeface.NORMAL),
                weight,
                false
            )
        } catch (_: RuntimeException) {
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }

    companion object {
        val DEFAULT = LIQUID_GLASS_STACKED

        fun fromId(id: String?): ClockStyle {
            if (id == null) return DEFAULT
            return entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}

/**
 * The surface a live wallpaper fills, as width/height. Used as the default
 * [ClockFaceRenderer.screenAspect]: the wallpaper is always the display, and
 * only the calibration screen — which draws into a smaller view — has to say
 * otherwise.
 */
private fun displaySize(context: Context): Pair<Int, Int> {
    val metrics = try {
        context.getSystemService(android.view.WindowManager::class.java)
            ?.currentWindowMetrics
            ?.bounds
    } catch (_: RuntimeException) {
        null
    }
    val width = metrics?.width() ?: context.resources.displayMetrics.widthPixels
    val height = metrics?.height() ?: context.resources.displayMetrics.heightPixels
    return width to height
}

private fun displayAspect(context: Context): Float {
    val (width, height) = displaySize(context)
    if (width <= 0 || height <= 0) return 0.46f
    return width.toFloat() / height.toFloat()
}

private fun displayHeightPx(context: Context): Float {
    val height = displaySize(context).second
    return if (height > 0) height.toFloat() else 2400f
}

/**
 * Draws the clock face into a reusable bitmap, and owns both the
 * digit-change animation and the entry animation.
 *
 * Shared deliberately by [ClockTextureProvider] (GLES) and
 * [com.app.nosatmosphereeffect.renderer.vulkan.VulkanClockTextureUploader]
 * (Vulkan): the previous version of this feature had two copies of the
 * drawing code, which is how the two backends ended up disagreeing about
 * geometry. The only thing the two wrappers still do differently is the
 * upload step.
 *
 * Not thread-safe. Each backend owns its own instance and only touches it
 * from its own render thread.
 *
 * ## Why the bitmap has a fixed size
 *
 * Digit slots are laid out using the widest digit's advance rather than the
 * advance of whichever digit is currently showing. That costs a few pixels
 * of padding on narrow digits and buys three things: the clock stops
 * shifting sideways as the time changes, the animation has somewhere stable
 * to slide within, and the bitmap dimensions never change — so the GLES path
 * can texSubImage2D into the existing texture instead of reallocating, and
 * the Vulkan path re-uploads the same extent every time.
 *
 * ## Two animations, one clock
 *
 * [beginEntry] plays when the wallpaper becomes visible: the whole face
 * rises, brightens and settles, staggered left to right. The digit
 * transition plays when a displayed digit changes, staggered right to left
 * so a rollover cascades. They run off the same monotonic clock and are
 * mutually exclusive by construction — [beginEntry] cancels any digit
 * transition in flight, because a clock that is still arriving has no
 * previous digits to slide away.
 */
class ClockFaceRenderer(private val context: Context) {

    var style: ClockStyle = ClockStyle.DEFAULT
        set(value) {
            if (field != value) {
                field = value
                invalidateLayout()
            }
        }

    /** Draws the day and date, wherever [datePlacement] puts it. */
    var showDate: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidateLayout()
            }
        }

    /**
     * Where the digits sit on screen. Not used to draw them — the shader does
     * the placing — but the date is positioned against this box, so the face
     * has to know it.
     */
    var clockPlacement: ClockPlacement = AtmosphereClockPolicy.DEFAULT_PLACEMENT
        set(value) {
            if (field != value) {
                field = value
                if (showDate) invalidateDateLayout()
            }
        }

    /** Where the date sits on screen, set and stored exactly like the clock. */
    var datePlacement: ClockPlacement = AtmosphereClockPolicy.DEFAULT_DATE_PLACEMENT
        set(value) {
            if (field != value) {
                field = value
                if (showDate) invalidateDateLayout()
            }
        }

    /**
     * Width/height of the surface the clock is drawn on. Only the date needs
     * it: its offset from the digits is a horizontal distance measured in
     * digit-box widths, and converting between the two needs to know how wide
     * a screen-height fraction is.
     *
     * Defaults to the display, which is what a live wallpaper always fills.
     * The calibration screen overrides it with its own preview's aspect so
     * that what it shows is what the wallpaper will draw.
     */
    var screenAspect: Float = displayAspect(context)
        set(value) {
            val safe = if (value.isFinite() && value > 0f) value else field
            if (field != safe) {
                field = safe
                if (showDate) invalidateDateLayout()
            }
        }

    /**
     * Colour the glyphs are drawn in, already resolved (never
     * [ClockPalette.AUTO]). Changing it only needs a redraw, not a relayout.
     */
    var color: Int = ClockPalette.DEFAULT_FALLBACK
        set(value) {
            val opaque = value or (0xFF shl 24)
            if (field != opaque) {
                field = opaque
                invalidate()
            }
        }

    var animateDigits: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                if (!value) transitionStartUptimeMs = NO_TRANSITION
            }
        }

    /**
     * Whether the entry animation plays at all. Shares the user's single
     * "animation" switch with the digit transition — someone who turned
     * animation off wants a clock that simply appears.
     */
    var animateEntry: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                if (!value) entryStartUptimeMs = NO_TRANSITION
            }
        }

    /** Width of the most recently rendered bitmap, in pixels. */
    var width: Int = 0
        private set

    /** Height of the most recently rendered bitmap, in pixels. */
    var height: Int = 0
        private set

    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 1f

    /**
     * null = follow the system setting; true/false = explicit override.
     */
    var hourFormatOverride: Boolean? = null
        set(value) {
            if (field != value) {
                field = value
                invalidateLayout()
            }
        }

    private var systemIs24Hour: Boolean = DateFormat.is24HourFormat(context)

    private val is24Hour: Boolean
        get() = hourFormatOverride ?: systemIs24Hour

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Set per glyph in drawGlyph — alpha is animated, so the colour has
        // to be reapplied each time anyway.
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }

    /**
     * The date line. It is set in the clock's own typeface, so the two read
     * as one clock rather than as a clock with a caption.
     */
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }

    // Kept rather than rebuilt per frame: the pattern lookup and the parse
    // behind SimpleDateFormat are not free, and this runs on the render path.
    private var dateFormatter: java.text.SimpleDateFormat? = null
    private var dateFormatterLocale: java.util.Locale? = null

    private val calendar: Calendar = Calendar.getInstance()

    /** Used to keep the rasterisation in proportion to the clock on screen. */
    private val screenHeightPx: Float = displayHeightPx(context)

    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null

    private var layout: FaceLayout? = null
    private var currentRows: List<String> = emptyList()
    private var previousRows: List<String> = emptyList()

    private var lastRenderedKey: Long = Long.MIN_VALUE
    private var lastRenderUptimeMs: Long = 0L
    private var transitionStartUptimeMs: Long = NO_TRANSITION
    private var entryStartUptimeMs: Long = NO_TRANSITION

    /**
     * Starts the entry animation. Called when the wallpaper engine becomes
     * visible — screen on, returning from an app, the picker opening a
     * preview — so the clock arrives rather than being already there.
     *
     * [uptimeMs] must come from a monotonic clock. Cancels any digit
     * transition in flight: the face is about to be composed from scratch,
     * so there is nothing for the old digits to slide away from.
     */
    fun beginEntry(uptimeMs: Long) {
        if (!animateEntry) {
            entryStartUptimeMs = NO_TRANSITION
            return
        }
        entryStartUptimeMs = uptimeMs
        transitionStartUptimeMs = NO_TRANSITION
        // The displayed time has not necessarily changed, but the pixels
        // have — force the next render rather than letting the key check
        // short-circuit it.
        lastRenderedKey = Long.MIN_VALUE
    }

    /**
     * True when a frame is due — either the displayed time changed or an
     * animation is still in flight. Callers use this both to decide whether
     * to redraw and to decide whether to schedule another frame.
     */
    fun needsRender(nowMillis: Long, uptimeMs: Long): Boolean {
        if (bitmap == null) return true
        if (timeKey(nowMillis) != lastRenderedKey) return true
        return isAnimating(uptimeMs)
    }

    fun isAnimating(uptimeMs: Long): Boolean =
        isEntering(uptimeMs) || isChangingDigits(uptimeMs)

    /**
     * True only while the entry animation is running. Separate from
     * [isAnimating] because the backends throttle it differently: an entry
     * animation is a one-off worth spending frames on, a digit transition
     * happens every minute.
     */
    fun isEntering(uptimeMs: Long): Boolean {
        if (!animateEntry || entryStartUptimeMs == NO_TRANSITION) return false
        return uptimeMs - entryStartUptimeMs < entryTotalDurationMs()
    }

    private fun isChangingDigits(uptimeMs: Long): Boolean {
        if (!animateDigits || transitionStartUptimeMs == NO_TRANSITION) return false
        return uptimeMs - transitionStartUptimeMs < TRANSITION_DURATION_MS
    }

    /**
     * Renders the face for [nowMillis]. [uptimeMs] must come from a
     * monotonic clock (SystemClock.uptimeMillis) so that a user or network
     * time change moves the digits without corrupting the animation.
     *
     * [minimumIntervalMs] throttles animation frames; pass 0 to force a
     * render. Returns the bitmap to upload, or null when nothing needs to
     * be uploaded this frame.
     */
    fun render(
        nowMillis: Long,
        uptimeMs: Long,
        minimumIntervalMs: Long = 0L
    ): Bitmap? {
        val key = timeKey(nowMillis)
        val timeChanged = key != lastRenderedKey
        val animating = isAnimating(uptimeMs)
        if (!timeChanged && !animating && bitmap != null) return null
        if (
            !timeChanged &&
            bitmap != null &&
            uptimeMs - lastRenderUptimeMs < minimumIntervalMs
        ) {
            return null
        }

        val rows = formatRows(nowMillis)
        if (
            timeChanged &&
            currentRows.isNotEmpty() &&
            rows != currentRows &&
            // A digit change landing mid-entry is absorbed by the entry
            // animation rather than starting a competing slide.
            !isEntering(uptimeMs)
        ) {
            previousRows = currentRows
            if (animateDigits) transitionStartUptimeMs = uptimeMs
        }
        if (currentRows.isEmpty()) previousRows = rows
        currentRows = rows

        val face = ensureLayout(rows, dateText(nowMillis))
        val target = ensureBitmap(face) ?: return null
        val target2d = canvas ?: return null

        target.eraseColor(Color.TRANSPARENT)
        drawFace(target2d, face, uptimeMs)

        lastRenderedKey = key
        lastRenderUptimeMs = uptimeMs
        width = target.width
        height = target.height
        return target
    }

    /**
     * Where the digits sit inside the face bitmap, without drawing anything.
     * The bitmap carries margin for the animations and, when the date is on,
     * room for the date wherever it was placed; the calibration screen and
     * the renderers both need the digits' real extent, because that is what
     * the user positions.
     */
    fun measureFace(nowMillis: Long): ClockFaceBox =
        boxOf(ensureLayout(formatRows(nowMillis), dateText(nowMillis)))

    /**
     * The digits' box in the layout as it stands, without building one. The
     * render path calls this every frame to turn the stored placement into
     * the rectangle the shader samples, so it must not allocate or relayout.
     */
    val faceBox: ClockFaceBox
        get() = layout?.let(::boxOf) ?: ClockFaceBox.IDENTITY

    /**
     * The date line's natural width/height ratio in this style's typeface, or
     * null when there is no date. The calibration screen needs it to size the
     * date's box the way it sizes the clock's.
     */
    fun measureDateAspect(nowMillis: Long): Float? {
        val text = dateText(nowMillis) ?: return null
        return ensureLayout(formatRows(nowMillis), text).dateNaturalAspect
    }

    private fun boxOf(face: FaceLayout): ClockFaceBox {
        val width = max(face.bitmapWidth, 1f)
        val height = max(face.bitmapHeight, 1f)
        return ClockFaceBox(
            aspect = width / height,
            left = face.digitsLeft / width,
            top = face.digitsTop / height,
            right = (face.digitsLeft + face.digitsWidth) / width,
            bottom = (face.digitsTop + face.digitsHeight) / height
        )
    }

    /** Re-reads the system 12/24-hour setting; call on a config change. */
    fun refreshFormat() {
        val updated = DateFormat.is24HourFormat(context)
        if (updated != systemIs24Hour) {
            systemIs24Hour = updated
            if (hourFormatOverride == null) invalidateLayout()
        }
    }

    /**
     * Forces the next [render] to redraw and re-upload without animating —
     * for surface/context loss, where the GPU-side copy is gone but the
     * displayed time has not actually changed.
     */
    fun invalidate() {
        lastRenderedKey = Long.MIN_VALUE
        transitionStartUptimeMs = NO_TRANSITION
        entryStartUptimeMs = NO_TRANSITION
    }

    fun release() {
        bitmap?.recycle()
        bitmap = null
        canvas = null
        layout = null
        width = 0
        height = 0
        invalidate()
        currentRows = emptyList()
        previousRows = emptyList()
    }

    // ---------------------------------------------------------------- draw

    private fun drawFace(target: Canvas, face: FaceLayout, uptimeMs: Long) {
        val progress = transitionProgress(uptimeMs)
        val entry = entryProgress(uptimeMs)
        drawDate(target, face, entry)
        val rowCount = face.rows.size
        // Slots are staggered across the whole face, not per row, so a
        // stacked clock cascades down as well as across instead of both rows
        // starting together.
        var globalSlot = 0

        for (rowIndex in 0 until rowCount) {
            val row = face.rows[rowIndex]
            val previousRow = previousRows.getOrNull(rowIndex)
            val currentRow = currentRows.getOrNull(rowIndex) ?: continue
            var x = face.digitsLeft + (face.digitsWidth - row.width) / 2f
            val baseline = face.rowBaselines[rowIndex]

            for (slotIndex in row.slots.indices) {
                val slot = row.slots[slotIndex]
                val newChar = currentRow.getOrNull(slotIndex)
                if (newChar == null) {
                    x += slot.advance
                    globalSlot++
                    continue
                }
                val centerX = x + slot.advance / 2f
                val entrySlot = entry?.let { staggeredEntry(it, globalSlot) }

                // Only a slot whose character actually changed animates, and
                // only while a transition is running. Held in one nullable
                // local so the non-null branch below smart-casts.
                val outgoing = previousRow
                    ?.getOrNull(slotIndex)
                    ?.takeIf { progress != null && it != newChar }
                val slotProgress = if (outgoing == null || progress == null) {
                    null
                } else {
                    staggered(progress, slotIndex, row.slots.size)
                }

                if (outgoing == null || slotProgress == null) {
                    drawGlyph(
                        target = target,
                        character = newChar,
                        centerX = centerX,
                        baseline = baseline,
                        face = face,
                        alpha = 1f,
                        offsetY = 0f,
                        scale = 1f,
                        entry = entrySlot
                    )
                } else {
                    val eased = easeOutCubic(slotProgress)
                    val shift = face.textSize * TRANSITION_TRAVEL_EM
                    // Outgoing digit rises and fades; incoming rises into
                    // place from below. Scale is nudged so the swap reads as
                    // depth rather than a flat slide.
                    drawGlyph(
                        target = target,
                        character = outgoing,
                        centerX = centerX,
                        baseline = baseline,
                        face = face,
                        alpha = 1f - eased,
                        offsetY = -shift * eased,
                        scale = 1f - 0.10f * eased,
                        entry = entrySlot
                    )
                    drawGlyph(
                        target = target,
                        character = newChar,
                        centerX = centerX,
                        baseline = baseline,
                        face = face,
                        alpha = eased,
                        offsetY = shift * (1f - eased),
                        scale = 0.90f + 0.10f * eased,
                        entry = entrySlot
                    )
                }
                x += slot.advance
                globalSlot++
            }
        }
    }

    /**
     * The day and date line, drawn to fill the box the user placed for it in
     * the same typeface as the digits.
     */
    private fun drawDate(target: Canvas, face: FaceLayout, entry: Float?) {
        val text = face.dateText ?: return
        if (!face.drawsDate) return
        // Arrives with the first glyph rather than on its own schedule, so the
        // face reads as one thing coming into place.
        val alpha = entry?.let { easeOutCubic((staggeredEntry(it, 0) * 1.35f).coerceAtMost(1f)) }
            ?: 1f
        if (alpha <= 0.004f) return
        datePaint.color = color
        datePaint.alpha = (alpha * DATE_OPACITY * 255f).toInt().coerceIn(0, 255)
        // Re-asserted per frame: the layout is only rebuilt when the shape
        // changes, and any other paint user would otherwise leak into this.
        datePaint.typeface = style.typeface()
        datePaint.textSize = face.dateTextSize
        datePaint.textScaleX = face.dateScaleX
        datePaint.letterSpacing = DATE_TRACKING_EM
        target.drawText(text, face.dateX, face.dateBaseline, datePaint)
    }

    /**
     * [entry] is this slot's own entry progress, 0..1, or null when no entry
     * animation is running. It composes multiplicatively with whatever the
     * digit transition is doing, so a minute rolling over mid-entry degrades
     * gracefully instead of fighting.
     */
    private fun drawGlyph(
        target: Canvas,
        character: Char,
        centerX: Float,
        baseline: Float,
        face: FaceLayout,
        alpha: Float,
        offsetY: Float,
        scale: Float,
        entry: Float?
    ) {
        // Alpha leads the motion slightly: a glyph that is still travelling
        // but already solid reads as arriving, where one that fades in on the
        // same curve as it moves reads as sluggish.
        val entryAlpha = entry?.let { easeOutCubic((it * 1.35f).coerceAtMost(1f)) } ?: 1f
        val clamped = (alpha * entryAlpha).coerceIn(0f, 1f)
        if (clamped <= 0.004f) return
        val isSeparator = character == ':'
        val styleAlpha = if (isSeparator) style.separatorAlpha else 1f
        val finalAlpha = clamped * styleAlpha
        if (finalAlpha <= 0.004f) return

        val entryRise = entry?.let {
            face.textSize * ENTRY_RISE_EM * (1f - easeOutQuint(it))
        } ?: 0f
        // A small overshoot on the way in — the settle is what makes it feel
        // deliberate rather than merely fast.
        val entryScale = entry?.let { ENTRY_SCALE_FROM + (1f - ENTRY_SCALE_FROM) * easeOutBack(it) } ?: 1f
        // Shadow starts wide and tightens, so the glyph reads as coming into
        // focus. Free: the shadow layer is already being set per glyph.
        val bloom = entry?.let { 1f + ENTRY_BLOOM * (1f - easeOutCubic(it)) } ?: 1f

        textPaint.color = color
        textPaint.alpha = (finalAlpha * 255f).toInt().coerceIn(0, 255)
        // Re-asserted per glyph rather than trusted to persist from
        // ensureLayout: the layout is only rebuilt when the rows change, so a
        // paint reset anywhere else would silently draw wide glyphs into
        // narrow slots.
        textPaint.textScaleX = style.horizontalScale
        // Shadow strength tracks alpha so a fading digit does not leave a
        // hard drop shadow behind it.
        if (style.liquidGlass) {
            textPaint.clearShadowLayer()
        } else {
            textPaint.setShadowLayer(
                face.textSize * SHADOW_RADIUS_EM * bloom,
                0f,
                face.textSize * SHADOW_DY_EM,
                Color.argb((0x66 * finalAlpha).toInt().coerceIn(0, 255), 0, 0, 0)
            )
        }

        val text = character.toString()
        val glyphWidth = textPaint.measureText(text)
        val scaleX = scale * entryScale
        // The style's vertical stretch rides on the same transform as the
        // animation scales, so there is one scale call per glyph rather than
        // two nested ones.
        val scaleY = scale * entryScale * face.verticalStretch
        target.save()
        target.translate(0f, offsetY + entryRise)
        target.scale(scaleX, scaleY, centerX, baseline)
        target.drawText(text, centerX - glyphWidth / 2f, baseline, textPaint)
        target.restore()
    }

    // -------------------------------------------------------------- layout

    private fun invalidateLayout() {
        layout = null
        bitmap?.recycle()
        bitmap = null
        canvas = null
        width = 0
        height = 0
        currentRows = emptyList()
        previousRows = emptyList()
        invalidate()
    }

    /**
     * Drops the layout but keeps the bitmap, for a change that only moves the
     * date. The bitmap is reused whenever the new layout happens to want the
     * same size, which — because the date's box is quantised — is most frames
     * of a drag.
     */
    private fun invalidateDateLayout() {
        layout = null
        invalidate()
    }

    /** The date's box relative to the digits', as the layout will draw it. */
    private fun dateBoxFor(contentAspect: Float, dateAspect: Float): ClockDateLayout =
        ClockBoxPlacement.relativeDateBox(
            clock = clockPlacement,
            date = datePlacement,
            contentAspect = contentAspect,
            dateAspect = dateAspect,
            screenAspect = screenAspect
        ).quantized()

    private fun ensureLayout(rows: List<String>, dateText: String?): FaceLayout {
        val existing = layout
        if (existing != null && existing.matches(rows, dateText)) {
            // Same shape; the date may still have been dragged since.
            if (
                dateText == null ||
                existing.dateBox == dateBoxFor(existing.contentAspect, existing.dateNaturalAspect)
            ) {
                return existing
            }
        }
        var built = buildLayout(rows, dateText, TEXT_SIZE_PX)
        // Two reasons to rasterise smaller than the nominal size, both about
        // a bitmap that is redrawn and re-uploaded whenever the face animates.
        //
        // The first is oversampling: the nominal size is fixed, so a small
        // clock was being drawn at three times the pixels it is displayed at.
        // The second is the date, which can be dragged far from the digits and
        // makes the bitmap that has to span both of them enormous — and that
        // is worst for exactly the small clock the first rule already shrinks.
        val displayedHeight = clockPlacement.height * screenHeightPx
        val nominalHeight = max(built.digitsHeight, 1f)
        val area = built.bitmapWidth * built.bitmapHeight
        val wanted = minOf(
            displayedHeight * MAX_OVERSAMPLE / nominalHeight,
            if (area > 0f) sqrt(MAX_FACE_PIXELS / area) else 1f
        )
        // The budget is allowed to take the face down to where the digits are
        // drawn at about the size they are displayed, and no further: a clock
        // blurred to fit the date's bitmap is a worse answer than a bitmap
        // over budget, and the date can be dragged back if it matters.
        val floor = min(1f, displayedHeight * MIN_SAMPLE / nominalHeight)
        val scale = min(1f, max(wanted, floor))
        if (scale < 0.99f) {
            built = buildLayout(
                rows = rows,
                dateText = dateText,
                textSize = max(TEXT_SIZE_PX * scale, MIN_TEXT_SIZE_PX)
            )
        }
        layout = built
        return built
    }

    private fun buildLayout(
        rows: List<String>,
        dateText: String?,
        textSize: Float
    ): FaceLayout {
        textPaint.typeface = style.typeface()
        textPaint.textSize = textSize
        textPaint.letterSpacing = style.letterSpacingEm
        // Set before the advances are measured, so the slots below are sized
        // for the condensed form rather than the wide one.
        textPaint.textScaleX = style.horizontalScale
        textPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)

        // Slot width is the widest digit, so the layout never reflows as the
        // time changes.
        var digitAdvance = 0f
        for (digit in '0'..'9') {
            digitAdvance = max(digitAdvance, textPaint.measureText(digit.toString()))
        }
        val separatorAdvance = textPaint.measureText(":")

        val stretch = style.verticalStretch
        val metrics = textPaint.fontMetrics
        // Glyphs are scaled about their baseline, so the ascent and descent
        // both grow by the stretch factor and the row box grows with them.
        val rowHeight = (metrics.bottom - metrics.top) * stretch
        val rowLayouts = rows.map { rowText ->
            val slots = rowText.map { character ->
                Slot(
                    advance = if (character == ':') separatorAdvance else digitAdvance
                )
            }
            RowLayout(slots = slots, width = slots.sumOf { it.advance.toDouble() }.toFloat())
        }

        val digitsWidth = rowLayouts.maxOfOrNull { it.width } ?: digitAdvance
        val rowSpacing = if (rows.size > 1) textSize * ROW_SPACING_EM else 0f

        // The digits' ink box. This is what the user drags, so it is measured
        // from the glyphs themselves rather than from the font's line box:
        // a line box includes room for accents no digit has, which made the
        // calibration frame sit visibly loose around the clock.
        val ink = android.graphics.Rect()
        textPaint.getTextBounds(DIGITS_SAMPLE, 0, DIGITS_SAMPLE.length, ink)
        val baselineFromInkTop = -ink.top * stretch
        val digitsHeight = (rowHeight + rowSpacing) * (rows.size - 1) +
            (ink.bottom - ink.top) * stretch
        val contentAspect = (digitsWidth / max(digitsHeight, 1f)).coerceIn(0.02f, 50f)

        // The date is drawn to fill its own box, in digits-local coordinates
        // for now: (0, 0) is the digits' top-left corner.
        var dateBox: ClockDateLayout? = null
        var dateNaturalAspect = 1f
        var dateTextSize = 0f
        var dateScaleX = 1f
        var dateX = 0f
        var dateBaseline = 0f
        var dateLeft = 0f
        var dateTop = 0f
        var dateRight = 0f
        var dateBottom = 0f
        var drawsDate = false
        if (dateText != null) {
            datePaint.typeface = style.typeface()
            datePaint.letterSpacing = DATE_TRACKING_EM
            datePaint.textScaleX = 1f
            datePaint.textSize = DATE_PROBE_PX
            val probe = android.graphics.Rect()
            datePaint.getTextBounds(dateText, 0, dateText.length, probe)
            val probeWidth = max(probe.width().toFloat(), 1f)
            val probeHeight = max(probe.height().toFloat(), 1f)
            dateNaturalAspect = probeWidth / probeHeight
            val box = dateBoxFor(contentAspect, dateNaturalAspect)
            dateBox = box
            val boxWidth = box.width * digitsWidth
            val boxHeight = box.height * digitsHeight
            if (boxWidth >= MIN_DATE_PX && boxHeight >= MIN_DATE_PX) {
                drawsDate = true
                dateTextSize = DATE_PROBE_PX * boxHeight / probeHeight
                val sizeRatio = dateTextSize / DATE_PROBE_PX
                dateScaleX = (boxWidth / (probeWidth * sizeRatio)).coerceIn(0.2f, 5f)
                dateLeft = box.offsetX * digitsWidth
                dateTop = box.offsetY * digitsHeight
                dateRight = dateLeft + boxWidth
                dateBottom = dateTop + boxHeight
                // Positioned by its ink, so the glyphs fill the box the user
                // dragged rather than the font's line box doing it.
                dateX = dateLeft - probe.left * sizeRatio * dateScaleX
                dateBaseline = dateTop - probe.top * sizeRatio
                datePaint.textSize = dateTextSize
                datePaint.textScaleX = dateScaleX
            }
        }

        // Margin leaves room for whichever animation travels furthest, plus
        // the bloomed shadow and the scale overshoot, so a glyph mid-flight
        // is never clipped by the texture edge. Split per axis because the
        // vertical budget is much larger than the horizontal one and a shared
        // value would waste bitmap width on every upload.
        val travelEm = max(TRANSITION_TRAVEL_EM, ENTRY_RISE_EM)
        // A glass face draws no shadow, so it needs no room for one. That was
        // most of the margin: it left the visible digits sitting inside a
        // texture a third larger than they are, which both wasted upload
        // bandwidth and made the calibration box look loose around them.
        val bloomedShadowEm = if (style.liquidGlass) {
            0f
        } else {
            SHADOW_RADIUS_EM * (1f + ENTRY_BLOOM)
        }
        val marginY = textSize * (travelEm + (bloomedShadowEm + OVERSHOOT_MARGIN_EM) * stretch)
        val marginX = textSize * (bloomedShadowEm + OVERSHOOT_MARGIN_EM)
        val dateMargin = if (drawsDate) {
            max((dateBottom - dateTop) * DATE_MARGIN_FRACTION, 2f)
        } else {
            0f
        }

        // Horizontally the bitmap stays symmetric about the digits whatever
        // the date does: every renderer places the texture by the digits'
        // centre line, so a lop-sided bitmap would slide the clock sideways.
        val leftExtent = if (drawsDate) max(marginX, dateMargin - dateLeft) else marginX
        val rightExtent = if (drawsDate) {
            max(marginX, dateRight + dateMargin - digitsWidth)
        } else {
            marginX
        }
        val sideExtent = max(leftExtent, rightExtent)
        val topExtent = if (drawsDate) max(marginY, dateMargin - dateTop) else marginY
        val bottomExtent = if (drawsDate) {
            max(marginY, dateBottom + dateMargin - digitsHeight)
        } else {
            marginY
        }

        val digitsLeft = sideExtent
        val digitsTop = topExtent
        val baselines = FloatArray(rows.size)
        for (index in rows.indices) {
            baselines[index] = digitsTop + baselineFromInkTop + (rowHeight + rowSpacing) * index
        }

        val face = FaceLayout(
            rows = rowLayouts,
            rowBaselines = baselines,
            rowTexts = rows,
            digitsLeft = digitsLeft,
            digitsTop = digitsTop,
            digitsWidth = digitsWidth,
            digitsHeight = digitsHeight,
            bitmapWidth = digitsWidth + sideExtent * 2f,
            bitmapHeight = digitsHeight + topExtent + bottomExtent,
            rowHeight = rowHeight,
            rowSpacing = rowSpacing,
            contentAspect = contentAspect,
            dateText = dateText,
            dateBox = dateBox,
            dateNaturalAspect = dateNaturalAspect,
            drawsDate = drawsDate,
            dateTextSize = dateTextSize,
            dateScaleX = dateScaleX,
            dateX = digitsLeft + dateX,
            dateBaseline = digitsTop + dateBaseline,
            textSize = textSize,
            verticalStretch = stretch
        )
        layout = face
        return face
    }

    private fun ensureBitmap(face: FaceLayout): Bitmap? {
        val targetWidth = face.bitmapWidth.roundToInt().coerceAtLeast(1)
        val targetHeight = face.bitmapHeight.roundToInt().coerceAtLeast(1)
        val existing = bitmap
        if (
            existing != null &&
            !existing.isRecycled &&
            existing.width == targetWidth &&
            existing.height == targetHeight
        ) {
            return existing
        }
        existing?.recycle()
        return try {
            val created = createBitmap(targetWidth, targetHeight)
            bitmap = created
            canvas = Canvas(created)
            created
        } catch (_: OutOfMemoryError) {
            bitmap = null
            canvas = null
            null
        }
    }

    // --------------------------------------------------------------- time

    private fun timeKey(nowMillis: Long): Long {
        // The clock never shows seconds, so a minute is the finest step.
        val divisor = 60_000L
        // Local-offset aware so a timezone change re-renders even when the
        // UTC minute has not rolled over.
        calendar.timeInMillis = nowMillis
        val offset = calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)
        return (nowMillis + offset) / divisor
    }

    private fun formatRows(nowMillis: Long): List<String> {
        calendar.timeInMillis = nowMillis
        val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

        // Always two digits, in both formats. A bare "1:30" is a digit
        // narrower than "12:30", and because the face is centred on screen
        // that missing digit pulls the whole clock off centre — the colon
        // lands right of where it sat a minute ago. Padding costs one leading
        // zero and buys a clock that never moves. Stacked faces needed this
        // anyway, since their rows are centred on each other.
        val hourText = if (is24Hour) {
            twoDigits(hour24)
        } else {
            twoDigits(if (hour24 % 12 == 0) 12 else hour24 % 12)
        }
        val minuteText = twoDigits(minute)

        return if (style.stacked) {
            listOf(hourText, minuteText)
        } else {
            listOf("$hourText:$minuteText")
        }
    }

    /** "Fri, 25 Oct" in the device's locale, or null when the date is off. */
    private fun dateText(nowMillis: Long): String? {
        if (!showDate) return null
        val locale = java.util.Locale.getDefault()
        val formatter = dateFormatter?.takeIf { dateFormatterLocale == locale } ?: try {
            java.text.SimpleDateFormat(
                DateFormat.getBestDateTimePattern(locale, DATE_SKELETON),
                locale
            ).also {
                dateFormatter = it
                dateFormatterLocale = locale
            }
        } catch (_: RuntimeException) {
            return null
        }
        return try {
            formatter.format(java.util.Date(nowMillis))
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun twoDigits(value: Int): String {
        val safe = abs(value) % 100
        return if (safe < 10) "0$safe" else safe.toString()
    }

    // ---------------------------------------------------------- animation

    private fun transitionProgress(uptimeMs: Long): Float? {
        if (!animateDigits || transitionStartUptimeMs == NO_TRANSITION) return null
        val elapsed = uptimeMs - transitionStartUptimeMs
        if (elapsed < 0L) {
            // Monotonic clock went backwards (should not happen, but a
            // paused-then-resumed engine can produce odd deltas): finish now
            // rather than animating for the length of a long.
            transitionStartUptimeMs = NO_TRANSITION
            return null
        }
        if (elapsed >= TRANSITION_DURATION_MS) {
            transitionStartUptimeMs = NO_TRANSITION
            return null
        }
        return elapsed.toFloat() / TRANSITION_DURATION_MS.toFloat()
    }

    /**
     * Whole-face entry progress, 0..1 across the staggered total. Individual
     * slots take their own slice of it in [staggeredEntry].
     */
    private fun entryProgress(uptimeMs: Long): Float? {
        if (!animateEntry || entryStartUptimeMs == NO_TRANSITION) return null
        val total = entryTotalDurationMs()
        val elapsed = uptimeMs - entryStartUptimeMs
        if (elapsed < 0L || elapsed >= total) {
            entryStartUptimeMs = NO_TRANSITION
            return null
        }
        return elapsed.toFloat() / total.toFloat()
    }

    private fun entryTotalDurationMs(): Long {
        val slots = layout?.rows?.sumOf { it.slots.size } ?: DEFAULT_SLOT_COUNT
        return ENTRY_DURATION_MS +
            ENTRY_STAGGER_MS * (slots - 1).coerceAtLeast(0)
    }

    /**
     * Leftmost slot leads, each slot to its right starting slightly later —
     * the opposite direction to [staggered], deliberately. A clock arriving
     * reads left to right like text; a clock rolling over cascades right to
     * left from the digit that actually changed.
     */
    private fun staggeredEntry(progress: Float, slotIndex: Int): Float {
        val total = entryTotalDurationMs().toFloat()
        if (total <= 0f) return 1f
        val delay = (ENTRY_STAGGER_MS * slotIndex).toFloat() / total
        val span = ENTRY_DURATION_MS.toFloat() / total
        if (span <= 0f) return 1f
        return ((progress - delay) / span).coerceIn(0f, 1f)
    }

    /**
     * Rightmost slot leads, each slot to its left starting slightly later,
     * so a rollover like 09:59 -> 10:00 cascades instead of flipping as one
     * block.
     */
    private fun staggered(progress: Float, slotIndex: Int, slotCount: Int): Float {
        val fromRight = (slotCount - 1 - slotIndex).coerceAtLeast(0)
        val delay = (fromRight * STAGGER_FRACTION).coerceIn(0f, 0.6f)
        val span = 1f - delay
        if (span <= 0f) return 1f
        return ((progress - delay) / span).coerceIn(0f, 1f)
    }

    private fun easeOutCubic(value: Float): Float {
        return 1f - (1f - value.coerceIn(0f, 1f)).pow(3)
    }

    private fun easeOutQuint(value: Float): Float {
        return 1f - (1f - value.coerceIn(0f, 1f)).pow(5)
    }

    /**
     * Overshoots slightly past 1 and settles back. [BACK_OVERSHOOT] is kept
     * small on purpose — the layout reserves padding for the overshoot, and
     * a larger one would cost bitmap area on every upload for a flourish
     * nobody asked for.
     */
    private fun easeOutBack(value: Float): Float {
        val t = value.coerceIn(0f, 1f) - 1f
        return 1f + (BACK_OVERSHOOT + 1f) * t.pow(3) + BACK_OVERSHOOT * t.pow(2)
    }

    private data class Slot(val advance: Float)

    private data class RowLayout(val slots: List<Slot>, val width: Float)

    private class FaceLayout(
        val rows: List<RowLayout>,
        /** The text baseline of each row, absolute in the bitmap. */
        val rowBaselines: FloatArray,
        val rowTexts: List<String>,
        /** The digits' ink box inside the bitmap, in pixels. */
        val digitsLeft: Float,
        val digitsTop: Float,
        val digitsWidth: Float,
        val digitsHeight: Float,
        val bitmapWidth: Float,
        val bitmapHeight: Float,
        val rowHeight: Float,
        val rowSpacing: Float,
        val contentAspect: Float,
        val dateText: String?,
        /** The date's box this layout was built for; a new one means a new layout. */
        val dateBox: ClockDateLayout?,
        val dateNaturalAspect: Float,
        val drawsDate: Boolean,
        val dateTextSize: Float,
        val dateScaleX: Float,
        val dateX: Float,
        val dateBaseline: Float,
        val textSize: Float,
        val verticalStretch: Float
    ) {
        /**
         * A layout is reusable while the *shape* is unchanged — same number
         * of rows and same slot pattern. "09:41" and "10:00" share a layout;
         * "9:41" and "10:41" do not, because the 12-hour hour field grows a
         * digit at ten o'clock.
         *
         * The date's placement is checked separately by the caller, which has
         * to convert it before it can be compared.
         */
        fun matches(candidate: List<String>, candidateDate: String?): Boolean {
            // The date's text decides its natural width, and so the layout's.
            if (dateText != candidateDate) return false
            if (candidate.size != rowTexts.size) return false
            for (index in candidate.indices) {
                val existing = rowTexts[index]
                val other = candidate[index]
                if (existing.length != other.length) return false
                for (position in existing.indices) {
                    if ((existing[position] == ':') != (other[position] == ':')) return false
                }
            }
            return true
        }
    }

    private companion object {
        /**
         * Nominal em size the face is rasterised at. The shader scales the
         * result to whatever size the user picked, so this only decides
         * sharpness, not how big the clock looks.
         *
         * Dropped 320 -> 280 alongside the vertical stretch, which is a
         * straight win rather than a compromise: the stretch multiplies the
         * glyph's height by 1.18-1.34, so 280px of em height rasterises
         * MORE vertical detail than the old 320 did while costing less
         * bitmap area. A Modern face measures ~946x888 here against
         * ~1302x778 before — 17% fewer pixels to re-upload on every
         * animation frame, which is the budget the 640 -> 320 change was
         * protecting in the first place.
         */
        const val TEXT_SIZE_PX = 280f
        const val ROW_SPACING_EM = 0.04f
        /**
         * Smallest the whole face may be rasterised at, whatever the date's
         * placement asks for. Below this the digits would be visibly soft.
         */
        const val MIN_TEXT_SIZE_PX = 48f
        /**
         * Ceiling on the face bitmap's area. It is re-uploaded on every
         * animation frame, so this is an upload budget as much as a memory
         * one; only a date placed far from the digits ever reaches it.
         */
        const val MAX_FACE_PIXELS = 2_000_000f
        /**
         * How many face pixels per displayed pixel is worth drawing. Above
         * this the extra detail cannot be seen, and the shader is downscaling
         * it away on every frame.
         */
        const val MAX_OVERSAMPLE = 1.6f
        /**
         * The fewest face pixels per displayed pixel the budget may force.
         * Slightly under 1:1 is barely perceptible; below it the digits are.
         */
        const val MIN_SAMPLE = 0.9f
        /** Size the date is measured at before being scaled into its box. */
        const val DATE_PROBE_PX = 100f
        /** Transparent margin around the date, as a fraction of its height. */
        const val DATE_MARGIN_FRACTION = 0.08f
        /** Below this the date's box is too small to draw into. */
        const val MIN_DATE_PX = 4f
        const val DATE_TRACKING_EM = 0.02f
        /** The date is a shade softer than the digits, as a lock screen sets it. */
        const val DATE_OPACITY = 0.88f
        /** Day, date and month, ordered and punctuated by the device's locale. */
        const val DATE_SKELETON = "EEEdMMM"

        const val SHADOW_RADIUS_EM = 0.085f
        const val SHADOW_DY_EM = 0.022f
        const val TRANSITION_DURATION_MS = 520L
        const val TRANSITION_TRAVEL_EM = 0.42f
        const val STAGGER_FRACTION = 0.13f

        /** Per-glyph entry duration, before the stagger is added. */
        const val ENTRY_DURATION_MS = 620L
        const val ENTRY_STAGGER_MS = 70L
        const val ENTRY_RISE_EM = 0.34f
        const val ENTRY_SCALE_FROM = 0.88f
        const val ENTRY_BLOOM = 1.6f
        const val BACK_OVERSHOOT = 0.9f
        /** Covers the easeOutBack overshoot plus antialiasing slack. */
        const val OVERSHOOT_MARGIN_EM = 0.06f
        /** Used only to size the entry before a layout exists ("00:00"). */
        const val DEFAULT_SLOT_COUNT = 5

        const val NO_TRANSITION = Long.MIN_VALUE
        const val DIGITS_SAMPLE = "0123456789"
    }
}

/**
 * The digits' extent inside a face bitmap, as fractions of its width and
 * height, plus the bitmap's own aspect ratio.
 *
 * The bitmap is larger than this: it carries margin for the animations and,
 * when the date is shown, whatever room the date needs wherever the user put
 * it. Only the digits are described here, because the digits are what the
 * user places — everything else follows from them.
 */
data class ClockFaceBox(
    val aspect: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val widthFraction: Float get() = (right - left).coerceIn(0.01f, 1f)
    val heightFraction: Float get() = (bottom - top).coerceIn(0.01f, 1f)

    /**
     * The digits' own width/height ratio in pixels — what the stored
     * placement is relative to, and unaffected by the date or by the
     * animation margin.
     */
    val contentAspect: Float
        get() = (aspect * widthFraction / heightFraction).coerceIn(0.02f, 50f)

    companion object {
        /** A face that is all digits: what a renderer with no layout reports. */
        val IDENTITY = ClockFaceBox(aspect = 1f, left = 0f, top = 0f, right = 1f, bottom = 1f)
    }
}

/**
 * Where the date sits, in units of the digits' box: (0, 0) is the digits'
 * top-left corner and (1, 1) the bottom-right, so 1 on [width] is exactly as
 * wide as the digits are.
 *
 * ## Why the date is stored relative to the digits
 *
 * The date is drawn into the same bitmap as the digits — one texture, one
 * rectangle, one sampler, on every effect and both backends. Its position
 * therefore has to be expressed against the digits rather than against the
 * screen, and [ClockBoxPlacement.relativeDateBox] is the one place that
 * converts the user's screen placement into this.
 */
data class ClockDateLayout(
    val offsetX: Float,
    val offsetY: Float,
    val width: Float,
    val height: Float
) {
    /**
     * Rounded to a fixed grid. Each distinct value is a bitmap of a different
     * size, so a drag that produced a new one on every frame would reallocate
     * (and re-upload) several megabytes 60 times a second. The grid is fine
     * enough to be invisible and coarse enough that most drag frames reuse
     * the bitmap they already have.
     */
    fun quantized(): ClockDateLayout = ClockDateLayout(
        offsetX = snap(offsetX),
        offsetY = snap(offsetY),
        width = snap(width),
        height = snap(height)
    )

    private fun snap(value: Float): Float =
        (value * QUANTIZE).roundToInt() / QUANTIZE

    private companion object {
        const val QUANTIZE = 384f
    }
}
