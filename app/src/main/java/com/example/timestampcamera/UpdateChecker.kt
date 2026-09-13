package com.example.timestampcamera

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Info rilis terbaru, diambil langsung dari GitHub Releases API. */
data class LatestRelease(
    val versionName: String,
    val downloadUrl: String,
    val notes: String
)

object UpdateChecker {

    /**
     * Endpoint resmi GitHub, publik, TIDAK butuh token karena repo-nya public.
     * Ganti USERNAME/NAMA-REPO sesuai repo kamu.
     */
    private const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/kuwatsubhi207/TimestampCamera/releases/latest"

    /** Nama file APK yang dicari di antara release assets (arm64-v8a = hampir semua HP modern). */
    private const val PREFERRED_APK_NAME_HINT = "arm64-v8a"

    /**
     * Network call -- WAJIB dipanggil dari coroutine di IO dispatcher, bukan main
     * thread. Return null kalau tidak ada update, atau gagal cek (mis. tidak ada
     * internet) -- app tetap jalan normal, pengecekan cuma di-skip diam-diam.
     */
    fun checkForUpdate(currentVersionName: String): LatestRelease? {
        return try {
            val connection = URL(LATEST_RELEASE_API_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val tagName = json.getString("tag_name").removePrefix("v")
            if (!isNewer(latest = tagName, current = currentVersionName)) return null

            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk") && name.contains(PREFERRED_APK_NAME_HINT)) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            // Fallback: kalau tidak ketemu yang arm64-v8a spesifik, ambil .apk pertama
            if (apkUrl == null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }
            }
            val finalApkUrl = apkUrl ?: return null

            LatestRelease(
                versionName = tagName,
                downloadUrl = finalApkUrl,
                notes = json.optString("body", "")
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Bandingkan versi ala "1.10" vs "1.9" secara numerik per-segmen, bukan string biasa. */
    private fun isNewer(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until len) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }
}