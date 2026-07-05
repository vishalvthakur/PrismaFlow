package com.example

import android.content.Context
import android.net.Uri
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class QueuedVideo(
    val id: String,
    val originalUri: String,
    val name: String,
    val size: String,
    val styleMode: String,
    var status: String = "Pending", // "Pending", "Processing", "Completed", "Failed"
    var progress: Int = 0,
    var outputPath: String? = null,
    var errorMessage: String? = null,
    var addedTime: Long = System.currentTimeMillis(),
    val targetResolution: String = "480p",
    val targetFps: Int = 15
)

object QueueManager {
    private const val PREFS_NAME = "video_queue_prefs"
    private const val KEY_QUEUE = "video_queue"

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val listType = Types.newParameterizedType(List::class.java, QueuedVideo::class.java)
    private val adapter = moshi.adapter<List<QueuedVideo>>(listType)

    @Synchronized
    fun getQueue(context: Context): List<QueuedVideo> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        return try {
            adapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveQueue(context: Context, queue: List<QueuedVideo>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val json = adapter.toJson(queue)
            prefs.edit().putString(KEY_QUEUE, json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun addToQueue(context: Context, video: QueuedVideo) {
        val current = getQueue(context).toMutableList()
        current.add(video)
        saveQueue(context, current)
    }

    @Synchronized
    fun updateVideoStatus(context: Context, id: String, status: String, progress: Int = 0, outputPath: String? = null, errorMessage: String? = null) {
        val current = getQueue(context).toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index != -1) {
            val video = current[index]
            current[index] = video.copy(
                status = status,
                progress = progress,
                outputPath = outputPath ?: video.outputPath,
                errorMessage = errorMessage ?: video.errorMessage
            )
            saveQueue(context, current)
        }
    }

    @Synchronized
    fun removeVideo(context: Context, id: String) {
        val current = getQueue(context).filter { it.id != id }
        saveQueue(context, current)
    }

    @Synchronized
    fun clearQueue(context: Context) {
        saveQueue(context, emptyList())
    }
}
