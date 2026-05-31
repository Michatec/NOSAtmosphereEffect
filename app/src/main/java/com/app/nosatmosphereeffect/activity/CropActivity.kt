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
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.helper.TouchImageView
import com.app.nosatmosphereeffect.service.AtmosphereService
import com.app.nosatmosphereeffect.service.ColorFillService
import com.app.nosatmosphereeffect.service.FrostedService
import com.app.nosatmosphereeffect.service.HalftoneService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import androidx.core.content.edit

class CropActivity : AppCompatActivity() {
    private var effectId: String = "ORIGINAL" // Default
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        val windowController = WindowCompat.getInsetsController(window, window.decorView)
        windowController.isAppearanceLightStatusBars = false
        windowController.isAppearanceLightNavigationBars = false

        windowController.hide(WindowInsetsCompat.Type.systemBars())
        windowController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContentView(R.layout.activity_crop)

        effectId = intent.getStringExtra("EFFECT_ID") ?: "ORIGINAL"

        val cropView = findViewById<TouchImageView>(R.id.cropImageView)
        val btnSave = findViewById<Button>(R.id.btnSaveCrop)

        btnSave.setText(R.string.action_apply)

        val uri = intent.data ?: run {
            Toast.makeText(this, R.string.error_no_image_data, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Use a background thread to load heavy images to prevent UI freeze
        Thread {
            try {
                // Load safely with Downsampling + Rotation
                val correctedBitmap = decodeSampledBitmapFromUri(this, uri, 4096, 4096)

                runOnUiThread {
                    if (correctedBitmap != null) {
                        cropView.setInitialImage(correctedBitmap)
                    } else {
                        Toast.makeText(this, R.string.error_invalid_format, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.error_prefix, e.message), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()

        btnSave.setOnClickListener {
            val cropped = cropView.getCroppedBitmap()
            showApplyDialog(cropped)
        }
    }

    // --- ROBUST IMAGE LOADER ---
    // 1. Checks Image Size first (without loading to memory)
    // 2. Calculates Scale Factor (to prevent OutOfMemory on 200MP photos)
    // 3. Decodes & Rotates based on Exif (Supports HEIC, WebP, JPG)
    private fun decodeSampledBitmapFromUri(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        var inputStream: InputStream? = null
        try {
            // A. First pass: Decode dimensions only
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            inputStream = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            // B. Calculate inSampleSize (Scale down factor)
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            // Preferred config for high quality but lower memory than HARDWARE
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            // C. Decode bitmap with inSampleSize
            inputStream = context.contentResolver.openInputStream(uri)
            val rawBitmap = BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            if (rawBitmap == null) return null

            // D. Handle Rotation (HEIC/Samsung often needs this)
            return handleExifRotation(context, uri, rawBitmap)

        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, context.getString(R.string.error_prefix, e.message), Toast.LENGTH_LONG).show()
            }
            return null
        } finally {
            try { inputStream?.close() } catch (e: Exception) {Toast.makeText(this, getString(R.string.error_prefix, e.message), Toast.LENGTH_LONG).show()}
        }
    }

    private fun handleExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        var inputStream: InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) return bitmap

            // Use ExifInterface (Supports HEIC on API 28+)
            val exifInterface = ExifInterface(inputStream)
            val orientation = exifInterface.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val rotationInDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            // If no rotation needed, return original
            if (rotationInDegrees == 0f) return bitmap

            // Create rotated bitmap
            val matrix = Matrix()
            matrix.postRotate(rotationInDegrees)
            val rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )

            if (rotatedBitmap != bitmap) {
                bitmap.recycle() // Clean up old memory
            }
            return rotatedBitmap

        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, context.getString(R.string.error_prefix, e.message), Toast.LENGTH_LONG).show()
            }
            return bitmap
        } finally {
            inputStream?.close()
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        // 1. Find the largest dimension of the original image
        val maxImageDimension = kotlin.math.max(height, width)

        // 2. Find the texture limit (e.g., 4096)
        // Take the min of reqWidth/Height to ensure we stay within the strictest limit provided
        val maxTextureSize = kotlin.math.min(reqWidth, reqHeight)

        // 3. Only scale if the image is actually larger than the limit
        if (maxImageDimension > maxTextureSize) {

            // 4. Calculate the Factor: How many times larger is the image?
            val factor = maxImageDimension.toFloat() / maxTextureSize.toFloat()

            // 5. Find the nearest Power of 2 that covers this factor
            while (inSampleSize < factor) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    private fun showApplyDialog(bitmap: Bitmap) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_apply_title)
            .setMessage(R.string.dialog_apply_message)
            .setPositiveButton(R.string.action_set_wallpaper) { _, _ ->
                applyWallpaper(bitmap)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun applyWallpaper(bitmap: Bitmap) {
        Toast.makeText(this, R.string.status_applying, Toast.LENGTH_SHORT).show()

        Thread {
            try {

                getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .edit {
                        clear()
                    }

                getSharedPreferences("wallpaper_prefs", MODE_PRIVATE)
                    .edit {
                        clear()
                    }

                val playlistDir = File(filesDir, "playlist")
                if (playlistDir.exists()) playlistDir.deleteRecursively()

                val nextWallpaper = File(filesDir, "next_wallpaper.jpg")
                if (nextWallpaper.exists()) nextWallpaper.delete()

                saveFixedWallpaper(bitmap)

                runOnUiThread {
                    Toast.makeText(this, R.string.status_setup_complete, Toast.LENGTH_LONG).show()
                    val intent = Intent("com.app.nosatmosphereeffect.RELOAD_WALLPAPER")
                    intent.setPackage(packageName)
                    sendBroadcast(intent)

                    Toast.makeText(this, R.string.status_setup_complete, Toast.LENGTH_LONG).show()

                    activateService()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.error_prefix, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun saveFixedWallpaper(bitmap: Bitmap) {
        val file = File(filesDir, "wallpaper.jpg")
        if (file.exists()) file.delete()
        val out = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        out.flush()
        out.close()
    }
    private fun activateService() {
        try {
            val serviceClass = when (effectId) {
                "FROSTED" -> {
                    FrostedService::class.java
                }
                "HALFTONE" -> {
                    HalftoneService::class.java
                }
                "COLORFILL" -> {
                    ColorFillService::class.java
                }
                else -> {
                    AtmosphereService::class.java
                }
            }

            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent.putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this, serviceClass)
            )
            startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
            startActivity(intent)
        } finally {
            finish()
        }
    }
}