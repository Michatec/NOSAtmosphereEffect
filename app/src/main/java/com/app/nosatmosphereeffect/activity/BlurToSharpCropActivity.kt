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
import com.app.nosatmosphereeffect.service.BlurToSharpService
import com.app.nosatmosphereeffect.service.ColorFillReverseService
import com.app.nosatmosphereeffect.service.ColorFillService
import com.app.nosatmosphereeffect.service.FrostedReverseService
import com.app.nosatmosphereeffect.service.HalftoneReverseService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class BlurToSharpCropActivity : AppCompatActivity() {
    private var effectId: String = "REVERSE"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        val windowController = WindowCompat.getInsetsController(window, window.decorView)
        windowController.isAppearanceLightStatusBars = false
        windowController.isAppearanceLightNavigationBars = false

        windowController.hide(WindowInsetsCompat.Type.systemBars())
        windowController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContentView(R.layout.activity_crop_blur_to_sharp)

        effectId = intent.getStringExtra("EFFECT_ID") ?: "REVERSE"

        val cropView = findViewById<TouchImageView>(R.id.cropImageView)
        val btnSave = findViewById<Button>(R.id.btnSaveCrop)

        btnSave.setText(R.string.action_apply)

        val uri = intent.data ?: run {
            Toast.makeText(this, "No Image Data Found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Thread {
            try {
                val correctedBitmap = decodeSampledBitmapFromUri(this, uri, 4096, 4096)
                runOnUiThread {
                    if (correctedBitmap != null) {
                        cropView.setInitialImage(correctedBitmap)
                    } else {
                        Toast.makeText(this, "Could not load image format.", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
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
    private fun decodeSampledBitmapFromUri(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        var inputStream: InputStream? = null
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            inputStream = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            inputStream = context.contentResolver.openInputStream(uri)
            val rawBitmap = BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            if (rawBitmap == null) return null
            return handleExifRotation(context, uri, rawBitmap)

        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return null
        } finally {
            try { inputStream?.close() } catch (e: Exception) {Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()}
        }
    }

    private fun handleExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        var inputStream: InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) return bitmap

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

            if (rotationInDegrees == 0f) return bitmap

            val matrix = Matrix()
            matrix.postRotate(rotationInDegrees)
            val rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )

            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }
            return rotatedBitmap

        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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
            .setTitle("Apply Wallpaper")
            .setMessage("In the next screen, please select:\n\nSet Wallpaper > Home Screen and Lock Screen.\n\n(This ensures the lock screen effect works correctly).")
            .setPositiveButton("Set Wallpaper") { _, _ ->
                applyWallpaper(bitmap)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyWallpaper(bitmap: Bitmap) {
        Toast.makeText(this, "Applying...", Toast.LENGTH_SHORT).show()

        Thread {
            try {

                getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()

                getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()

                val playlistDir = File(filesDir, "playlist")
                if (playlistDir.exists()) playlistDir.deleteRecursively()

                val nextWallpaper = File(filesDir, "next_wallpaper.jpg")
                if (nextWallpaper.exists()) nextWallpaper.delete()

                saveFixedWallpaper(bitmap)

                runOnUiThread {
                    Toast.makeText(this, "Setup complete! Now lock and unlock the screen to activate.", Toast.LENGTH_LONG).show()
                    val intent = Intent("com.app.nosatmosphereeffect.RELOAD_WALLPAPER")
                    intent.setPackage(packageName)
                    sendBroadcast(intent)

                    Toast.makeText(this, "Setup complete! Now lock and unlock the screen to activate.", Toast.LENGTH_LONG).show()

                    activateService()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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
            val serviceClass = if (effectId == "FROSTED_REVERSE") {
                FrostedReverseService::class.java
            } else if (effectId == "HALFTONE_REVERSE"){
                HalftoneReverseService::class.java
            } else if (effectId == "COLORFILL_REVERSE"){
                ColorFillReverseService::class.java
            } else {
                BlurToSharpService::class.java
            }

            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent.putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this, serviceClass)
            )
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
            startActivity(intent)
        } finally {
            finish()
        }
    }
}