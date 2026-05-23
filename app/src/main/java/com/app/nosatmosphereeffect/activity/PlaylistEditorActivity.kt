package com.app.nosatmosphereeffect.activity

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.helper.PlaylistAdapter
import com.app.nosatmosphereeffect.service.AtmosphereService
import com.app.nosatmosphereeffect.service.BlurToSharpService
import com.app.nosatmosphereeffect.service.ColorFillReverseService
import com.app.nosatmosphereeffect.service.ColorFillService
import com.app.nosatmosphereeffect.service.FrostedReverseService
import com.app.nosatmosphereeffect.service.FrostedService
import com.app.nosatmosphereeffect.service.HalftoneReverseService
import com.app.nosatmosphereeffect.service.HalftoneService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

class PlaylistEditorActivity : AppCompatActivity() {

    private lateinit var adapter: PlaylistAdapter
    private val playlistItems = mutableListOf<PlaylistItem>()
    private var effectId: String = "ORIGINAL"
    private var editingPosition = -1

    data class PlaylistItem(
        val originalUri: Uri,
        var isEdited: Boolean = false,
        var editedFilePath: String? = null,
        var matrixState: FloatArray? = null
    )

    private lateinit var btnApplyAll: Button
    private lateinit var tvCounter: TextView

    private val pickMultipleImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val startPos = playlistItems.size
            uris.forEach { playlistItems.add(PlaylistItem(it)) }
            adapter.notifyItemRangeInserted(startPos, uris.size)
            updateUIState()
            Toast.makeText(this, "${uris.size} images added", Toast.LENGTH_SHORT).show()
        }
    }

    private val editImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val resultUriString = result.data?.getStringExtra("CROPPED_IMAGE_PATH")
            val matrixState = result.data?.getFloatArrayExtra("MATRIX_STATE")

            if (resultUriString != null && editingPosition != -1 && editingPosition < playlistItems.size) {
                val item = playlistItems[editingPosition]
                item.isEdited = true
                item.editedFilePath = resultUriString
                item.matrixState = matrixState

                adapter.notifyItemChanged(editingPosition)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        setContentView(R.layout.activity_playlist_editor)

        effectId = intent.getStringExtra("EFFECT_ID") ?: "ORIGINAL"

        val isEditExisting = intent.getBooleanExtra("EDIT_EXISTING", false)
        if (isEditExisting) {
            loadExistingPlaylist()
        } else {
            val uris = intent.getParcelableArrayListExtra("IMAGE_URIS", Uri::class.java)
            if (uris != null) {
                uris.forEach { playlistItems.add(PlaylistItem(it)) }
            }
        }

        btnApplyAll = findViewById(R.id.btnApplyAll)
        tvCounter = findViewById(R.id.tvCounter)
        val btnAddMore = findViewById<Button>(R.id.btnAddMore)
        val recycler = findViewById<RecyclerView>(R.id.recyclerPlaylist)

        setupCarouselRecyclerView(recycler)

        adapter = PlaylistAdapter(this, playlistItems,
            onItemClick = { pos ->
                editingPosition = pos
                launchEditActivity(playlistItems[pos])
            },
            onDeleteClick = { pos ->
                playlistItems.removeAt(pos)
                adapter.notifyItemRemoved(pos)
                adapter.notifyItemRangeChanged(pos, playlistItems.size)
                updateUIState()
            }
        )
        recycler.adapter = adapter

        btnAddMore.setOnClickListener { pickMultipleImages.launch("image/*") }

        btnApplyAll.setOnClickListener {
           showApplyDialog()
        }

        if (savedInstanceState != null) {
            editingPosition = savedInstanceState.getInt("EDITING_POS", -1)
        }
        updateUIState()
    }

    private fun setupCarouselRecyclerView(recycler: RecyclerView) {
        recycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        val snapHelper = PagerSnapHelper()
        snapHelper.attachToRecyclerView(recycler)

        recycler.post {
            val screenWidth = windowManager.currentWindowMetrics.bounds.width()
            val cardWidthPx = (300 * resources.displayMetrics.density).toInt()
            val cardMarginPx = (16 * resources.displayMetrics.density).toInt()
            val totalItemWidth = cardWidthPx + cardMarginPx
            val padding = (screenWidth - totalItemWidth) / 2
            recycler.setPadding(padding, 0, padding, 0)
            recycler.scrollToPosition(0)
        }
    }

    private fun updateUIState() {
        val count = playlistItems.size
        tvCounter.text = "$count Images Selected"
        if (count > 0) {
            btnApplyAll.isEnabled = true
            btnApplyAll.alpha = 1.0f
        } else {
            btnApplyAll.isEnabled = false
            btnApplyAll.alpha = 0.5f
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("EDITING_POS", editingPosition)
    }

    private fun launchEditActivity(item: PlaylistItem) {
        val intent = Intent(this, MultiImageCropActivity::class.java)
        intent.data = item.originalUri
        if (item.matrixState != null) {
            intent.putExtra("MATRIX_STATE", item.matrixState)
        }
        editImageLauncher.launch(intent)
    }

    private fun applyPlaylist() {
        val loadingView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(50, 50, 50, 50)
            gravity = android.view.Gravity.CENTER_VERTICAL

            addView(android.widget.ProgressBar(this@PlaylistEditorActivity).apply {
                isIndeterminate = true
            })

            addView(android.widget.TextView(this@PlaylistEditorActivity).apply {
                text = "Processing playlist..."
                textSize = 16f
                setPadding(40, 0, 0, 0)
                setTextColor(android.graphics.Color.WHITE) // Adjust based on your theme
            })
        }

        val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(loadingView)
            .setCancelable(false)
            .create()

        progressDialog.show()

        Thread {
            try {

                // 1. USE A TEMPORARY FOLDER INSTEAD OF DELETING THE ACTIVE ONE YET
                val tempDir = File(filesDir, "playlist_temp")
                if (tempDir.exists()) tempDir.deleteRecursively()
                tempDir.mkdirs()

                val tempOriginalsDir = File(filesDir, "playlist_originals_temp")
                if (tempOriginalsDir.exists()) tempOriginalsDir.deleteRecursively()
                tempOriginalsDir.mkdirs()

                // 2. CLEANUP STALE SINGLE-IMAGE DATA (Important!)
                val nextWallpaper = File(filesDir, "next_wallpaper.jpg")
                if (nextWallpaper.exists()) nextWallpaper.delete()

                val metaArray = JSONArray()

                // 3. Process each item
                playlistItems.forEachIndexed { index, item ->
                    val destFile = File(tempDir, "wallpaper_$index.jpg")
                    val origFile = File(tempOriginalsDir, "original_$index.jpg")


                    try {
                        contentResolver.openInputStream(item.originalUri)?.use { input ->
                            FileOutputStream(origFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    if (item.isEdited && item.editedFilePath != null) {
                        val srcEdited = File(item.editedFilePath!!)
                        if (srcEdited.exists() && srcEdited.absolutePath != destFile.absolutePath) {
                            srcEdited.copyTo(destFile, overwrite = true)
                        }
                    } else {
                        val bitmap = decodeCenterCropBitmap(item.originalUri)
                        if (bitmap != null) {
                            FileOutputStream(destFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                            }
                        }
                    }

                    val metaObj = JSONObject()
                    metaObj.put("original", "original_$index.jpg")
                    metaObj.put("isEdited", item.isEdited)
                    if (item.matrixState != null) {
                        val matrixJson = JSONArray()
                        item.matrixState!!.forEach { matrixJson.put(it.toDouble()) }
                        metaObj.put("matrix", matrixJson)
                    }
                    metaArray.put(metaObj)


                }

                File(tempDir, "metadata.json").writeText(metaArray.toString())

                // 4. SWAP THE FOLDERS SAFELY
                val playlistDir = File(filesDir, "playlist")
                if (playlistDir.exists()) playlistDir.deleteRecursively()
                tempDir.renameTo(playlistDir)

                val originalsDir = File(filesDir, "playlist_originals")
                if (originalsDir.exists()) originalsDir.deleteRecursively()
                tempOriginalsDir.renameTo(originalsDir)

                // 5. Set Main Wallpaper
                val firstFile = File(playlistDir, "wallpaper_0.jpg")
                val activeWallpaper = File(filesDir, "wallpaper.jpg")
                if (firstFile.exists()) {
                    firstFile.copyTo(activeWallpaper, overwrite = true)
                }

                // 6. RESET ALL PREFERENCES TO ENSURE FRESH START
                val wallpaperPrefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                wallpaperPrefs.edit().clear().apply()
                getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().clear().apply()

                if(playlistItems.size > 1){
                    val nextFile = File(filesDir, "next_wallpaper.jpg")
                    val secondFile = File(playlistDir, "wallpaper_1.jpg")
                    if (secondFile.exists()) {
                        secondFile.copyTo(nextFile, overwrite = true)
                    }
                    // Tell the rotation logic that wallpaper_1 is queued, so it doesn't pick it again next time
                    wallpaperPrefs.edit().putString("last_playlist_image", "wallpaper_1.jpg").apply()
                } else if (playlistItems.size == 1) {
                    // Fallback if only 1 image exists
                    val nextFile = File(filesDir, "next_wallpaper.jpg")
                    if (firstFile.exists()) {
                        firstFile.copyTo(nextFile, overwrite = true)
                    }
                    wallpaperPrefs.edit().putString("last_playlist_image", "wallpaper_0.jpg").apply()
                }

                // --- BUG FIX: Pre-seed current theme state so it doesn't auto-rotate on first boot ---
                val currentUiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                val isNightMode = (currentUiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES)
                wallpaperPrefs.edit().putInt("active_theme_state", if (isNightMode) 1 else 0).apply()

                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "Setup complete! Now lock and unlock the screen to activate.", Toast.LENGTH_LONG).show()
                    val intent = Intent("com.app.nosatmosphereeffect.RELOAD_WALLPAPER")
                    intent.setPackage(packageName)
                    sendBroadcast(intent)
                    // Proceed to "Reapply" by showing the preview screen
                    activateService()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun decodeCenterCropBitmap(uri: Uri): Bitmap? {
        val metrics = windowManager.currentWindowMetrics.bounds
        val reqW = metrics.width()
        val reqH = metrics.height()

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

        options.inSampleSize = calculateInSampleSize(options, reqW, reqH)
        options.inJustDecodeBounds = false

        var bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        bitmap = handleExifRotation(this, uri, bitmap)

        val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val screenRatio = reqW.toFloat() / reqH.toFloat()

        val matrix = Matrix()
        val scale: Float
        if (bitmapRatio > screenRatio) {
            scale = reqH.toFloat() / bitmap.height.toFloat()
        } else {
            scale = reqW.toFloat() / bitmap.width.toFloat()
        }

        matrix.setScale(scale, scale)
        val scaledBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        val x = max(0, (scaledBitmap.width - reqW) / 2)
        val y = max(0, (scaledBitmap.height - reqH) / 2)
        val finalW = min(reqW, scaledBitmap.width - x)
        val finalH = min(reqH, scaledBitmap.height - y)

        return Bitmap.createBitmap(scaledBitmap, x, y, finalW, finalH)
    }

    private fun handleExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(input)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            input.close()

            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotation == 0f) return bitmap
            val matrix = Matrix().apply { postRotate(rotation) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch(e: Exception) { return bitmap }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun activateService() {
        try {
            val serviceClass = when(effectId) {
                "ORIGINAL" -> AtmosphereService::class.java
                "REVERSE" -> BlurToSharpService::class.java
                "FROSTED" -> FrostedService::class.java
                "FROSTED_REVERSE" -> FrostedReverseService::class.java
                "HALFTONE" -> HalftoneService::class.java
                "HALFTONE_REVERSE" -> HalftoneReverseService::class.java
                "COLORFILL" -> ColorFillService::class.java
                "COLORFILL_REVERSE" -> ColorFillReverseService::class.java
                else -> AtmosphereService::class.java
            }
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, serviceClass))
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        } finally {
            finish()
        }
    }

    private fun showApplyDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Apply Wallpaper")
            .setMessage("In the next screen, please select:\n\nSet Wallpaper > Home Screen and Lock Screen.\n\n(This ensures the lock screen effect works correctly).")
            .setPositiveButton("Set Wallpaper") { _, _ ->
                applyFromDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadExistingPlaylist() {
        val playlistDir = File(filesDir, "playlist")
        val originalsDir = File(filesDir, "playlist_originals")
        val metaFile = File(playlistDir, "metadata.json")

        if (metaFile.exists()) {
            try {
                val jsonStr = metaFile.readText()
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val origName = obj.getString("original")
                    val isEdited = obj.getBoolean("isEdited")

                    val origFile = File(originalsDir, origName)
                    val originalUri = Uri.parse("file://${origFile.absolutePath}")

                    var editedPath: String? = null
                    if (isEdited) {
                        editedPath = File(playlistDir, "wallpaper_$i.jpg").absolutePath
                    }

                    var matrixState: FloatArray? = null
                    if (obj.has("matrix")) {
                        val matrixArray = obj.getJSONArray("matrix")
                        matrixState = FloatArray(matrixArray.length())
                        for (j in 0 until matrixArray.length()) {
                            matrixState[j] = matrixArray.getDouble(j).toFloat()
                        }
                    }

                    playlistItems.add(PlaylistItem(originalUri, isEdited, editedPath, matrixState))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            // Fallback for older playlists created before this update
            val files = playlistDir.listFiles { _, name -> name.endsWith(".jpg") }
            if (!files.isNullOrEmpty()) {
                files.sortBy { it.nameWithoutExtension.substringAfter('_').toIntOrNull() ?: 0 }
                files.forEach { file ->
                    playlistItems.add(PlaylistItem(Uri.parse("file://${file.absolutePath}")))
                }
            }
        }
    }

    private fun applyFromDialog(){
        if (playlistItems.isEmpty()) {
            Toast.makeText(this, "Playlist is empty", Toast.LENGTH_SHORT).show()
        } else {
            applyPlaylist()
        }
    }
}