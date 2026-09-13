package com.example.timestampcamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.File
import java.util.Date
import java.util.concurrent.ExecutorService

fun capturePhotoWithLocation(
    context: Context,
    imageCapture: ImageCapture?,
    executor: ExecutorService,
    fusedLocationClient: FusedLocationProviderClient,
    hasLocationPermission: Boolean,
    onCaptureStarted: () -> Unit,
    // ---- BARU: dipanggil begitu bitmap+watermark selesai dibuat, SEBELUM disimpan ke galeri ----
    onPhotoReady: (Bitmap) -> Unit,
    onCaptureFinished: (Uri?) -> Unit
) {
    val capture = imageCapture ?: return

    // ---- PENTING: catat waktu di sini, PALING AWAL, SEBELUM request lokasi dimulai. ----
    // ---- fusedLocationClient.getCurrentLocation() di bawah bisa makan waktu 1-3 detik, ----
    // ---- jadi kalau Date() dihitung setelah lokasi didapat (apalagi setelah takePicture ----
    // ---- selesai), jam di watermark bisa beda cukup jauh dari jam yang terlihat di ----
    // ---- preview saat tombol shutter ditekan. Nilai inilah yang harus sama dengan ----
    // ---- preview, bukan waktu setelah semua proses selesai.
    val capturedAt = Date()

    onCaptureStarted()

    fun proceedWithLocation(location: Location?) {
        // ---- Tolak jika lokasi terdeteksi berasal dari aplikasi fake-GPS ----
        if (location != null && location.isFromMockProvider) {
            (context as ComponentActivity).runOnUiThread {
                Toast.makeText(
                    context,
                    "Lokasi terdeteksi palsu (mock location). Foto dibatalkan.",
                    Toast.LENGTH_LONG
                ).show()
                onCaptureFinished(null)
            }
            return
        }

        val tempFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        capture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val addressText = location?.let {
                            getAddressText(context, it)
                        } ?: "Lokasi tidak tersedia"

                        val coordText = location?.let {
                            formatCoordText(it)
                        } ?: ""

                        val watermarkedBitmap = addWatermark(
                            file = tempFile,
                            addressLines = addressText,
                            coordText = coordText,
                            capturedAt = capturedAt
                        )

                        // ---- Tampilkan preview SEKETIKA, sebelum menunggu proses simpan ke galeri ----
                        (context as ComponentActivity).runOnUiThread {
                            onPhotoReady(watermarkedBitmap)
                        }

                        val savedUri = saveBitmapToMediaStore(context, watermarkedBitmap)
                        tempFile.delete()

                        // ---- Antre upload ke Drive di background (foto lokal akan otomatis ----
                        // ---- terhapus oleh DriveUploadWorker begitu upload sukses) ----
                        if (savedUri != null) {
                            enqueueDriveUpload(context, savedUri)
                        }

                        context.runOnUiThread {
                            Toast.makeText(context, "Foto tersimpan", Toast.LENGTH_SHORT).show()
                            onCaptureFinished(savedUri)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        (context as ComponentActivity).runOnUiThread {
                            Toast.makeText(
                                context,
                                "Gagal memproses foto: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                            onCaptureFinished(null)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    (context as ComponentActivity).runOnUiThread {
                        Toast.makeText(
                            context,
                            "Gagal mengambil foto: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                        onCaptureFinished(null)
                    }
                }
            }
        )
    }

    if (hasLocationPermission &&
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            CancellationTokenSource().token
        ).addOnSuccessListener { location ->
            proceedWithLocation(location)
        }.addOnFailureListener {
            proceedWithLocation(null)
        }
    } else {
        proceedWithLocation(null)
    }
}