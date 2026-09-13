package com.example.timestampcamera

import androidx.camera.core.CameraEffect
import androidx.camera.effects.OverlayEffect
import java.util.Date

/**
 * Bikin OverlayEffect yang menggambar watermark (alamat, koordinat, jam) langsung
 * ke buffer VIDEO_CAPTURE -- bukan ke PREVIEW, karena preview sudah punya watermark
 * versi Compose (WatermarkOverlay) untuk ditampilkan ke layar. Jadi user tetap
 * lihat watermark yang sama persis, tapi yang di-burn ke file video digambar
 * terpisah oleh effect ini.
 *
 * Style & urutan baris disamakan PERSIS dengan addWatermark() (foto): font family,
 * shadow, letter spacing, dan urutan baris (jam -> garis pemisah -> alamat -> koordinat
 * dari bawah ke atas), supaya watermark video dan foto identik.
 *
 * CATATAN PENTING soal orientasi (hasil investigasi lapangan):
 * - Video (konten kamera) SUDAH benar tanpa kompensasi apa pun -- CameraX
 *   menangani rotasi video-nya sendiri secara terpisah.
 * - overlayCanvas TIDAK ikut ter-rotasi otomatis seperti video-nya, jadi teks
 *   watermark perlu di-rotate manual supaya sejajar dengan orientasi video akhir.
 * - Arah rotasi yang benar terbukti -frame.rotationDegrees (bukan +), lihat
 *   riwayat percobaan sebelumnya (galat 180 derajat kalau pakai +).
 * - PENTING: rotate-around-center SAJA tidak cukup kalau rotasinya 90/270 --
 *   itu cuma membetulkan ARAH BACA teks, tapi lebar & tinggi "logis" untuk
 *   hitung padding/posisi tetap harus DITUKAR (karena buffer kamera asli
 *   landscape, sedangkan video akhir portrait -- lebar & tinggi tertukar).
 *   Makanya sebelumnya orientasi sudah benar tapi posisi masih kepotong/
 *   tertimpa. Sekarang dipakai pola rotate+translate standar (dipakai luas
 *   untuk koreksi orientasi Camera/Bitmap) yang membetulkan KEDUANYA sekaligus.
 * - PENTING #2 (offset crop): frame.cropRect BUKAN mulai dari (0,0) kalau
 *   ratioMode yang diminta beda dari rasio native buffer kamera (mis. pilih
 *   4:3 padahal buffer native 16:9) -- sebagian buffer dipotong dan cropRect.left
 *   / cropRect.top jadi > 0. overlayCanvas sendiri berukuran PENUH (sebesar
 *   frame.getSize(), bukan sebesar cropRect), jadi kalau digambar relatif ke
 *   (0,0) canvas tanpa kompensasi, watermark ketarik ke posisi yang salah
 *   relatif ke area yang benar-benar terlihat di video akhir (bagian yang
 *   harusnya mepet pojok malah ikut kepotong, sisanya kelihatan lebih ke
 *   tengah). Makanya origin canvas digeser ke pojok kiri-atas cropRect DULU,
 *   sebelum rotate+translate orientasi di bawah.
 */
private fun correctionDegreesFor(rotationDegrees: Int): Int {
    // Arah koreksi yang terbukti benar: -rotationDegrees, dinormalisasi ke 0..270
    return (((-rotationDegrees) % 360) + 360) % 360
}

