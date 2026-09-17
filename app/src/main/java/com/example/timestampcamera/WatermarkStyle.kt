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
    const val QR_SIZE_RATIO = 0.25f
}