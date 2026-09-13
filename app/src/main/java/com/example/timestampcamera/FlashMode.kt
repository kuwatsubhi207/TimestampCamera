package com.example.timestampcamera

import androidx.camera.core.ImageCapture

enum class FlashMode(val label: String, val camXValue: Int) {
    OFF("OFF", ImageCapture.FLASH_MODE_OFF),
    ON("ON", ImageCapture.FLASH_MODE_ON),
    AUTO("AUTO", ImageCapture.FLASH_MODE_AUTO)
}