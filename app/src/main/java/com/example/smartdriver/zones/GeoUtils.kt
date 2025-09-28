package com.example.smartdriver.zones

import org.osmdroid.util.GeoPoint

/**
 * Utilitários neutros (sem Google Maps/LatLng).
 * Usamos DTOs simples para (de)serializar pontos.
 */
object GeoUtils {

    data class LL(val lat: Double, val lon: Double)

    fun toLL(points: List<GeoPoint>): List<LL> =
        points.map { LL(it.latitude, it.longitude) }

    fun fromLL(list: List<LL>): MutableList<GeoPoint> =
        list.map { GeoPoint(it.lat, it.lon) }.toMutableList()

    fun centroid(points: List<GeoPoint>): GeoPoint? {
        if (points.isEmpty()) return null
        var sx = 0.0; var sy = 0.0
        points.forEach { sx += it.latitude; sy += it.longitude }
        return GeoPoint(sx / points.size, sy / points.size)
    }
}
