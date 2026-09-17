package com.example.timestampcamera

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    // ---- Update APK: id download yang sedang berjalan, dicocokkan di receiver ----
    private var updateDownloadId: Long = -1L

    private val onUpdateDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == updateDownloadId) {
                installDownloadedApk(id)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---- Daftarkan receiver untuk notifikasi download APK update selesai ----
        val updateFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // WAJIB pakai RECEIVER_EXPORTED, BUKAN RECEIVER_NOT_EXPORTED -- broadcast
            // ACTION_DOWNLOAD_COMPLETE dikirim oleh SISTEM (DownloadManager), bukan
            // dari app ini sendiri. Dengan NOT_EXPORTED, broadcast dari sistem tidak
            // pernah sampai ke receiver ini, jadi download sukses tapi instalasi APK
            // tidak pernah otomatis terbuka.
            registerReceiver(onUpdateDownloadComplete, updateFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(onUpdateDownloadComplete, updateFilter)
        }

        // ==================================================================
        // TESTING: pakai scheduleOneTimeForTesting supaya hasil hapus foto
        // kelihatan cepat (20 detik setelah app dibuka), karena periodic work
        // tidak bisa lebih cepat dari 15 menit di Android.
        // GANTI KE schedulePeriodic(applicationContext) SEBELUM RILIS.
        // ==================================================================
//        LocalCleanupWorker.scheduleOneTimeForTesting(applicationContext, delaySeconds = 20)

        // ---- Versi PRODUKSI (aktifkan lagi nanti, ganti baris di atas dengan ini) ----
        LocalCleanupWorker.schedulePeriodic(applicationContext)

        setContent {
            var hasAudioPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }
            var hasCameraPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }
            var hasLocationPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            var permissionRequested by remember { mutableStateOf(false) }

            // ---- Navigasi ke layar galeri ----
            var showGallery by remember { mutableStateOf(false) }
            var showVerification by remember { mutableStateOf(false) }

            // ---- Status Drive ----
            // Prefs cuma untuk INDIKATOR UI supaya tidak selalu menampilkan tombol
            // "Hubungkan ke Drive" tiap buka app. Kebenaran sebenarnya (apakah izin
            // masih berlaku) tetap dicek ulang lewat authorizeDrive()/getFreshAccessTokenSilently()
            // di Worker -- prefs ini bukan sumber kebenaran, cuma cache tampilan.
            val prefs = remember { getSharedPreferences("drive_prefs", Context.MODE_PRIVATE) }
            var isDriveConnected by remember {
                mutableStateOf(prefs.getBoolean(PREF_DRIVE_CONNECTED, false))
            }
            // Nama akun Google yang lagi sign-in, buat ditampilin sebagai ganti teks
            // generik "Drive tersambung". Disimpan di prefs juga supaya tetap kelihatan
            // setelah app ditutup-buka lagi (getFreshAccessTokenSilently() saat startup
            // cuma ngecek token, nggak ngembaliin identitas/nama akun lagi).
            var driveAccountName by remember {
                mutableStateOf(prefs.getString(PREF_DRIVE_ACCOUNT_NAME, null))
            }
            var isConnectingDrive by remember { mutableStateOf(false) }
            var showSignOutConfirm by remember { mutableStateOf(false) }
            val coroutineScope = rememberCoroutineScope()

            fun setDriveConnected(connected: Boolean) {
                isDriveConnected = connected
                prefs.edit().putBoolean(PREF_DRIVE_CONNECTED, connected).apply()
            }

            fun setDriveAccountName(name: String?) {
                driveAccountName = name
                prefs.edit().putString(PREF_DRIVE_ACCOUNT_NAME, name).apply()
            }

            // ---- Cek ulang diam-diam saat app dibuka, supaya status tidak "nyangkut" ----
            // ---- kalau izin ternyata sudah dicabut user dari luar app                ----
            LaunchedEffect(Unit) {
                if (prefs.getBoolean(PREF_DRIVE_CONNECTED, false)) {
                    val token = DriveAuth.getFreshAccessTokenSilently(this@MainActivity)
                    setDriveConnected(token != null)
                    if (token != null) {
                        // Jaga-jaga ada foto yang gagal terupload di sesi sebelumnya
                        // (mis. app ditutup sebelum sempat retry) -- daftarkan ulang.
                        DriveUploadWorker.retryPendingUploads(this@MainActivity)
                    }
                }
            }

            // ---- Progres upload: jumlah foto yang masih menunggu/sedang diupload ----
            var pendingUploadCount by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                WorkManager.getInstance(this@MainActivity)
                    .getWorkInfosByTagFlow(DriveUploadWorker.DRIVE_UPLOAD_TAG)
                    .collectLatest { workInfos ->
                        pendingUploadCount = workInfos.count {
                            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                        }
                    }
            }

            // ---- Cek update aplikasi dari GitHub Releases ----
            var updateInfo by remember { mutableStateOf<LatestRelease?>(null) }
            LaunchedEffect(Unit) {
                val currentVersion = packageManager
                    .getPackageInfo(packageName, 0)
                    .versionName ?: "0"
                val release = withContext(Dispatchers.IO) {
                    UpdateChecker.checkForUpdate(currentVersion)
                }
                updateInfo = release
            }

            // Launcher untuk consent screen izin Drive (dipanggil kalau authorizeDrive()
            // mengembalikan hasResolution() == true, yaitu izin belum pernah diberikan).
            val authorizeResolutionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { activityResult ->
                coroutineScope.launch {
                    try {
                        val result = Identity.getAuthorizationClient(this@MainActivity)
                            .getAuthorizationResultFromIntent(activityResult.data)
                        setDriveConnected(result.accessToken != null)
                        if (result.accessToken != null) {
                            DriveUploadWorker.retryPendingUploads(this@MainActivity)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("DriveAuth", "authorizeResolutionLauncher gagal", e)
                        Toast.makeText(
                            this@MainActivity,
                            "Gagal menyelesaikan izin Drive: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        setDriveConnected(false)
                    } finally {
                        isConnectingDrive = false
                    }
                }
            }

            fun connectDrive() {
                isConnectingDrive = true
                coroutineScope.launch {
                    try {
                        // Step 1: sign-in identitas Google lewat Credential Manager
                        val credential = DriveAuth.signIn(this@MainActivity)
                        val email = DriveAuth.extractEmailFromIdToken(credential.idToken)
                        setDriveAccountName(email ?: credential.displayName)

                        // Step 2: minta izin scope Drive
                        val result = DriveAuth.authorizeDrive(this@MainActivity)
                        if (result.hasResolution()) {
                            // Izin belum pernah diberikan -> tampilkan consent screen
                            val intentSenderRequest = IntentSenderRequest
                                .Builder(result.pendingIntent!!.intentSender)
                                .build()
                            authorizeResolutionLauncher.launch(intentSenderRequest)
                            // isConnectingDrive di-set false di callback launcher di atas
                        } else {
                            // Izin sudah ada, token langsung siap dipakai
                            setDriveConnected(true)
                            DriveUploadWorker.retryPendingUploads(this@MainActivity)
                            isConnectingDrive = false
                        }
                    } catch (e: Exception) {
                        // ---- PENTING: jangan gagal diam-diam. Kalau WEB_CLIENT_ID di ----
                        // ---- DriveAuth.kt masih placeholder atau salah, proses akan  ----
                        // ---- gagal PERSIS di titik ini (setelah user memilih akun),  ----
                        // ---- dan tanpa log/toast ini akan terlihat seolah "tidak     ----
                        // ---- terjadi apa-apa".
                        android.util.Log.e("DriveAuth", "connectDrive gagal", e)
                        Toast.makeText(
                            this@MainActivity,
                            "Gagal hubungkan Drive: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        setDriveConnected(false)
                        isConnectingDrive = false
                    }
                }
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                hasAudioPermission = results[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission
                hasCameraPermission =
                    results[Manifest.permission.CAMERA] ?: hasCameraPermission
                hasLocationPermission =
                    results[Manifest.permission.ACCESS_FINE_LOCATION] ?: hasLocationPermission
                permissionRequested = true
            }

            LaunchedEffect(Unit) {
                val needed = mutableListOf<String>()
                if (!hasAudioPermission) needed.add(Manifest.permission.RECORD_AUDIO)
                if (!hasCameraPermission) needed.add(Manifest.permission.CAMERA)
                if (!hasLocationPermission) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (needed.isNotEmpty()) {
                    permissionLauncher.launch(needed.toTypedArray())
                } else {
                    permissionRequested = true
                }
            }

            // Background hitam sebagai dasar (bukan putih kosong bawaan Compose),
            // supaya terasa seperti layar kamera sejak awal, bukan layar kosong.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                if (showGallery) {
                    GalleryScreen(onBack = { showGallery = false })
                } else if (showVerification) {
                    VerificationScreen(onBack = { showVerification = false })
                } else {
                    when {
                        hasCameraPermission -> {
                            // Preview langsung tampil begitu izin kamera ada,
                            // tidak menunggu izin lokasi.
                            Box(modifier = Modifier.fillMaxSize()) {
                                CameraScreen(
                                    hasLocationPermission = hasLocationPermission,
                                    hasAudioPermission = hasAudioPermission,
                                    onOpenGallery = { showGallery = true },
                                    onOpenVerification = { showVerification = true }
                                )

                                // ---- Status/tombol Drive di pojok kiri atas ----
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(top = 24.dp, start = 16.dp)
                                ) {
                                    when {
                                        isConnectingDrive -> {
                                            Text(
                                                text = "Menghubungkan...",
                                                color = Color.White
                                            )
                                        }

                                        !isDriveConnected -> {
                                            OutlinedButton(
                                                onClick = { connectDrive() },
                                                border = BorderStroke(2.dp, Color(0xFF2196F3)),
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    contentColor = Color.White
                                                )
                                            ) {
                                                Text("Hubungkan ke Drive")
                                            }
                                        }

                                        else -> {
                                            // Sudah terhubung -- tampilkan status/nama akun
                                            // di atas, tombol sign-out di bawahnya.
                                            Column(horizontalAlignment = Alignment.Start) {
                                                Text(
                                                    text = if (pendingUploadCount > 0) {
                                                        "☁️ Mengupload $pendingUploadCount file..."
                                                    } else {
                                                        "☁️ ${driveAccountName ?: "Drive"} tersambung"
                                                    },
                                                    color = Color.White
                                                )
                                                Surface(
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .clickable { showSignOutConfirm = true },
                                                    shape = CircleShape,
                                                    color = Color.Black.copy(alpha = 0.4f),
                                                    border = BorderStroke(2.dp, Color.Red)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = Icons.Filled.ExitToApp,
                                                            contentDescription = "Keluar / sign out",
                                                            tint = Color.White
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (showSignOutConfirm) {
                                    AlertDialog(
                                        onDismissRequest = { showSignOutConfirm = false },
                                        title = { Text("Keluar dari akun?") },
                                        text = {
                                            Text(
                                                "Kamu perlu masuk lagi dengan akun Google " +
                                                        "untuk menyimpan foto ke Drive."
                                            )
                                        },
                                        confirmButton = {
                                            TextButton(onClick = {
                                                showSignOutConfirm = false
                                                coroutineScope.launch {
                                                    DriveAuth.signOut(this@MainActivity)
                                                    setDriveConnected(false)
                                                    setDriveAccountName(null)
                                                }
                                            }) {
                                                Text("Keluar")
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showSignOutConfirm = false }) {
                                                Text("Batal")
                                            }
                                        }
                                    )
                                }

                                // ---- Dialog update aplikasi (kalau ada versi baru) ----
                                updateInfo?.let { release ->
                                    AlertDialog(
                                        onDismissRequest = { /* wajib pilih salah satu tombol */ },
                                        title = { Text("Update tersedia: v${release.versionName}") },
                                        text = {
                                            Text(
                                                if (release.notes.isNotBlank()) release.notes
                                                else "Versi baru sudah tersedia. Update sekarang?"
                                            )
                                        },
                                        confirmButton = {
                                            TextButton(onClick = {
                                                startApkDownload(release)
                                                updateInfo = null
                                            }) {
                                                Text("Update")
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { updateInfo = null }) {
                                                Text("Nanti")
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        permissionRequested -> {
                            // Izin kamera ditolak — tampilkan pesan + tombol coba lagi,
                            // daripada layar kosong selamanya.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black)
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Izin kamera diperlukan untuk menggunakan aplikasi ini.",
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Button(
                                        modifier = Modifier.padding(top = 16.dp),
                                        onClick = {
                                            permissionLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.CAMERA,
                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                    Manifest.permission.RECORD_AUDIO
                                                )
                                            )
                                        }
                                    ) {
                                        Text("Berikan Izin")
                                    }
                                }
                            }
                        }

                        else -> {
                            // Sedang menunggu dialog izin dijawab — biarkan kosong hitam
                            // (tidak perlu spinner, karena dialog sistem sudah menutupi layar).
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(onUpdateDownloadComplete)
    }

    /** Download APK update lewat DownloadManager (support file besar, ada notif progres). */
    private fun startApkDownload(release: LatestRelease) {
        val fileName = "TimestampCamera-${release.versionName}.apk"
        val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
            .setTitle("Mengunduh update")
            .setDescription("TimestampCamera v${release.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                this,
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        updateDownloadId = downloadManager.enqueue(request)
    }

    /** Buka installer APK setelah download update selesai. */
    private fun installDownloadedApk(downloadId: Long) {
        try {
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)
            if (cursor.moveToFirst()) {
                val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val localUriString = cursor.getString(localUriIndex)
                cursor.close()

                val file = File(Uri.parse(localUriString).path ?: return)
                val apkUri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    file
                )

                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(installIntent)
            } else {
                cursor.close()
                android.util.Log.e("UpdateInstall", "Download tidak ditemukan di DownloadManager, id=$downloadId")
            }
        } catch (e: Exception) {
            // ---- PENTING: jangan gagal diam-diam. Kalau path FileProvider di ----
            // ---- file_paths.xml tidak cocok dengan lokasi file APK yang     ----
            // ---- sebenarnya, FileProvider.getUriForFile() akan throw di sini ----
            // ---- dan tanpa log/toast ini akan terlihat seolah "tidak terjadi ----
            // ---- apa-apa" setelah download selesai.
            android.util.Log.e("UpdateInstall", "Gagal membuka installer APK", e)
            Toast.makeText(
                this,
                "Gagal membuka installer update: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        private const val PREF_DRIVE_CONNECTED = "drive_connected"
        private const val PREF_DRIVE_ACCOUNT_NAME = "drive_account_name"
    }
}