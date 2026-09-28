package com.terrain.explorer.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Single GPS/location entry point shared by the UI and 3D camera.
 * Uses WGS84 lat/lon — the same geographic CRS as the terrain engine.
 */
class LocationFacade(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun currentPosition(highAccuracy: Boolean = true): Wgs84Position? {
        if (!hasPermission()) return null
        val priority = if (highAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val last = suspendCancellableCoroutine { cont ->
            client.lastLocation
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }
        if (last != null) {
            return Wgs84Position(last.latitude, last.longitude, last.altitude)
        }

        return suspendCancellableCoroutine { cont ->
            val request = LocationRequest.Builder(priority, 2_000L)
                .setMaxUpdates(1)
                .setDurationMillis(12_000L)
                .build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    client.removeLocationUpdates(this)
                    val loc = result.lastLocation
                    if (loc != null) {
                        cont.resume(Wgs84Position(loc.latitude, loc.longitude, loc.altitude))
                    } else {
                        cont.resume(null)
                    }
                }
            }
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            cont.invokeOnCancellation { client.removeLocationUpdates(callback) }
        }
    }
}

data class Wgs84Position(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
)
