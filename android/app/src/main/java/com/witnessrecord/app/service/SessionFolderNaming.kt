package com.witnessrecord.app.service

import android.location.Geocoder
import android.os.Build
import com.witnessrecord.app.WitnessApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Builds the per-session Drive folder name from a user-configurable template
 * (see [AppSettings.folderNameTemplate]), substituting placeholders for the
 * recording's start time, location, and session ID.
 */
object SessionFolderNaming {

    /**
     * Placeholders recognized in the template, and their example values -
     * shown together in Settings as the legend.
     */
    val placeholders: List<Pair<String, String>> = listOf(
        "{date}" to "2026-07-17",
        "{time}" to "13-04-22",
        "{location}" to "San Francisco, CA",
        "{session}" to "a1b2c3d4",
    )

    suspend fun folderName(
        sessionID: String,
        date: Long,
        latitude: Double?,
        longitude: Double?,
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFormat = SimpleDateFormat("HH-mm-ss", Locale.US)

        val location = resolveLocation(latitude, longitude)
        val name = AppSettings.folderNameTemplate
            .replace("{date}", dateFormat.format(Date(date)))
            .replace("{time}", timeFormat.format(Date(date)))
            .replace("{location}", location)
            .replace("{session}", sessionID.take(8))

        val trimmed = sanitize(name).trim()
        // A template that resolves to nothing (empty, or edited down to just
        // punctuation) would produce an unusable or colliding folder name -
        // fall back to the default rather than let that happen silently.
        return trimmed.ifEmpty { defaultName(date, location) }
    }

    private fun defaultName(date: Long, location: String): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US)
        return sanitize("Witness ${format.format(Date(date))}, $location")
    }

    private suspend fun resolveLocation(latitude: Double?, longitude: Double?): String {
        val lat = latitude ?: return "Unknown location"
        val lon = longitude ?: return "Unknown location"
        val coordString = String.format(Locale.US, "%.4f, %.4f", lat, lon)

        // Reverse geocoding is a network call behind a system service; a slow
        // or offline lookup must never hold up the folder that footage is
        // about to upload into, so it is strictly time-boxed.
        val resolved = withTimeoutOrNull(8_000L) {
            runCatching { reverseGeocode(lat, lon) }.getOrNull()
        }
        return resolved ?: coordString
    }

    private suspend fun reverseGeocode(lat: Double, lon: Double): String? {
        val geocoder = Geocoder(WitnessApplication.appContext, Locale.getDefault())
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(lat, lon, 1) { result ->
                    continuation.resume(result)
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)
            }
        }
        val placemark = addresses?.firstOrNull() ?: return null
        val parts = listOfNotNull(placemark.locality, placemark.adminArea)
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /**
     * Drive tolerates most characters, but slashes read as path separators in
     * some clients - strip them so the folder name stays intact.
     */
    private fun sanitize(name: String): String = name.replace("/", "-")
}
