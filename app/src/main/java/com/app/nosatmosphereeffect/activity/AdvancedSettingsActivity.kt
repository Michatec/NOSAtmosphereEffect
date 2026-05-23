package com.app.nosatmosphereeffect.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.app.nosatmosphereeffect.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class AdvancedSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_settings)

        val halftoneContainer = findViewById<LinearLayout>(R.id.halftoneSettingsContainer)
        val colorFillContainer = findViewById<LinearLayout>(R.id.colorFillSettingsContainer)

        val sliderDotSize = findViewById<Slider>(R.id.sliderDotSize)
        val switchGrayscale = findViewById<SwitchMaterial>(R.id.switchGrayscale)
        val sliderOriginX = findViewById<Slider>(R.id.sliderOriginX)
        val sliderOriginY = findViewById<Slider>(R.id.sliderOriginY)

        val layoutPoll = findViewById<TextInputLayout>(R.id.layoutPollInterval)
        val layoutDelay = findViewById<TextInputLayout>(R.id.layoutLockDelay)
        val inputPoll = findViewById<TextInputEditText>(R.id.inputPollInterval)
        val inputDelay = findViewById<TextInputEditText>(R.id.inputLockDelay)
        val inputDuration = findViewById<TextInputEditText>(R.id.inputAnimDuration)
        val btnApply = findViewById<Button>(R.id.btnApplyAdvanced)
        val btnReset = findViewById<Button>(R.id.btnResetDefaults)
        val switchNoise = findViewById<MaterialSwitch>(R.id.switchNoise)
        val layoutNoise = findViewById<LinearLayout>(R.id.layoutNoiseSettings)
        val inputNoiseScale = findViewById<TextInputEditText>(R.id.inputNoiseScale)
        val inputNoiseStrength = findViewById<TextInputEditText>(R.id.inputNoiseStrength)
        val activeEffect = intent.getStringExtra("ACTIVE_EFFECT_TYPE") ?: "ORIGINAL"

        // Handle specific container visibility based on the active effect
        if (activeEffect.contains("HALFTONE")) {
            halftoneContainer.visibility = View.VISIBLE
            colorFillContainer.visibility = View.GONE
            switchNoise.visibility = View.GONE
            switchNoise.isChecked = false
            layoutNoise.visibility = View.GONE
        } else if (activeEffect.contains("COLORFILL")) {
            halftoneContainer.visibility = View.GONE
            colorFillContainer.visibility = View.VISIBLE
            switchNoise.visibility = View.GONE
            switchNoise.isChecked = false
            layoutNoise.visibility = View.GONE
        } else {
            halftoneContainer.visibility = View.GONE
            colorFillContainer.visibility = View.GONE
            switchNoise.visibility = View.VISIBLE
        }

        val blobColorContainer = findViewById<LinearLayout>(R.id.blobColorSettingsContainer)
        val sliderSat = findViewById<Slider>(R.id.sliderSaturation)
        val sliderCon = findViewById<Slider>(R.id.sliderContrast)

        // Show ONLY for original and reverse original
        if (activeEffect == "ORIGINAL" || activeEffect == "REVERSE") {
            blobColorContainer.visibility = View.VISIBLE
        } else {
            blobColorContainer.visibility = View.GONE
        }

        val isSamsung = intent.getBooleanExtra("IS_SAMSUNG", false)
        val defaultDuration = if (activeEffect == "REVERSE" || activeEffect.contains("COLORFILL")) 1500L else if (activeEffect == "ORIGINAL") 2500L else 500L
        val defaultPoll = if (isSamsung) 30000L else 50L
        val defaultDelay = if (isSamsung) 0L else 800L
        val layoutRotationContainer = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layoutRotationContainer)
        val isPlaylistMode = intent.getBooleanExtra("IS_PLAYLIST_MODE", false)

        // Hide if not a playlist
        layoutRotationContainer.visibility = if (isPlaylistMode) View.VISIBLE else View.GONE
        val dropdownRotation = findViewById<android.widget.AutoCompleteTextView>(R.id.dropdownRotation)
        val rotationOptions = arrayOf("System Theme (Light/Dark)", "Every Lock (Instant)", "1 Minute", "15 Minutes", "30 Minutes", "1 Hour", "3 Hours", "6 Hours", "12 Hours", "24 Hours")
        val rotationValues = longArrayOf(-1, 0, 1, 15, 30, 60, 180, 360, 720, 1440)

        val wpPrefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val savedRotation = wpPrefs.getLong("rotation_interval_minutes", 0)

        val adapter = android.widget.ArrayAdapter(this, R.layout.item_dropdown, rotationOptions)
        dropdownRotation.setAdapter(adapter)

        // Find current selection, default to 'Every Lock' (index 1) if not found
        val savedIndex = rotationValues.indexOf(savedRotation).takeIf { it >= 0 } ?: 1
        dropdownRotation.setText(rotationOptions[savedIndex], false)

        var selectedRotationValue = savedRotation
        dropdownRotation.setOnItemClickListener { _, _, position, _ ->
            selectedRotationValue = rotationValues[position]
        }

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

        // Load existing effect-specific values
        sliderDotSize.value = prefs.getFloat("halftone_dot_size", 12.0f)
        switchGrayscale.isChecked = prefs.getBoolean("halftone_grayscale", false)
        sliderOriginX.value = prefs.getFloat("origin_x", 0.5f)
        sliderOriginY.value = prefs.getFloat("origin_y", 0.8f)

        // Load existing general values or show defaults in placeholder
        val savedPoll = prefs.getLong("poll_interval", -1L)
        val savedDelay = prefs.getLong("lock_delay", -1L)
        val savedDuration = prefs.getLong("anim_duration", -1L)
        sliderSat.value = prefs.getFloat("blob_saturation", 1.0f)
        sliderCon.value = prefs.getFloat("blob_contrast", 1.0f)

        inputPoll.setText(if (savedPoll != -1L) savedPoll.toString() else defaultPoll.toString())
        inputDelay.setText(if (savedDelay != -1L) savedDelay.toString() else defaultDelay.toString())

        // For duration, show what is currently saved, or leave empty/generic if using defaults
        if (savedDuration != -1L) {
            inputDuration.setText(savedDuration.toString())
        } else {
            inputDuration.setText(defaultDuration.toString())
        }

        switchNoise.isChecked = prefs.getBoolean("enable_noise", false)
        val savedNoiseScale = prefs.getFloat("noise_scale", -1f)
        val savedNoiseStrength = prefs.getFloat("noise_strength", -1f)

        inputNoiseScale.setText(if (savedNoiseScale != -1f) savedNoiseScale.toString() else "2000.0")
        inputNoiseStrength.setText(if (savedNoiseStrength != -1f) savedNoiseStrength.toString() else "0.06")

        val isNoiseEnabled = prefs.getBoolean("enable_noise", false)
        switchNoise.isChecked = isNoiseEnabled

        // Hide noise sub-settings if a non-noise effect is active
        layoutNoise.visibility = if (isNoiseEnabled && (!activeEffect.contains("HALFTONE") && !activeEffect.contains("COLORFILL"))) View.VISIBLE else View.GONE

        switchNoise.setOnCheckedChangeListener { _, isChecked ->
            layoutNoise.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        layoutPoll.setEndIconOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Unlock Check Interval")
                .setMessage(
                    "Controls how frequently the app checks if the device has been unlocked.\n\n" +
                            "• What it solves:\n" +
                            "If you unlock your phone and the animation starts after a delay, lower this value.\n\n" +
                            "• Recommended:\n" +
                            "30000ms for Samsung and most devices (Saves Battery).\n" +
                            "50ms if you experience delayed animation start."
                )
                .setPositiveButton("Got it", null)
                .show()
        }

        layoutDelay.setEndIconOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Lock Delay")
                .setMessage(
                    "Adds a pause before the wallpaper resets when you lock the phone.\n\n" +
                            "• What it solves:\n" +
                            "If you see a glimpse of the wallpaper resetting/snapping back before the screen turns fully black, increase this value.\n\n" +
                            "• Recommended:\n" +
                            "0ms for Samsung/Most devices.\n" +
                            "500ms - 800ms if you experience the glitch.\n\n" +
                            "⚠️ Note: If this value is too high, unlocking immediately after locking might show the wallpaper in its previous state."
                )
                .setPositiveButton("Got it", null)
                .show()
        }


        btnApply.setOnClickListener {
            val poll = inputPoll.text.toString().toLongOrNull() ?: defaultPoll
            val delay = inputDelay.text.toString().toLongOrNull() ?: defaultDelay
            val duration = inputDuration.text.toString().toLongOrNull() ?: defaultDuration
            val enableNoise = switchNoise.isChecked
            val noiseScale = inputNoiseScale.text.toString().toFloatOrNull() ?: 2000.0f
            val noiseStrength = inputNoiseStrength.text.toString().toFloatOrNull() ?: 0.06f
            val dotSize = sliderDotSize.value
            val isGrayscale = switchGrayscale.isChecked
            val originX = sliderOriginX.value
            val originY = sliderOriginY.value

            wpPrefs.edit().putLong("rotation_interval_minutes", selectedRotationValue).apply()

            prefs.edit {
                putLong("poll_interval", poll)
                putLong("lock_delay", delay)
                putLong("anim_duration", duration)
                putBoolean("enable_noise", enableNoise)
                putFloat("noise_scale", noiseScale)
                putFloat("noise_strength", noiseStrength)
                putFloat("halftone_dot_size", dotSize)
                putBoolean("halftone_grayscale", isGrayscale)
                putFloat("blob_saturation", sliderSat.value)
                putFloat("blob_contrast", sliderCon.value)
                putFloat("origin_x", originX)
                putFloat("origin_y", originY)
            }
            sendUpdateBroadcast()
        }

        btnReset.setOnClickListener {
            // Remove keys to revert to Service-specific hardcoded defaults
            prefs.edit {
                remove("poll_interval")
                remove("lock_delay")
                remove("anim_duration")
                remove("enable_noise")
                remove("noise_scale")
                remove("noise_strength")
                remove("halftone_dot_size")
                remove("halftone_grayscale")
                remove("blob_saturation")
                remove("blob_contrast")
                remove("origin_x")
                remove("origin_y")
            }

            // Visual reset
            inputPoll.setText(defaultPoll.toString())
            inputDelay.setText(defaultDelay.toString())
            inputDuration.setText(defaultDuration.toString())
            switchNoise.isChecked = false
            layoutNoise.visibility = View.GONE
            inputNoiseScale.setText("2000.0")
            inputNoiseStrength.setText("0.06")
            sliderDotSize.value = 12.0f
            switchGrayscale.isChecked = false
            sliderSat.value = 1.0f
            sliderCon.value = 1.0f
            sliderOriginX.value = 0.5f
            sliderOriginY.value = 0.8f

            sendUpdateBroadcast()
        }
    }

    private fun sendUpdateBroadcast() {
        val intent = Intent("com.app.nosatmosphereeffect.UPDATE_CONFIG")
        intent.setPackage(packageName)
        sendBroadcast(intent)
        Toast.makeText(this, "Settings Applied!", Toast.LENGTH_SHORT).show()
        finish()
    }
}