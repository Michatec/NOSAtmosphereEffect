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

    private val effectsList = listOf(
        EffectItem(
            id = "ORIGINAL",
            title = "Original Atmosphere",
            description = "Wake up: Sharp ➔ Blur\nSignature style. Drifting ambient atmospheric clouds."
        ),
        EffectItem(
            id = "REVERSE",
            title = "Reverse Atmosphere",
            description = "Wake up: Blur ➔ Sharp\nMysterious reveal. Ambient clouds fade to a clear view."
        ),
        EffectItem(
            id = "FROSTED",
            title = "Simple Frosted",
            description = "Wake up: Sharp ➔ Blur\nModern minimalism. A clean, uniform frosted glass layer."
        ),
        EffectItem(
            id = "FROSTED_REVERSE",
            title = "Simple Frosted (Reverse)",
            description = "Wake up: Blur ➔ Sharp\nElegant clarity. Heavy frost dissolves to crystal clear."
        ),
        EffectItem(
            id = "HALFTONE",
            title = "Halftone Print",
            description = "Wake up: Sharp ➔ Halftone\nRetro aesthetic. Sharp view dissolves into comic-book CMYK dots."
        ),
        EffectItem(
            id = "HALFTONE_REVERSE",
            title = "Halftone Print (Reverse)",
            description = "Wake up: Halftone ➔ Sharp\nRetro aesthetic. CMYK dots seamlessly expand into continuous color."
        ),
        EffectItem(
            id = "COLORFILL",
            title = "Color Fill",
            description = "Wake up: B&W ➔ Color\nLiquid awakening. Colors flow outward from your fingerprint."
        ),
        EffectItem(
            id = "COLORFILL_REVERSE",
            title = "Color Fill (Reverse)",
            description = "Wake up: Color ➔ B&W\nFluid drain. Colors wash away into grayscale."
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

        val adapter = EffectsAdapter(effectsList) { item ->
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
        val options = arrayOf("Single Image", "Multiple Images (Playlist)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Select Wallpaper Mode")
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