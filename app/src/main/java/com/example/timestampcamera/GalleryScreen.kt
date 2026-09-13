package com.example.timestampcamera

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow

private enum class GalleryTab { LOCAL, DRIVE }

// Urutan berlaku untuk kedua tab (Lokal & Drive) sekaligus, di-sort di sisi klien --
// tidak query ulang ke MediaStore/Drive API tiap ganti urutan.
private enum class SortOption(val label: String) {
    DATE_NEWEST("Terbaru"),
    DATE_OLDEST("Terlama")
}

private fun sortLocalMedia(items: List<LocalMedia>, option: SortOption): List<LocalMedia> {
    return when (option) {
        SortOption.DATE_NEWEST -> items.sortedByDescending { it.dateAddedSeconds }
        SortOption.DATE_OLDEST -> items.sortedBy { it.dateAddedSeconds }
    }
}

private fun sortDriveFiles(files: List<DriveFile>, option: SortOption): List<DriveFile> {
    // createdTime dari Drive API berformat ISO 8601 UTC (mis. "2024-01-15T10:30:00.000Z"),
    // urutan string-nya sudah sama dengan urutan kronologisnya -- tidak perlu di-parse.
    return when (option) {
        SortOption.DATE_NEWEST -> files.sortedByDescending { it.createdTime }
        SortOption.DATE_OLDEST -> files.sortedBy { it.createdTime }
    }
}

// ---- Pengelompokan per 7 hari (dipakai di kedua tab) ----
//
// Bukan kalender minggu (Senin-Minggu), tapi rentang bergulir 7 hari dari SEKARANG:
// hari ke 0-6 = "Minggu ini", 7-13 = "1 minggu lalu", dst. List yang masuk HARUS
// sudah terurut berdasarkan tanggal (naik atau turun, sesuai SortOption yang aktif)
// -- fungsi ini cuma mendeteksi titik dimana rentang 7 harinya berubah, tidak
// mengurutkan ulang.

private data class WeekBucket<T>(val label: String, val items: List<T>)

private fun weekIndexFromEpochSeconds(epochSeconds: Long, nowEpochSeconds: Long): Long {
    val daysAgo = (nowEpochSeconds - epochSeconds) / (24 * 60 * 60)
    return daysAgo.coerceAtLeast(0) / 7
}

private fun weekLabel(weekIndex: Long): String {
    return when {
        weekIndex <= 0L -> "Minggu ini"
        weekIndex == 1L -> "1 minggu lalu"
        else -> "$weekIndex minggu lalu"
    }
}

private fun <T> groupByWeek(items: List<T>, epochSecondsOf: (T) -> Long): List<WeekBucket<T>> {
    if (items.isEmpty()) return emptyList()
    val nowEpochSeconds = System.currentTimeMillis() / 1000

    val buckets = mutableListOf<WeekBucket<T>>()
    var currentWeekIndex: Long? = null
    var currentItems = mutableListOf<T>()

    for (element in items) {
        val weekIndex = weekIndexFromEpochSeconds(epochSecondsOf(element), nowEpochSeconds)
        if (currentWeekIndex != null && weekIndex != currentWeekIndex) {
            buckets.add(WeekBucket(weekLabel(currentWeekIndex), currentItems))
            currentItems = mutableListOf()
        }
        currentWeekIndex = weekIndex
        currentItems.add(element)
    }
    currentWeekIndex?.let { buckets.add(WeekBucket(weekLabel(it), currentItems)) }

    return buckets
}

// Drive API mengembalikan createdTime dalam format ISO 8601 UTC. java.time.Instant.parse()
// butuh API 26+, sedangkan minSdk project ini 25 -- jadi dipakai SimpleDateFormat yang
// kompatibel dari API level berapa pun. Dicoba dua pola karena Drive API kadang mengirim
// tanpa milidetik.
private val DRIVE_TIME_PATTERNS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    "yyyy-MM-dd'T'HH:mm:ss'Z'"
)

private fun driveEpochSeconds(createdTime: String): Long {
    for (pattern in DRIVE_TIME_PATTERNS) {
        try {
            val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = sdf.parse(createdTime)
            if (parsed != null) return parsed.time / 1000
        } catch (_: Exception) {
            // coba pola berikutnya
        }
    }
    return 0L
}

@Composable
private fun WeekHeader(label: String) {
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)
    )
}

/**
 * Grid 3 kolom yang dikelompokkan per minggu, disusun manual pakai LazyColumn + Row
 * (bukan LazyVerticalGrid) supaya tidak bergantung pada API item(span=...) yang belum
 * tentu tersedia di semua versi Compose Foundation.
 */
