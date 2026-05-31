package com.app.nosatmosphereeffect.activity

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
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
import java.io.File
import java.io.FileOutputStream

class MultiImageCropActivity : AppCompatActivity() {

    private lateinit var cropView: TouchImageView
    private var sourceUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make it full screen like CropActivity
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        val windowController = WindowCompat.getInsetsController(window, window.decorView)
        windowController.hide(WindowInsetsCompat.Type.systemBars())
        windowController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContentView(R.layout.activity_crop_multi)

        cropView = findViewById(R.id.cropImageView)
        val btnDone = findViewById<Button>(R.id.btnSaveCrop) // Ensure ID matches layout

        sourceUri = intent.data
        val savedMatrix = intent.getFloatArrayExtra("MATRIX_STATE") // Receive State

        if (sourceUri != null) {
            loadImage(sourceUri!!, savedMatrix)
        } else {
            Toast.makeText(this, R.string.error_no_image_data, Toast.LENGTH_SHORT).show()
            finish()
        }

        btnDone.setOnClickListener {
            val croppedBitmap = cropView.getCroppedBitmap()
                // Get the current zoom state to save it
                val currentMatrix = cropView.getCurrentMatrixValues()
                saveAndReturnResult(croppedBitmap, currentMatrix)
        }
    }

    private fun loadImage(uri: Uri, savedMatrix: FloatArray?) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            // Handle Rotation
            val rotatedBitmap = handleExifRotation(uri, bitmap)

            // KEY FIX: Use setInitialImage instead of setImageBitmap
            // This initializes bounds and restores zoom state if provided
            cropView.setInitialImage(rotatedBitmap, savedMatrix)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, R.string.error_failed_load_image, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAndReturnResult(bitmap: Bitmap, matrixValues: FloatArray) {
        try {
            val filename = "cropped_playlist_${System.currentTimeMillis()}.jpg"
            val destFile = File(cacheDir, filename)

            val out = FileOutputStream(destFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush()
            out.close()

            val resultIntent = Intent()
            resultIntent.putExtra("CROPPED_IMAGE_PATH", destFile.absolutePath)
            // Return the Matrix State so we can zoom out later
            resultIntent.putExtra("MATRIX_STATE", matrixValues)

            setResult(RESULT_OK, resultIntent)
            finish()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, R.string.error_failed_save_image, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        try {
            val input = contentResolver.openInputStream(uri) ?: return bitmap
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
        } catch (_: Exception) {
            return bitmap
        }
    }
}