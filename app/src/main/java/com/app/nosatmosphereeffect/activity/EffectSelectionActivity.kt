package com.app.nosatmosphereeffect.activity

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.app.nosatmosphereeffect.helper.EffectItem
import com.app.nosatmosphereeffect.helper.EffectsAdapter
import com.app.nosatmosphereeffect.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class EffectSelectionActivity : AppCompatActivity() {

    private var selectedEffectId: String = "ORIGINAL"

    private fun getEffectsList() = listOf(
        EffectItem(
            id = "ORIGINAL",
            title = getString(R.string.effect_original_title),
            description = getString(R.string.effect_original_desc)
        ),
        EffectItem(
            id = "REVERSE",
            title = getString(R.string.effect_reverse_title),
            description = getString(R.string.effect_reverse_desc)
        ),
        EffectItem(
            id = "FROSTED",
            title = getString(R.string.effect_frosted_title),
            description = getString(R.string.effect_frosted_desc)
        ),
        EffectItem(
            id = "FROSTED_REVERSE",
            title = getString(R.string.effect_frosted_reverse_title),
            description = getString(R.string.effect_frosted_reverse_desc)
        ),
        EffectItem(
            id = "HALFTONE",
            title = getString(R.string.effect_halftone_title),
            description = getString(R.string.effect_halftone_desc)
        ),
        EffectItem(
            id = "HALFTONE_REVERSE",
            title = getString(R.string.effect_halftone_reverse_title),
            description = getString(R.string.effect_halftone_reverse_desc)
        ),
        EffectItem(
            id = "COLORFILL",
            title = getString(R.string.effect_colorfill_title),
            description = getString(R.string.effect_colorfill_desc)
        ),
        EffectItem(
            id = "COLORFILL_REVERSE",
            title = getString(R.string.effect_colorfill_reverse_title),
            description = getString(R.string.effect_colorfill_reverse_desc)
        )
    )

    private val pickSingleImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { launchCropActivity(it) }
    }

    // Multiple Image Picker
    private val pickMultipleImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            launchMultiCropActivity(ArrayList(uris))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_effect_selection)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerEffects)
        val isUpdateOnly = intent.getBooleanExtra("UPDATE_EFFECT_ONLY", false)

        val adapter = EffectsAdapter(getEffectsList()) { item ->
            selectedEffectId = item.id
            if (isUpdateOnly) {
                applyEffectDirectly(selectedEffectId)
            } else {
                showSelectionDialog() // Old behavior for 1st time
            }
        }
        recyclerView.adapter = adapter
    }

    private fun showSelectionDialog() {
        val options = arrayOf(
            getString(R.string.mode_single_image),
            getString(R.string.mode_multiple_images)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_select_mode_title)
            .setItems(options) { _, which ->
                if (which == 0) {
                    pickSingleImage.launch("image/*")
                } else {
                    pickMultipleImages.launch("image/*")
                }
            }
            .show()
    }

    private fun launchCropActivity(uri: Uri) {
        val intent = if (selectedEffectId.contains("REVERSE")) {
            Intent(this, BlurToSharpCropActivity::class.java)
        } else {
            Intent(this, CropActivity::class.java)
        }
        intent.data = uri
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.putExtra("EFFECT_ID", selectedEffectId)
        startActivity(intent)
        finish()
    }

    private fun launchMultiCropActivity(uris: ArrayList<Uri>) {
        val intent = Intent(this, PlaylistEditorActivity::class.java)

        intent.data = uris[0]
        val clipData = ClipData.newUri(contentResolver, "Images", uris[0])
        for (i in 1 until uris.size) {
            clipData.addItem(ClipData.Item(uris[i]))
        }
        intent.clipData = clipData
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.putParcelableArrayListExtra("IMAGE_URIS", uris)
        intent.putExtra("EFFECT_ID", selectedEffectId)
        startActivity(intent)
        finish()
    }
    private fun applyEffectDirectly(effectId: String) {
        val serviceClass = when(effectId) {
            "ORIGINAL" -> com.app.nosatmosphereeffect.service.AtmosphereService::class.java
            "REVERSE" -> com.app.nosatmosphereeffect.service.BlurToSharpService::class.java
            "FROSTED" -> com.app.nosatmosphereeffect.service.FrostedService::class.java
            "FROSTED_REVERSE" -> com.app.nosatmosphereeffect.service.FrostedReverseService::class.java
            "HALFTONE" -> com.app.nosatmosphereeffect.service.HalftoneService::class.java
            "HALFTONE_REVERSE" -> com.app.nosatmosphereeffect.service.HalftoneReverseService::class.java
            "COLORFILL" -> com.app.nosatmosphereeffect.service.ColorFillService::class.java
            "COLORFILL_REVERSE" -> com.app.nosatmosphereeffect.service.ColorFillReverseService::class.java
            else -> com.app.nosatmosphereeffect.service.AtmosphereService::class.java
        }
        val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        intent.putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, android.content.ComponentName(this, serviceClass))
        startActivity(intent)
        finish()
    }
}