package com.example.timestampcamera

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DriveFile(
    val id: String,
    val name: String,
    val createdTime: String,
    val thumbnailLink: String?,
    val webViewLink: String?,
    val mimeType: String
) {
    val type: MediaType
        get() = if (mimeType.startsWith("video/")) {
            MediaType.VIDEO
        } else {
            MediaType.PHOTO
        }
}

/**
 * Satu halaman hasil Drive.
 *
 * files = maksimal 50 file
 * nextPageToken = token untuk mengambil halaman berikutnya
 */
data class DrivePage(
    val files: List<DriveFile>,
    val nextPageToken: String?
)

private const val DRIVE_FOLDER_NAME = "TimestampCamera"
private const val DRIVE_PAGE_SIZE = 50


// ============================================================
// THUMBNAIL CACHE
// ============================================================
//
// Cache thumbnail Drive sekarang memakai sharedThumbnailCache (ThumbnailCache.kt),
// yang juga dipakai oleh thumbnail foto/video lokal -- satu pool memori untuk
// semua sumber, bukan cache terpisah per sumber.


// ============================================================
// FOLDER ID CACHE
// ============================================================
//
// findFolderId() adalah 1 request HTTP tersendiri. Sebelumnya listDriveFiles()
// dan countDriveFiles() masing-masing memanggilnya sendiri-sendiri -- artinya
// setiap refreshGallery() melakukan PENCARIAN FOLDER YANG SAMA dua kali secara
// terpisah, padahal ID folder App ini tidak akan berubah selama sesi berjalan.
// Di-cache di sini (in-memory, per proses) supaya lookup itu cuma terjadi
// sekali per sesi aplikasi, bukan sekali per pemanggilan fungsi.
//
// Sengaja TIDAK men-cache hasil null: kalau folder belum ada (user belum pernah
// motret), null tidak disimpan, supaya begitu folder terbentuk (foto/video
// pertama diambil), lookup berikutnya bisa langsung ketemu tanpa perlu restart app.
@Volatile
private var cachedFolderId: String? = null

private fun resolveFolderId(accessToken: String): String? {
    cachedFolderId?.let { return it }
    val id = findFolderId(accessToken, DRIVE_FOLDER_NAME)
    if (id != null) {
        cachedFolderId = id
    }
    return id
}


// ============================================================
// LIST DRIVE - SATU HALAMAN SAJA
// ============================================================

/**
 * Mengambil maksimal 50 file.
 *
 * Fungsi ini TIDAK mengambil semua halaman.
 * Halaman berikutnya diambil ketika user scroll.
 */
suspend fun listDriveFiles(
    accessToken: String,
    pageToken: String? = null
): DrivePage {

    return withContext(Dispatchers.IO) {

        try {

            val folderId =
                resolveFolderId(accessToken)
                    ?: return@withContext DrivePage(
                        emptyList(),
                        null
                    )

            val query = URLEncoder.encode(
                "'$folderId' in parents and trashed = false and " +
                        "(mimeType contains 'image/' or mimeType contains 'video/')",
                "UTF-8"
            )

            val fields = URLEncoder.encode(
                "nextPageToken," +
                        "files(" +
                        "id,name,createdTime,thumbnailLink,webViewLink,mimeType" +
                        ")",
                "UTF-8"
            )

            val tokenParam =
                if (pageToken != null) {
                    "&pageToken=" +
                            URLEncoder.encode(
                                pageToken,
                                "UTF-8"
                            )
                } else {
                    ""
                }

            val url = URL(
                "https://www.googleapis.com/drive/v3/files" +
                        "?q=$query" +
                        "&fields=$fields" +
                        "&orderBy=createdTime%20desc" +
                        "&pageSize=$DRIVE_PAGE_SIZE" +
                        tokenParam
            )

            val response =
                httpGet(
                    url,
                    accessToken
                )
                    ?: return@withContext DrivePage(
                        emptyList(),
                        null
                    )

            val json = JSONObject(response)

            val filesArray =
                json.optJSONArray("files")

            val result =
                mutableListOf<DriveFile>()

            if (filesArray != null) {

                for (i in 0 until filesArray.length()) {

                    val obj =
                        filesArray.getJSONObject(i)

                    result.add(
                        DriveFile(
                            id = obj.getString("id"),

                            name = obj.optString(
                                "name"
                            ),

                            createdTime =
                                obj.optString(
                                    "createdTime"
                                ),

                            thumbnailLink =
                                obj.optString(
                                    "thumbnailLink"
                                ).ifBlank {
                                    null
                                },

                            webViewLink =
                                obj.optString(
                                    "webViewLink"
                                ).ifBlank {
                                    null
                                },

                            mimeType =
                                obj.optString(
                                    "mimeType"
                                )
                        )
                    )
                }
            }

            val nextToken =
                json.optString(
                    "nextPageToken"
                ).ifBlank {
                    null
                }

            DrivePage(
                files = result,
                nextPageToken = nextToken
            )

        } catch (e: Exception) {

            e.printStackTrace()

            DrivePage(
                emptyList(),
                null
            )
        }
    }
}


// ============================================================
// HITUNG TOTAL FILE DRIVE
// ============================================================

