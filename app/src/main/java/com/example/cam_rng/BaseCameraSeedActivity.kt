package com.example.cam_rng

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SeedResult(val statusResId: Int, val resultText: CharSequence? = null)

abstract class BaseCameraSeedActivity : Activity() {
    protected abstract val statusText: TextView
    protected abstract val previewImage: ImageView
    protected abstract val actionButton: Button
    protected abstract val saveButton: Button
    protected open val processingDelayMs: Long
        get() = resources.getInteger(R.integer.processing_delay_ms).toLong()

    private val photoDirName: String by lazy { getString(R.string.photo_dir_name) }
    private val photoMimeType: String by lazy { getString(R.string.photo_mime_jpeg) }
    private val photoFilePrefix: String by lazy { getString(R.string.photo_file_prefix) }

    @Volatile
    private var capturing = false

    private var currentPhotoFile: File? = null
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
    }

    protected open fun onCaptureFailed() {
        resetUi()
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
            startEllipsisAnimation()
            previewImage.postDelayed(
                {
                    stopEllipsisAnimation()
                    renderSeedResult(result)
                },
                processingDelayMs
            )
        } catch (exc: IOException) {
            showError(R.string.status_error)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STORAGE) {
            return
        }
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            val photoFile = currentPhotoFile
            if (photoFile != null && photoFile.exists()) {
                savePhotoToGallery(photoFile)
            } else {
                showError(R.string.status_save_error)
            }
        } else {
            setStatus(R.string.status_storage_permission)
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
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: cacheDir
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
        if (photoFile == null || !photoFile.exists()) {
            showError(R.string.status_save_error)
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_STORAGE
            )
            return
        }

        savePhotoToGallery(photoFile)
    }

    private fun savePhotoToGallery(photoFile: File) {
        stopEllipsisAnimation()
        saveButton.isEnabled = false
        setStatus(R.string.status_saving)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, photoFile.name)
                put(MediaStore.Images.Media.MIME_TYPE, photoMimeType)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/$photoDirName"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                onSaveFailed()
                return
            }
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    photoFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Unable to open output stream")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                onSaveSuccess(photoFile)
            } catch (exc: IOException) {
                resolver.delete(uri, null, null)
                onSaveFailed()
            }
        } else {
            val picturesDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val appDir = File(picturesDir, photoDirName)
            if (!appDir.exists() && !appDir.mkdirs()) {
                onSaveFailed()
                return
            }
            val destFile = File(appDir, photoFile.name)
            try {
                photoFile.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(destFile.absolutePath),
                    arrayOf(photoMimeType),
                    null
                )
                onSaveSuccess(photoFile)
            } catch (exc: IOException) {
                onSaveFailed()
            }
        }
    }

    private fun onSaveSuccess(photoFile: File) {
        setStatus(R.string.status_saved)
        cleanupPhoto(photoFile)
        setSaveAvailable(false)
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
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return
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

    companion object {
        private const val REQUEST_IMAGE_CAPTURE = 2
        private const val REQUEST_STORAGE = 3
        private const val ELLIPSIS_INTERVAL_MS = 250L
    }
}
