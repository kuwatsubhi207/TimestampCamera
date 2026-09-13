package com.example.timestampcamera

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Cache thumbnail BERSAMA untuk semua sumber (foto lokal, video lokal, Drive).
 *
 * Sebelumnya ada 2 LruCache terpisah -- satu untuk lokal, satu untuk Drive --
 * masing-masing dijatah sampai maxMemory/8. Kalau dua-duanya kebetulan penuh
 * bersamaan, total pemakaian cache bisa sampai maxMemory/4, padahal isinya
 * cuma thumbnail kecil untuk grid. Digabung jadi SATU pool memori dengan batas
 * TETAP (bukan persentase heap) supaya total pemakaian jelas dan kecil,
 * berapa pun besar heap devicenya.
 *
 * Key dipakai untuk membedakan sumber, misal:
 * - "local:<uri>|<targetSize>|<type>"  -- untuk foto/video lokal
 * - "drive:<fileId>"                   -- untuk thumbnail Drive
 */
private const val THUMBNAIL_CACHE_SIZE_KB = 8 * 1024 // total 8 MB untuk SEMUA thumbnail

val sharedThumbnailCache: LruCache<String, Bitmap> =
    object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_SIZE_KB) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

fun getCachedThumbnail(key: String): Bitmap? {
    return synchronized(sharedThumbnailCache) {
        sharedThumbnailCache.get(key)
    }
}

fun putCachedThumbnail(key: String, bitmap: Bitmap) {
    synchronized(sharedThumbnailCache) {
        sharedThumbnailCache.put(key, bitmap)
    }
}

/**
 * Thumbnail tidak butuh channel alpha (transparansi) -- ARGB_8888 pakai 4 byte/piksel,
 * RGB_565 cuma 2 byte/piksel. Dipakai sebelum bitmap disimpan ke cache supaya tiap
 * entri makan separuh memori dibanding kalau disimpan apa adanya.
 */
fun Bitmap.toMemoryEfficientConfig(): Bitmap {
    if (config == Bitmap.Config.RGB_565) return this
    return try {
        copy(Bitmap.Config.RGB_565, false)
    } catch (e: Exception) {
        // Beberapa bitmap (mis. hasil decode tertentu) bisa gagal dikonversi --
        // kalau gagal, tetap pakai bitmap asli daripada crash.
        this
    }
}