package com.example.smartdriver.zones

import kotlin.math.abs

/**
 * Geofencing leve: ponto-em-polígono para a primeira zona ativa que contiver o ponto.
 */
object ZoneRuntime {

    private var currentZone: Zone? = null

    fun updatePosition(lat: Double, lon: Double) {
        currentZone = firstZoneMatch(lat, lon)
    }

    fun current(): Zone? = currentZone

    fun isInNoGo(lat: Double, lon: Double): Boolean {
        return firstZoneMatch(lat, lon)?.type == ZoneType.NO_GO
    }

    fun firstZoneMatch(lat: Double, lon: Double): Zone? {
        val zs = ZoneRepository.list().filter { it.active && it.points.size >= 3 }
        for (z in zs) {
            if (pointInPolygon(lat, lon, z)) return z
        }
        return null
    }

    // Ray casting (edge inclusive)
    private fun pointInPolygon(lat: Double, lon: Double, zone: Zone): Boolean {
        val pts = zone.points
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val xi = pts[i].latitude
            val yi = pts[i].longitude
            val xj = pts[j].latitude
            val yj = pts[j].longitude

            // Check if point is exactly on a vertex/edge (tolerância pequena)
            if (abs(lat - xi) < 1e-9 && abs(lon - yi) < 1e-9) return true

            val intersect = ((yi > lon) != (yj > lon)) &&
                    (lat < (xj - xi) * (lon - yi) / ((yj - yi) + 1e-12) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }
}
