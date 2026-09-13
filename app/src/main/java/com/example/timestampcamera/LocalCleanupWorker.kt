package com.example.timestampcamera

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Menghapus foto TimestampCamera dari penyimpanan lokal HP yang sudah berumur
 * lebih dari [RETENTION_DAYS] hari -- TERLEPAS dari status upload ke Drive.
 *
 * Ini kebijakan retensi lokal murni berbasis umur, sengaja dipisah dari
 * DriveUploadWorker: upload jalan langsung di background begitu foto diambil,
 * sedangkan penghapusan lokal jalan belakangan sebagai jadwal harian terpisah.
 *
 * PERHATIAN: kalau upload ke Drive gagal terus (izin dicabut, dll) dan tidak
 * ketahuan sebelum masa retensi habis, foto akan terhapus dari HP tanpa pernah
 * tersimpan di Drive. Ini konsekuensi dari desain "hapus berbasis umur saja".
 */
class LocalCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val cutoffEpochSeconds = (System.currentTimeMillis() - RETENTION_DAYS * DAY_MILLIS) / 1000

            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection =
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
                        "${MediaStore.Images.Media.DATE_ADDED} < ?"
            val selectionArgs = arrayOf(
                "%Pictures/TimestampCamera%",
                cutoffEpochSeconds.toString()
            )

            applicationContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    applicationContext.contentResolver.delete(uri, null, null)
                }
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            // Bukan hal kritis kalau gagal sekali -- akan dicoba lagi di jadwal berikutnya
            Result.success()
        }
    }

    companion object {
        private const val RETENTION_DAYS = 3L

        // ==================================================================
        // TESTING: DAY_MILLIS dipendekkan jadi 15 detik supaya retensi efektif
        // cuma 45 detik (3 x 15 detik). Interval periodic worker minimal yang
        // diizinkan Android adalah 15 menit -- tidak bisa lebih cepat dari itu,
        // jadi untuk melihat hasil hapus dengan cepat pakai scheduleOneTimeForTesting()
        // di bawah, bukan schedulePeriodic().
        // GANTI KE VERSI PRODUKSI SEBELUM RILIS -- lihat baris di bawah.
        // ==================================================================
//        private const val DAY_MILLIS = 15 * 1000L

        // ---- Versi PRODUKSI (aktifkan lagi nanti, ganti baris di atas dengan ini) ----
         private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

        private const val UNIQUE_PERIODIC_WORK_NAME = "local_cleanup_daily"
        private const val UNIQUE_TEST_WORK_NAME = "local_cleanup_test_trigger"

        /**
         * Versi PRODUKSI: jalan tiap 1 hari (minimum interval WorkManager memang 15 menit,
         * tidak bisa lebih cepat dari itu untuk periodic work).
         * Panggil sekali saja, misal di MainActivity.onCreate(). Aman dipanggil berkali-kali.
         *
         * Pakai UPDATE (bukan KEEP) supaya kalau kamu ubah konfigurasi worker di kode,
         * jadwal yang sudah ter-enqueue dari run sebelumnya ikut ter-update -- KEEP akan
         * mengabaikan perubahan kalau sudah pernah ada jadwal dengan nama yang sama.
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<LocalCleanupWorker>(1, TimeUnit.DAYS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /**
         * KHUSUS TESTING: pakai OneTimeWorkRequest dengan delay pendek (bukan periodic),
         * karena WorkManager tidak mengizinkan periodic work lebih cepat dari 15 menit.
         * Jalankan berulang tiap kali dipanggil supaya kamu bisa lihat hasil deletion
         * dalam hitungan detik saat testing. HAPUS pemanggilan ini sebelum rilis,
         * ganti balik ke schedulePeriodic() saja di MainActivity.
         */
        fun scheduleOneTimeForTesting(context: Context, delaySeconds: Long = 20) {
            val request = OneTimeWorkRequestBuilder<LocalCleanupWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_TEST_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}