package com.example.timestampcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hasil yang sedang ditampilkan di panel overlay, entah datang dari scan QR live
 * (cuma info registrasi, TANPA bisa bandingkan hash -- tidak ada file fisik
 * yang dipegang) atau dari file yang dipilih user (verifikasi PENUH, hash
 * dihitung & dibandingkan).
 */
private sealed class VerificationPanelState {
    object Loading : VerificationPanelState()
    data class FromScan(val fetch: FetchVerificationResult) : VerificationPanelState()
    data class FromFile(val state: VerifyPhotoUiState, val previewBitmap: Bitmap?) : VerificationPanelState()
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun VerificationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = context as ComponentActivity
    val coroutineScope = rememberCoroutineScope()

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var panelState by remember { mutableStateOf<VerificationPanelState?>(null) }

    val barcodeScanner = remember { BarcodeScanning.getClient() }
    DisposableEffect(Unit) { onDispose { barcodeScanner.close() } }

    // ---- Bind kamera + analyzer QR sekali saja, tetap AKTIF selama layar ini ----
    // ---- terbuka -- kamera live cuma mengisi zona tengah (60%) layar. ----
    LaunchedEffect(cameraProvider, previewView) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val pv = previewView ?: return@LaunchedEffect

        val preview = Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
            val mediaImage = imageProxy.image
            // ---- Skip analisa kalau lagi ada hasil ditampilkan (loading atau ----
            // ---- panel hasil terbuka) -- supaya tidak trigger fetch berkali-kali ----
            // ---- dan tidak mengganggu hasil yang sedang dilihat user. ----
            if (panelState != null || mediaImage == null) {
                imageProxy.close()
                return@setAnalyzer
            }
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    val rawValue = barcodes.firstOrNull()?.rawValue
                    val id = rawValue?.let { extractVerificationIdFromValue(it) }
                    if (id != null && panelState == null) {
                        panelState = VerificationPanelState.Loading
                        coroutineScope.launch {
                            val result = fetchVerificationRecord(id)
                            panelState = VerificationPanelState.FromScan(result)
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        panelState = VerificationPanelState.FromFile(VerifyPhotoUiState.Loading, null)
        coroutineScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            if (bytes == null) {
                panelState = VerificationPanelState.FromFile(
                    VerifyPhotoUiState.Error("Tidak bisa membaca file yang dipilih"),
                    null
                )
                return@launch
            }

            val previewBitmap = withContext(Dispatchers.IO) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }

            val result = withContext(Dispatchers.Default) { verifyPhotoBytes(bytes) }
            panelState = VerificationPanelState.FromFile(result, previewBitmap)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        Column(modifier = Modifier.fillMaxSize()) {

            // ============================================================
            // ZONA 1 (20%): bar hitam solid paling atas, berisi tombol back
            // ============================================================
            Box(
                modifier = Modifier
                    .weight(0.2f)
                    .fillMaxWidth()
                    .background(Color.Black)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp)
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Kembali", tint = Color.White)
                }
            }

