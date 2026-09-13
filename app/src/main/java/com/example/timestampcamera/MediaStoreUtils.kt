package com.example.timestampcamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Simpan bitmap foto ke MediaStore, disimpan di Pictures/TimestampCamera/Photos --
 * subfolder terpisah dari video, supaya rapi saat dilihat lewat Files/Google Photos.
 */
fun saveBitmapToMediaStore(
    context: Context,
    bitmap: Bitmap,
    maxSizeBytes: Int = 200 * 1024
): Uri? {
    val timestamp = SimpleDateFormat(
        "yyyyMMdd_HHmmss",
        Locale.getDefault()
    ).format(System.currentTimeMillis())

    val fileName = "IMG_$timestamp.jpg"

    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Subfolder "Photos" di dalam folder app -- dipisah dari video supaya
            // tidak campur aduk saat dilihat lewat Files/Google Photos.
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TimestampCamera")
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

    val compressedBytes = compressBitmapToMaxSize(bitmap, maxSizeBytes)

    uri?.let {
        resolver.openOutputStream(it)?.use { outputStream ->
            outputStream.write(compressedBytes)
        }
    }

    return uri
}