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
 * Tulis bytes JPEG yang SUDAH di-compress ke MediaStore, disimpan di
 * Pictures/TimestampCamera -- tanpa compress ulang.
 *
 * PENTING: fungsi ini sengaja menerima ByteArray (bukan Bitmap) supaya bytes
 * yang ditulis ke disk PERSIS SAMA dengan bytes yang sudah dihitung SHA-256-nya
 * di capturePhotoWithLocation. Kalau compress dilakukan dua kali (sekali untuk
 * hash, sekali lagi di sini), hasil encoder JPEG berpotensi sedikit berbeda
 * antar pemanggilan tergantung device -- itu akan membuat hash yang terdaftar
 * di server TIDAK COCOK dengan file yang benar-benar tersimpan, padahal
 * sumber bitmap-nya identik.
 */
fun saveBytesToMediaStore(
    context: Context,
    jpegBytes: ByteArray,
    fileName: String
): Uri? {
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Subfolder "TimestampCamera" -- dipisah dari video supaya tidak
            // campur aduk saat dilihat lewat Files/Google Photos.
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TimestampCamera")
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

    uri?.let {
        resolver.openOutputStream(it)?.use { outputStream ->
            outputStream.write(jpegBytes)
        }
    }

    return uri
}

/**
 * Helper untuk generate nama file JPEG konsisten dari waktu capture, dipakai
 * bersama oleh capturePhotoWithLocation (nama file yang disimpan) dan
 * HashRegisterWorker (nama file yang dikirim ke server) -- supaya keduanya
 * SELALU sama, tidak pernah dihitung dua kali secara terpisah dengan hasil
 * yang mungkin beda (mis. kalau timestamp di-generate ulang beberapa
 * milidetik kemudian).
 */
fun buildPhotoFileName(capturedAt: java.util.Date): String {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(capturedAt)
    return "IMG_$timestamp.jpg"
}

/**
 * @deprecated Pakai saveBytesToMediaStore() dengan bytes yang sudah di-compress
 * SATU KALI di pemanggil, supaya bytes yang disimpan konsisten dengan bytes
 * yang di-hash untuk fitur verifikasi. Fungsi ini disisakan cuma untuk
 * kompatibilitas kalau ada pemanggil lama yang belum dipindah.
 */
@Deprecated(
    message = "Compress bitmap sendiri di pemanggil, lalu pakai saveBytesToMediaStore() " +
            "supaya bytes yang disimpan sama dengan bytes yang di-hash.",
    replaceWith = ReplaceWith("saveBytesToMediaStore(context, jpegBytes, fileName)")
)
fun saveBitmapToMediaStore(
    context: Context,
    bitmap: Bitmap,
    maxSizeBytes: Int = 200 * 1024
): Uri? {
    val fileName = buildPhotoFileName(java.util.Date())
    val compressedBytes = compressBitmapToMaxSize(bitmap, maxSizeBytes)
    return saveBytesToMediaStore(context, compressedBytes, fileName)
}