/**
 * Menghitung total foto + video.
 *
 * Fungsi ini hanya dipanggil ketika:
 * - pertama kali membuka Galeri
 * - menekan Refresh
 *
 * Tidak dipanggil ketika scroll.
 *
 * CATATAN PERFORMA: fungsi ini tetap mem-paginasi SEMUA file untuk menghitung
 * total (Drive API tidak menyediakan endpoint count langsung). Kalau folder
 * berisi ratusan+ file, ini akan memakan beberapa request berurutan setiap
 * refresh. Untuk saat ini dibiarkan (kebutuhan saat ini: total count akurat),
 * tapi kalau ke depannya folder makin besar dan refresh terasa lambat,
 * pertimbangkan: tampilkan total dari cache lama dulu sambil hitungan baru
 * jalan di background, atau hilangkan total count dan cukup tampilkan
 * "50+" / jumlah yang sudah termuat.
 */
suspend fun countDriveFiles(
    accessToken: String
): Int {

    return withContext(Dispatchers.IO) {

        try {

            val folderId =
                resolveFolderId(accessToken)
                    ?: return@withContext 0

            val query = URLEncoder.encode(
                "'$folderId' in parents and trashed = false and " +
                        "(mimeType contains 'image/' or mimeType contains 'video/')",
                "UTF-8"
            )

            val fields = URLEncoder.encode(
                "nextPageToken,files(id)",
                "UTF-8"
            )

            var pageToken: String? = null
            var total = 0

            do {

                val tokenParam =
                    if (pageToken != null) {
                        "&pageToken=" +
                                URLEncoder.encode(
                                    pageToken,
                                    "UTF-8"
                                )
                    } else {
                        ""
                    }

                val url = URL(
                    "https://www.googleapis.com/drive/v3/files" +
                            "?q=$query" +
                            "&fields=$fields" +
                            "&pageSize=$DRIVE_PAGE_SIZE" +
                            tokenParam
                )

                val response =
                    httpGet(
                        url,
                        accessToken
                    )
                        ?: return@withContext total

                val json =
                    JSONObject(response)

                val filesArray =
                    json.optJSONArray("files")

                if (filesArray != null) {
                    total += filesArray.length()
                }

                pageToken =
                    json.optString(
                        "nextPageToken"
                    ).ifBlank {
                        null
                    }

            } while (pageToken != null)

            total

        } catch (e: Exception) {

            e.printStackTrace()

            0
        }
    }
}


// ============================================================
// FIND FOLDER
// ============================================================

private fun findFolderId(
    accessToken: String,
    folderName: String
): String? {

    val query = URLEncoder.encode(
        "mimeType = 'application/vnd.google-apps.folder' " +
                "and name = '$folderName' " +
                "and trashed = false",
        "UTF-8"
    )

    val url = URL(
        "https://www.googleapis.com/drive/v3/files" +
                "?q=$query" +
                "&fields=files(id)" +
                "&pageSize=1"
    )

    val response =
        httpGet(
            url,
            accessToken
        )
            ?: return null

    val json =
        JSONObject(response)

    val filesArray =
        json.optJSONArray("files")
            ?: return null

    if (filesArray.length() == 0) {
        return null
    }

    return filesArray
        .getJSONObject(0)
        .getString("id")
}


// ============================================================
// DOWNLOAD THUMBNAIL + CACHE
// ============================================================

suspend fun downloadDriveThumbnail(
    thumbnailUrl: String,
    accessToken: String,
    fileId: String? = null
): Bitmap? {

    val cacheKey = fileId?.let { "drive:$it" }

    // Cek cache
    if (cacheKey != null) {
        getCachedThumbnail(cacheKey)?.let { return it }
    }

    // Download
    val bitmap =
        withContext(Dispatchers.IO) {

            try {

                val connection =
                    URL(thumbnailUrl)
                        .openConnection()
                            as HttpURLConnection

                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $accessToken"
                )

                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.connect()

                if (
                    connection.responseCode ==
                    HttpURLConnection.HTTP_OK
                ) {

                    connection.inputStream.use { stream ->
                        val options = android.graphics.BitmapFactory.Options().apply {
                            // Thumbnail tidak butuh alpha -- RGB_565 = separuh memori
                            // dibanding ARGB_8888 (default).
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        android.graphics.BitmapFactory
                            .decodeStream(stream, null, options)
                    }

                } else {
                    null
                }

            } catch (e: Exception) {

                e.printStackTrace()

                null
            }
        }

    // Simpan cache
    if (
        bitmap != null &&
        cacheKey != null
    ) {
        putCachedThumbnail(cacheKey, bitmap)
    }

    return bitmap
}


// ============================================================
// HTTP GET
// ============================================================

private fun httpGet(
    url: URL,
    accessToken: String
): String? {

    return try {

        val connection =
            url.openConnection()
                    as HttpURLConnection

        connection.setRequestProperty(
            "Authorization",
            "Bearer $accessToken"
        )

        connection.requestMethod = "GET"

        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        connection.connect()

        if (
            connection.responseCode ==
            HttpURLConnection.HTTP_OK
        ) {

            connection.inputStream
                .bufferedReader()
                .use {
                    it.readText()
                }

        } else {

            null
        }

    } catch (e: Exception) {

        e.printStackTrace()

        null
    }
}