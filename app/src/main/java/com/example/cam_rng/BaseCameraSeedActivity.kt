package com.example.cam_rng

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Environment
import android.provider.MediaStore
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SeedResult(
    val statusResId: Int,
    val resultText: CharSequence? = null,
    val galleryTitle: String,
    val galleryValue: String
)

abstract class BaseCameraSeedActivity : Activity() {
    protected abstract val statusText: TextView
    protected open val resultText: TextView? = null
    protected abstract val previewImage: ImageView
    protected abstract val actionButton: Button
    protected abstract val saveButton: Button
    protected open val processingDelayMs: Long
        get() = resources.getInteger(R.integer.processing_delay_ms).toLong()

    private val photoFilePrefix: String by lazy { getString(R.string.photo_file_prefix) }
    private val resultAccentColor: Int by lazy { getColor(R.color.result_accent) }

    @Volatile
    private var capturing = false

    private var currentPhotoFile: File? = null
    private var currentSeedResult: SeedResult? = null
    private var ellipsisRunnable: Runnable? = null
    private var ellipsisTick = 0

    protected fun initCameraSeedUi() {
        cleanupStalePhotos()
        setSaveAvailable(false)
        actionButton.setOnClickListener { launchCamera() }
        saveButton.setOnClickListener { handleSaveClick() }
    }

    protected open fun prepareCapture(): Boolean = true

    protected abstract fun buildSeedResult(seedBytes: ByteArray): SeedResult
    protected abstract fun renderSeedResult(result: SeedResult)

    protected open fun resetUi() {}
    protected open fun onCaptureCanceled() {
        resetUi()
        currentSeedResult = null
    }

    protected open fun onCaptureFailed() {
        resetUi()
        currentSeedResult = null
    }

    protected open fun onAfterSaveSuccess() {}

    protected fun setStatus(messageId: Int, vararg formatArgs: Any) {
        stopEllipsisAnimation()
        statusText.text = getString(messageId, *formatArgs)
    }

    protected fun showError(messageId: Int, vararg formatArgs: Any) {
        setStatus(messageId, *formatArgs)
        actionButton.isEnabled = true
        capturing = false
        setSaveAvailable(false)
        cleanupPhoto(currentPhotoFile)
        onCaptureFailed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE_CAPTURE) {
            return
        }

        val photoFile = currentPhotoFile
        capturing = false
        actionButton.isEnabled = true

        if (resultCode != RESULT_OK) {
            setStatus(R.string.status_canceled)
            onCaptureCanceled()
            cleanupPhoto(photoFile)
            setSaveAvailable(false)
            return
        }

        if (photoFile == null || !photoFile.exists()) {
            showError(R.string.status_error)
            return
        }

