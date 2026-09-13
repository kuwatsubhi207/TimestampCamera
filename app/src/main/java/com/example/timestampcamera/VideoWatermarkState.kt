package com.example.timestampcamera

/**
 * Jembatan nilai watermark (alamat, koordinat) dari Compose (CameraScreen)
 * ke OverlayEffect.setOnDrawListener, yang dipanggil di thread terpisah untuk
 * setiap frame video. @Volatile cukup di sini karena cuma baca referensi String,
 * tidak perlu lock -- worst case satu frame telat update teks 1 langkah, tidak masalah.
 *
 * CATATAN: dateTimeText SENGAJA tidak ada di sini -- createVideoWatermarkEffect()
 * menghitung jam/tanggal sendiri langsung di dalam setOnDrawListener (Date() +
 * SimpleDateFormat), supaya bisa dipecah jadi 2 baris terpisah (tanggal & jam)
 * persis seperti addWatermark() di foto. Kalau field ini ditambahkan lagi tanpa
 * dipakai, dia cuma jadi dead code yang membingungkan.
 */
object VideoWatermarkState {
    @Volatile var addressText: String = ""
    @Volatile var coordText: String = ""
    @Volatile var isMockLocationDetected: Boolean = false
}