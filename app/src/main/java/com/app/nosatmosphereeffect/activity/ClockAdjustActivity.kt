package com.app.nosatmosphereeffect.activity

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.app.nosatmosphereeffect.helper.AtmosphereClockPolicy
import com.app.nosatmosphereeffect.helper.ClockFaceBox
import com.app.nosatmosphereeffect.helper.ClockFaceRenderer
import android.os.SystemClock
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Rect
import com.app.nosatmosphereeffect.helper.ClockAdaptiveLayout
import com.app.nosatmosphereeffect.helper.ClockSubjectLayout
import com.app.nosatmosphereeffect.helper.SubjectProfile
import com.app.nosatmosphereeffect.ui.components.ClockBoxHandle
import com.app.nosatmosphereeffect.ui.components.ClockBoxOverlay
import com.app.nosatmosphereeffect.ui.components.ClockGlassPreview
import com.app.nosatmosphereeffect.helper.ClockPalette
import com.app.nosatmosphereeffect.helper.ClockStyle
import com.app.nosatmosphereeffect.helper.SegmentationCrashGuard
import com.app.nosatmosphereeffect.helper.SubjectMaskDiagnostics
import com.app.nosatmosphereeffect.image.BitmapDecoder
import com.app.nosatmosphereeffect.ui.components.AtmoTextButton
import com.app.nosatmosphereeffect.ui.components.SettingSwitchRow
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Placement and styling for the wallpaper clock, against a live preview of the
 * applied wallpaper. Drag to move, pinch to resize, tap to hide the controls
 * and see the result unobstructed — the same tap-to-hide behaviour as the crop
 * screen, and for the same reason: placement cannot be judged with a panel
 * covering a third of the screen.
 */
class ClockAdjustActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AtmoEngineTheme {
                ClockAdjustScreen(onDone = { finish() })
            }
        }
    }
}