fun createVideoWatermarkEffect(
    handler: android.os.Handler,
    onError: (Throwable) -> Unit
): OverlayEffect {
    val effect = OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, handler, onError)

    effect.setOnDrawListener { frame ->
        val canvas = frame.overlayCanvas
        canvas.save()

        // ---- WAJIB: bersihkan canvas dulu tiap frame ----
        // Tanpa ini, pixel watermark dari frame/detik sebelumnya bisa tidak
        // sepenuhnya tertutup oleh watermark baru (terutama kalau bentuk digit
        // beda, misal "9" -> "10"), sehingga terlihat menumpuk/smear antar detik.
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        val cropRect = frame.cropRect

        // ---- Geser origin canvas ke pojok kiri-atas cropRect ----
        // Wajib dilakukan PALING AWAL (sebelum rotate+translate orientasi di bawah),
        // karena cropRect dalam koordinat buffer PENUH (0,0 = pojok buffer, bukan
        // pojok area yang akhirnya terlihat). Hanya konten di dalam cropRect yang
        // akan tampil setelah CameraX crop+rotate+mirror -- lihat dokumentasi
        // Frame.getCropRect(). Tanpa translate ini, watermark salah posisi setiap
        // kali ratioMode berbeda dari rasio native buffer kamera.
        canvas.translate(cropRect.left.toFloat(), cropRect.top.toFloat())

        val rawWidth = cropRect.width()
        val rawHeight = cropRect.height()
        val correctionDegrees = correctionDegreesFor(frame.rotationDegrees)
        val isSwapped = correctionDegrees == 90 || correctionDegrees == 270

        // ---- Lebar & tinggi LOGIS (sudah dalam orientasi video akhir), ----
        // ---- dihitung SEBELUM transform karena dipakai sebagai jarak translate ----
        val width = if (isSwapped) rawHeight.toFloat() else rawWidth.toFloat()
        val logicalHeight = if (isSwapped) rawWidth.toFloat() else rawHeight.toFloat()
        val logicalBottom = logicalHeight

        // ---- Rotate + translate, translate-nya pakai ukuran LOGIS (width/ ----
        // ---- logicalHeight), BUKAN rawWidth/rawHeight -- inilah bug yang ----
        // ---- kemarin bikin watermark ter-translate jauh keluar frame (hilang). ----
        when (correctionDegrees) {
            90 -> {
                canvas.rotate(90f)
                canvas.translate(0f, -logicalHeight)
            }
            180 -> {
                canvas.rotate(180f)
                canvas.translate(-width, -logicalHeight)
            }
            270 -> {
                canvas.rotate(270f)
                canvas.translate(-width, 0f)
            }
            // 0 -> tidak perlu transform apa pun
        }

        if (!VideoWatermarkState.isMockLocationDetected) {
            val padding = width * WatermarkStyle.PADDING_RATIO
            val addressTextSize = width * WatermarkStyle.PREVIEW_ADDRESS_SP_RATIO
            val timeTextSize = width * WatermarkStyle.PREVIEW_TIME_SP_RATIO

            val addressTypeface = android.graphics.Typeface.create(
                WatermarkStyle.ADDRESS_FONT_FAMILY, android.graphics.Typeface.NORMAL
            )
            val timeTypeface = android.graphics.Typeface.create(
                WatermarkStyle.TIME_FONT_FAMILY, android.graphics.Typeface.NORMAL
            )

            val addressPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = addressTextSize
                isAntiAlias = true
                typeface = addressTypeface
                setShadowLayer(6f, 2f, 2f, android.graphics.Color.BLACK)
                textAlign = android.graphics.Paint.Align.LEFT
            }
            val timePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = timeTextSize
                isAntiAlias = true
                typeface = timeTypeface
                setShadowLayer(8f, 3f, 3f, android.graphics.Color.BLACK)
                textAlign = android.graphics.Paint.Align.LEFT
                letterSpacing = WatermarkStyle.LETTER_SPACING_TIME
            }
            val linePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                strokeWidth = 3f
                setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
            }

            fun lineHeight(paint: android.graphics.Paint): Float {
                val fm = paint.fontMetrics
                return (fm.descent - fm.ascent) * 1.15f
            }

            val addressRows = VideoWatermarkState.addressText
                .split("\n")
                .filter { it.isNotBlank() }
            val allRows = mutableListOf<String>()
            allRows.add("📍 ${addressRows.getOrElse(0) { "" }}")
            for (i in 1 until addressRows.size) {
                allRows.add(addressRows[i])
            }
            if (VideoWatermarkState.coordText.isNotBlank()) {
                allRows.add(VideoWatermarkState.coordText)
            }

            var y = logicalBottom - padding

            // ---- Baris tanggal+jam (paling bawah), SATU baris & SATU gaya (timePaint) ----
            // ---- -- persis sama dengan addWatermark() foto: pakai getCurrentDateTimeText() ----
            // ---- yang sama, BUKAN VideoWatermarkState.dateTimeText, supaya jam selalu ----
            // ---- akurat saat frame digambar (bukan nilai yang di-throttle dari Compose).
            val dateTimeText = getCurrentDateTimeText(Date())

            canvas.drawText(dateTimeText, padding, y, timePaint)
            y -= lineHeight(timePaint)

            // Garis pemisah, dengan jarak di atas & bawahnya
            val dividerGap = addressTextSize * 0.3f
            y -= dividerGap
            canvas.drawLine(padding, y, width * 0.6f, y, linePaint)
            y -= dividerGap

            // Baris alamat & koordinat, disusun dari bawah ke atas
            for (i in allRows.indices.reversed()) {
                y -= lineHeight(addressPaint)
                canvas.drawText(allRows[i], padding, y, addressPaint)
            }
        } else {
            val warnPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.YELLOW
                textSize = width * WatermarkStyle.PREVIEW_ADDRESS_SP_RATIO
                isAntiAlias = true
            }
            canvas.drawText(
                "⚠️ Lokasi Palsu Terdeteksi",
                width * WatermarkStyle.PADDING_RATIO,
                logicalBottom - width * WatermarkStyle.PADDING_RATIO,
                warnPaint
            )
        }

        canvas.restore()
        true
    }

    return effect
}