package com.example.smartdriver.overlay.widgets

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import com.example.smartdriver.R
import com.example.smartdriver.zones.ZoneRepository
import com.example.smartdriver.zones.ZonesRenderOverlay
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.*

class MiniMapOverlay(context: Context) : FrameLayout(context), OnMapReadyCallback {

    data class LatLngD(val lat: Double, val lon: Double)

    private val mapView: MapView
    private var googleMap: GoogleMap? = null
    private var renderer: ZonesRenderOverlay? = null

    private val closeBtn: TextView

    private var routePolyline: Polyline? = null
    private var originMarker: Marker? = null
    private var destMarker: Marker? = null

    // Caso setRoute seja chamado antes do mapa estar pronto
    private var pendingRoute: List<LatLng>? = null

    init {
        setBackgroundColor(0xEE000000.toInt()) // fundo semitransparente

        // MapView do Google Maps (sem fragment)
        mapView = MapView(context)
        mapView.onCreate(null)
        addView(
            mapView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            }
        )
        mapView.getMapAsync(this)

        closeBtn = TextView(context).apply {
            text = "Fechar mapa"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xAA000000.toInt())
            setOnClickListener { onCloseRequested?.invoke() }
        }
        addView(
            closeBtn,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(12)
                rightMargin = dp(12)
            }
        )
    }

    // -------- Lifecycle opcional (chama do host se quiseres) --------
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onDestroy() {
        renderer?.clear()
        renderer = null
        mapView.onDestroy()
    }

    private var onCloseRequested: (() -> Unit)? = null
    fun setOnCloseRequested(cb: (() -> Unit)?) { onCloseRequested = cb }

    // -------- API pública --------
    fun setRoute(points: List<LatLngD>) {
        val gm = googleMap
        val latLngs = points.map { LatLng(it.lat, it.lon) }

        if (gm == null) {
            pendingRoute = latLngs
            return
        }
        drawRouteOnMap(gm, latLngs)
    }

    fun zoomToRoute(paddingDp: Int = 24) {
        val gm = googleMap ?: return
        val poly = routePolyline ?: return
        val pts = poly.points
        if (pts.isNullOrEmpty()) return

        val builder = LatLngBounds.Builder()
        pts.forEach { builder.include(it) }
        val bounds = builder.build()
        val paddingPx = dp(paddingDp)
        // garantir que já tem tamanho para calcular bounds
        doOnLayout {
            gm.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
        }
    }

    /** Desenha zonas (se existir ZoneRepository). */
    fun tryEnableZonesOverlay(enable: Boolean) {
        if (!enable) return
        val gm = googleMap ?: return
        val act = (context as? Activity) ?: return  // precisa de Activity para estilos
        if (renderer == null) {
            renderer = ZonesRenderOverlay(act, gm).also {
                it.renderAll(ZoneRepository.list())
            }
        } else {
            renderer?.renderAll(ZoneRepository.list())
        }
    }

    // -------- OnMapReady --------
    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // Estilo claro tipo Uber (se tiveres res/raw/sd_light_style.json)
        try {
            map.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, R.raw.sd_light_style))
        } catch (_: Exception) { /* segue default */ }

        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = false

        // Se já havia rota pendente, desenha agora
        pendingRoute?.let { latLngs ->
            drawRouteOnMap(map, latLngs)
            pendingRoute = null
        }
    }

    // -------- Internos --------
    private fun drawRouteOnMap(map: GoogleMap, latLngs: List<LatLng>) {
        // limpar anteriores
        routePolyline?.remove(); routePolyline = null
        originMarker?.remove(); originMarker = null
        destMarker?.remove(); destMarker = null

        if (latLngs.isEmpty()) return

        routePolyline = map.addPolyline(
            PolylineOptions()
                .addAll(latLngs)
                .width(dp(3).toFloat())
                .color(Color.WHITE)
        )

        originMarker = map.addMarker(
            MarkerOptions()
                .position(latLngs.first())
                .icon(circleBitmapDescriptor("#2E7D32"))
                .anchor(0.5f, 1f)
        )
        destMarker = map.addMarker(
            MarkerOptions()
                .position(latLngs.last())
                .icon(circleBitmapDescriptor("#C62828"))
                .anchor(0.5f, 1f)
        )

        // ajustar câmara
        val builder = LatLngBounds.Builder()
        latLngs.forEach { builder.include(it) }
        val bounds = builder.build()
        doOnLayout {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(24)))
        }
    }

    private fun circleBitmapDescriptor(hex: String): BitmapDescriptor {
        val color = Color.parseColor(hex)
        val size = dp(20)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
        }
        val r = size / 2f
        c.drawCircle(r, r, r - dp(3).toFloat(), pFill)
        c.drawCircle(r, r, r - dp(3).toFloat(), pStroke)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()
}