@Composable
private fun ClockAdjustScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val prefs = remember(context) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    var centerX by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                AtmosphereClockPolicy.CENTER_X_KEY,
                AtmosphereClockPolicy.DEFAULT_CENTER_X
            )
        )
    }
    var top by remember {
        mutableFloatStateOf(
            prefs.getFloat(AtmosphereClockPolicy.TOP_KEY, AtmosphereClockPolicy.DEFAULT_TOP)
        )
    }
    var heightFraction by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                AtmosphereClockPolicy.HEIGHT_KEY,
                AtmosphereClockPolicy.DEFAULT_HEIGHT
            )
        )
    }
    var widthScale by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                AtmosphereClockPolicy.WIDTH_SCALE_KEY,
                AtmosphereClockPolicy.DEFAULT_WIDTH_SCALE
            )
        )
    }
    var heightScale by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                AtmosphereClockPolicy.HEIGHT_SCALE_KEY,
                AtmosphereClockPolicy.DEFAULT_HEIGHT_SCALE
            )
        )
    }
    var opacity by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                AtmosphereClockPolicy.OPACITY_KEY,
                AtmosphereClockPolicy.DEFAULT_OPACITY
            )
        )
    }
    var style by remember {
        mutableStateOf(
            ClockStyle.fromId(prefs.getString(AtmosphereClockPolicy.STYLE_KEY, null))
        )
    }
    var showSeconds by remember {
        mutableStateOf(
            prefs.getBoolean(
                AtmosphereClockPolicy.SECONDS_KEY,
                AtmosphereClockPolicy.DEFAULT_SECONDS
            )
        )
    }
    var animate by remember {
        mutableStateOf(
            prefs.getBoolean(
                AtmosphereClockPolicy.ANIMATE_KEY,
                AtmosphereClockPolicy.DEFAULT_ANIMATE
            )
        )
    }
    var colorPref by remember {
        mutableStateOf(
            prefs.getInt(
                AtmosphereClockPolicy.COLOR_KEY,
                AtmosphereClockPolicy.DEFAULT_COLOR
            )
        )
    }
    var hourFormat by remember {
        mutableStateOf(
            AtmosphereClockPolicy.sanitizeHourFormat(
                prefs.getString(AtmosphereClockPolicy.HOUR_FORMAT_KEY, null)
            )
        )
    }

    var autoColor by remember { mutableStateOf<Int?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var eyedropperArmed by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }

    var interacting by remember { mutableStateOf(false) }
    var lastInteractionMs by remember { mutableStateOf(0L) }

    // The stored width/height stretch is folded into one box the moment this
    // screen opens: from here on the box IS the size, so there is exactly one
    // representation on screen and one in preferences.
    LaunchedEffect(Unit) {
        if (heightScale != 1f && heightScale > 0f) {
            heightFraction = AtmosphereClockPolicy.sanitizeHeight(heightFraction * heightScale)
            widthScale = AtmosphereClockPolicy.sanitizeAxisScale(widthScale / heightScale)
            heightScale = 1f
        }
    }

    var wallpaperBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var wallpaperLoadFinished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        wallpaperBitmap = withContext(Dispatchers.IO) { loadCurrentWallpaperBitmap(context) }
        wallpaperLoadFinished = true
    }
    LaunchedEffect(Unit) {
        autoColor = withContext(Dispatchers.IO) { ClockPalette.autoColorFor(context) }
    }

    // SubjectMaskDiagnostics is a plain in-memory holder written from the
    // segmentation worker, not Compose state, so poll it. Worth surfacing:
    // when segmentation fails there is otherwise nothing on screen to
    // distinguish "no subject in this photo" from "the model is broken on this
    // build" — which is exactly the F-Droid litert mismatch.
    var maskFailure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            maskFailure = SubjectMaskDiagnostics.lastFailure
            delay(700)
        }
    }

    var thumbnails by remember { mutableStateOf<Map<ClockStyle, ImageBitmap>>(emptyMap()) }
    LaunchedEffect(showSeconds, hourFormat) {
        thumbnails = withContext(Dispatchers.Default) {
            renderStyleThumbnails(context, showSeconds, hourFormat)
        }
    }

    fun persist() {
        centerX = AtmosphereClockPolicy.sanitizeCenterX(centerX)
        top = AtmosphereClockPolicy.sanitizeTop(top)
        heightFraction = AtmosphereClockPolicy.sanitizeHeight(heightFraction)
        widthScale = AtmosphereClockPolicy.sanitizeAxisScale(widthScale)
        heightScale = AtmosphereClockPolicy.sanitizeAxisScale(heightScale)
        opacity = AtmosphereClockPolicy.sanitizeOpacity(opacity)
        prefs.edit {
            putFloat(AtmosphereClockPolicy.CENTER_X_KEY, centerX)
            putFloat(AtmosphereClockPolicy.TOP_KEY, top)
            putFloat(AtmosphereClockPolicy.HEIGHT_KEY, heightFraction)
            putFloat(AtmosphereClockPolicy.WIDTH_SCALE_KEY, widthScale)
            putFloat(AtmosphereClockPolicy.HEIGHT_SCALE_KEY, heightScale)
            putFloat(AtmosphereClockPolicy.OPACITY_KEY, opacity)
            putString(AtmosphereClockPolicy.STYLE_KEY, style.id)
            putBoolean(AtmosphereClockPolicy.SECONDS_KEY, showSeconds)
            putBoolean(AtmosphereClockPolicy.ANIMATE_KEY, animate)
            putInt(
                AtmosphereClockPolicy.COLOR_KEY,
                AtmosphereClockPolicy.sanitizeColor(colorPref)
            )
            putString(AtmosphereClockPolicy.HOUR_FORMAT_KEY, hourFormat)
        }
        val update = Intent("com.app.nosatmosphereeffect.UPDATE_CONFIG")
        update.setPackage(context.packageName)
        context.sendBroadcast(update)
    }

    LaunchedEffect(
        centerX, top, heightFraction, widthScale, heightScale, opacity, style,
        showSeconds, animate, colorPref, hourFormat
    ) {
        delay(350)
        persist()
    }

    LaunchedEffect(lastInteractionMs) {
        if (lastInteractionMs == 0L) return@LaunchedEffect
        delay(1_200)
        interacting = false
    }
    val guideAlpha by animateFloatAsState(
        targetValue = if (interacting) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "clockGuideAlpha"
    )

    var containerSizePx by remember { mutableStateOf(IntSize.Zero) }
    val containerWidthPx = containerSizePx.width.toFloat()
    val containerHeightPx = containerSizePx.height.toFloat()

    // The face bitmap, redrawn on a worker and handed over as an immutable
    // copy: the renderer reuses one bitmap, and the preview must never read it
    // while it is being drawn into.
    val faceRenderer = remember { ClockFaceRenderer(context) }
    var faceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var faceRevision by remember { mutableIntStateOf(0) }
    var faceAspect by remember { mutableFloatStateOf(1f) }
    // Where the digits sit inside the face texture. The texture carries margin
    // for the digit animation, so the box the user drags is this content area
    // rather than the whole texture — otherwise the frame would float well
    // clear of the digits it is supposed to be sizing.
    var faceContent by remember {
        mutableStateOf(ClockFaceBox(aspect = 1f, left = 0f, top = 0f, right = 1f, bottom = 1f))
    }
    DisposableEffect(faceRenderer) {
        onDispose { faceRenderer.release() }
    }

    val adaptiveEnabled = remember {
        prefs.getBoolean(
            AtmosphereClockPolicy.ADAPTIVE_KEY,
            AtmosphereClockPolicy.DEFAULT_ADAPTIVE
        )
    }
    var subjectProfile by remember { mutableStateOf<SubjectProfile?>(null) }
    var subjectChecked by remember { mutableStateOf(false) }
    LaunchedEffect(adaptiveEnabled) {
        if (!adaptiveEnabled) {
            subjectChecked = true
            return@LaunchedEffect
        }
        subjectProfile = withContext(Dispatchers.IO) { ClockSubjectLayout.compute(context) }
        subjectChecked = true
    }

    val resolvedColor = ClockPalette.resolve(colorPref, autoColor)
    val screenAspect = if (containerHeightPx > 0f) containerWidthPx / containerHeightPx else 0.5f
    // The box: height is the stored fraction, width follows from the face's
    // own proportions and the user's width stretch — the same arithmetic the
    // shaders do, so what is dragged here is what the wallpaper draws.
    val boxHeight = heightFraction.coerceIn(
        AtmosphereClockPolicy.MIN_HEIGHT,
        AtmosphereClockPolicy.MAX_HEIGHT
    )
    val boxWidth = if (screenAspect > 0f) {
        boxHeight * faceAspect * widthScale / screenAspect
    } else {
        boxHeight
    }
    val box = Rect(
        left = centerX - boxWidth / 2f,
        top = top,
        right = centerX + boxWidth / 2f,
        bottom = top + boxHeight
    )
    // What the user sees and drags: the digits' own frame.
    val contentBox = Rect(
        left = box.left + faceContent.left * box.width,
        top = box.top + faceContent.top * box.height,
        right = box.left + faceContent.right * box.width,
        bottom = box.top + faceContent.bottom * box.height
    )

    val digitFit = remember(subjectProfile, centerX, top, boxHeight, boxWidth, adaptiveEnabled) {
        if (!adaptiveEnabled) {
            null
        } else {
            subjectProfile?.let { profile ->
                ClockAdaptiveLayout.digitFit(
                    profile = profile,
                    centerX = centerX,
                    boxTop = top,
                    boxHeight = boxHeight,
                    boxWidth = boxWidth
                )
            }
        }
    }

    // One coroutine owns the renderer. It is keyed on the settings that change
    // the face's shape, and reads the adaptive fit and the drag state through
    // rememberUpdatedState instead — restarting the loop on every frame of a
    // drag would re-measure the face on every frame of a drag.
    val latestFit by rememberUpdatedState(digitFit)
    val dragging by rememberUpdatedState(interacting)
    LaunchedEffect(style, showSeconds, animate, resolvedColor, hourFormat) {
        val measured = withContext(Dispatchers.Default) {
            faceRenderer.style = style
            faceRenderer.showSeconds = showSeconds
            faceRenderer.animateDigits = animate
            faceRenderer.animateEntry = false
            faceRenderer.color = resolvedColor
            faceRenderer.hourFormatOverride =
                AtmosphereClockPolicy.hourFormatOverride(hourFormat)
            runCatching { faceRenderer.measureFace(System.currentTimeMillis()) }.getOrNull()
        }
        if (measured != null) {
            faceAspect = measured.aspect
            faceContent = measured
        }
        while (true) {
            val snapshot = withContext(Dispatchers.Default) {
                runCatching {
                    if (faceRenderer.digitFit != latestFit) {
                        faceRenderer.digitFit = latestFit
                    }
                    // Handed over as a copy: the renderer keeps reusing its
                    // own bitmap, and the preview must never read one that is
                    // being drawn into.
                    faceRenderer.render(
                        nowMillis = System.currentTimeMillis(),
                        uptimeMs = SystemClock.uptimeMillis()
                    )?.copy(Bitmap.Config.ARGB_8888, false)
                }.getOrNull()
            }
            if (snapshot != null) {
                faceBitmap = snapshot
                faceRevision++
            }
            val animating = faceRenderer.isAnimating(SystemClock.uptimeMillis())
            delay(if (dragging || showSeconds || animating) 90L else 1_000L)
        }
    }

    fun applyBox(proposed: Rect, handle: ClockBoxHandle) {
        interacting = true
        lastInteractionMs = System.currentTimeMillis()
        if (screenAspect <= 0f || faceAspect <= 0f) return
        // [proposed] is the digits' frame. Everything is worked out there, so
        // the edge under the finger is the edge that moves, and only the last
        // step converts back to the texture rectangle that gets stored.
        val contentWidthFraction = (faceContent.right - faceContent.left).coerceAtLeast(0.05f)
        val contentHeightFraction = (faceContent.bottom - faceContent.top).coerceAtLeast(0.05f)
        val wantedContentHeight =
            if (handle.resizesHeight) proposed.height else contentBox.height
        val wantedContentWidth =
            if (handle.resizesWidth) proposed.width else contentBox.width

        val textureHeight = AtmosphereClockPolicy.sanitizeHeight(
            wantedContentHeight / contentHeightFraction
        )
        val wantedTextureWidth = wantedContentWidth / contentWidthFraction
        val newWidthScale = AtmosphereClockPolicy.sanitizeAxisScale(
            wantedTextureWidth * screenAspect / (textureHeight * faceAspect)
        )
        val textureWidth = textureHeight * faceAspect * newWidthScale / screenAspect
        val settledContentWidth = textureWidth * contentWidthFraction
        val settledContentHeight = textureHeight * contentHeightFraction

        val contentLeft = when (handle) {
            ClockBoxHandle.MOVE -> proposed.left
            ClockBoxHandle.TOP_LEFT, ClockBoxHandle.BOTTOM_LEFT, ClockBoxHandle.LEFT ->
                contentBox.right - settledContentWidth
            ClockBoxHandle.TOP_RIGHT, ClockBoxHandle.BOTTOM_RIGHT, ClockBoxHandle.RIGHT ->
                contentBox.left
            else -> contentBox.left + (contentBox.width - settledContentWidth) / 2f
        }
        val contentTop = when (handle) {
            ClockBoxHandle.MOVE -> proposed.top
            ClockBoxHandle.TOP_LEFT, ClockBoxHandle.TOP_RIGHT, ClockBoxHandle.TOP ->
                contentBox.bottom - settledContentHeight
            else -> contentBox.top
        }

        val textureLeft = contentLeft - faceContent.left * textureWidth
        val textureTop = contentTop - faceContent.top * textureHeight
        val newCenterX = textureLeft + textureWidth / 2f
        centerX = if (abs(newCenterX - 0.5f) < CENTER_SNAP) {
            if (abs(centerX - 0.5f) >= CENTER_SNAP) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            0.5f
        } else {
            AtmosphereClockPolicy.sanitizeCenterX(newCenterX)
        }
        top = AtmosphereClockPolicy.sanitizeTop(textureTop)
        heightFraction = textureHeight
        widthScale = newWidthScale
        heightScale = 1f
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSizePx = it }
    ) {
        if (wallpaperLoadFinished) {
            ClockGlassPreview(
                wallpaper = wallpaperBitmap,
                face = faceBitmap,
                box = box,
                opacity = opacity,
                faceRevision = faceRevision,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        if (containerWidthPx > 0f && containerHeightPx > 0f) {
            ClockBoxOverlay(
                box = contentBox,
                centered = abs(centerX - 0.5f) < 0.001f,
                showHandles = !eyedropperArmed,
                onBoxChange = ::applyBox,
                onDragStarted = {
                    interacting = true
                    lastInteractionMs = System.currentTimeMillis()
                },
                onDragFinished = { lastInteractionMs = System.currentTimeMillis() },
                onTap = { position ->
                    val source = wallpaperBitmap
                    if (eyedropperArmed && source != null) {
                        val sampled = sampleWallpaperColor(
                            bitmap = source,
                            tap = position,
                            viewWidth = containerWidthPx,
                            viewHeight = containerHeightPx
                        )
                        if (sampled != null) {
                            colorPref = sampled
                            eyedropperArmed = false
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    } else {
                        chromeVisible = !chromeVisible
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                        )
                    )
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(4.dp)
            ) {
                IconButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Done",
                        tint = Color.White
                    )
                }
                Text(
                    if (eyedropperArmed) {
                        "Tap the wallpaper to pick a colour"
                    } else {
                        "Drag the box to move · corners resize · tap to hide"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible && !interacting && !eyedropperArmed,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
            ClockControls(
                thumbnails = thumbnails,
                selected = style,
                onStyleSelected = { style = it },
                opacity = opacity,
                onOpacityChange = { opacity = AtmosphereClockPolicy.sanitizeOpacity(it) },
                showSeconds = showSeconds,
                onShowSecondsChange = { showSeconds = it },
                animate = animate,
                onAnimateChange = { animate = it },
                hourFormat = hourFormat,
                onHourFormatChange = { hourFormat = it },
                colorPref = colorPref,
                autoColor = autoColor,
                onColorSelected = { colorPref = it },
                pickerOpen = pickerOpen,
                onTogglePicker = { pickerOpen = !pickerOpen },
                canEyedrop = wallpaperBitmap != null,
                onArmEyedropper = { eyedropperArmed = true },
                maskFailure = maskFailure,
                adaptiveNotice = when {
                    !adaptiveEnabled ->
                        "Adaptive size is off — turn it on in Advanced Settings " +
                            "to have the digits fit around the subject."
                    !subjectChecked -> "Looking for a subject in this photo…"
                    subjectProfile == null ->
                        "No clear subject in this photo, so the digits keep their " +
                            "full height."
                    digitFit == null ->
                        "The subject is clear of the box, so the digits keep their " +
                            "full height. Drag the box over it to see them fit."
                    else -> "Digits are fitted above the subject."
                },
                segmentationDisabled = SegmentationCrashGuard.isDisabled(context),
                onResetSegmentation = { SegmentationCrashGuard.reset(context) },
                onResetPlacement = {
                    colorPref = AtmosphereClockPolicy.DEFAULT_COLOR
                    centerX = AtmosphereClockPolicy.DEFAULT_CENTER_X
                    top = AtmosphereClockPolicy.DEFAULT_TOP
                    heightFraction = AtmosphereClockPolicy.DEFAULT_HEIGHT
                    widthScale = AtmosphereClockPolicy.DEFAULT_WIDTH_SCALE
                    heightScale = AtmosphereClockPolicy.DEFAULT_HEIGHT_SCALE
                    opacity = AtmosphereClockPolicy.DEFAULT_OPACITY
                }
            )
        }
    }
}

@Composable
private fun ClockControls(
    thumbnails: Map<ClockStyle, ImageBitmap>,
    selected: ClockStyle,
    onStyleSelected: (ClockStyle) -> Unit,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    showSeconds: Boolean,
    onShowSecondsChange: (Boolean) -> Unit,
    animate: Boolean,
    onAnimateChange: (Boolean) -> Unit,
    hourFormat: String,
    onHourFormatChange: (String) -> Unit,
    colorPref: Int,
    autoColor: Int?,
    onColorSelected: (Int) -> Unit,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    canEyedrop: Boolean,
    onArmEyedropper: () -> Unit,
    maskFailure: String?,
    adaptiveNotice: String?,
    segmentationDisabled: Boolean,
    onResetSegmentation: () -> Unit,
    onResetPlacement: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.86f))
                )
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        SectionLabel("Style")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            items(ClockStyle.entries) { candidate ->
                StyleCard(
                    style = candidate,
                    thumbnail = thumbnails[candidate],
                    selected = candidate == selected,
                    onClick = { onStyleSelected(candidate) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Colour")
            Spacer(Modifier.width(8.dp))
            AtmoTextButton(
                text = if (pickerOpen) "Close wheel" else "Colour wheel",
                onClick = onTogglePicker,
                contentColor = Color.White
            )
            if (canEyedrop) {
                AtmoTextButton(
                    text = "Pick from wallpaper",
                    onClick = onArmEyedropper,
                    contentColor = Color.White
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ColorSwatch(
                    color = autoColor ?: ClockPalette.DEFAULT_FALLBACK,
                    label = "Auto",
                    selected = ClockPalette.isAuto(colorPref),
                    onClick = { onColorSelected(ClockPalette.AUTO) }
                )
            }
            items(ClockPalette.PRESETS) { swatch ->
                ColorSwatch(
                    color = swatch.color,
                    label = swatch.label,
                    selected = !ClockPalette.isAuto(colorPref) && colorPref == swatch.color,
                    onClick = { onColorSelected(swatch.color) }
                )
            }
        }

        if (pickerOpen) {
            ColorWheelPicker(
                current = ClockPalette.resolve(colorPref, autoColor),
                onColorChange = onColorSelected
            )
        }

        Spacer(Modifier.height(10.dp))
        // No size sliders: the box on the preview is the size control. Drag it
        // to move the clock, drag a corner for both dimensions or an edge for
        // one — which is both fewer controls and the only way to see what a
        // given size does against the actual photo.
        LabelledSlider(
            label = "Opacity",
            value = opacity,
            valueRange = 0f..1f,
            onValueChange = onOpacityChange
        )

        Spacer(Modifier.height(4.dp))
        SectionLabel("Hour format")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(
                label = "System",
                selected = hourFormat == AtmosphereClockPolicy.HOUR_FORMAT_SYSTEM,
                onClick = { onHourFormatChange(AtmosphereClockPolicy.HOUR_FORMAT_SYSTEM) }
            )
            ChoiceChip(
                label = "12-hour",
                selected = hourFormat == AtmosphereClockPolicy.HOUR_FORMAT_12,
                onClick = { onHourFormatChange(AtmosphereClockPolicy.HOUR_FORMAT_12) }
            )
            ChoiceChip(
                label = "24-hour",
                selected = hourFormat == AtmosphereClockPolicy.HOUR_FORMAT_24,
                onClick = { onHourFormatChange(AtmosphereClockPolicy.HOUR_FORMAT_24) }
            )
        }

        SettingSwitchRow(
            title = "Show seconds",
            checked = showSeconds,
            onCheckedChange = onShowSecondsChange
        )
        SettingSwitchRow(
            title = "Animate digit changes",
            checked = animate,
            onCheckedChange = onAnimateChange,
            subtitle = "Digits slide as the time changes."
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AtmoTextButton(text = "Reset", onClick = onResetPlacement)
            if (segmentationDisabled) {
                AtmoTextButton(
                    text = "Re-enable subject detection",
                    onClick = onResetSegmentation
                )
            }
        }
        val notice = when {
            segmentationDisabled ->
                "Subject detection was switched off after repeated crashes in a " +
                    "system component, so nothing will occlude the clock until it " +
                    "is re-enabled."
            maskFailure != null -> "No depth effect yet: $maskFailure"
            adaptiveNotice != null -> adaptiveNotice
            else -> null
        }
        if (notice != null) {
            Text(
                notice,
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = if (selected) 0.22f else 0.07f))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = Color.White.copy(alpha = if (selected) 0.9f else 0.16f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StyleCard(
    style: ClockStyle,
    thumbnail: ImageBitmap?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = if (selected) 0.18f else 0.07f))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = Color.White.copy(alpha = if (selected) 0.9f else 0.16f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        // Taller than the old 46dp: the faces are now stretched vertically,
        // and ContentScale.Fit would otherwise shrink a tall face until its
        // digits were unreadable in the picker — which is the one place the
        // shape is what the user is choosing between.
        Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = style.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(style.label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ColorSwatch(
    color: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(56.dp)
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(Color(color))
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.28f),
                    shape = RoundedCornerShape(19.dp)
                )
                .clickable(onClick = onClick)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = Color.White.copy(alpha = if (selected) 1f else 0.7f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

/**
 * Standard HSV wheel: angle is hue, distance from the centre is saturation,
 * with a brightness slider beside it — a flat disc has nowhere to put the third
 * axis.
 *
 * The disc is rasterised once into a bitmap and then blitted. Computing it
 * per-pixel inside the draw scope would re-run tens of thousands of colour
 * conversions on every recomposition, which is what makes hand-rolled wheels
 * feel sluggish.
 */
@Composable
private fun ColorWheelPicker(
    current: Int,
    onColorChange: (Int) -> Unit
) {
    val hsv = remember(current) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(current, it) }
    }
    var hue by remember(current) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(current) { mutableFloatStateOf(hsv[1]) }
    var value by remember(current) { mutableFloatStateOf(hsv[2]) }

    val wheel = remember { buildHueWheel(WHEEL_PX).asImageBitmap() }

    fun emit() {
        onColorChange(
            android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)) or
                (0xFF shl 24)
        )
    }

    fun applyPointer(position: Offset, sizePx: Float) {
        val radius = sizePx / 2f
        if (radius <= 0f) return
        val dx = position.x - radius
        val dy = position.y - radius
        saturation = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
        val degrees = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        hue = (degrees + 360f) % 360f
        emit()
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(WHEEL_DP.dp)
                .pointerInput(Unit) {
                    detectTapGestures { applyPointer(it, size.width.toFloat()) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        applyPointer(change.position, size.width.toFloat())
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(
                    image = wheel,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )
                // Brightness is not on the disc, so dim it to match rather than
                // showing colours the picker cannot currently produce.
                if (value < 1f) {
                    drawCircle(color = Color.Black.copy(alpha = 1f - value))
                }
                val radius = size.minDimension / 2f
                val angle = Math.toRadians(hue.toDouble())
                val marker = Offset(
                    radius + (cos(angle) * saturation * radius).toFloat(),
                    radius + (sin(angle) * saturation * radius).toFloat()
                )
                drawCircle(
                    color = Color.White,
                    radius = 9.dp.toPx(),
                    center = marker,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Color(
                            android.graphics.Color.HSVToColor(
                                floatArrayOf(hue, saturation, value)
                            )
                        )
                    )
            )
            LabelledSlider(
                label = "Brightness",
                value = value,
                valueRange = 0f..1f,
                onValueChange = { value = it; emit() }
            )
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            valueRange = valueRange,
            onValueChange = onValueChange
        )
    }
}

/** Rasterises the hue/saturation disc once. Pixels outside it stay transparent. */
private fun buildHueWheel(sizePx: Int): Bitmap {
    val bitmap = createBitmap(sizePx, sizePx)
    val pixels = IntArray(sizePx * sizePx)
    val radius = sizePx / 2f
    val hsv = FloatArray(3)
    hsv[2] = 1f
    for (y in 0 until sizePx) {
        val dy = y - radius
        for (x in 0 until sizePx) {
            val dx = x - radius
            val distance = hypot(dx, dy)
            if (distance > radius) continue
            hsv[0] = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
            hsv[1] = (distance / radius).coerceIn(0f, 1f)
            pixels[y * sizePx + x] = android.graphics.Color.HSVToColor(hsv)
        }
    }
    bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    return bitmap
}

/**
 * Maps a tap on the full-screen preview back to a pixel in the wallpaper.
 *
 * The preview centre-crops the photo to fill the surface, so the same mapping
 * is inverted here. Returns null if the bitmap is unusable.
 */
private fun sampleWallpaperColor(
    bitmap: Bitmap,
    tap: Offset,
    viewWidth: Float,
    viewHeight: Float
): Int? {
    if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
    if (viewWidth <= 0f || viewHeight <= 0f) return null
    val scale = max(viewWidth / bitmap.width, viewHeight / bitmap.height)
    if (scale <= 0f) return null
    val originX = (viewWidth - bitmap.width * scale) / 2f
    val originY = (viewHeight - bitmap.height * scale) / 2f
    val sourceX = ((tap.x - originX) / scale).toInt().coerceIn(0, bitmap.width - 1)
    val sourceY = ((tap.y - originY) / scale).toInt().coerceIn(0, bitmap.height - 1)

    return try {
        // Average a small block rather than reading one pixel: a single sample
        // lands on JPEG noise often enough that the picked colour feels random.
        var red = 0
        var green = 0
        var blue = 0
        var count = 0
        for (y in (sourceY - SAMPLE_RADIUS_PX)..(sourceY + SAMPLE_RADIUS_PX)) {
            if (y < 0 || y >= bitmap.height) continue
            for (x in (sourceX - SAMPLE_RADIUS_PX)..(sourceX + SAMPLE_RADIUS_PX)) {
                if (x < 0 || x >= bitmap.width) continue
                val pixel = bitmap.getPixel(x, y)
                red += (pixel shr 16) and 0xFF
                green += (pixel shr 8) and 0xFF
                blue += pixel and 0xFF
                count++
            }
        }
        if (count == 0) return null
        val averaged = android.graphics.Color.rgb(red / count, green / count, blue / count)
        // Lift very dark samples so tapping a shadow does not produce an
        // invisible clock. Hue and relative saturation are preserved.
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(averaged, hsl)
        hsl[2] = max(hsl[2], MIN_PICKED_LIGHTNESS)
        ColorUtils.HSLToColor(hsl) or (0xFF shl 24)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun renderStyleThumbnails(
    context: Context,
    showSeconds: Boolean,
    hourFormat: String
): Map<ClockStyle, ImageBitmap> {
    val now = System.currentTimeMillis()
    val result = LinkedHashMap<ClockStyle, ImageBitmap>()
    for (candidate in ClockStyle.entries) {
        val renderer = ClockFaceRenderer(context).apply {
            style = candidate
            this.showSeconds = showSeconds
            animateDigits = false
            hourFormatOverride = AtmosphereClockPolicy.hourFormatOverride(hourFormat)
        }
        try {
            val rendered = renderer.render(nowMillis = now, uptimeMs = 0L) ?: continue
            if (rendered.width <= 0 || rendered.height <= 0) continue
            val targetWidth = min(THUMBNAIL_WIDTH_PX, rendered.width)
            val targetHeight = (rendered.height.toFloat() * targetWidth / rendered.width)
                .toInt()
                .coerceAtLeast(1)
            result[candidate] =
                Bitmap.createScaledBitmap(rendered, targetWidth, targetHeight, true)
                    .asImageBitmap()
        } catch (_: RuntimeException) {
            // A face that will not render is left out of the gallery.
        } catch (_: OutOfMemoryError) {
            break
        } finally {
            renderer.release()
        }
    }
    return result
}

private suspend fun loadCurrentWallpaperBitmap(context: Context): Bitmap? {
    val file = File(context.filesDir, "wallpaper.jpg")
    if (!file.exists()) return null
    return try {
        BitmapDecoder.decodePreview(file)
    } catch (_: java.io.IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

private const val CENTER_SNAP = 0.015f
private const val THUMBNAIL_WIDTH_PX = 260
private const val WHEEL_PX = 240
private const val WHEEL_DP = 132
private const val SAMPLE_RADIUS_PX = 3
private const val MIN_PICKED_LIGHTNESS = 0.55f
