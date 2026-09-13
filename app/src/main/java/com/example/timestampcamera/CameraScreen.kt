package com.example.timestampcamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

// Mode rasio foto yang bisa dipilih user
enum class RatioMode(val camXValue: Int, val label: String) {
    RATIO_4_3(AspectRatio.RATIO_4_3, "4:3"),
    RATIO_16_9(AspectRatio.RATIO_16_9, "16:9")
}

enum class CaptureMode { PHOTO, VIDEO }

/**
 * Pilihan resolusi/kualitas video yang bisa dipilih user. Urutan dipakai untuk
 * tombol siklus (tap berulang -> lanjut ke opsi berikutnya, kembali ke awal
 * kalau sudah di ujung).
 */
enum class VideoResolution(
    val quality: androidx.camera.video.Quality,
    val label: String
) {
    SD(androidx.camera.video.Quality.SD, "SD"),
    HD(androidx.camera.video.Quality.HD, "HD"),
    FHD(androidx.camera.video.Quality.FHD, "FHD")
}

/**
 * Ambil rotasi display saat ini TANPA bergantung pada apakah suatu View sudah
 * ter-attach ke Window atau belum (beda dengan `view.display` yang bisa null
 * kalau View belum attach -- rawan race condition di Compose AndroidView).
 */
private fun getDisplayRotationCompat(context: android.content.Context): Int {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        context.display?.rotation ?: android.view.Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        val windowManager = context.getSystemService(android.content.Context.WINDOW_SERVICE)
                as android.view.WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.rotation
    }
}

