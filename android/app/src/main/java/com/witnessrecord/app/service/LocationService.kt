package com.witnessrecord.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.witnessrecord.app.WitnessApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Continuous location while recording. Recording runs inside a foreground
 * service with the `location` type declared, so foreground-only location
 * permission is all this needs - there is no background location use to
 * justify asking for it.
 *
 * Built directly on the platform's [LocationManager] rather than the Play
 * Services fused location client. The witness this app is built for may well
 * be carrying a de-Googled phone - GrapheneOS, a bare LineageOS build with no
 * microG - and location is not an optional, provider-picked feature the way
 * Google Drive is: it gets embedded in every chunk's chain-of-custody
 * metadata regardless of where the footage ends up. A dependency that only
 * works with Google Play Services installed would silently blank that out on
 * exactly the phones most likely to be stripped of it. [LocationManager] asks
 * GPS and the network provider directly and works identically everywhere
 * Android runs, at the cost of the fused client's fix-smoothing heuristics -
 * a trade this app has no real use for.
 */
object LocationService {

    private val _lastLocation = MutableStateFlow<Location?>(null)
    val lastLocationFlow: StateFlow<Location?> = _lastLocation.asStateFlow()
    val lastLocation: Location? get() = _lastLocation.value

    private val manager: LocationManager
        get() = WitnessApplication.appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /**
     * Both providers are requested where available: GPS for accuracy once it
     * has a fix, network for a much faster first fix (especially indoors,
     * where GPS may never lock at all during a short encounter). Whichever
     * update arrives is simply the latest one - no fusion logic, since a
     * recent coarse fix is more useful than an accurate one that never comes.
     */
    private val listener = LocationListener { location -> _lastLocation.value = location }

    private var updating = false
    private val activeProviders = mutableListOf<String>()

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            WitnessApplication.appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                WitnessApplication.appContext, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    fun startUpdating() {
        if (updating || !hasPermission) return
        updating = true

        val locationManager = manager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }

        if (providers.isEmpty()) {
            // No provider is switched on (airplane mode, location off system-
            // wide) - not an error, just nothing to subscribe to yet.
            EventLogger.log("location.no_provider_enabled")
            updating = false
            return
        }

        for (provider in providers) {
            runCatching {
                locationManager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                activeProviders.add(provider)
                // Seed immediately so the first chunk of a recording isn't
                // stamped with a null location just because no fix has
                // arrived yet.
                if (_lastLocation.value == null) {
                    locationManager.getLastKnownLocation(provider)?.let { _lastLocation.value = it }
                }
            }.onFailure {
                // Keep the last known fix; recording must never stop over a
                // location error.
                EventLogger.log("location.start_failed", detail = "$provider: ${it.message}")
            }
        }
    }

    /**
     * One fresh fix, independent of [startUpdating] - for the emergency-contact
     * ping, which has to work even when it is triggered before continuous
     * updates have a first fix (or were never started). Whichever provider
     * answers first simply lands in [lastLocation] like any other update.
     */
    @Suppress("MissingPermission")
    fun requestOneShotLocation() {
        if (!hasPermission) return
        val context = WitnessApplication.appContext
        val locationManager = manager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        for (provider in providers) {
            runCatching {
                locationManager.getCurrentLocation(
                    provider, null, ContextCompat.getMainExecutor(context)
                ) { location -> if (location != null) _lastLocation.value = location }
            }.onFailure {
                EventLogger.log("location.one_shot_failed", detail = "$provider: ${it.message}")
            }
        }
    }

    fun stopUpdating() {
        if (!updating) return
        updating = false
        runCatching { manager.removeUpdates(listener) }
        activeProviders.clear()
    }
}
