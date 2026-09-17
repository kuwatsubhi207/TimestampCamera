package com.example.timestampcamera

/** Record hasil GET /api/v1/verify/{id} dari Cloudflare Worker. */
data class VerificationRecord(
    val id: String,
    val algorithm: String,
    val sha256: String,
    val capturedAt: String,
    val app: String,
    val version: String
)

/**
 * Hasil pemanggilan fetchVerificationRecord() -- dipisah 3 kemungkinan supaya
 * pemanggil (ScanQrScreen, verifyPhotoBytes) bisa membedakan dengan jelas:
 * "ID tidak terdaftar" (NotFound, status valid dari server) vs "tidak bisa
 * cek sekarang" (NetworkError, mis. tidak ada internet) -- dua hal ini TIDAK
 * boleh ditampilkan dengan pesan yang sama ke user.
 */
sealed class FetchVerificationResult {
    data class Found(val record: VerificationRecord) : FetchVerificationResult()
    object NotFound : FetchVerificationResult()
    data class NetworkError(val message: String) : FetchVerificationResult()
}