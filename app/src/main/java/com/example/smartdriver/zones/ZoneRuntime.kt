package com.example.smartdriver.zones

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil

/**
 * Geofencing leve: ponto-em-polígono para a primeira zona ativa que contiver o ponto.
 * Usa Google Maps Utils para maior precisão.
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
        val p = LatLng(lat, lon)
        for (z in zs) {
            if (containsLatLng(p, z)) return z
        }
        return null
    }

    private fun containsLatLng(point: LatLng, zone: Zone): Boolean {
        // Converte pontos da zona para Google LatLng (ordem correta: latitude, longitude)
        val pts = zone.points.map { LatLng(it.latitude, it.longitude) }
        return try {
            PolyUtil.containsLocation(point, pts, /* geodesic = */ true)
        } catch (_: Throwable) {
            // fallback extremamente raro (lista vazia, etc.)
            false
        }
    }
}
