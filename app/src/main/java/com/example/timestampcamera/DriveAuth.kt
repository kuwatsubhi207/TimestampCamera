package com.example.timestampcamera

import android.content.Context
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sama persis dengan konstanta DriveScopes.DRIVE_FILE dari google-api-services-drive,
 * tapi ditulis manual di sini supaya app TIDAK perlu bawa seluruh library client
 * resmi itu cuma untuk 1 string scope.
 */
private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/**
 * Scope yang dipakai: DRIVE_FILE, artinya app HANYA bisa akses/lihat file
 * yang dibuat oleh app ini sendiri di Drive user -- bukan seluruh isi Drive mereka.
 *
 * Migrated off legacy GoogleSignIn (removed in play-services-auth 22.0.0) to:
 *  - Credential Manager: identity / "who is signed in"
 *  - AuthorizationClient: OAuth scope grant for Drive access
 */
object DriveAuth {

    private const val WEB_CLIENT_ID = "278094992785-6vi6kaq3j3b94359dhpt38o1tcqaqii6.apps.googleusercontent.com"

    /** Step 1: sign the user in and get their Google identity via Credential Manager. */
    suspend fun signIn(context: Context): GoogleIdTokenCredential {
        val option = GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        val response: GetCredentialResponse =
            CredentialManager.create(context).getCredential(context, request)

        return GoogleIdTokenCredential.createFrom(response.credential.data)
    }

    /**
     * Ambil klaim "email" dari payload JWT idToken (bagian tengah, dipisah titik).
     *
     * PENTING: ini HANYA didekode untuk keperluan TAMPILAN (menunjukkan akun mana yang
     * sedang login) -- signature JWT-nya TIDAK diverifikasi di sini. Jangan pakai hasil
     * fungsi ini untuk keputusan keamanan/otorisasi apa pun; untuk itu server/API Google
     * yang sudah memvalidasi token (lewat scope Drive di authorizeDrive()) yang dipakai.
     */
    fun extractEmailFromIdToken(idToken: String): String? {
        return try {
            val parts = idToken.split(".")
            if (parts.size < 2) return null

            // JWT pakai base64url TANPA padding -- ganti karakter khasnya lalu tambahkan
            // padding manual supaya bisa didekode dengan Base64 standar.
            var payload = parts[1].replace('-', '+').replace('_', '/')
            val padNeeded = (4 - payload.length % 4) % 4
            payload += "=".repeat(padNeeded)

            val decodedBytes = Base64.decode(payload, Base64.DEFAULT)
            val json = JSONObject(String(decodedBytes, Charsets.UTF_8))
            json.optString("email").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Step 2: request the Drive scope.
     * Caller MUST check result.hasResolution() -- if true, launch result.pendingIntent
     * via an ActivityResultLauncher before an access token is available.
     * If false, result.accessToken is ready to use immediately.
     */
    suspend fun authorizeDrive(context: Context): AuthorizationResult {
        val client: AuthorizationClient = Identity.getAuthorizationClient(context)
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()

        return client.authorize(request).await()
    }

    /**
     * For use in background contexts (e.g. WorkManager) with no Activity available.
     * Tries to get a fresh access token WITHOUT showing any UI.
     * Returns null if the user has never granted Drive access, or the grant was revoked --
     * in that case the caller must give up (cannot show a consent screen from the background).
     */
    suspend fun getFreshAccessTokenSilently(context: Context): String? {
        return try {
            val client = Identity.getAuthorizationClient(context)
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
                .build()
            val result = client.authorize(request).await()
            if (result.hasResolution()) null else result.accessToken
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Mencabut token OAuth Drive yang sedang aktif lewat Google's revoke endpoint.
     *
     * Kenapa perlu ini: sebelumnya signOut() cuma clearCredentialState() (identitas
     * Credential Manager), TIDAK mencabut izin Drive itu sendiri -- artinya
     * getFreshAccessTokenSilently() masih bisa dapat token baru diam-diam setelah
     * user "sign out" dari sisi UI. Dipanggil dari signOut() supaya "Sign out" di
     * app benar-benar memutus akses Drive juga, bukan cuma menyembunyikan status
     * login di UI.
     *
     * Aman dipanggil walau user belum pernah authorize Drive (getFreshAccessTokenSilently
     * akan return null, langsung di-skip) atau lagi offline (exception ditelan --
     * sign-out tidak boleh gagal cuma karena revoke gagal, user tetap bisa revoke
     * manual lewat Google Account permissions kalau ini gagal diam-diam).
     */
    private suspend fun revokeDriveAccess(context: Context) {
        val token = getFreshAccessTokenSilently(context) ?: return
        withContext(Dispatchers.IO) {
            try {
                val connection = (URL("https://oauth2.googleapis.com/revoke?token=$token")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                connection.responseCode // trigger request; result intentionally ignored
                connection.disconnect()
            } catch (e: Exception) {
                // Tidak fatal -- lihat komentar di atas fungsi ini.
                e.printStackTrace()
            }
        }
    }

    suspend fun signOut(context: Context) {
        revokeDriveAccess(context)
        CredentialManager.create(context).clearCredentialState(
            ClearCredentialStateRequest()
        )
    }
}