            // ============================================================
            // ZONA 2 (60%): area kamera live untuk scan QR
            // ============================================================
            Box(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxWidth()
                    .background(Color.Black)
            ) {
                AndroidViewCameraPreview(
                    onPreviewReady = { pv ->
                        previewView = pv
                        val future = ProcessCameraProvider.getInstance(context)
                        future.addListener({ cameraProvider = future.get() }, ContextCompat.getMainExecutor(context))
                    }
                )

                if (panelState == null) {
                    QrAlignmentFrame(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxSize(0.75f)
                    )

                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp),
                        shape = RoundedCornerShape(50),
                        color = Color.Black.copy(alpha = 0.5f)
                    ) {
                        Text(
                            "Posisikan QR di dalam kotak",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // ============================================================
            // ZONA 3 (20%): bar hitam solid paling bawah, berisi pilih file
            // ============================================================
            Box(
                modifier = Modifier
                    .weight(0.2f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (panelState == null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .clickable {
                                pickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.PhotoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Pilih File dari Galeri",
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        // ============================================================
        // LAPISAN PALING ATAS: overlay full-screen saat ada hasil --
        // menutupi ketiga zona (20% / 60% / 20%) sekaligus.
        // ============================================================
        if (panelState != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { /* cegah tap tembus ke kamera di belakang */ }
            ) {
                VerificationResultPanel(
                    panelState = panelState!!,
                    onDismiss = { panelState = null },
                    onPickAnotherFile = {
                        pickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/** Kotak dengan aksen sudut ala scanner QR, menandai area tempat QR harus dipaskan. */
@Composable
private fun QrAlignmentFrame(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cornerLength = size.width * 0.18f
        val strokeWidth = 5f
        val color = Color.White

        // Kiri-atas
        drawLine(color, Offset(0f, 0f), Offset(cornerLength, 0f), strokeWidth)
        drawLine(color, Offset(0f, 0f), Offset(0f, cornerLength), strokeWidth)
        // Kanan-atas
        drawLine(color, Offset(size.width, 0f), Offset(size.width - cornerLength, 0f), strokeWidth)
        drawLine(color, Offset(size.width, 0f), Offset(size.width, cornerLength), strokeWidth)
        // Kiri-bawah
        drawLine(color, Offset(0f, size.height), Offset(cornerLength, size.height), strokeWidth)
        drawLine(color, Offset(0f, size.height), Offset(0f, size.height - cornerLength), strokeWidth)
        // Kanan-bawah
        drawLine(color, Offset(size.width, size.height), Offset(size.width - cornerLength, size.height), strokeWidth)
        drawLine(color, Offset(size.width, size.height), Offset(size.width, size.height - cornerLength), strokeWidth)

        // Border tipis transparan di sekeliling kotak, supaya area terlihat jelas
        // walau belum ada QR yang pas di dalamnya.
        drawRect(
            color = Color.White.copy(alpha = 0.25f),
            style = Stroke(width = 2f)
        )
    }
}

@Composable
private fun AndroidViewCameraPreview(onPreviewReady: (PreviewView) -> Unit) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            val pv = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            onPreviewReady(pv)
            pv
        },
        modifier = Modifier.fillMaxSize()
    )
}

// ============================================================
// Panel hasil -- overlay penuh (menutupi ketiga zona), dari scan ATAU dari file
// ============================================================

@Composable
private fun VerificationResultPanel(
    panelState: VerificationPanelState,
    onDismiss: () -> Unit,
    onPickAnotherFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            when (panelState) {
                VerificationPanelState.Loading -> {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                is VerificationPanelState.FromScan -> {
                    when (val fetch = panelState.fetch) {
                        is FetchVerificationResult.Found -> {
                            StatusBadge(StatusKind.OK, "REGISTERED")
                            Spacer(Modifier.height(12.dp))
                            RecordDetailRow("ID", fetch.record.id)
                            RecordDetailRow("Captured at", fetch.record.capturedAt)
                            RecordDetailRow("App", "${fetch.record.app} v${fetch.record.version}")
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Untuk memastikan file yang kamu pegang PERSIS cocok, pilih file itu lewat \"Pilih File dari Galeri\".",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        FetchVerificationResult.NotFound -> {
                            StatusBadge(StatusKind.WARN, "? NOT REGISTERED")
                            Spacer(Modifier.height(8.dp))
                            Text("ID ini tidak ditemukan di database verifikasi.", fontSize = 13.sp)
                        }
                        is FetchVerificationResult.NetworkError -> {
                            StatusBadge(StatusKind.WARN, "Gagal memeriksa")
                            Spacer(Modifier.height(8.dp))
                            Text(fetch.message, fontSize = 13.sp)
                        }
                    }
                }

                is VerificationPanelState.FromFile -> {
                    panelState.previewBitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Preview foto yang dipilih",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    when (val state = panelState.state) {
                        VerifyPhotoUiState.Idle -> {}

                        VerifyPhotoUiState.Loading -> {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }

                        VerifyPhotoUiState.NoQrFound -> {
                            StatusBadge(StatusKind.WARN, "QR Tidak Ditemukan")
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Tidak ada QR verifikasi TimestampCamera yang terdeteksi pada foto ini.",
                                fontSize = 14.sp
                            )
                        }

                        is VerifyPhotoUiState.Verified -> {
                            StatusBadge(StatusKind.OK, "✓ VERIFIED")
                            Spacer(Modifier.height(8.dp))
                            Text("File identik dengan yang didaftarkan.", fontSize = 14.sp)
                            Spacer(Modifier.height(12.dp))
                            RecordDetailRow("ID", state.record.id)
                            RecordDetailRow("Captured at", state.record.capturedAt)
                            RecordDetailRow("App", "${state.record.app} v${state.record.version}")
                        }

                        is VerifyPhotoUiState.Modified -> {
                            StatusBadge(StatusKind.BAD, "✗ MODIFIED")
                            Spacer(Modifier.height(8.dp))
                            Text("File BERBEDA dengan yang didaftarkan.", fontSize = 14.sp)
                            Spacer(Modifier.height(12.dp))
                            RecordDetailRow("ID", state.record.id)
                            RecordDetailRow("Hash terdaftar", state.record.sha256)
                            RecordDetailRow("Hash file ini", state.actualHash)
                        }

                        VerifyPhotoUiState.NotRegistered -> {
                            StatusBadge(StatusKind.WARN, "? NOT REGISTERED")
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "QR ditemukan, tapi ID-nya tidak ada di database verifikasi.",
                                fontSize = 14.sp
                            )
                        }

                        is VerifyPhotoUiState.Error -> {
                            StatusBadge(StatusKind.WARN, "Gagal Memeriksa")
                            Spacer(Modifier.height(8.dp))
                            Text(state.message, fontSize = 14.sp)
                        }
                    }
                }
            }

            if (panelState !is VerificationPanelState.Loading) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(
                        "Scan lagi",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onDismiss() }
                    )
                    Text(
                        "Pilih file lain",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onPickAnotherFile() }
                    )
                }
            }
        }
    }
}

// ============================================================
// Komponen status bersama
// ============================================================

private enum class StatusKind { OK, BAD, WARN }

@Composable
private fun StatusBadge(kind: StatusKind, label: String) {
    val (bg, fg) = when (kind) {
        StatusKind.OK -> Color(0xFFE7F3EC) to Color(0xFF2F7D4F)
        StatusKind.BAD -> Color(0xFFFBEAEA) to Color(0xFFB23B3B)
        StatusKind.WARN -> Color(0xFFF0EFE8) to Color(0xFF6B6A60)
    }
    Surface(shape = RoundedCornerShape(8.dp), color = bg) {
        Text(
            label,
            color = fg,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun RecordDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(
            value,
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}