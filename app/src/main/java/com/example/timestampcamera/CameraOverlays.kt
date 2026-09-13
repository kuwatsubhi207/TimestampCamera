package com.example.timestampcamera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * Overlay alamat, koordinat, dan jam yang tampil di atas preview kamera
 * (bukan hasil foto final -- itu digambar terpisah lewat addWatermark()).
 *
 * @param frameWidthDp Lebar area kamera yang BENAR-BENAR terlihat di layar (setelah
 *   dikurangi letterbox akibat FIT_CENTER) -- BUKAN lebar layar penuh. Semua ukuran
 *   di sini diskalakan dari nilai ini, sama seperti addWatermark() menskalakan dari
 *   lebar bitmap foto, supaya hasilnya identik.
 */
@Composable
fun WatermarkOverlay(
    addressText: String,
    coordText: String,
    currentDateTime: String,
    isMockLocationDetected: Boolean,
    frameWidthDp: Dp,
    addressFontFamily: FontFamily,
    timeFontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val addressFontSize = (frameWidthDp.value * WatermarkStyle.PREVIEW_ADDRESS_SP_RATIO).sp
    val timeFontSize = (frameWidthDp.value * WatermarkStyle.PREVIEW_TIME_SP_RATIO).sp

    Column(modifier = modifier) {
        Text(
            text = "📍 $addressText",
            color = if (isMockLocationDetected) ComposeColor.Red else ComposeColor.White,
            style = TextStyle(
                fontSize = addressFontSize,
                fontFamily = addressFontFamily,
                shadow = Shadow(color = ComposeColor.Black, blurRadius = 6f)
            )
        )
        if (coordText.isNotBlank()) {
            Text(
                text = coordText,
                color = ComposeColor.White,
                style = TextStyle(
                    fontSize = addressFontSize,
                    fontFamily = addressFontFamily,
                    shadow = Shadow(color = ComposeColor.Black, blurRadius = 6f)
                )
            )
        }
        // Lebar divider dihitung dari frameWidthDp (0.6x), sama seperti addWatermark()
        // yang menggambar garis dari padding sampai width * 0.6f -- bukan fillMaxWidth
        // relatif ke Column, karena lebar Column mengikuti teks terpanjang, bukan foto.
        HorizontalDivider(
            thickness = 1.dp,
            color = ComposeColor.White,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .width(frameWidthDp * 0.6f)
        )
        Text(
            text = currentDateTime,
            color = ComposeColor.White,
            style = TextStyle(
                fontSize = timeFontSize,
                letterSpacing = (timeFontSize.value * WatermarkStyle.LETTER_SPACING_TIME).sp,
                fontFamily = timeFontFamily,
                shadow = Shadow(color = ComposeColor.Black, blurRadius = 8f)
            )
        )
    }
}

/** Lingkaran putih sesaat sebagai feedback visual saat tap-to-focus. */
@Composable
fun FocusIndicator(focusPoint: Offset?, onTimeout: () -> Unit) {
    val focusAlpha by animateFloatAsState(
        targetValue = if (focusPoint != null) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "focusAlpha"
    )

    focusPoint?.let { point ->
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = ComposeColor.White.copy(alpha = focusAlpha),
                radius = 60f,
                center = point,
                style = Stroke(width = 4f)
            )
        }
        LaunchedEffect(point) {
            delay(1.seconds)
            onTimeout()
        }
    }
}

/** Badge angka zoom (mis. "2.0x") yang muncul sesaat lalu hilang otomatis. */
@Composable
fun ZoomIndicator(
    zoomRatio: Float,
    visible: Boolean,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (visible) {
        LaunchedEffect(zoomRatio) {
            delay(1.seconds)
            onTimeout()
        }
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(ComposeColor.Black.copy(alpha = 0.5f))
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text(
                text = "${(zoomRatio * 10).roundToInt() / 10f}x",
                color = ComposeColor.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}