@Composable
private fun <T> WeekGroupedGrid(
    weekBuckets: List<WeekBucket<T>>,
    modifier: Modifier = Modifier,
    itemKey: (T) -> Any,
    onLoadMore: (() -> Unit)? = null,
    isLoadingMore: Boolean = false,
    itemContent: @Composable (T) -> Unit
) {
    val listState = rememberLazyListState()

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(4.dp)
    ) {
        weekBuckets.forEach { bucket ->

            item(
                key = "header_${bucket.label}"
            ) {
                WeekHeader(bucket.label)
            }

            val rows = bucket.items.chunked(3)

            items(
                items = rows,
                key = { row ->
                    itemKey(row.first())
                }
            ) { rowItems ->

                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {

                    rowItems.forEach { entry ->

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(2.dp)
                        ) {
                            itemContent(entry)
                        }
                    }

                    repeat(3 - rowItems.size) {
                        Spacer(
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (isLoadingMore) {
            item(
                key = "loading_more"
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }

    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    val currentIsLoadingMore by rememberUpdatedState(isLoadingMore)

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index
        }.collect { lastIndex ->

            val totalItems = listState.layoutInfo.totalItemsCount

            if (
                currentOnLoadMore != null &&
                !currentIsLoadingMore &&
                totalItems > 0 &&
                lastIndex != null &&
                lastIndex >= totalItems - 5
            ) {
                currentOnLoadMore?.invoke()
            }
        }
    }
}

@Composable
fun GalleryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(GalleryTab.LOCAL) }

    var localMedia by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    var isLoadingLocal by remember { mutableStateOf(true) }

    var driveFiles by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var driveTotalCount by remember { mutableStateOf(0) }
    var driveNextPageToken by remember { mutableStateOf<String?>(null) }
    var isLoadingDrive by remember { mutableStateOf(true) }
    var isLoadingMoreDrive by remember { mutableStateOf(false) }
    var driveErrorMessage by remember { mutableStateOf<String?>(null) }
    var accessToken by remember { mutableStateOf<String?>(null) }

    var sortOption by remember { mutableStateOf(SortOption.DATE_NEWEST) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val sortedLocalMedia = remember(localMedia, sortOption) {
        sortLocalMedia(localMedia, sortOption)
    }

    val sortedDriveFiles = remember(driveFiles, sortOption) {
        sortDriveFiles(driveFiles, sortOption)
    }

    // ---------------------------------------------------------
    // Fungsi refresh seluruh galeri
    // ---------------------------------------------------------
    fun refreshGallery() {
        scope.launch {
            isLoadingLocal = true
            isLoadingDrive = true
            isLoadingMoreDrive = false
            driveErrorMessage = null

            driveFiles = emptyList()
            driveNextPageToken = null
            driveTotalCount = 0

            // Refresh lokal
            localMedia = listLocalMedia(context)
            isLoadingLocal = false

            // Refresh Drive
            val token = DriveAuth.getFreshAccessTokenSilently(activity)
            accessToken = token

            if (token == null) {
                driveFiles = emptyList()
                driveTotalCount = 0
                driveNextPageToken = null
                driveErrorMessage = "Belum terhubung ke Drive"
                isLoadingDrive = false
            } else {
                val firstPage = listDriveFiles( accessToken = token )
                driveFiles = firstPage.files

                driveNextPageToken = firstPage.nextPageToken

                driveTotalCount = countDriveFiles(token)
                isLoadingDrive = false
            }
        }
    }

    fun loadMoreDrive() {

        val token = accessToken ?: return
        val nextToken = driveNextPageToken ?: return

        if (isLoadingMoreDrive) return

        scope.launch {

            isLoadingMoreDrive = true

            val nextPage = listDriveFiles(
                accessToken = token,
                pageToken = nextToken
            )

            driveFiles =
                driveFiles + nextPage.files

            driveNextPageToken =
                nextPage.nextPageToken

            isLoadingMoreDrive = false
        }
    }

    // ---------------------------------------------------------
    // Load pertama kali
    // ---------------------------------------------------------
    LaunchedEffect(Unit) {
        refreshGallery()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {

            // -------------------------------------------------
            // Header
            // -------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = 24.dp,
                        start = 8.dp,
                        end = 8.dp,
                        bottom = 8.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {

                IconButton(
                    onClick = onBack
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Kembali",
                        tint = Color.White
                    )
                }

                Text(
                    text = "Galeri",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )

                // -------------------------------------------------
                // Tombol Refresh
                // -------------------------------------------------
                IconButton(
                    onClick = {
                        refreshGallery()
                    },
                    enabled = !isLoadingLocal && !isLoadingDrive
                ) {
                    if (isLoadingLocal || isLoadingDrive) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                            tint = Color.White
                        )
                    }
                }

                // -------------------------------------------------
                // Tombol Sort
                // -------------------------------------------------
                Box {
                    TextButton(
                        onClick = {
                            sortMenuExpanded = true
                        }
                    ) {
                        Text(
                            text = sortOption.label,
                            color = Color.White
                        )

                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }

                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = {
                            sortMenuExpanded = false
                        }
                    ) {
                        SortOption.values().forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(option.label)
                                },
                                onClick = {
                                    sortOption = option
                                    sortMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // -------------------------------------------------
            // Tab Lokal / Drive
            // -------------------------------------------------
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = Color.Black,
                contentColor = Color.White
            ) {

                Tab(
                    selected = selectedTab == GalleryTab.LOCAL,
                    onClick = {
                        selectedTab = GalleryTab.LOCAL
                    },
                    text = {
                        Text("Lokal (${localMedia.size})")
                    }
                )

                Tab(
                    selected = selectedTab == GalleryTab.DRIVE,
                    onClick = {
                        selectedTab = GalleryTab.DRIVE
                    },
                    text = {
                        Text("Drive ($driveTotalCount)")
                    }
                )
            }

            // -------------------------------------------------
            // Isi galeri
            // -------------------------------------------------
            when (selectedTab) {

                GalleryTab.LOCAL -> LocalGalleryGrid(
                    media = sortedLocalMedia,
                    isLoading = isLoadingLocal
                )

                GalleryTab.DRIVE -> DriveGalleryGrid(
                    files = sortedDriveFiles,
                    isLoading = isLoadingDrive,
                    errorMessage = driveErrorMessage,
                    accessToken = accessToken,
                    onLoadMore = { loadMoreDrive()},
                    isLoadingMore = isLoadingMoreDrive
                )
            }
        }
    }
}

