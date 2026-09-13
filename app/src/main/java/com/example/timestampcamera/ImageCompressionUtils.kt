package com.example.timestampcamera

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Kompres bitmap ke JPEG sampai ukurannya di bawah maxSizeBytes.
 * Strategi: binary search quality (maks ~7 percobaan, jauh lebih cepat dari linear step-10).
 * Kalau di quality minimum masih terlalu besar (jarang terjadi kalau bitmap
 * sudah didownscale di addWatermark), baru kecilkan dimensi bitmap lalu ulangi.
 */
fun compressBitmapToMaxSize(
    bitmap: Bitmap,
    maxSizeBytes: Int = 200 * 1024
): ByteArray {
    var currentBitmap = bitmap

    fun compressAt(quality: Int, source: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    // ---- Binary search kualitas JPEG ----
    var low = 10
    var high = 95
    var bestBytes: ByteArray? = null
    var bestQuality = 10

    while (low <= high) {
        val mid = (low + high) / 2
        val bytes = compressAt(mid, currentBitmap)

        if (bytes.size <= maxSizeBytes) {
            bestBytes = bytes
            bestQuality = mid
            low = mid + 1 // coba kualitas lebih tinggi selama masih di bawah target
        } else {
            high = mid - 1
        }
    }

    var outputBytes = bestBytes ?: compressAt(10, currentBitmap)

    // ---- Fallback: kalau quality 10 pun masih kebesaran, baru kecilkan resolusi ----
    while (outputBytes.size > maxSizeBytes && currentBitmap.width > 480) {
        val newWidth = (currentBitmap.width * 0.7f).toInt()
        val newHeight = (currentBitmap.height * 0.7f).toInt()
        val resized = Bitmap.createScaledBitmap(currentBitmap, newWidth, newHeight, true)

        if (currentBitmap != bitmap) {
            currentBitmap.recycle()
        }
        currentBitmap = resized

        outputBytes = compressAt(bestQuality.coerceAtLeast(40), currentBitmap)
    }

    if (currentBitmap != bitmap) {
        currentBitmap.recycle()
    }

    return outputBytes
}