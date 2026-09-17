package com.example.timestampcamera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Generate verification ID secara LOKAL (offline), tanpa perlu koneksi ke
 * server. Formatnya: "TC-yyyyMMdd-XXXXXX" (6 karakter random dari UUID).
 *
 * ID ini ditempel ke QR pada foto SEBELUM upload ke Cloudflare -- pendaftaran
 * hash ke server menyusul lewat WorkManager, jadi capture tetap bisa jalan
 * offline. Kalau pendaftaran gagal/foto tidak pernah diupload, hasil verifikasi
 * nanti akan "NOT REGISTERED", bukan error -- aman secara UX.
 *
 * Risiko collision ID sangat kecil (UUID random 6 karakter alfanumerik = 36^6
 * kemungkinan per hari, cukup untuk skala penggunaan personal/tim kecil).
 * Kalau butuh jaminan keunikan mutlak, cek keunikan bisa ditambahkan di sisi
 * server saat INSERT ke D1 (PRIMARY KEY sudah mencegah duplikat masuk).
 */
object VerificationId {
    fun generate(): String {
        val datePart = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val randomPart = UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(6)
            .uppercase(Locale.US)
        return "TC-$datePart-$randomPart"
    }
}