@Composable
fun CameraScreen(
    hasLocationPermission: Boolean,
    hasAudioPermission: Boolean,
    onOpenGallery: () -> Unit
) {

    val context = LocalContext.current
    val lifecycleOwner = context as ComponentActivity
    val coroutineScope = rememberCoroutineScope()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val fusedLocationClient: FusedLocationProviderClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }

    var currentDateTime by remember { mutableStateOf(getCurrentDateTimeText()) }
    var addressText by remember { mutableStateOf("Mencari lokasi...") }
    var coordText by remember { mutableStateOf("") }
    var isMockLocationDetected by remember { mutableStateOf(false) }

    var isCapturing by remember { mutableStateOf(false) }
    var lastPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var flashMode by remember { mutableStateOf(FlashMode.OFF) }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }

    // Rasio foto yang sedang aktif (default  4:3)
    var ratioMode by remember { mutableStateOf(RatioMode.RATIO_4_3) }

    var captureMode by remember { mutableStateOf(CaptureMode.PHOTO) }

    // Resolusi/kualitas video yang sedang aktif (default FHD)
    var videoResolution by remember { mutableStateOf(VideoResolution.FHD) }

    var isRecording by remember { mutableStateOf(false) }
    var recordingElapsedSeconds by remember { mutableStateOf(0) }
    var activeRecording by remember { mutableStateOf<androidx.camera.video.Recording?>(null) }
    var videoCapture by remember { mutableStateOf<androidx.camera.video.VideoCapture<androidx.camera.video.Recorder>?>(null) }
    val effectHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val videoWatermarkEffect = remember {
        createVideoWatermarkEffect(effectHandler) { it.printStackTrace() }
    }

    // Bersihkan resource OpenGL milik OverlayEffect begitu CameraScreen dibuang
    // dari composition (mis. saat pindah ke GalleryScreen atau Activity ditutup).
    DisposableEffect(Unit) {
        onDispose { videoWatermarkEffect.close() }
    }

    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    var zoomRatio by remember { mutableStateOf(1f) }
    var minZoomRatio by remember { mutableStateOf(1f) }
    var maxZoomRatio by remember { mutableStateOf(1f) }
    var showZoomIndicator by remember { mutableStateOf(false) }

    var thumbnailBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // Observe zoomState sebagai LiveData -- nilainya bisa terisi async setelah
    // kamera bind, jadi tidak cukup dibaca .value sekali saja.
    DisposableEffect(camera) {
        val zoomLiveData = camera?.cameraInfo?.zoomState
        val observer = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { zoomState ->
            minZoomRatio = zoomState.minZoomRatio
            maxZoomRatio = zoomState.maxZoomRatio
            zoomRatio = zoomState.zoomRatio
        }
        zoomLiveData?.observe(lifecycleOwner, observer)

        onDispose {
            zoomLiveData?.removeObserver(observer)
        }
    }

    // Muat foto terakhir dari galeri saat app baru dibuka
    LaunchedEffect(Unit) {
        loadLastPhotoThumbnail(context)?.let { (uri, bitmap) ->
            thumbnailBitmap = bitmap.asImageBitmap()
            lastPhotoUri = uri
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentDateTime = getCurrentDateTimeText()
            delay(1.seconds)
        }
    }

    // Hitung durasi rekam: reset ke 0 tiap kali mulai rekam baru, lalu naik tiap
    // detik SELAMA isRecording true. Loop otomatis berhenti begitu isRecording
    // jadi false (recomposition membatalkan LaunchedEffect lama & key berubah).
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingElapsedSeconds = 0
            while (isRecording) {
                delay(1.seconds)
                recordingElapsedSeconds++
            }
        }
    }

    LaunchedEffect(hasLocationPermission) {
        while (true) {
            if (hasLocationPermission &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    CancellationTokenSource().token
                ).addOnSuccessListener { location ->
                    if (location != null) {
                        isMockLocationDetected = location.isFromMockProvider
                        if (isMockLocationDetected) {
                            addressText = "⚠️ Lokasi Palsu Terdeteksi (Mock GPS)"
                            coordText = ""
                        } else {
                            coordText = formatCoordText(location)
                            addressText = getAddressText(context, location)
                        }
                    }
                }
            } else {
                addressText = "Izin lokasi tidak diberikan"
            }
            delay(30.seconds)
        }
    }

    // Kontrol flash & torch
    LaunchedEffect(flashMode, imageCapture, camera) {
        imageCapture?.flashMode = flashMode.camXValue

        val hasFlashUnit = camera?.cameraInfo?.hasFlashUnit() == true
        if (hasFlashUnit) {
            when (flashMode) {
                FlashMode.ON -> camera?.cameraControl?.enableTorch(true)
                FlashMode.OFF, FlashMode.AUTO -> camera?.cameraControl?.enableTorch(false)
            }
        }
    }

    // dateTimeText dihapus dari sini -- sudah tidak dibaca oleh
    // createVideoWatermarkEffect() (video menghitung jam sendiri), jadi
    // currentDateTime juga dilepas dari key supaya effect ini tidak re-run
    // tiap detik, cukup saat lokasi/alamat berubah (tiap 30 detik).
    LaunchedEffect(addressText, coordText, isMockLocationDetected) {
        VideoWatermarkState.addressText = addressText
        VideoWatermarkState.coordText = coordText
        VideoWatermarkState.isMockLocationDetected = isMockLocationDetected
    }

    // Kompensasi rotasi fisik device via sensor (accelerometer), bukan lewat
    // window/display -- getDisplayRotationCompat() di bawah cuma benar untuk
    // rotasi 0/90/270. Untuk rotasi 180 (HP dipegang terbalik), Android tidak
    // me-rotate UI activity yang di-lock portrait, jadi display rotation tetap
    // ROTATION_0 walau fisik HP terbalik. targetRotation bisa di-update live
    // tanpa rebind kamera.
    DisposableEffect(videoCapture, imageCapture) {
        val orientationListener = object : android.view.OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> android.view.Surface.ROTATION_270
                    in 135 until 225 -> android.view.Surface.ROTATION_180
                    in 225 until 315 -> android.view.Surface.ROTATION_90
                    else -> android.view.Surface.ROTATION_0
                }
                videoCapture?.targetRotation = rotation
                imageCapture?.targetRotation = rotation
            }
        }
        orientationListener.enable()
        onDispose { orientationListener.disable() }
    }

    // Bind/rebind kamera setiap kali lensFacing, ratioMode, captureMode, atau
    // videoResolution berubah, atau provider siap
    LaunchedEffect(cameraProvider, previewView, lensFacing, ratioMode, captureMode, videoResolution) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val pv = previewView ?: return@LaunchedEffect

        val preview = Preview.Builder()
            .setTargetAspectRatio(ratioMode.camXValue)
            .build()
            .also { it.surfaceProvider = pv.surfaceProvider }

        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        try {
            provider.unbindAll()
            if (captureMode == CaptureMode.PHOTO) {
                val capture = ImageCapture.Builder()
                    .setFlashMode(flashMode.camXValue)
                    .setTargetAspectRatio(ratioMode.camXValue)
                    .build()
                imageCapture = capture
                camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, capture)
            } else {
                val recorder = androidx.camera.video.Recorder.Builder()
                    // WAJIB: kasih tahu Recorder rasio yang diinginkan, samakan dengan
                    // ViewPort di bawah. Tanpa ini, Recorder selalu memilih resolusi
                    // native dari QualitySelector (FHD = 16:9) berapa pun ratioMode-nya,
                    // dan ViewPort sendirian tanpa aspect ratio use case yang cocok
                    // menghasilkan crop ganda alih-alih benar-benar ganti rasio --
                    // makanya tombol rasio kelihatan tidak berfungsi untuk video.
                    .setAspectRatio(ratioMode.camXValue)
                    // Kualitas dipilih dari tombol resolusi (SD/HD/FHD). Fallback ke
                    // kualitas lebih rendah kalau device tidak mendukung kualitas yang
                    // dipilih, supaya tidak gagal bind di device low-end.
                    .setQualitySelector(
                        androidx.camera.video.QualitySelector.from(
                            videoResolution.quality,
                            androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(
                                videoResolution.quality
                            )
                        )
                    ).build()

                // Rotasi dari WindowManager (bukan pv.display, yang bisa null kalau
                // PreviewView belum ter-attach ke Window). Nilai ini cuma jadi rotasi
                // AWAL saat bind; OrientationEventListener di atas yang mengambil
                // alih update targetRotation secara live setelahnya.
                val displayRotation = getDisplayRotationCompat(context)
                val capture = androidx.camera.video.VideoCapture.Builder(recorder)
                    .setTargetRotation(displayRotation)
                    .build()
                videoCapture = capture

                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(capture)
                    .addEffect(videoWatermarkEffect)
                    // ---- ViewPort: crop Preview & VideoCapture ke rasio ratioMode ----
                    // yang sama, di level GPU -- terlepas dari resolusi asli yang
                    // dipilih QualitySelector (mis. FHD 1920x1080 = 16:9 native).
                    // Tanpa ini, video SELALU 16:9 walau QualitySelector minta lain,
                    // karena VideoCapture.Builder tidak punya setTargetAspectRatio()
                    // seperti ImageCapture.Builder.
                    .setViewPort(
                        androidx.camera.core.ViewPort.Builder(
                            when (ratioMode) {
                                RatioMode.RATIO_4_3 -> android.util.Rational(3, 4)
                                RatioMode.RATIO_16_9 -> android.util.Rational(9, 16)
                            },
                            displayRotation
                        ).build()
                    )
                    .build()
                camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidthDp = maxWidth
        val screenHeightDp = maxHeight

        // Rasio TAMPILAN (portrait) dari mode yang aktif. AspectRatio.RATIO_4_3/16_9 di
        // CameraX didefinisikan lebar:tinggi dalam orientasi natural sensor (landscape),
        // sedangkan yang dilihat user dalam mode portrait itu kebalikannya
        // -> 4:3 tampil sebagai 3:4, 16:9 tampil sebagai 9:16.
        val frameAspect = when (ratioMode) {
            RatioMode.RATIO_4_3 -> 3f / 4f
            RatioMode.RATIO_16_9 -> 9f / 16f
        }
        val containerAspect = screenWidthDp / screenHeightDp

        // Ukuran frame kamera yang BENAR-BENAR terlihat di layar. PreviewView pakai
        // FIT_CENTER, jadi kalau rasio target beda dari rasio layar akan ada bar hitam
        // (letterbox) di atas-bawah atau kiri-kanan. Watermark preview harus dihitung
        // relatif terhadap frame ini -- bukan terhadap seluruh layar -- supaya posisi &
        // skalanya sama persis dengan watermark di hasil foto (yang dihitung dari lebar
        // bitmap foto asli, tanpa letterbox).
        val frameWidthDp: Dp
        val frameHeightDp: Dp
        if (frameAspect > containerAspect) {
            // Dibatasi lebar layar -> bar hitam di atas & bawah
            frameWidthDp = screenWidthDp
            frameHeightDp = screenWidthDp / frameAspect
        } else {
            // Dibatasi tinggi layar -> bar hitam di kiri & kanan
            frameHeightDp = screenHeightDp
            frameWidthDp = screenHeightDp * frameAspect
        }
        val frameOffsetX = (screenWidthDp - frameWidthDp) / 2
        val frameOffsetY = (screenHeightDp - frameHeightDp) / 2

        // Padding watermark dihitung dari lebar FRAME (bukan layar), pakai PADDING_RATIO
        // yang sama dengan addWatermark() -- supaya jarak ke tepi foto identik antara
        // preview & hasil akhir.
        val watermarkPadding = frameWidthDp * WatermarkStyle.PADDING_RATIO

        AndroidView(
            factory = { ctx ->
                val pv = PreviewView(ctx).apply {
                    // FIT_CENTER: tampilkan SELURUH frame kamera tanpa dipotong,
                    // supaya area yang terlihat = persis area yang akan ter-capture.
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    setBackgroundColor(android.graphics.Color.DKGRAY)
                }
                previewView = pv

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    cameraProvider = cameraProviderFuture.get()
                }, ContextCompat.getMainExecutor(ctx))

                pv
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(camera, previewView) {
                    detectTapGestures { tapOffset ->
                        val pv = previewView ?: return@detectTapGestures
                        val cam = camera ?: return@detectTapGestures

                        val meteringPointFactory = pv.meteringPointFactory
                        val point = meteringPointFactory.createPoint(tapOffset.x, tapOffset.y)
                        val action = FocusMeteringAction.Builder(point)
                            .disableAutoCancel()
                            .build()

                        cam.cameraControl.startFocusAndMetering(action)
                        focusPoint = tapOffset
                    }
                }
                .pointerInput(camera) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val newRatio = (zoomRatio * zoomChange)
                            .coerceIn(minZoomRatio, maxZoomRatio)
                        zoomRatio = newRatio
                        cam.cameraControl.setZoomRatio(newRatio)
                        showZoomIndicator = true
                    }
                }
        )

        FocusIndicator(focusPoint = focusPoint, onTimeout = { focusPoint = null })

        ZoomIndicator(
            zoomRatio = zoomRatio,
            visible = showZoomIndicator,
            onTimeout = { showZoomIndicator = false },
            modifier = Modifier.align(Alignment.Center)
        )

        if (isRecording) {
            RecordingIndicator(
                elapsedSeconds = recordingElapsedSeconds,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
            )
        }

        val addressFontFamily = FontFamily(
            Font(familyName = DeviceFontFamilyName(WatermarkStyle.ADDRESS_FONT_FAMILY))
        )
        val timeFontFamily = FontFamily(
            Font(familyName = DeviceFontFamilyName(WatermarkStyle.TIME_FONT_FAMILY))
        )

        WatermarkOverlay(
            addressText = addressText,
            coordText = coordText,
            currentDateTime = currentDateTime,
            isMockLocationDetected = isMockLocationDetected,
            frameWidthDp = frameWidthDp,
            addressFontFamily = addressFontFamily,
            timeFontFamily = timeFontFamily,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = frameOffsetX + watermarkPadding,
                    bottom = frameOffsetY + watermarkPadding
                )
        )

        // Tombol flash, tombol rasio, tombol mode, dan (khusus mode video) tombol
        // resolusi disusun vertikal di pojok kanan atas. Ganti rasio/mode/resolusi
        // DIKUNCI (tidak boleh) selama sedang merekam video -- karena itu akan
        // memicu provider.unbindAll() di tengah Recording aktif (lihat
        // LaunchedEffect binding kamera di atas), yang bisa membuat file video
        // corrupt atau melempar exception.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 24.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FlashButton(
                flashMode = flashMode,
                onToggle = {
                    flashMode = when (flashMode) {
                        FlashMode.OFF -> FlashMode.ON
                        FlashMode.ON -> FlashMode.AUTO
                        FlashMode.AUTO -> FlashMode.OFF
                    }
                }
            )

            RatioButton(
                ratioMode = ratioMode,
                onToggle = {
                    if (!isRecording) {
                        ratioMode = when (ratioMode) {
                            RatioMode.RATIO_16_9 -> RatioMode.RATIO_4_3
                            RatioMode.RATIO_4_3 -> RatioMode.RATIO_16_9
                        }
                    }
                }
            )

            ModeButton(
                captureMode = captureMode,
                onToggle = {
                    if (!isRecording) {
                        captureMode = when (captureMode) {
                            CaptureMode.PHOTO -> CaptureMode.VIDEO
                            CaptureMode.VIDEO -> CaptureMode.PHOTO
                        }
                    }
                }
            )

            // Tombol resolusi video hanya muncul saat mode video aktif.
            if (captureMode == CaptureMode.VIDEO) {
                VideoResolutionButton(
                    videoResolution = videoResolution,
                    onToggle = {
                        if (!isRecording) {
                            videoResolution = when (videoResolution) {
                                VideoResolution.SD -> VideoResolution.HD
                                VideoResolution.HD -> VideoResolution.FHD
                                VideoResolution.FHD -> VideoResolution.SD
                            }
                        }
                    }
                )
            }
        }

        ZoomPresetControls(
            currentZoomRatio = zoomRatio,
            minZoomRatio = minZoomRatio,
            maxZoomRatio = maxZoomRatio,
            onZoomSelected = { preset ->
                zoomRatio = preset
                camera?.cameraControl?.setZoomRatio(preset)
                showZoomIndicator = true
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 116.dp)
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 28.dp, start = 24.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            ViewResultButton(
                lastPhotoUri = lastPhotoUri,
                thumbnailBitmap = thumbnailBitmap,
                onClick = onOpenGallery
            )

            Spacer(modifier = Modifier.weight(1f))

            CaptureButton(
                isCapturing = isCapturing,
                isRecording = isRecording,
                isVideoMode = captureMode == CaptureMode.VIDEO,
                enabled = !isCapturing && !isMockLocationDetected && when (captureMode) {
                    CaptureMode.PHOTO -> imageCapture != null
                    CaptureMode.VIDEO -> videoCapture != null
                },
                onCapture = {
                    if (captureMode == CaptureMode.PHOTO) {
                        capturePhotoWithLocation(
                            context = context,
                            imageCapture = imageCapture,
                            executor = cameraExecutor,
                            fusedLocationClient = fusedLocationClient,
                            hasLocationPermission = hasLocationPermission,
                            onCaptureStarted = { isCapturing = true },
                            onPhotoReady = { bitmap ->
                                val previewWidth = 200
                                val previewHeight =
                                    (previewWidth.toFloat() * bitmap.height / bitmap.width).toInt()
                                        .coerceAtLeast(1)
                                val scaled = Bitmap.createScaledBitmap(
                                    bitmap, previewWidth, previewHeight, true
                                )
                                thumbnailBitmap = scaled.asImageBitmap()
                            },
                            onCaptureFinished = { uri ->
                                isCapturing = false
                                if (uri != null) lastPhotoUri = uri
                            }
                        )
                    } else {
                        val vc = videoCapture ?: return@CaptureButton
                        if (!isRecording) {
                            activeRecording = startVideoRecording(
                                context = context,
                                videoCapture = vc,
                                hasAudioPermission = hasAudioPermission
                            ) { uri ->
                                isRecording = false
                                if (uri != null) {
                                    lastPhotoUri = uri
                                    // Ambil frame pertama video sebagai thumbnail preview,
                                    // dikerjakan di IO dispatcher supaya tidak nge-block UI.
                                    coroutineScope.launch {
                                        val frame = withContext(Dispatchers.IO) {
                                            extractVideoThumbnail(context, uri)
                                        }
                                        if (frame != null) {
                                            val previewWidth = 200
                                            val previewHeight = (previewWidth.toFloat() * frame.height / frame.width)
                                                .toInt()
                                                .coerceAtLeast(1)
                                            val scaled = Bitmap.createScaledBitmap(
                                                frame, previewWidth, previewHeight, true
                                            )
                                            thumbnailBitmap = scaled.asImageBitmap()
                                        }
                                    }
                                }
                            }
                            isRecording = true
                        } else {
                            activeRecording?.stop()
                            activeRecording = null
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            SwitchCameraButton(
                onClick = {
                    if (!isRecording) {
                        lensFacing =
                            if (lensFacing == CameraSelector.LENS_FACING_BACK)
                                CameraSelector.LENS_FACING_FRONT
                            else
                                CameraSelector.LENS_FACING_BACK
                    }
                }
            )
        }
    }
}

@Composable
fun RatioButton(
    ratioMode: RatioMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(48.dp)
            .clickable { onToggle() },
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.4f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = ratioMode.label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ModeButton(
    captureMode: CaptureMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(48.dp)
            .clickable { onToggle() },
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.4f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (captureMode == CaptureMode.PHOTO)
                    Icons.Filled.Videocam
                else
                    Icons.Filled.PhotoCamera,
                contentDescription = if (captureMode == CaptureMode.PHOTO)
                    "Ganti ke mode video"
                else
                    "Ganti ke mode foto",
                tint = Color.White
            )
        }
    }
}

/** Tombol siklus untuk memilih resolusi/kualitas video: SD -> HD -> FHD -> SD -> ... */
@Composable
fun VideoResolutionButton(
    videoResolution: VideoResolution,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(48.dp)
            .clickable { onToggle() },
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.4f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = videoResolution.label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Ambil satu frame dari video (frame pertama yang bisa di-decode) untuk dipakai
 * sebagai thumbnail preview, mirip seperti bitmap hasil foto. Wajib dipanggil
 * dari background thread (IO dispatcher) -- MediaMetadataRetriever melakukan
 * decode yang cukup berat untuk main thread.
 */
private fun extractVideoThumbnail(context: android.content.Context, uri: Uri): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.frameAtTime
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

/** "125 detik" -> "02:05". Jam ditambah otomatis ("01:02:05") kalau sudah lewat 1 jam. */
private fun formatRecordingDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@Composable
fun RecordingIndicator(
    elapsedSeconds: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color.Red, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatRecordingDuration(elapsedSeconds),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}