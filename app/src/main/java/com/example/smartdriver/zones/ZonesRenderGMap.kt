package com.example.smartdriver.zones

import android.content.Context
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.*
import org.osmdroid.util.GeoPoint

class ZonesRenderGMap(
    private val context: Context,
    private val map: GoogleMap
) {

    private val polygons = mutableMapOf<String, Polygon>()

    fun clear() {
        polygons.values.forEach { it.remove() }
        polygons.clear()
    }

    fun renderAll(zones: List<Zone>) {
        // remove os que já não existem
        val keepIds = zones.filter { it.active && it.points.size >= 3 }.map { it.id }.toSet()
        val toRemove = polygons.keys.filter { it !in keepIds }
        toRemove.forEach { id -> polygons.remove(id)?.remove() }

        // (re)desenhar ativos
        for (z in zones) {
            if (!z.active || z.points.size < 3) continue
            val fillColor = when (z.type) {
                ZoneType.NO_GO      -> 0x66_B7_1C_1C.toInt()  // vermelho translúcido
                ZoneType.SOFT_AVOID -> 0x66_E6_51_00.toInt()  // laranja translúcido
                ZoneType.PREFERRED  -> 0x66_2E_7D_32.toInt()  // verde translúcido
            }
            val strokeColor = when (z.type) {
                ZoneType.NO_GO      -> 0xFF_B7_1C_1C.toInt()
                ZoneType.SOFT_AVOID -> 0xFF_E6_51_00.toInt()
                ZoneType.PREFERRED  -> 0xFF_2E_7D_32.toInt()
            }

            val pts = z.points.map { LatLng(it.latitude, it.longitude) }

            val existing = polygons[z.id]
            if (existing == null) {
                val poly = map.addPolygon(
                    PolygonOptions()
                        .addAll(pts)
                        .strokeWidth(4f)
                        .strokeColor(strokeColor)
                        .fillColor(fillColor)
                        .zIndex(
                            when (z.type) {
                                ZoneType.NO_GO      -> 3f
                                ZoneType.SOFT_AVOID -> 2f
                                ZoneType.PREFERRED  -> 1f
                            }
                        )
                )
                polygons[z.id] = poly
            } else {
                existing.points = pts
                existing.strokeColor = strokeColor
                existing.fillColor = fillColor
            }
        }
    }
}
