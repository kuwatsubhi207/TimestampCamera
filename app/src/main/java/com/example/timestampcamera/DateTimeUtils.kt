package com.example.timestampcamera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * @param at Waktu yang mau ditampilkan. Default Date() (sekarang) untuk dipakai di preview
 *   (ticker tiap 1 detik). Saat dipanggil dari watermark hasil foto, WAJIB kirim `capturedAt`
 *   yang sama dengan yang dipakai addWatermark(), supaya teksnya identik dengan preview.
 */
fun getCurrentDateTimeText(at: Date = Date()): String {
    val dateSdf = SimpleDateFormat(
        WatermarkStyle.DATE_PATTERN,
        Locale.Builder().setLanguage("id").setRegion("ID").build()
    )
    val timeSdf = SimpleDateFormat(WatermarkStyle.TIME_PATTERN, Locale.getDefault())
    return "${dateSdf.format(at)} | ${timeSdf.format(at)} ${WatermarkStyle.TIME_ZONE_LABEL}"
}