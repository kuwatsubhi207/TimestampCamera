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
    // ---- Dipanggil begitu bitmap+watermark+QR selesai dibuat, SEBELUM disimpan ke galeri ----
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

                        val verificationId = VerificationId.generate()
                        val verificationUrl = buildVerificationUrl(verificationId)
                        val finalBitmap = drawQrOntoBitmap(watermarkedBitmap, verificationUrl)

                        // ---- Tampilkan preview SEKETIKA (sudah termasuk watermark & QR), ----
                        // ---- sebelum menunggu proses compress/simpan/upload ----
                        (context as ComponentActivity).runOnUiThread {
                            onPhotoReady(finalBitmap)
                        }

                        // ---- Compress SATU KALI SAJA. Bytes hasil compress ini yang ----
                        // ---- dipakai untuk DUA hal: dihitung hash-nya, dan ditulis ke ----
                        // ---- MediaStore -- supaya keduanya PERSIS SAMA (lihat catatan ----
                        // ---- di computeSha256(ByteArray) dan saveBytesToMediaStore()). ----
                        val jpegBytes = compressBitmapToMaxSize(finalBitmap, maxSizeBytes = 200 * 1024)
                        val sha256 = computeSha256(jpegBytes)

                        val fileName = buildPhotoFileName(capturedAt)
                        val savedUri = saveBytesToMediaStore(context, jpegBytes, fileName)
                        tempFile.delete()

                        if (savedUri != null) {
                            // ---- Antre upload ke Drive di background (foto lokal akan ----
                            // ---- otomatis terhapus oleh DriveUploadWorker begitu upload ----
                            // ---- sukses) ----
                            enqueueDriveUpload(context, savedUri)

                            // ---- Antre pendaftaran hash ke Cloudflare, terpisah dari upload ----
                            // ---- Drive -- keduanya independen, kalau salah satu gagal/telat ----
                            // ---- tidak saling memblokir yang lain. ----
                            HashRegisterWorker.enqueue(
                                context = context,
                                verificationId = verificationId,
                                sha256 = sha256,
                                fileName = fileName,
                                capturedAt = formatIso8601(capturedAt)
                            )

                            (context as ComponentActivity).runOnUiThread {
                                Toast.makeText(context, "Foto tersimpan", Toast.LENGTH_SHORT).show()
                                onCaptureFinished(savedUri)
                            }
                        } else {
                            (context as ComponentActivity).runOnUiThread {
                                Toast.makeText(context, "Gagal menyimpan foto ke galeri", Toast.LENGTH_SHORT).show()
                                onCaptureFinished(null)
                            }
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