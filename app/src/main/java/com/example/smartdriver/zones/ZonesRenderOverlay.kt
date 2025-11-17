package com.example.smartdriver.zones

import android.content.Context
import android.graphics.Color
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions

/**
 * Renderiza zonas ativas no GoogleMap.
 */
class ZonesRenderOverlay(
    @Suppress("UNUSED_PARAMETER") private val context: Context,
    private val gmap: GoogleMap
) {
    private val drawn: MutableList<Polygon> = mutableListOf()

    fun clear() {
        drawn.forEach { runCatching { it.remove() } }
        drawn.clear()
    }

    fun renderAll(zones: List<Zone>) {
        clear()
        zones.asSequence()
            .filter { it.active && it.points.size >= 3 }
            .forEach { z ->
                val latLngs = z.points.map { LatLng(it.latitude, it.longitude) }
                val (fill, stroke) = when (z.type) {
                    ZoneType.NO_GO      -> Color.argb(80, 183, 28, 28) to Color.parseColor("#B71C1C")
                    ZoneType.SOFT_AVOID -> Color.argb(70, 230, 81, 0)  to Color.parseColor("#E65100")
                    ZoneType.PREFERRED  -> Color.argb(70, 46, 125, 50) to Color.parseColor("#2E7D32")
                }
                val poly = gmap.addPolygon(
                    PolygonOptions()
                        .addAll(latLngs)
                        .strokeColor(stroke)
                        .strokeWidth(3f)
                        .fillColor(fill)
                        .zIndex(0f)
                )
                drawn += poly
            }
    }
}
