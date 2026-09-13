package com.example.timestampcamera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import android.widget.Toast

/**
 * Mulai rekam video ke MediaStore, disimpan di Movies/TimestampCamera/Video --
 * subfolder terpisah dari foto (Pictures/TimestampCamera/Photos), supaya rapi
 * saat dilihat lewat Files/Google Photos.
 */
fun startVideoRecording(
    context: Context,
    videoCapture: VideoCapture<*>,
    hasAudioPermission: Boolean,
    onRecordingFinished: (Uri?) -> Unit
): Recording {
    val name = "VID_${System.currentTimeMillis()}.mp4"
    val contentValues = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TimestampCamera")
        }
    }
    val outputOptions = MediaStoreOutputOptions.Builder(
        context.contentResolver,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    ).setContentValues(contentValues).build()

    @Suppress("UNCHECKED_CAST")
    val pending: PendingRecording = (videoCapture as VideoCapture<androidx.camera.video.Recorder>)
        .output
        .prepareRecording(context, outputOptions)
        .let { if (hasAudioPermission) it.withAudioEnabled() else it }

    return pending.start(ContextCompat.getMainExecutor(context)) { event ->
        when (event) {
            is VideoRecordEvent.Finalize -> {
                if (!event.hasError()) {
                    onRecordingFinished(event.outputResults.outputUri)
                    enqueueDriveUpload(context, event.outputResults.outputUri)
                } else {
                    event.cause?.printStackTrace()
                    (context as ComponentActivity).runOnUiThread {
                        Toast.makeText(
                            context,
                            "Gagal menyimpan video: ${event.cause?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    onRecordingFinished(null)
                }
            }
            else -> Unit
        }
    }
}