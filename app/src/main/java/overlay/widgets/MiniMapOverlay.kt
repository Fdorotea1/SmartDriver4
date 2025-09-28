package com.example.smartdriver.overlay.widgets

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.preference.PreferenceManager
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

// Ajusta o import se a tua classe estiver noutro package
import com.example.smartdriver.zones.ZonesRenderOverlay

class MiniMapOverlay(context: Context) : FrameLayout(context) {

    data class LatLngD(val lat: Double, val lon: Double)

    private val map: MapView
    private val closeBtn: TextView
    private var polyline: Polyline? = null

    init {
        setBackgroundColor(0xEE000000.toInt()) // fundo semitransparente

        // OSMDroid init
        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))
        map = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
        }
        addView(map, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.CENTER
        })

        closeBtn = TextView(context).apply {
            text = "Fechar mapa"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xAA000000.toInt())
            setOnClickListener { onCloseRequested?.invoke() }
        }
        addView(closeBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(12)
            rightMargin = dp(12)
        })
    }

    private var onCloseRequested: (() -> Unit)? = null
    fun setOnCloseRequested(cb: (() -> Unit)?) { onCloseRequested = cb }

    fun setRoute(points: List<LatLngD>) {
        polyline?.let { map.overlays.remove(it) }
        if (points.isEmpty()) return

        val geo = points.map { GeoPoint(it.lat, it.lon) }

        polyline = Polyline().apply {
            outlinePaint.color = Color.WHITE
            outlinePaint.strokeWidth = dp(3).toFloat()
            setPoints(geo)
        }
        map.overlays.add(polyline)

        addMarker(geo.first(), Color.parseColor("#2E7D32")) // origem (verde)
        addMarker(geo.last(), Color.parseColor("#C62828"))  // destino (vermelho)
        map.invalidate()
        zoomToRoute()
    }

    fun zoomToRoute(paddingDp: Int = 24) {
        val pl = polyline ?: return
        val bbox = pl.bounds ?: return
        val padding = dp(paddingDp)
        map.post {
            map.controller.setCenter(bbox.centerWithDateLine)
            map.zoomToBoundingBox(bbox, true, padding)
        }
    }

    fun tryEnableZonesOverlay(enable: Boolean) {
        if (!enable) return
        try {
            // ✅ CORRIGIDO: passar ctx
            val zones = ZonesRenderOverlay(context.applicationContext)
            map.overlays.add(zones)
            map.invalidate()
        } catch (_: Throwable) {
            // Se o módulo de zonas não estiver presente neste build, ignorar silenciosamente.
        }
    }

    private fun addMarker(point: GeoPoint, color: Int) {
        val m = Marker(map)
        m.position = point
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        m.icon = MarkerIconFactory.circle(color)
        map.overlays.add(m)
    }

    private fun dp(v: Int): Int =
        (resources.displayMetrics.density * v).toInt()

    object MarkerIconFactory {
        fun circle(color: Int): android.graphics.drawable.Drawable {
            val d = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape())
            d.intrinsicWidth = 24
            d.intrinsicHeight = 24
            d.paint.color = color
            return d
        }
    }
}
