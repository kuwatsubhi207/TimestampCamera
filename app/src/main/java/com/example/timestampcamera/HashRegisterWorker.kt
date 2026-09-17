package com.example.timestampcamera

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ---- GANTI dengan URL Worker Cloudflare kamu yang sebenarnya setelah deploy ----
private const val HASH_REGISTER_API_URL = "https://timestampcamera-verify.kuwatsubhi207.workers.dev/api/v1/register"

// ---- PENTING: HARUS SAMA PERSIS dengan env.HMAC_SECRET di Cloudflare Worker. ----
// ---- Karena APK bisa di-decompile, constant ini pada akhirnya BISA diekstrak ----
// ---- oleh yang cukup niat -- HMAC di sini cuma mencegah orang iseng menembak ----
// ---- endpoint /register langsung dari luar app, bukan proteksi mutlak. Upgrade ----
// ---- natural ke depan: signing pakai Android Keystore (private key tidak pernah ----
// ---- meninggalkan secure hardware device, jadi tidak bisa diekstrak sama sekali).
private const val HMAC_SECRET = "YrTpltuD2L733iZtLmFHxF4j4bDcT3PTAhM0ekr4d09nPeZTq4xWPF5hvZFIKF1zyHgi859m2IR7T3rUd1fW9aReEuEuWtOQv1zqdCotgftIeIZQFbEE2o3OvyE9ZEE0"

/**
 * WorkManager job untuk mendaftarkan hash SHA-256 foto ke Cloudflare Worker,
 * supaya nanti bisa diverifikasi lewat menu Verification atau lewat
 * verify.html. Mengikuti pola yang sama dengan DriveUploadWorker: retry
 * otomatis kalau gagal (mis. tidak ada internet saat itu), dijalankan hanya
 * saat ada koneksi (Constraints.NetworkType.CONNECTED).
 *
 * Kalau job ini gagal permanen atau device offline lama, hasilnya BUKAN error
 * fatal -- foto tetap tersimpan lokal/Drive seperti biasa. Nanti kalau user
 * coba verifikasi foto itu sebelum pendaftaran berhasil, hasilnya "NOT
 * REGISTERED", bukan crash -- ini konsekuensi yang sudah disadari & diterima
 * di desain awal.
 */
class HashRegisterWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val verificationId = inputData.getString(KEY_VERIFICATION_ID)
        val sha256 = inputData.getString(KEY_SHA256)
        val fileName = inputData.getString(KEY_FILE_NAME)
        val capturedAt = inputData.getString(KEY_CAPTURED_AT)

        if (verificationId == null || sha256 == null || fileName == null || capturedAt == null) {
            // Data input cacat -- retry tidak akan memperbaiki apa pun, jadi gagal permanen.
            return@withContext Result.failure()
        }

        try {
            val body = JSONObject().apply {
                put("verification_id", verificationId)
                put("sha256", sha256)
                put("file_name", fileName)
                put("captured_at", capturedAt)
                put("app_version", BuildConfig.VERSION_NAME)
            }.toString()

            val timestamp = System.currentTimeMillis().toString()
            val signature = signPayload(timestamp, body)

            val connection = URL(HASH_REGISTER_API_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Timestamp", timestamp)
            connection.setRequestProperty("X-Signature", signature)
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.outputStream.use { it.write(body.toByteArray()) }

            when (val code = connection.responseCode) {
                200, 201 -> Result.success()
                in 500..599 -> Result.retry() // masalah sementara di server, layak dicoba lagi
                else -> {
                    // 4xx (mis. signature invalid, payload salah): retry TIDAK akan
                    // membantu, request-nya sendiri yang salah.
                    android.util.Log.e("HashRegisterWorker", "Register gagal, HTTP $code")
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            // Kemungkinan besar tidak ada internet saat ini -- WorkManager akan
            // coba lagi otomatis sesuai backoff policy yang di-set saat enqueue.
            e.printStackTrace()
            Result.retry()
        }
    }

    private fun signPayload(timestamp: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_SECRET.toByteArray(), "HmacSHA256"))
        val signatureBytes = mac.doFinal("$timestamp.$body".toByteArray())
        return signatureBytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_VERIFICATION_ID = "verification_id"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_FILE_NAME = "file_name"
        private const val KEY_CAPTURED_AT = "captured_at"

        const val HASH_REGISTER_TAG = "hash_register"

        /** Antre pendaftaran hash foto ke Cloudflare, dipanggil setelah foto tersimpan. */
        fun enqueue(
            context: Context,
            verificationId: String,
            sha256: String,
            fileName: String,
            capturedAt: String
        ) {
            val inputData = workDataOf(
                KEY_VERIFICATION_ID to verificationId,
                KEY_SHA256 to sha256,
                KEY_FILE_NAME to fileName,
                KEY_CAPTURED_AT to capturedAt
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<HashRegisterWorker>()
                .setInputData(inputData)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(HASH_REGISTER_TAG)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}