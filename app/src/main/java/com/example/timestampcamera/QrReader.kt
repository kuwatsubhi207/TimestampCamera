package com.example.timestampcamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * QR yang ditempel ke foto berisi FULL URL App Link
 * (https://verify.domain.com/verify.html?id=TC-...), bukan ID polos --
 * supaya scanner generik/kamera bawaan HP juga bisa langsung buka halaman
 * verifikasi. Fungsi ini menangani KEDUA kemungkinan: kalau isi QR berupa URL,
 * ekstrak query parameter `id`; kalau isi QR ternyata ID polos (mis. QR lama
 * dari versi app sebelumnya, atau dari sumber lain), pakai apa adanya asal
 * formatnya cocok prefix "TC-".
 */
fun extractVerificationIdFromValue(value: String): String? {
    return try {
        if (value.startsWith("http://") || value.startsWith("https://")) {
            Uri.parse(value).getQueryParameter("id")
        } else if (value.startsWith("TC-")) {
            value
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Baca QR dari bytes file gambar (dipakai di alur Verify Photo, dimana kita
 * sudah punya bytes asli file untuk dihitung hash-nya juga). Bitmap di-downsample
 * (inSampleSize) KHUSUS untuk deteksi QR -- ini TIDAK mempengaruhi hash, karena
 * hash dihitung terpisah dari bytes asli, bukan dari bitmap yang di-downsample ini.
 */
suspend fun readVerificationIdFromBytes(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
    try {
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return@withContext null
        readVerificationIdFromBitmap(bitmap)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/** Baca QR dari sebuah Bitmap yang sudah di-decode. */
suspend fun readVerificationIdFromBitmap(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
    try {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val scanner = BarcodeScanning.getClient()
        val barcodes = scanner.process(inputImage).await()
        val rawValue = barcodes.firstOrNull()?.rawValue ?: return@withContext null
        extractVerificationIdFromValue(rawValue)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}