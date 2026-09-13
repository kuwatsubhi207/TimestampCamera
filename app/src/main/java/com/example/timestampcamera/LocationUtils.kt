package com.example.timestampcamera

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import java.util.Locale

fun getAddressText(context: Context, location: Location): String {
    return try {
        val geocoder = Geocoder(context, Locale.getDefault())

        @Suppress("DEPRECATION")
        val addresses: List<Address>? = geocoder.getFromLocation(
            location.latitude, location.longitude, 1
        )

        val address = addresses?.firstOrNull() ?: return "Alamat tidak ditemukan"

        val line1 = listOfNotNull(address.thoroughfare, address.subThoroughfare)
            .joinToString(" ")
            .ifBlank { address.featureName ?: "" }

        val line2 = listOfNotNull(address.subLocality, address.subAdminArea)
            .joinToString(", ")

        val line3 = listOfNotNull(address.locality ?: address.adminArea, address.adminArea)
            .distinct()
            .joinToString(", ")

        listOf(line1, line2, line3)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .ifBlank { "Alamat tidak ditemukan" }

    } catch (e: Exception) {
        e.printStackTrace()
        "Alamat tidak ditemukan"
    }
}

fun formatCoordText(location: Location): String {
    val akurasi = if (location.hasAccuracy()) {
        " (±${"%.0f".format(location.accuracy)}m)"
    } else {
        ""
    }
    return "Lat: ${"%.6f".format(location.latitude)}, Long: ${"%.6f".format(location.longitude)}$akurasi"
}