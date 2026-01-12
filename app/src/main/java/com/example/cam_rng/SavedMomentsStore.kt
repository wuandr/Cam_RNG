package com.example.cam_rng

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedMoment(
    val id: String,
    val createdAt: Long,
    val mode: String,
    val resultTitle: String,
    val resultValue: String,
    val annotation: String,
    val photoPath: String
)

object SavedMomentsStore {
    private const val FILE_NAME = "saved_moments.json"

    fun load(context: Context): MutableList<SavedMoment> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            return mutableListOf()
        }
        val content = file.readText()
        if (content.isBlank()) {
            return mutableListOf()
        }
        val array = try {
            JSONArray(content)
        } catch (exc: Exception) {
            return mutableListOf()
        }
        val moments = mutableListOf<SavedMoment>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val id = obj.optString(KEY_ID)
            if (id.isBlank()) {
                continue
            }
            moments.add(
                SavedMoment(
                    id = id,
                    createdAt = obj.optLong(KEY_CREATED_AT),
                    mode = obj.optString(KEY_MODE),
                    resultTitle = obj.optString(KEY_RESULT_TITLE),
                    resultValue = obj.optString(KEY_RESULT_VALUE),
                    annotation = obj.optString(KEY_ANNOTATION),
                    photoPath = obj.optString(KEY_PHOTO_PATH)
                )
            )
        }
        return moments
    }

    fun add(context: Context, moment: SavedMoment): Boolean {
        val moments = load(context)
        moments.add(0, moment)
        return write(context, moments)
    }

    fun updateAnnotation(context: Context, id: String, annotation: String): Boolean {
        val moments = load(context)
        val index = moments.indexOfFirst { it.id == id }
        if (index == -1) {
            return false
        }
        val existing = moments[index]
        moments[index] = existing.copy(annotation = annotation)
        return write(context, moments)
    }

    private fun write(context: Context, moments: List<SavedMoment>): Boolean {
        val array = JSONArray()
        for (moment in moments) {
            val obj = JSONObject()
            obj.put(KEY_ID, moment.id)
            obj.put(KEY_CREATED_AT, moment.createdAt)
            obj.put(KEY_MODE, moment.mode)
            obj.put(KEY_RESULT_TITLE, moment.resultTitle)
            obj.put(KEY_RESULT_VALUE, moment.resultValue)
            obj.put(KEY_ANNOTATION, moment.annotation)
            obj.put(KEY_PHOTO_PATH, moment.photoPath)
            array.put(obj)
        }
        return try {
            File(context.filesDir, FILE_NAME).writeText(array.toString())
            true
        } catch (exc: Exception) {
            false
        }
    }

    private const val KEY_ID = "id"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_MODE = "mode"
    private const val KEY_RESULT_TITLE = "resultTitle"
    private const val KEY_RESULT_VALUE = "resultValue"
    private const val KEY_ANNOTATION = "annotation"
    private const val KEY_PHOTO_PATH = "photoPath"
}
