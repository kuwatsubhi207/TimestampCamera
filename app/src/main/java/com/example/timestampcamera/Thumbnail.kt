package com.example.timestampcamera

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mengambil foto terakhir dari folder Pictures/TimestampCamera di galeri,
 * di-downsample supaya ukurannya kecil (cocok untuk thumbnail).
 */
suspend fun loadLastPhotoThumbnail(context: Context, targetSize: Int = 200): Pair<Uri, Bitmap>? {
    return withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.RELATIVE_PATH
            )
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%Pictures/TimestampCamera%")
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            var found: Pair<Uri, Bitmap>? = null

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val id = cursor.getLong(idColumn)
                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )

                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val boundsOptions = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeStream(input, null, boundsOptions)

                        context.contentResolver.openInputStream(uri)?.use { input2 ->
                            var sampleSize = 1
                            while ((boundsOptions.outWidth / sampleSize) > targetSize ||
                                (boundsOptions.outHeight / sampleSize) > targetSize
                            ) {
                                sampleSize *= 2
                            }
                            val decodeOptions = BitmapFactory.Options().apply {
                                inSampleSize = sampleSize
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }
                            val bitmap = BitmapFactory.decodeStream(input2, null, decodeOptions)
                            if (bitmap != null) {
                                found = uri to bitmap
                            }
                        }
                    }
                }
            }
            found
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

/**
 * Thumbnail untuk satu item galeri (foto atau video), dijalankan di background thread.
 * Dicek dulu dari cache bersama (sharedThumbnailCache di ThumbnailCache.kt) sebelum
 * decode ulang.
 */
suspend fun loadThumbnailFromUri(
    context: Context,
    uri: Uri,
    targetSize: Int,
    type: MediaType
): Bitmap? {
    val cacheKey = "local:$uri|$targetSize|$type"

    getCachedThumbnail(cacheKey)?.let { return it }

    val bitmap = withContext(Dispatchers.IO) {
        when (type) {
            MediaType.PHOTO -> loadImageThumbnail(context, uri, targetSize)
            MediaType.VIDEO -> loadVideoThumbnail(context, uri, targetSize)
        }
    }

    if (bitmap != null) {
        putCachedThumbnail(cacheKey, bitmap)
    }

    return bitmap
}

private fun loadImageThumbnail(context: Context, uri: Uri, targetSize: Int): Bitmap? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, boundsOptions)

            context.contentResolver.openInputStream(uri)?.use { input2 ->
                var sampleSize = 1
                while ((boundsOptions.outWidth / sampleSize) > targetSize ||
                    (boundsOptions.outHeight / sampleSize) > targetSize
                ) {
                    sampleSize *= 2
                }
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    // Thumbnail tidak butuh alpha -- RGB_565 = separuh memori dari
                    // ARGB_8888 (default) untuk bitmap yang sama.
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeStream(input2, null, decodeOptions)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/**
 * Thumbnail video: coba cache MediaStore dulu (cepat, biasanya sudah pernah dibuat
 * sistem), baru fallback ke decode manual lewat MediaMetadataRetriever kalau cache-nya
 * belum ada / null. Menghindari decode video penuh di grid yang isinya banyak video
 * sekaligus, yang berat kalau selalu pakai retriever.
 */
private fun loadVideoThumbnail(context: Context, uri: Uri, targetSize: Int): Bitmap? {
    val cached = loadVideoThumbnailFromCache(context, uri)
    val bitmap = cached ?: loadVideoThumbnailFromRetriever(context, uri)
    return bitmap
        ?.let { downscaleToMaxDimension(it, targetSize) }
        // MediaStore.Video.Thumbnails & MediaMetadataRetriever selalu mengembalikan
        // ARGB_8888 -- dikonversi ke RGB_565 di sini sebelum masuk cache.
        ?.toMemoryEfficientConfig()
}

/**
 * Pakai MediaStore.Video.Thumbnails.getThumbnail -- deprecated di API baru,
 * tapi tetap paling kompatibel & cepat untuk minSdk rendah (project ini minSdk 25).
 */
private fun loadVideoThumbnailFromCache(context: Context, uri: Uri): Bitmap? {
    return try {
        val id = ContentUris.parseId(uri)
        @Suppress("DEPRECATION")
        MediaStore.Video.Thumbnails.getThumbnail(
            context.contentResolver,
            id,
            MediaStore.Video.Thumbnails.MINI_KIND,
            null
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun loadVideoThumbnailFromRetriever(context: Context, uri: Uri): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        // OPTION_CLOSEST_SYNC, bukan t=0 persis -- beberapa encoder tidak taruh
        // keyframe di detik ke-0.
        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } finally {
        retriever.release()
    }
}

private fun downscaleToMaxDimension(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val largestSide = maxOf(bitmap.width, bitmap.height)
    if (largestSide <= maxDimension) return bitmap

    val scale = maxDimension.toFloat() / largestSide
    val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}