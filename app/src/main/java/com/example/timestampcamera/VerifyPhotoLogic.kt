package com.example.timestampcamera

sealed class VerifyPhotoUiState {
    object Idle : VerifyPhotoUiState()
    object Loading : VerifyPhotoUiState()
    object NoQrFound : VerifyPhotoUiState()
    data class Verified(val record: VerificationRecord) : VerifyPhotoUiState()
    data class Modified(val record: VerificationRecord, val actualHash: String) : VerifyPhotoUiState()
    object NotRegistered : VerifyPhotoUiState()
    data class Error(val message: String) : VerifyPhotoUiState()
}

/**
 * Alur lengkap Verify Photo:
 * 1. Baca QR dari file yang dipilih user -> dapat verification_id
 * 2. Hitung SHA-256 dari BYTES ASLI file (bukan bitmap yang di-downsample untuk QR)
 * 3. GET record dari Cloudflare
 * 4. Bandingkan hash lokal vs hash yang terdaftar
 *
 * Kalau file bukan hasil TimestampCamera sama sekali (tidak ada QR terbaca),
 * hasilnya NoQrFound -- beda dari NotRegistered (yang berarti QR ADA tapi
 * ID-nya tidak ditemukan di server).
 */
suspend fun verifyPhotoBytes(bytes: ByteArray): VerifyPhotoUiState {
    return try {
        val verificationId = readVerificationIdFromBytes(bytes)
            ?: return VerifyPhotoUiState.NoQrFound

        val actualHash = computeSha256(bytes)

        when (val fetchResult = fetchVerificationRecord(verificationId)) {
            is FetchVerificationResult.Found -> {
                if (fetchResult.record.sha256.equals(actualHash, ignoreCase = true)) {
                    VerifyPhotoUiState.Verified(fetchResult.record)
                } else {
                    VerifyPhotoUiState.Modified(fetchResult.record, actualHash)
                }
            }
            FetchVerificationResult.NotFound -> VerifyPhotoUiState.NotRegistered
            is FetchVerificationResult.NetworkError -> VerifyPhotoUiState.Error(fetchResult.message)
        }
    } catch (e: Exception) {
        VerifyPhotoUiState.Error(e.message ?: "Terjadi kesalahan tidak terduga")
    }
}