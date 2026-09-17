package com.example.timestampcamera

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ---- GANTI dengan URL Worker Cloudflare kamu yang sebenarnya setelah deploy ----
// ---- (harus di domain yang sama/terkait dengan HASH_REGISTER_API_URL di ----
// ---- HashRegisterWorker.kt, cuma beda endpoint: /register vs /verify) ----
private const val VERIFICATION_API_BASE = "https://timestampcamera-verify.kuwatsubhi207.workers.dev/api/v1"

/**
 * GET /api/v1/verify/{id} -- endpoint PUBLIK, TIDAK perlu HMAC signing (beda
 * dari /register yang di HashRegisterWorker.kt). Siapa pun boleh memanggil ini,
 * termasuk verifier pihak ketiga yang mengikuti protokol publik.
 *
 * Mengembalikan FetchVerificationResult, bukan nullable/exception, supaya
 * pemanggil WAJIB menangani ketiga kemungkinan secara eksplisit (Found /
 * NotFound / NetworkError) -- terutama membedakan "tidak terdaftar" (404,
 * status valid) dari "tidak bisa dihubungi" (gagal jaringan), yang harus
 * ditampilkan berbeda ke user.
 */
suspend fun fetchVerificationRecord(verificationId: String): FetchVerificationResult {
    return withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(verificationId, "UTF-8")
            val url = URL("$VERIFICATION_API_BASE/verify/$encodedId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        FetchVerificationResult.Found(
                            VerificationRecord(
                                id = json.getString("id"),
                                algorithm = json.getString("algorithm"),
                                sha256 = json.getString("sha256"),
                                capturedAt = json.getString("captured_at"),
                                app = json.getString("app"),
                                version = json.getString("version")
                            )
                        )
                    }
                    HttpURLConnection.HTTP_NOT_FOUND -> FetchVerificationResult.NotFound
                    else -> FetchVerificationResult.NetworkError(
                        "Server mengembalikan status ${connection.responseCode}"
                    )
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            FetchVerificationResult.NetworkError(e.message ?: "Tidak bisa menghubungi server verifikasi")
        }
    }
}