@Composable
private fun LocalGalleryGrid(media: List<LocalMedia>, isLoading: Boolean) {
    val context = LocalContext.current

    if (isLoading) {
        LoadingState()
        return
    }
    if (media.isEmpty()) {
        EmptyState("Belum ada foto/video lokal")
        return
    }

    val weekBuckets = remember(media) { groupByWeek(media) { it.dateAddedSeconds } }

    WeekGroupedGrid(
        weekBuckets = weekBuckets,
        modifier = Modifier.fillMaxSize(),
        itemKey = { it.uri.toString() }
    ) { item ->
        var thumbnail by remember(item.uri) {
            mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        }

        // type ikut jadi key: kalau item yang sama pernah dipakai untuk uri lain
        // (harusnya tidak terjadi, tapi jaga-jaga) thumbnail tidak nyangkut ke tipe salah.
        LaunchedEffect(item.uri, item.type) {
            thumbnail = loadThumbnailFromUri(context, item.uri, 300, item.type)
                ?.asImageBitmap()
        }

        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .clickable {
                    val mimeType = if (item.type == MediaType.VIDEO) "video/*" else "image/*"
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(item.uri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                },
            contentAlignment = Alignment.Center
        ) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.DarkGray)
                )
            }

            // ---- Penanda video: lingkaran gelap semi-transparan + ikon play di tengah ----
            if (item.type == MediaType.VIDEO) {
                VideoBadge()
            }
        }
    }
}

@Composable
private fun DriveGalleryGrid(
    files: List<DriveFile>,
    isLoading: Boolean,
    errorMessage: String?,
    accessToken: String?,
    onLoadMore: () -> Unit,
    isLoadingMore: Boolean
) {
    val context = LocalContext.current

    if (isLoading) {
        LoadingState()
        return
    }
    if (errorMessage != null) {
        EmptyState(errorMessage)
        return
    }
    if (files.isEmpty()) {
        EmptyState("Belum ada foto di Drive")
        return
    }

    val weekBuckets = remember(files) { groupByWeek(files) { driveEpochSeconds(it.createdTime) } }

    WeekGroupedGrid(
        weekBuckets = weekBuckets,
        modifier = Modifier.fillMaxSize(),
        itemKey = { it.id },
        onLoadMore = onLoadMore,
        isLoadingMore = isLoadingMore
    ) { file ->
        var thumbnail by remember(file.id) {
            mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        }

        LaunchedEffect(file.id, accessToken) {
            val link = file.thumbnailLink
            if (link != null && accessToken != null) {
                thumbnail = downloadDriveThumbnail(thumbnailUrl = link, accessToken = accessToken, fileId = file.id)?.asImageBitmap()
            }
        }

        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .clickable {
                    file.webViewLink?.let { link ->
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                        context.startActivity(intent)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.DarkGray)
                )
            }

            // ---- Sama seperti tab Lokal: file.type sudah tersedia di DriveFile,
            // ---- tinggal dipakai supaya video di Drive juga kebeda dari foto. ----
            if (file.type == MediaType.VIDEO) {
                VideoBadge()
            }
        }
    }
}

/** Badge lingkaran gelap + ikon play, dipakai bersama oleh grid Lokal & Drive. */
@Composable
private fun VideoBadge() {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(Color.Black.copy(alpha = 0.45f), shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "Video",
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, color = Color.Gray)
    }
}