package com.app.nosatmosphereeffect.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

internal data class EffectTiming(
    val pollIntervalMs: Long,
    val lockDelayMs: Long,
    val animationDurationMs: Long
)

internal class WallpaperEventController(
    private val context: Context,
    private val logTag: String,
    private val timing: () -> EffectTiming,
    private val transitionsEnabled: () -> Boolean,
    private val isKeyguardLocked: () -> Boolean,
    private val onUnlock: () -> Unit,
    private val onPrepareForLock: () -> Unit,
    private val onShowLocked: () -> Unit,
    private val onResumeHome: () -> Unit,
    private val onScreenOff: () -> Unit,
    private val onReload: () -> Unit,
    private val onConfigUpdate: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var locked = true
    private var closed = false
    private var systemReceiverRegistered = false
    private var appReceiverRegistered = false
    private var lastUnlockUptimeMs = NEVER_UNLOCKED

    /**
     * True while a locked keyguard reading arrived so soon after an unlock that
     * it is probably the OS settling (screen-off fingerprint unlock on some
     * skins reports the keyguard, visibility and screen events out of order).
     * The locked visuals are withheld until [confirmLocked] re-checks.
     */
    private var lockVisualDeferred = false

    private val prepareForLock = Runnable {
        runCallback("prepare the next unlock", onPrepareForLock)
    }

    private val confirmLocked = Runnable {
        if (closed || !locked) return@Runnable
        if (isKeyguardLocked()) {
            lockVisualDeferred = false
            runCallback("show the lock-screen state", onShowLocked)
        } else {
            completeUnlock()
        }
    }

    private val unlockChecker = object : Runnable {
        override fun run() {
            if (closed) return
            if (!isKeyguardLocked()) {
                completeUnlock()
            } else {
                handler.postDelayed(this, timing().pollIntervalMs)
            }
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> handleScreenOn()
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_USER_PRESENT -> handleUserPresent()
            }
        }
    }

    private val appReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_RELOAD_WALLPAPER -> runCallback("reload the wallpaper", onReload)
                ACTION_UPDATE_CONFIG -> runCallback("update the wallpaper configuration", onConfigUpdate)
            }
        }
    }

    fun start(initiallyLocked: Boolean) {
        if (closed) return
        locked = initiallyLocked
        registerSystemReceiver()
        registerAppReceiver()
    }

    fun setLocked(value: Boolean) {
        locked = value
        if (!value) {
            handler.removeCallbacks(unlockChecker)
            handler.removeCallbacks(confirmLocked)
            lockVisualDeferred = false
        }
    }

    /** True shortly after an unlock, while the OS may still emit stale events. */
    fun isSettlingAfterUnlock(): Boolean {
        return !locked && SystemClock.uptimeMillis() - lastUnlockUptimeMs < UNLOCK_SETTLE_MS
    }

    /**
     * Reconciles the wallpaper becoming visible with the keyguard when
     * transitions are enabled, choosing exactly one of the unlock animation,
     * the locked state or the settled home state.
     */
    fun onVisible(keyguardLocked: Boolean) {
        if (closed) return
        if (!keyguardLocked) {
            if (locked) {
                // Still showing the locked state (e.g. the screen woke already
                // unlocked): animate once instead of snapping.
                completeUnlock()
            } else {
                runCallback("show the home-screen state", onResumeHome)
            }
            return
        }
        if (isSettlingAfterUnlock()) {
            deferLockVisual()
        } else if (!lockVisualDeferred) {
            locked = true
            runCallback("show the lock-screen state", onShowLocked)
        }
        startUnlockPolling()
    }

    fun onTransitionModeChanged() {
        if (transitionsEnabled()) return
        handler.removeCallbacks(unlockChecker)
        handler.removeCallbacks(prepareForLock)
        handler.removeCallbacks(confirmLocked)
        lockVisualDeferred = false
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(unlockChecker)
        handler.removeCallbacks(prepareForLock)
        handler.removeCallbacks(confirmLocked)
        unregisterSystemReceiver()
        unregisterAppReceiver()
    }

    private fun handleScreenOn() {
        if (closed) return
        handler.removeCallbacks(unlockChecker)
        if (transitionsEnabled()) {
            val keyguardLocked = isKeyguardLocked()
            // USER_PRESENT or a visibility change may already have played the
            // unlock; forcing `locked` here replayed it from the locked state.
            if (!locked && !keyguardLocked) return
            if (!locked && isSettlingAfterUnlock()) {
                deferLockVisual()
            } else {
                locked = true
            }
            startUnlockPolling()
        } else {
            locked = isKeyguardLocked()
            if (locked) {
                runCallback("show the fixed lock-screen state", onPrepareForLock)
            } else {
                runCallback("show the fixed home-screen state", onUnlock)
            }
        }
    }

    private fun handleScreenOff() {
        if (closed) return
        handler.removeCallbacks(unlockChecker)
        handler.removeCallbacks(prepareForLock)
        handler.removeCallbacks(confirmLocked)
        lockVisualDeferred = false
        lastUnlockUptimeMs = NEVER_UNLOCKED
        locked = true
        if (transitionsEnabled()) {
            handler.postDelayed(prepareForLock, timing().lockDelayMs)
        } else {
            runCallback("show the fixed lock-screen state", onPrepareForLock)
        }
        runCallback("rotate the wallpaper playlist", onScreenOff)
    }

    private fun handleUserPresent() {
        if (closed) return
        handler.removeCallbacks(prepareForLock)
        if (!locked) return
        completeUnlock()
    }

    private fun startUnlockPolling() {
        handler.removeCallbacks(unlockChecker)
        handler.post(unlockChecker)
    }

    private fun deferLockVisual() {
        locked = true
        lockVisualDeferred = true
        handler.removeCallbacks(confirmLocked)
        handler.postDelayed(confirmLocked, LOCK_CONFIRM_DELAY_MS)
    }

    /**
     * Single exit from the locked state. Cancels every pending lock-side
     * callback (including a lock delay that has not fired yet), then plays the
     * unlock animation only if the locked state was actually shown.
     */
    private fun completeUnlock() {
        val lockVisualWasShown = !lockVisualDeferred
        locked = false
        lockVisualDeferred = false
        lastUnlockUptimeMs = SystemClock.uptimeMillis()
        handler.removeCallbacks(unlockChecker)
        handler.removeCallbacks(confirmLocked)
        handler.removeCallbacks(prepareForLock)
        if (!transitionsEnabled()) {
            runCallback("show the fixed home-screen state", onUnlock)
        } else if (lockVisualWasShown) {
            runCallback("play the unlock animation", onUnlock)
        } else {
            runCallback("show the home-screen state", onResumeHome)
        }
    }

    private fun registerSystemReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            context.registerReceiver(systemReceiver, filter, Context.RECEIVER_EXPORTED)
            systemReceiverRegistered = true
        } catch (failure: RuntimeException) {
            Log.e(logTag, "Unable to register the screen event receiver", failure)
        }
    }

    private fun registerAppReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_RELOAD_WALLPAPER)
            addAction(ACTION_UPDATE_CONFIG)
        }
        try {
            context.registerReceiver(appReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            appReceiverRegistered = true
        } catch (failure: RuntimeException) {
            Log.e(logTag, "Unable to register the wallpaper command receiver", failure)
        }
    }

    private fun unregisterSystemReceiver() {
        if (!systemReceiverRegistered) return
        systemReceiverRegistered = false
        try {
            context.unregisterReceiver(systemReceiver)
        } catch (failure: RuntimeException) {
            Log.w(logTag, "Unable to unregister the screen event receiver", failure)
        }
    }

    private fun unregisterAppReceiver() {
        if (!appReceiverRegistered) return
        appReceiverRegistered = false
        try {
            context.unregisterReceiver(appReceiver)
        } catch (failure: RuntimeException) {
            Log.w(logTag, "Unable to unregister the wallpaper command receiver", failure)
        }
    }

    private fun runCallback(operation: String, callback: () -> Unit) {
        try {
            callback()
        } catch (failure: RuntimeException) {
            Log.e(logTag, "Unable to $operation", failure)
        }
    }

    private companion object {
        const val NEVER_UNLOCKED = Long.MIN_VALUE / 2
        const val UNLOCK_SETTLE_MS = 1_500L
        const val LOCK_CONFIRM_DELAY_MS = 400L
        const val ACTION_RELOAD_WALLPAPER = "com.app.nosatmosphereeffect.RELOAD_WALLPAPER"
        const val ACTION_UPDATE_CONFIG = "com.app.nosatmosphereeffect.UPDATE_CONFIG"
    }
}
