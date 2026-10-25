package com.example.location_producer.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.example.location_producer.model.GpsData
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GpsCollector(context: Context) {

    private val tag = "GpsCollector"
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _gpsState = MutableStateFlow(GpsData())
    val gpsState: StateFlow<GpsData> = _gpsState.asStateFlow()

    private var locationCallback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(intervalMs: Long = 1000L) {
        Log.d(tag, "Starting GPS location updates with interval: ${intervalMs}ms")

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location != null) {
                    processLocation(location)
                } else {
                    _gpsState.value = _gpsState.value.copy(isValid = false)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            fusedLocationClient.lastLocation.addOnSuccessListener { loc: Location? ->
                if (loc != null) {
                    processLocation(loc)
                }
            }
        } catch (e: SecurityException) {
            Log.e(tag, "Permission missing for location updates", e)
            _gpsState.value = GpsData(isValid = false)
        }
    }

    fun stopLocationUpdates() {
        Log.d(tag, "Stopping GPS location updates")
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
    }

    private fun processLocation(location: Location) {
        val isFresh = (System.currentTimeMillis() - location.time) < 15000L
        val isValid = isFresh && location.accuracy > 0 && location.accuracy < 200

        val gpsData = GpsData(
            lat = location.latitude,
            lng = location.longitude,
            alt = location.altitude,
            accuracy = location.accuracy,
            isValid = isValid
        )
        _gpsState.value = gpsData
    }

    fun getLatestGps(): GpsData {
        val current = _gpsState.value
        // Verify time freshness
        return if (current.isValid) current else current.copy(isValid = false)
    }
}
