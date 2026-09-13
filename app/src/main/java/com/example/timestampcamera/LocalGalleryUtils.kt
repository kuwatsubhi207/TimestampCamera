package com.example.timestampcamera

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class MediaType { PHOTO, VIDEO }

data class LocalMedia(
    val uri: Uri,
    val dateAddedSeconds: Long,
    val type: MediaType
)

private const val PHOTO_RELATIVE_PATH = "%Pictures/TimestampCamera%"
private const val VIDEO_RELATIVE_PATH = "%Movies/TimestampCamera%"

/**
 * Ambil semua foto DAN video hasil aplikasi ini, dari dua folder terpisah:
 * - Foto: Pictures/TimestampCamera (MediaStore.Images)
 * - Video: Movies/TimestampCamera (MediaStore.Video)
 *
 * Sengaja pakai 2 query terpisah (bukan MediaStore.Files gabungan) -- MediaStore.Files
 * dengan filter MEDIA_TYPE pernah dilaporkan tidak konsisten mengembalikan RELATIVE_PATH
 * di sebagian OEM/ROM. Query per-collection (Images/Video) lebih predictable karena itu
 * memang collection native-nya masing-masing tipe. Hasil keduanya digabung jadi satu
 * list, terurut terbaru dulu.
 */
suspend fun listLocalMedia(context: Context): List<LocalMedia> {
    return withContext(Dispatchers.IO) {
        val photos = queryMedia(
            context = context,
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            relativePathPattern = PHOTO_RELATIVE_PATH,
            type = MediaType.PHOTO
        )
        val videos = queryMedia(
            context = context,
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            relativePathPattern = VIDEO_RELATIVE_PATH,
            type = MediaType.VIDEO
        )
        (photos + videos).sortedByDescending { it.dateAddedSeconds }
    }
}

private fun queryMedia(
    context: Context,
    collection: Uri,
    relativePathPattern: String,
    type: MediaType
): List<LocalMedia> {
    val result = mutableListOf<LocalMedia>()
    try {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(relativePathPattern)
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateAdded = cursor.getLong(dateColumn)
                val uri = Uri.withAppendedPath(collection, id.toString())
                result.add(LocalMedia(uri, dateAdded, type))
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return result
}