        try {
            val bytes = photoFile.readBytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                val previewBitmap = applyExifOrientation(photoFile, bitmap)
                previewImage.setImageBitmap(previewBitmap)
            }
            setSaveAvailable(true)
            val result = buildSeedResult(bytes)
            currentSeedResult = result
            startEllipsisAnimation()
            previewImage.postDelayed(
                {
                    stopEllipsisAnimation()
                    renderSeedResult(result)
                    highlightResult()
                },
                processingDelayMs
            )
        } catch (exc: IOException) {
            showError(R.string.status_error)
        }
    }

    private fun launchCamera() {
        if (capturing) {
            return
        }

        if (!prepareCapture()) {
            return
        }

        cleanupPhoto(currentPhotoFile)
        setSaveAvailable(false)
        currentSeedResult = null

        val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (captureIntent.resolveActivity(packageManager) == null) {
            showError(R.string.status_no_camera)
            return
        }

        val photoFile = try {
            createImageFile()
        } catch (exc: IOException) {
            showError(R.string.status_error)
            return
        }

        val photoUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            photoFile
        )

        currentPhotoFile = photoFile
        captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        captureIntent.addFlags(
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        capturing = true
        actionButton.isEnabled = false
        setStatus(R.string.status_capturing)
        startActivityForResult(captureIntent, REQUEST_IMAGE_CAPTURE)
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = getTempPhotoDir()
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        return File.createTempFile("${photoFilePrefix}${timeStamp}_", ".jpg", storageDir)
    }

    private fun cleanupPhoto(photoFile: File?) {
        if (photoFile != null && photoFile.exists()) {
            photoFile.delete()
        }
        if (photoFile == currentPhotoFile) {
            currentPhotoFile = null
        }
    }

    private fun handleSaveClick() {
        val photoFile = currentPhotoFile
        val seedResult = currentSeedResult
        if (photoFile == null || !photoFile.exists() || seedResult == null) {
            showError(R.string.status_save_error)
            return
        }

        promptForAnnotation(photoFile, seedResult)
    }

    private fun promptForAnnotation(photoFile: File, seedResult: SeedResult) {
        val input = EditText(this).apply {
            hint = getString(R.string.annotation_hint)
            filters = arrayOf(InputFilter.LengthFilter(MAX_ANNOTATION_CHARS))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.save_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.save_dialog_save) { _, _ ->
                val annotation = input.text?.toString()?.trim().orEmpty()
                savePhotoToAppStorage(photoFile, seedResult, annotation)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun savePhotoToAppStorage(
        photoFile: File,
        seedResult: SeedResult,
        annotation: String
    ) {
        stopEllipsisAnimation()
        saveButton.isEnabled = false
        setStatus(R.string.status_saving)

        val savedFile = copyPhotoToSavedDir(photoFile)
        if (savedFile == null) {
            onSaveFailed()
            return
        }

        val added = SavedMomentsStore.add(
            this,
            SavedMoment(
                id = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                mode = seedResult.galleryTitle,
                resultTitle = seedResult.galleryTitle,
                resultValue = seedResult.galleryValue,
                annotation = annotation,
                photoPath = savedFile.absolutePath
            )
        )
        if (!added) {
            savedFile.delete()
            onSaveFailed()
            return
        }
        onSaveSuccess(photoFile)
    }

    private fun copyPhotoToSavedDir(photoFile: File): File? {
        val savedDir = getSavedPhotoDir()
        if (!savedDir.exists() && !savedDir.mkdirs()) {
            return null
        }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val destFile = File(
            savedDir,
            "${photoFilePrefix}Saved_${timeStamp}_${UUID.randomUUID().toString().take(8)}.jpg"
        )
        return try {
            photoFile.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destFile
        } catch (exc: IOException) {
            null
        }
    }

    private fun onSaveSuccess(photoFile: File) {
        setStatus(R.string.status_saved)
        cleanupPhoto(photoFile)
        setSaveAvailable(false)
        currentSeedResult = null
        onAfterSaveSuccess()
    }

    private fun onSaveFailed() {
        setStatus(R.string.status_save_error)
        saveButton.isEnabled = true
    }

    private fun setSaveAvailable(available: Boolean) {
        saveButton.isEnabled = available
        saveButton.visibility = if (available) View.VISIBLE else View.GONE
    }

    private fun cleanupStalePhotos() {
        val storageDir = getTempPhotoDir()
        val files = storageDir.listFiles { file ->
            file.isFile && file.name.startsWith(photoFilePrefix) && file.extension.equals("jpg", true)
        } ?: return
        for (file in files) {
            if (file != currentPhotoFile) {
                file.delete()
            }
        }
    }

    private fun applyExifOrientation(photoFile: File, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(photoFile.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (exc: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    private fun getTempPhotoDir(): File {
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: cacheDir
        return File(baseDir, TEMP_DIR_NAME)
    }

    private fun getSavedPhotoDir(): File {
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        return File(baseDir, SAVED_DIR_NAME)
    }

    private fun startEllipsisAnimation() {
        stopEllipsisAnimation()
        ellipsisTick = 0
        val runnable = object : Runnable {
            override fun run() {
                val dots = when (ellipsisTick % 4) {
                    1 -> "."
                    2 -> ".."
                    3 -> "..."
                    else -> ""
                }
                statusText.text = getString(R.string.status_processing) + dots
                ellipsisTick++
                statusText.postDelayed(this, ELLIPSIS_INTERVAL_MS)
            }
        }
        ellipsisRunnable = runnable
        statusText.post(runnable)
    }

    private fun stopEllipsisAnimation() {
        val runnable = ellipsisRunnable
        if (runnable != null) {
            statusText.removeCallbacks(runnable)
            ellipsisRunnable = null
        }
    }

    private fun highlightResult() {
        statusText.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        statusText.playSoundEffect(SoundEffectConstants.CLICK)
        pulseText(statusText)
        val resultView = resultText
        if (resultView != null && resultView.visibility == View.VISIBLE && resultView.text.isNotEmpty()) {
            pulseText(resultView)
        }
    }

    private fun pulseText(textView: TextView) {
        val baseColor = textView.currentTextColor
        textView.animate().cancel()
        textView.scaleX = PULSE_START_SCALE
        textView.scaleY = PULSE_START_SCALE
        textView.setTextColor(resultAccentColor)
        textView.animate()
            .scaleX(PULSE_PEAK_SCALE)
            .scaleY(PULSE_PEAK_SCALE)
            .setDuration(PULSE_UP_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                textView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(PULSE_DOWN_MS)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            .start()
        animateTextColor(textView, resultAccentColor, baseColor)
    }

    private fun animateTextColor(textView: TextView, startColor: Int, endColor: Int) {
        ValueAnimator.ofArgb(startColor, endColor).apply {
            duration = COLOR_FADE_MS
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                textView.setTextColor(color)
            }
        }.start()
    }

    companion object {
        private const val REQUEST_IMAGE_CAPTURE = 2
        private const val ELLIPSIS_INTERVAL_MS = 250L
        private const val PULSE_UP_MS = 180L
        private const val PULSE_DOWN_MS = 140L
        private const val COLOR_FADE_MS = 600L
        private const val PULSE_START_SCALE = 0.96f
        private const val PULSE_PEAK_SCALE = 1.08f
        private const val MAX_ANNOTATION_CHARS = 140
        private const val TEMP_DIR_NAME = "temp"
        private const val SAVED_DIR_NAME = "saved"
    }
}
