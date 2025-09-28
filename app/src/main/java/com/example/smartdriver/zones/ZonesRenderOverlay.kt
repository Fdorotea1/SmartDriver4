package com.example.smartdriver.zones

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs

/**
 * Renderiza zonas no-go/avoid/preferred sobre OSMDroid.
 * - Preenchimento/contorno via ZonePainter
 * - Opcionalmente permite pedir edição por long-press
 * - Mostra o NOME da zona no centróide (com caixa semi-transparente)
 */
class ZonesRenderOverlay(
    private val ctx: Context
) : Overlay() {

    /** Callback opcional: long-press numa zona -> pedir edição */
    var onRequestEdit: ((Zone) -> Unit)? = null

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val zones = ZoneRepository.list().sortedBy { it.priority }
        if (zones.isEmpty()) return

        val pj = mapView.projection
        val path = Path()
        val pt = Point()
        val dens = ctx.resources.displayMetrics.density

        for (z in zones) {
            if (!z.active || z.points.size < 3) continue

            val style = z.style ?: ZoneDefaults.styleFor(z.type)
            val fill = ZonePainter.fillPaint(style)
            val stroke = ZonePainter.strokePaint(style)

            // Path do polígono
            path.reset()
            pj.toPixels(z.points[0], pt)
            path.moveTo(pt.x.toFloat(), pt.y.toFloat())
            for (i in 1 until z.points.size) {
                pj.toPixels(z.points[i], pt)
                path.lineTo(pt.x.toFloat(), pt.y.toFloat())
            }
            path.close()

            // Pintura
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)

            // Label com nome no centróide aproximado
            val c = centroid(z.points) ?: continue
            val labelPt = Point()
            pj.toPixels(c, labelPt)

            val textPaint = makeLabelTextPaint(mapView)
            val text = z.name.ifBlank { z.type.name }
            val textWidth = textPaint.measureText(text)
            val fm = textPaint.fontMetrics
            val textHeight = fm.bottom - fm.top
            val pad = 6f * dens

            val bgRect = RectF(
                labelPt.x - textWidth / 2f - pad,
                labelPt.y - textHeight - pad,
                labelPt.x + textWidth / 2f + pad,
                labelPt.y + pad
            )
            canvas.drawRoundRect(bgRect, 8f * dens, 8f * dens, makeLabelBgPaint())
            canvas.drawText(text, labelPt.x - textWidth / 2f, labelPt.y - fm.bottom, textPaint)
        }
    }

    override fun onLongPress(e: MotionEvent, mapView: MapView): Boolean {
        val gp = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
        val zones = ZoneRepository.list().filter { it.active && it.points.size >= 3 }
        for (z in zones.asReversed()) { // última no topo
            if (containsPoint(gp.latitude, gp.longitude, z.points)) {
                onRequestEdit?.invoke(z)
                return true
            }
        }
        return false
    }

    // ---------- Utilitários de geometria / pintura ----------

    private fun centroid(pts: List<GeoPoint>): GeoPoint? {
        val n = pts.size
        if (n == 0) return null
        if (n < 3) return pts[0]
        var a = 0.0
        var cx = 0.0
        var cy = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val xi = pts[i].latitude
            val yi = pts[i].longitude
            val xj = pts[j].latitude
            val yj = pts[j].longitude
            val cross = xi * yj - xj * yi
            a += cross
            cx += (xi + xj) * cross
            cy += (yi + yj) * cross
        }
        if (abs(a) < 1e-12) return pts[0]
        cx /= (3.0 * a); cy /= (3.0 * a)
        return GeoPoint(cx, cy)
    }

    private fun makeLabelTextPaint(mapView: MapView): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val sp = ctx.resources.displayMetrics.scaledDensity
        val zoom = mapView.zoomLevelDouble
        val factor = (((zoom - 10.0) / 6.0).toFloat()).coerceIn(0.7f, 2.0f)
        p.textSize = 12f * sp * factor
        p.color = Color.WHITE
        p.setShadowLayer(2f, 0f, 0f, 0xAA000000.toInt())
        return p
    }

    private fun makeLabelBgPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000 // preto semi-transparente
        style = Paint.Style.FILL
    }

    private fun containsPoint(lat: Double, lon: Double, pts: List<GeoPoint>): Boolean {
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val xi = pts[i].latitude
            val yi = pts[i].longitude
            val xj = pts[j].latitude
            val yj = pts[j].longitude
            val intersect = ((yi > lon) != (yj > lon)) &&
                    (lat < (xj - xi) * (lon - yi) / ((yj - yi) + 1e-12) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }
}
