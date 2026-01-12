package com.example.cam_rng

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import com.example.cam_rng.databinding.ActivityGalleryBinding
import com.example.cam_rng.databinding.ItemSavedMomentBinding
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryActivity : Activity() {
    private lateinit var binding: ActivityGalleryBinding
    private val moments = mutableListOf<SavedMoment>()
    private lateinit var adapter: SavedMomentAdapter
    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SavedMomentAdapter(layoutInflater, moments)
        binding.galleryList.adapter = adapter
        binding.galleryList.setOnItemClickListener { _, _, position, _ ->
            val moment = moments[position]
            showEditDialog(moment)
        }
    }

    override fun onResume() {
        super.onResume()
        loadMoments()
    }

    private fun loadMoments() {
        moments.clear()
        moments.addAll(SavedMomentsStore.load(this))
        adapter.notifyDataSetChanged()
        val empty = moments.isEmpty()
        binding.galleryEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.galleryList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun showEditDialog(moment: SavedMoment) {
        val input = EditText(this).apply {
            setText(moment.annotation)
            setSelection(text?.length ?: 0)
            hint = getString(R.string.annotation_hint)
            filters = arrayOf(InputFilter.LengthFilter(MAX_ANNOTATION_CHARS))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_annotation_title)
            .setView(input)
            .setPositiveButton(R.string.save_dialog_save) { _, _ ->
                val updated = input.text?.toString()?.trim().orEmpty()
                if (SavedMomentsStore.updateAnnotation(this, moment.id, updated)) {
                    val index = moments.indexOfFirst { it.id == moment.id }
                    if (index != -1) {
                        moments[index] = moment.copy(annotation = updated)
                        adapter.notifyDataSetChanged()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class SavedMomentAdapter(
        private val inflater: LayoutInflater,
        private val items: List<SavedMoment>
    ) : BaseAdapter() {
        private val thumbSizePx = dpToPx(84)

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): SavedMoment = items[position]

        override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val binding = if (convertView == null) {
                ItemSavedMomentBinding.inflate(inflater, parent, false)
            } else {
                ItemSavedMomentBinding.bind(convertView)
            }
            val moment = getItem(position)
            val resultLine = buildResultLine(moment)
            binding.resultText.text = resultLine

            val annotation = moment.annotation.trim()
            if (annotation.isEmpty()) {
                binding.annotationText.text = getString(R.string.gallery_annotation_placeholder)
                binding.annotationText.setTextColor(getColor(R.color.text_hint_contrast))
            } else {
                binding.annotationText.text = annotation
                binding.annotationText.setTextColor(getColor(R.color.text_high_contrast))
            }

            binding.dateText.text = dateFormat.format(Date(moment.createdAt))
            bindThumbnail(binding.photoThumb, moment.photoPath)
            return binding.root
        }

        private fun buildResultLine(moment: SavedMoment): String {
            val title = moment.resultTitle.trim()
            val value = moment.resultValue.trim()
            return if (value.isNotEmpty() && value != title) {
                getString(R.string.gallery_result_format, title, value)
            } else {
                title
            }
        }

        private fun bindThumbnail(imageView: ImageView, photoPath: String) {
            val file = File(photoPath)
            if (!file.exists()) {
                imageView.setImageDrawable(null)
                return
            }
            val bitmap = decodeThumbnail(photoPath, thumbSizePx, thumbSizePx)
            imageView.setImageBitmap(bitmap)
        }
    }

    private fun decodeThumbnail(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)
        options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
        options.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
        return applyExifOrientation(path, bitmap)
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun applyExifOrientation(path: String, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(path)
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val MAX_ANNOTATION_CHARS = 140
    }
}
