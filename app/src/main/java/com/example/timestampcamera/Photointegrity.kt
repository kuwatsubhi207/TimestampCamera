package com.example.timestampcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hitung SHA-256 dari sebuah file, dibaca secara streaming (buffer 8KB) supaya
 * tidak perlu load seluruh file JPEG/video ke memory sekaligus.
 *
 * Dipakai untuk kasus dimana kita cuma punya File (mis. saat verifikasi foto
 * yang dipilih user dari galeri), BUKAN untuk hash saat capture -- untuk capture,
 * pakai overload computeSha256(ByteArray) di bawah supaya hash dihitung dari
 * bytes yang SAMA PERSIS dengan yang ditulis ke MediaStore (lihat catatan di
 * overload tersebut).
 */
fun computeSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Hitung SHA-256 langsung dari ByteArray hasil compress bitmap.
 *
 * PENTING: ini WAJIB dipanggil dengan ByteArray yang SAMA PERSIS dengan yang
 * ditulis ke MediaStore (lihat capturePhotoWithLocation) -- bukan hasil
 * compress ULANG dari bitmap yang sama. Encoder JPEG tidak selalu deterministik
 * byte-for-byte antar pemanggilan tergantung device/versi Android, jadi
 * compress harus dilakukan SATU KALI, hasilnya dipakai untuk hash DAN untuk
 * disimpan.
 */
fun computeSha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * Generate bitmap QR code dari sebuah string (URL verifikasi). Pakai ZXing
 * core -- ringan, tidak butuh model ML seperti ML Kit, cocok untuk sekadar
 * generate (bukan membaca/scan).
 *
 * ErrorCorrectionLevel.M dipilih sebagai keseimbangan: cukup toleran kalau
 * sudut QR sedikit tertutup watermark/border, tanpa bikin QR jadi terlalu
 * padat/rapat modulnya (yang menyulitkan scan dari kamera HP di kondisi
 * pencahayaan kurang ideal).
 */
fun generateQrBitmap(content: String, sizePx: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1
    )
    val writer = QRCodeWriter()
    val matrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(
                x, y,
                if (matrix[x, y]) Color.BLACK else Color.WHITE
            )
        }
    }
    return bitmap
}

/** URL verifikasi yang ditempel ke QR -- App Link ke halaman verify.html di GitHub Pages. */
fun buildVerificationUrl(verificationId: String): String {
    return "https://verify.domain.com/verify.html?id=$verificationId"
}

/**
 * Tempel QR verifikasi ke pojok KANAN ATAS bitmap (watermark alamat/jam tetap
 * di kiri bawah -- lihat addWatermark.kt -- supaya tidak tumpang tindih).
 *
 * Ditambahkan kotak putih polos di belakang QR (dengan sedikit padding) supaya
 * QR tetap gampang di-scan meskipun latar foto di pojok itu gelap/ramai motif --
 * QR reader butuh kontras hitam-putih yang jelas di modul-modulnya.
 *
 * Menggambar LANGSUNG ke `bitmap` yang diberikan (harus mutable, seperti hasil
 * addWatermark()) -- bukan membuat copy baru -- supaya konsisten dengan pola
 * addWatermark() yang juga mutate in-place.
 *
 * WAJIB dipanggil SETELAH addWatermark() dan SEBELUM bitmap di-compress ke
 * JPEG untuk dihitung hash-nya -- urutan ini yang membuat hash mencakup QR.
 */
fun drawQrOntoBitmap(bitmap: Bitmap, verificationUrl: String): Bitmap {
    val canvas = Canvas(bitmap)
    val width = bitmap.width
    val height = bitmap.height

    // ---- QR_SIZE_RATIO = ukuran TOTAL kotak putih (bukan ukuran QR itu sendiri) ----
    // ---- QR digambar lebih kecil DI DALAM kotak ini, dikurangi QR_INNER_PADDING_RATIO ----
    // ---- di tiap sisi -- PERSIS logika yang sama dipakai preview (QrPreviewOverlay), ----
    // ---- supaya kotak putih yang terlihat user sebelum capture berukuran sama persis ----
    // ---- dengan yang muncul di hasil foto, bukan cuma "mirip".
    val totalBoxSizePx = (width * WatermarkStyle.QR_SIZE_RATIO).toInt().coerceAtLeast(1)
    val innerPaddingPx = totalBoxSizePx * WatermarkStyle.QR_INNER_PADDING_RATIO
    val qrSizePx = (totalBoxSizePx - 2 * innerPaddingPx).toInt().coerceAtLeast(1)

    val padding = width * WatermarkStyle.QR_PADDING_RATIO
    val boxLeft = width - padding - totalBoxSizePx
    val boxTop = padding

    val backgroundPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }
    val cornerRadiusPx = totalBoxSizePx * WatermarkStyle.QR_CORNER_RADIUS_RATIO
    canvas.drawRoundRect(
        boxLeft,
        boxTop,
        boxLeft + totalBoxSizePx,
        boxTop + totalBoxSizePx,
        cornerRadiusPx,
        cornerRadiusPx,
        backgroundPaint
    )

    val qrBitmap = generateQrBitmap(verificationUrl, qrSizePx)
    canvas.drawBitmap(qrBitmap, boxLeft + innerPaddingPx, boxTop + innerPaddingPx, null)

    return bitmap
}

/**
 * Format Date jadi ISO 8601 dengan offset timezone perangkat (mis.
 * "2026-09-17T10:15:01+07:00") -- format ini yang dikirim sebagai
 * `captured_at` ke Cloudflare Worker, supaya server tidak perlu menebak
 * timezone dari string lain.
 */
fun formatIso8601(date: Date): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    return format.format(date)
}