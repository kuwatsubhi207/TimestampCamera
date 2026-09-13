package com.example.timestampcamera

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FlashButton(
    flashMode: FlashMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(ComposeColor.Black.copy(alpha = 0.45f))
            .clickable { onToggle() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = "⚡ ${flashMode.label}",
            color = if (flashMode == FlashMode.OFF) ComposeColor.White.copy(alpha = 0.6f) else ComposeColor.Yellow,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@Composable
fun ViewResultButton(
    lastPhotoUri: Uri?,
    thumbnailBitmap: ImageBitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ComposeColor.DarkGray)
            .border(2.dp, ComposeColor.White, RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (thumbnailBitmap != null) {
            Image(
                bitmap = thumbnailBitmap,
                contentDescription = "Foto/video terakhir",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Belum ada foto/video sama sekali (mis. baru pertama kali buka app) --
            // tampilkan ikon galeri generik, bukan emoji, supaya konsisten dengan
            // gaya ikon Material lain di app (Cameraswitch, dll).
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = "Belum ada foto/video",
                tint = ComposeColor.White.copy(alpha = 0.6f),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
fun CaptureButton(
    isCapturing: Boolean,
    enabled: Boolean,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier,
    isRecording: Boolean = false,
    // ---- BARU: beda dari isRecording. isVideoMode true berarti "lagi di mode ----
    // ---- Video tapi BELUM menekan rekam" -- dipakai supaya tombol tetap kelihatan ----
    // ---- beda dari mode Foto walau belum ditekan sama sekali, bukan cuma pas ----
    // ---- sedang merekam.
    isVideoMode: Boolean = false
) {
    Box(
        modifier = modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(ComposeColor.White.copy(alpha = 0.25f))
            .border(4.dp, if (isRecording) ComposeColor.Red else ComposeColor.White, CircleShape)
            .clickable(enabled = enabled) { onCapture() },
        contentAlignment = Alignment.Center
    ) {
        when {
            isCapturing -> {
                CircularProgressIndicator(
                    color = ComposeColor.White,
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp
                )
            }
            // Kotak merah di tengah = ikon "stop" standar aplikasi kamera, supaya
            // user tahu tap berikutnya akan MENGHENTIKAN rekaman, bukan mulai lagi.
            isRecording -> {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ComposeColor.Red)
                )
            }
            // Idle tapi di mode Video: lingkaran merah polos (belum jadi kotak stop
            // karena belum mulai rekam) -- pola umum di aplikasi kamera (shutter
            // putih = foto, shutter merah = video) supaya kelihatan dari sekali lihat.
            isVideoMode -> {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(ComposeColor.Red)
                )
            }
            else -> {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(ComposeColor.White)
                )
            }
        }
    }
}

@Composable
fun SwitchCameraButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(ComposeColor.Black.copy(alpha = 0.45f))
            .border(2.dp, ComposeColor.White, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Cameraswitch,
            contentDescription = "Ganti kamera",
            tint = ComposeColor.White,
            modifier = Modifier.size(28.dp)
        )
    }
}

// ---- Ini sekarang top-level, bukan lagi ter-nest di dalam SwitchCameraButton ----
@Composable
fun ZoomPresetControls(
    currentZoomRatio: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    onZoomSelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // Kalau device cuma punya 1 titik zoom (min == max), tidak ada gunanya tampilkan tombol
    if (maxZoomRatio - minZoomRatio < 0.1f) return

    val allPresets = listOf(0.5f, 1f, 2f, 3f, 5f, 10f)
    val availablePresets = allPresets.filter { it in minZoomRatio..maxZoomRatio }

    // Kalau tidak ada preset bulat yang cocok, fallback tampilkan min & max device saja
    val presetsToShow = if (availablePresets.size >= 2) {
        availablePresets
    } else {
        listOf(minZoomRatio, maxZoomRatio).distinct()
    }

    Row(
        modifier = modifier.clip(RoundedCornerShape(50)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        presetsToShow.forEach { preset ->
            val isSelected = kotlin.math.abs(currentZoomRatio - preset) < 0.05f

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (isSelected) ComposeColor.Yellow
                        else ComposeColor.Black.copy(alpha = 0.45f)
                    )
                    .clickable { onZoomSelected(preset) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                val label = if (preset == preset.toInt().toFloat()) {
                    "${preset.toInt()}x"
                } else {
                    "%.1fx".format(preset)
                }
                Text(
                    text = label,
                    color = if (isSelected) ComposeColor.Black else ComposeColor.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        }
    }
}