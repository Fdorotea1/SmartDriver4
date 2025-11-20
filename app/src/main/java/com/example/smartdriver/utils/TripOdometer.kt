package com.example.smartdriver.utils

import com.google.android.gms.maps.model.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object TripOdometer {

    @Volatile
    private var active: Boolean = false

    private var lastPoint: LatLng? = null
    private var meters: Double = 0.0

    @Synchronized
    fun start(initialLat: Double? = null, initialLon: Double? = null) {
        meters = 0.0
        lastPoint = if (initialLat != null && initialLon != null) {
            LatLng(initialLat, initialLon)
        } else {
            null
        }
        active = true
    }

    @Synchronized
    fun stop() {
        active = false
        lastPoint = null
    }

    @Synchronized
    fun reset() {
        meters = 0.0
        lastPoint = null
        active = false
    }

    @Synchronized
    fun onLocation(lat: Double, lon: Double) {
        if (!active) return

        val here = LatLng(lat, lon)
        val prev = lastPoint

        if (prev != null) {
            val d = distanceMeters(prev, here)
            // ignora jitter e saltos absurdos
            if (d in 1.0..2000.0) {
                meters += d
            }
        }
        lastPoint = here
    }

    @Synchronized
    fun getKm(): Double = meters / 1000.0

    @Synchronized
    fun getKmOrNull(minMeters: Double = 50.0): Double? {
        return if (meters < minMeters) null else meters / 1000.0
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val sinDLat = sin(dLat / 2)
        val sinDLon = sin(dLon / 2)
        val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
        val c = 2 * atan2(sqrt(h), sqrt(1 - h))
        return R * c
    }
}
