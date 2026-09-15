package com.example.timestampcamera

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.File as JavaFile

/**
 * Dilempar saat Drive API menolak request dengan HTTP 401/403 -- artinya token
 * sudah tidak valid ATAU izin drive.file sudah di-revoke user (mis. lewat Google
 * Account permissions, atau lewat DriveAuth.signOut() yang sekarang memanggil
 * revokeDriveAccess()). Kondisi ini TIDAK akan sembuh sendiri dengan retry:
 * WorkManager retry dengan backoff hanya cocok untuk error transient (network
 * flaky, Drive lagi 5xx), bukan untuk "izin memang sudah dicabut".
 */
private class DriveAuthRevokedException(val httpCode: Int) :
    IOException("Drive access ditolak (HTTP $httpCode) -- token/izin tidak valid lagi")

/**
 * Daftar URI foto yang SUDAH diantre untuk diupload tapi BELUM berhasil, karena saat
 * worker jalan tidak ada akses Drive yang valid (mis. user sedang sign-out).
 *
 * Ini sumber kebenaran terpisah dari status WorkManager, karena begitu sebuah WorkRequest
 * di-mark Result.failure(), statusnya jadi terminal (WorkManager TIDAK akan otomatis
 * mencoba lagi) -- jadi kita perlu cara sendiri untuk tau foto mana yang perlu
 * di-enqueue ULANG saat user connect Drive lagi.
 */
private object PendingUploads {
    private const val PREFS_NAME = "drive_upload_prefs"
    private const val KEY_PENDING_URIS = "pending_uris"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun add(context: Context, uri: Uri) {
        val current = prefs(context).getStringSet(KEY_PENDING_URIS, emptySet()) ?: emptySet()
        prefs(context).edit()
            .putStringSet(KEY_PENDING_URIS, current + uri.toString())
            .apply()
    }

    fun remove(context: Context, uri: Uri) {
        val current = prefs(context).getStringSet(KEY_PENDING_URIS, emptySet()) ?: emptySet()
        prefs(context).edit()
            .putStringSet(KEY_PENDING_URIS, current - uri.toString())
            .apply()
    }

    fun getAll(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_PENDING_URIS, emptySet()) ?: emptySet()
    }
}

class DriveUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_PHOTO_URI) ?: return Result.failure()
        val uri = Uri.parse(uriString)

        // Coba ambil access token diam-diam (tanpa UI).
        // Kalau user belum pernah authorize / sedang sign-out / izin sudah dicabut ->
        // tidak ada gunanya WorkManager retry sendiri terus-menerus dari background,
        // jadi tetap Result.failure() di sini. TAPI uri-nya TETAP ada di PendingUploads
        // (tidak dihapus), supaya nanti bisa di-enqueue ULANG lewat retryPendingUploads()
        // begitu user berhasil connect Drive lagi. Foto lokal tetap aman tersimpan di HP.
        val accessToken = DriveAuth.getFreshAccessTokenSilently(applicationContext)
            ?: return Result.failure()

        var tempFile: JavaFile? = null

        return try {
            // Drive REST butuh java.io.File, jadi salin dulu dari content:// Uri ke cache
            val mimeType = applicationContext.contentResolver.getType(uri) ?: "application/octet-stream"
            val extension = if (mimeType.startsWith("video")) "mp4" else "jpg"

            tempFile = JavaFile(
                applicationContext.cacheDir,
                "drive_upload_${System.currentTimeMillis()}.$extension"
            )
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return Result.failure()

            val folderId = getOrCreateAppFolder(accessToken)
            uploadFile(accessToken, tempFile, mimeType, folderId)
                ?: throw IOException("Upload ke Drive gagal (response tidak sukses)")

            // ---- Foto TIDAK dihapus di sini lagi. Foto lokal tetap disimpan selama ----
            // ---- 3 hari (lihat LocalCleanupWorker), terlepas dari status upload -- ----
            // ---- ini kebijakan retensi lokal, dipisah dari proses upload.

            PendingUploads.remove(applicationContext, uri)
            Result.success()
        } catch (e: DriveAuthRevokedException) {
            // Izin sudah dicabut -- retry TIDAK akan pernah berhasil sampai user
            // authorize ulang secara manual (butuh UI, tidak bisa dari background).
            // uri tetap ada di PendingUploads supaya bisa di-retry lewat
            // retryPendingUploads() begitu user connect Drive lagi.
            e.printStackTrace()
            Result.failure()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry() // Error transient (network/5xx dll) -- coba lagi otomatis dengan exponential backoff
        } finally {
            tempFile?.delete()
        }
    }

    /**
     * Cari folder "TimestampCamera" milik app ini; buat baru kalau belum ada.
     * REST manual (bukan client library resmi) -- sama gayanya dengan
     * DriveGalleryUtils.kt, yang duluan pindah dari client resmi ke cara ini.
     */
    private fun getOrCreateAppFolder(accessToken: String): String {
        val folderName = "TimestampCamera"
        val query = URLEncoder.encode(
            "mimeType='application/vnd.google-apps.folder' and name='$folderName' and trashed=false",
            "UTF-8"
        )

        val listUrl = URL(
            "https://www.googleapis.com/drive/v3/files?q=$query&fields=files(id)&pageSize=1"
        )
        httpGet(listUrl, accessToken)?.let { response ->
            val files = JSONObject(response).optJSONArray("files")
            if (files != null && files.length() > 0) {
                return files.getJSONObject(0).getString("id")
            }
        }

        // Folder belum ada -> buat baru
        val metadataJson = JSONObject().apply {
            put("name", folderName)
            put("mimeType", "application/vnd.google-apps.folder")
        }

        val connection = (URL("https://www.googleapis.com/drive/v3/files?fields=id")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
        }

        connection.outputStream.use { it.write(metadataJson.toString().toByteArray(Charsets.UTF_8)) }

        val responseCode = connection.responseCode
        if (responseCode == 401 || responseCode == 403) {
            throw DriveAuthRevokedException(responseCode)
        }
        if (responseCode !in 200..299) {
            throw IOException("Gagal membuat folder Drive (HTTP $responseCode)")
        }

        val body = connection.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(body).getString("id")
    }

    /**
     * Upload 1 file lewat multipart/related (metadata JSON + isi file), pengganti manual
     * dari `driveService.files().create(metadata, mediaContent).execute()` milik client
     * resmi. Pakai fixed-length streaming supaya file tidak perlu di-buffer penuh ke memori.
     */
    /**
     * @throws DriveAuthRevokedException kalau Drive menolak dengan 401/403 (izin tidak valid lagi).
     */
    private fun uploadFile(
        accessToken: String,
        file: JavaFile,
        mimeType: String,
        folderId: String
    ): String? {
        val boundary = "----TimestampCameraBoundary${UUID.randomUUID()}"

        val metadataJson = JSONObject().apply {
            put("name", file.name)
            put("parents", JSONArray().put(folderId))
        }

        val prefix = (
                "--$boundary\r\n" +
                        "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                        "$metadataJson\r\n" +
                        "--$boundary\r\n" +
                        "Content-Type: $mimeType\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--".toByteArray(Charsets.UTF_8)

        val connection = (URL(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"
        ).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setFixedLengthStreamingMode(prefix.size.toLong() + file.length() + suffix.size)
        }

        connection.outputStream.use { output ->
            output.write(prefix)
            file.inputStream().use { it.copyTo(output) }
            output.write(suffix)
        }

        val responseCode = connection.responseCode
        if (responseCode == 401 || responseCode == 403) {
            throw DriveAuthRevokedException(responseCode)
        }
        if (responseCode !in 200..299) {
            return null
        }

        val body = connection.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(body).optString("id").ifBlank { null }
    }

    /**
     * @throws DriveAuthRevokedException kalau Drive menolak dengan 401/403 (izin tidak valid lagi).
     * Untuk error lain (404, 5xx, dll) tetap return null seperti sebelumnya --
     * caller (getOrCreateAppFolder) menganggapnya "belum ada folder" dan lanjut buat baru,
     * yang aman karena create juga sudah dicek 401/403-nya sendiri.
     */
    private fun httpGet(url: URL, accessToken: String): String? {
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.connect()

        val responseCode = connection.responseCode
        if (responseCode == 401 || responseCode == 403) {
            throw DriveAuthRevokedException(responseCode)
        }

        return if (responseCode == HttpURLConnection.HTTP_OK) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            null
        }
    }

    companion object {
        const val KEY_PHOTO_URI = "photo_uri"

        // Tag ini dipakai MainActivity untuk observasi progres upload lewat WorkManager
        const val DRIVE_UPLOAD_TAG = "drive_upload"

        fun enqueue(context: Context, photoUri: Uri) {
            // Catat sebagai "belum berhasil" SEBELUM enqueue -- supaya kalaupun worker
            // langsung gagal karena belum sign-in, uri-nya tidak hilang dan bisa
            // di-retry nanti lewat retryPendingUploads().
            PendingUploads.add(context, photoUri)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val uploadRequest = OneTimeWorkRequestBuilder<DriveUploadWorker>()
                .setInputData(workDataOf(KEY_PHOTO_URI to photoUri.toString()))
                .setConstraints(constraints)
                .addTag(DRIVE_UPLOAD_TAG)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            // enqueueUniqueWork mencegah duplikat kalau foto yang sama coba dijadwalkan ulang.
            // KEEP aman dipakai ulang di sini: kalau work sebelumnya untuk uri yang sama
            // sudah selesai (SUCCEEDED/FAILED, keduanya status terminal), WorkManager
            // akan tetap memasukkan work request yang baru ini.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "upload_$photoUri",
                ExistingWorkPolicy.KEEP,
                uploadRequest
            )
        }

        /**
         * Daftarkan ULANG semua foto yang sebelumnya gagal diupload karena belum ada
         * akses Drive (lihat PendingUploads). Panggil ini begitu user berhasil connect
         * Drive lagi -- baik lewat tombol "Hubungkan ke Drive", maupun saat app dibuka
         * dan ternyata token masih valid.
         */
        fun retryPendingUploads(context: Context) {
            PendingUploads.getAll(context).forEach { uriString ->
                enqueue(context, Uri.parse(uriString))
            }
        }
    }
}