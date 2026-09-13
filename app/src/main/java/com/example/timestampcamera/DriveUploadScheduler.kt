package com.example.timestampcamera

import android.content.Context
import android.net.Uri

/**
 * Titik masuk tunggal untuk mengantre upload foto ke Drive.
 * Diteruskan ke DriveUploadWorker.enqueue() supaya PATH-nya sama persis
 * dengan yang dipakai retryPendingUploads() -- termasuk tag DRIVE_UPLOAD_TAG
 * (dipakai MainActivity buat progress indicator) dan pencatatan ke
 * PendingUploads (dipakai buat retry kalau upload gagal karena belum sign-in).
 *
 * JANGAN bikin WorkRequest sendiri di sini lagi -- itu yang bikin bug
 * kemarin: dua jalur enqueue yang gak sinkron.
 */
fun enqueueDriveUpload(context: Context, photoUri: Uri) {
    DriveUploadWorker.enqueue(context, photoUri)
}