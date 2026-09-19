package com.example.timestampcamera

object WatermarkStyle {
    // ---- Rasio ukuran font, dipakai BERSAMA oleh preview (CameraScreen.kt) dan ----
    // ---- watermark foto tersimpan (addWatermark.kt). Jangan buat konstanta terpisah ----
    // ---- untuk masing-masing -- itu menyebabkan ukuran teks di preview dan hasil foto ----
    // ---- bisa beda meski dimaksudkan sama.
    const val PREVIEW_ADDRESS_SP_RATIO = 0.038f
    const val PREVIEW_TIME_SP_RATIO = 0.065f

    const val PADDING_RATIO = 0.04f

    const val LETTER_SPACING_TIME = 0.02f

    const val ADDRESS_FONT_FAMILY = "sans-serif-medium"
    const val TIME_FONT_FAMILY = "sans-serif-black"

    const val DATE_PATTERN = "dd MMM yyyy"
    const val TIME_PATTERN = "HH:mm:ss"
    const val TIME_ZONE_LABEL = "WIB"

    // ---- BARU: ukuran QR verifikasi, ditempel di pojok KANAN bawah foto (watermark ----
    // ---- alamat/jam tetap di kiri bawah, supaya tidak tumpang tindih). Rasio dari ----
    // ---- lebar foto, sama seperti pendekatan PADDING_RATIO -- supaya proporsional ----
    // ---- di semua resolusi output, bukan ukuran piksel tetap.
    const val QR_SIZE_RATIO = 0.16f

    // ---- Padding DALAM kotak QR (antara tepi kotak putih dan modul QR itu sendiri) ----
    // ---- -- dipakai BERSAMA oleh preview (QrPreviewOverlay) dan hasil akhir ----
    // ---- (drawQrOntoBitmap), dihitung sebagai persentase dari QR_SIZE_RATIO (ukuran ----
    // ---- TOTAL kotak, bukan cuma QR-nya), supaya kotak putih yang terlihat di preview ----
    // ---- berukuran PERSIS sama dengan yang di foto hasil -- bukan cuma rasio sama, ----
    // ---- tapi variable sumbernya sama, jadi tidak bisa desync lagi ke depannya.
    const val QR_INNER_PADDING_RATIO = 0.08f

    // ---- Radius sudut kotak putih di belakang QR, dihitung sebagai persentase dari ----
    // ---- ukuran TOTAL kotak (sama seperti QR_INNER_PADDING_RATIO) -- dipakai BERSAMA ----
    // ---- oleh preview (QrPreviewOverlay) dan hasil akhir (drawQrOntoBitmap), supaya ----
    // ---- gaya visualnya (siku tajam vs membulat) identik, bukan cuma ukurannya.
    const val QR_CORNER_RADIUS_RATIO = 0.06f

    // ---- Padding QR ke tepi foto -- SENGAJA dipisah dari PADDING_RATIO (dipakai ----
    // ---- watermark teks) supaya QR bisa lebih rapat ke tepi tanpa ikut menggeser ----
    // ---- posisi watermark alamat/jam. Dipakai BERSAMA oleh preview (CameraScreen.kt) ----
    // ---- dan hasil akhir (drawQrOntoBitmap()) -- jangan buat konstanta terpisah lagi ----
    // ---- untuk masing-masing, supaya posisi preview & hasil akhir selalu identik.
    const val QR_PADDING_RATIO = 0.01f
}