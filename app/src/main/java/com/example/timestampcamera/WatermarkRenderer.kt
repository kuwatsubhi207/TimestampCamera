package com.example.timestampcamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File
import java.util.Date

// Sisi terpanjang hasil akhir foto. 1280px sudah lebih dari cukup untuk dilihat di HP
// dan dibagikan, tapi jauh lebih ringan diproses & dikompres dibanding resolusi penuh kamera.
private const val MAX_OUTPUT_DIMENSION = 1280

/**
 * Menggambar watermark multi-baris di pojok kiri bawah foto:
 * Baris alamat, garis pemisah, tanggal, lalu jam (paling besar, paling bawah).
 *
 * @param capturedAt Waktu yang DITAMPILKAN di watermark. HARUS berupa waktu yang dicatat
 *   tepat saat tombol shutter ditekan (bukan waktu sekarang), supaya sama persis dengan
 *   apa yang terlihat di preview saat itu. Jangan hitung Date() di dalam fungsi ini --
 *   itu menyebabkan selisih waktu karena proses capture butuh waktu untuk selesai.
 */
fun addWatermark(
    file: File,
    addressLines: String,
    coordText: String,
    capturedAt: Date
): Bitmap {
    // ---- Decode LANGSUNG di ukuran kecil + mutable, tanpa decode+copy dobel ----
    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, boundsOptions)

    var sampleSize = 1
    while ((boundsOptions.outWidth / sampleSize) > MAX_OUTPUT_DIMENSION ||
        (boundsOptions.outHeight / sampleSize) > MAX_OUTPUT_DIMENSION
    ) {
        sampleSize *= 2
    }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inMutable = true
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val mutableBitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        ?: throw IllegalStateException("Gagal membaca file foto: ${file.absolutePath}")

    val canvas = Canvas(mutableBitmap)

    val width = mutableBitmap.width
    val height = mutableBitmap.height

    val addressTypeface = Typeface.create(WatermarkStyle.ADDRESS_FONT_FAMILY, Typeface.NORMAL)
    val timeTypeface = Typeface.create(WatermarkStyle.TIME_FONT_FAMILY, Typeface.NORMAL)

    // ---- Pakai rasio yang SAMA dengan preview (PREVIEW_*), bukan konstanta terpisah, ----
    // ---- supaya proporsi ukuran teks di foto tersimpan identik dengan yang terlihat ----
    // ---- di layar saat memotret.
    val addressTextSize = width * WatermarkStyle.PREVIEW_ADDRESS_SP_RATIO
    val timeTextSize = width * WatermarkStyle.PREVIEW_TIME_SP_RATIO

    val padding = width * WatermarkStyle.PADDING_RATIO

    val addressPaint = Paint().apply {
        color = Color.WHITE
        textSize = addressTextSize
        isAntiAlias = true
        typeface = addressTypeface
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
        textAlign = Paint.Align.LEFT
    }

    val timePaint = Paint().apply {
        color = Color.WHITE
        textSize = timeTextSize
        isAntiAlias = true
        typeface = timeTypeface
        setShadowLayer(8f, 3f, 3f, Color.BLACK)
        textAlign = Paint.Align.LEFT
        letterSpacing = WatermarkStyle.LETTER_SPACING_TIME
    }

    val linePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 3f
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    val dateTimeText = getCurrentDateTimeText(capturedAt)

    val addressRows = addressLines.split("\n").filter { it.isNotBlank() }
    val allRows = mutableListOf<String>()
    allRows.add("📍 ${addressRows.getOrElse(0) { "" }}")
    for (i in 1 until addressRows.size) {
        allRows.add(addressRows[i])
    }
    if (coordText.isNotBlank()) {
        allRows.add(coordText)
    }

    // ---- Tinggi baris dihitung dari metrik font ASLI (ascent/descent), bukan angka ----
    // ---- kelipatan tetap. Ini penting: teks jam jauh lebih besar dari teks alamat, ----
    // ---- jadi kalau jarak antar baris dihitung pakai ukuran font alamat, garis    ----
    // ---- pemisah akan menembus teks jam (bug yang sebelumnya terjadi).
    fun lineHeight(paint: Paint): Float {
        val fm = paint.fontMetrics
        return (fm.descent - fm.ascent) * 1.15f
    }

    var y = height - padding

    // Baris tanggal+jam (paling bawah), sama persis dengan teks & gaya di preview
    canvas.drawText(dateTimeText, padding, y, timePaint)
    y -= lineHeight(timePaint)

    // Garis pemisah, dengan sedikit jarak di atas & bawahnya (seperti padding vertical
    // pada HorizontalDivider di preview)
    val dividerGap = addressTextSize * 0.3f
    y -= dividerGap
    canvas.drawLine(padding, y, width * 0.6f, y, linePaint)
    y -= dividerGap

    // Baris alamat & koordinat, disusun dari bawah ke atas
    for (i in allRows.indices.reversed()) {
        y -= lineHeight(addressPaint)
        canvas.drawText(allRows[i], padding, y, addressPaint)
    }

    return mutableBitmap
}