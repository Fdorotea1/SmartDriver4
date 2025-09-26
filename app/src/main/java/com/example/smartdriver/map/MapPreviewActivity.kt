package com.example.smartdriver.map

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.text.Html
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

/**
 * Minimapa com OpenStreetMap (OSMDroid):
 *  - Rota real pickup ↔ destino (azul) via OSRM
 *  - Posição atual em tempo real (Fused Location Provider + fallback LocationManager)
 *  - Rota posição atual → alvo (ROXO) [alvo = pickup se existir, senão destino]
 *
 * Evita loops de permissões: só abre Definições quando o utilizador aceitar.
 * Mostra no topo: KMs "até à recolha", "até ao destino", e "total".
 */
class MapPreviewActivity : Activity() {

    companion object {
        const val ACTION_UPDATE_MAP = "com.example.smartdriver.map.ACTION_UPDATE_MAP"
        const val EXTRA_PICKUP_ADDRESS = "pickup_address"
        const val EXTRA_DEST_ADDRESS   = "dest_address"
        const val EXTRA_PICKUP_LAT = "pickup_lat"
        const val EXTRA_PICKUP_LON = "pickup_lon"
        const val EXTRA_DEST_LAT   = "dest_lat"
        const val EXTRA_DEST_LON   = "dest_lon"

        private const val REQ_LOC = 1001
        private const val REQ_RESOLVE_LOC = 2001
    }

    private lateinit var mapView: MapView
    private val routeProvider: RouteProvider by lazy { OSRMRouteProvider(userAgent = packageName) }

    // Lisboa fallback
    private val fallbackLisbon = GeoPoint(38.7223, -9.1393)

    // Overlays
    private var pickupMarker: Marker? = null
    private var destMarker: Marker? = null
    private var pickupDestRoute: Polyline? = null          // azul
    private var currentToTargetRoute: Polyline? = null     // roxo
    private var currentMarker: Marker? = null

    // Rota points guardados para calcular distâncias
    private var lastPickupDestPoints: List<GeoPoint>? = null
    private var lastCurrentToTargetPoints: List<GeoPoint>? = null
    private var currentRouteTargetIsPickup: Boolean = false

    // Estado
    @Volatile private var lastSeq = 0
    @Volatile private var lastLocSeq = 0
    private var lastRerouteTimeMs = 0L
    private var lastReroutePoint: GeoPoint? = null
    private var firstFixCentered = false

    // Localização
    private var fusedClient: FusedLocationProviderClient? = null
    private var fusedCallback: LocationCallback? = null
    private var locationManager: LocationManager? = null
    private var locationRequest: LocationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 2000L
    ).setMinUpdateIntervalMillis(1200L)
        .setMinUpdateDistanceMeters(5f)
        .setWaitForAccurateLocation(true)
        .build()

    // UI
    private lateinit var statusView: TextView
    private lateinit var recenterView: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            updateDistanceBanner()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent == null) return
            updateFromExtras(intent)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Janela tipo "cartão"
        val lp = WindowManager.LayoutParams().also {
            it.copyFrom(window.attributes)
            it.width = dp(540)
            it.height = dp(450)
            it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            it.y = dp(100)
        }
        window.attributes = lp
        /* no dimming behind map */
        setFinishOnTouchOutside(true)
        // Ensure no dimming behind this window
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)

        // UI base
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            elevation = dp(12).toFloat()
            background = roundedCardBackground()
        }

        val topBar = LinearLayout(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(8))
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#222222"))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
        }

        recenterView = TextView(this).apply {
            text = "Centrar em mim"
            setTextColor(Color.parseColor("#1976D2"))
            textSize = 14f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = pill(Color.parseColor("#E3F2FD"))
            setOnClickListener { centerOnMe() }
        }

        val closeView = TextView(this).apply {
            text = "Fechar"
            setTextColor(Color.parseColor("#1976D2"))
            textSize = 14f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { finish() }
        }

        topBar.addView(titleView)
        topBar.addView(recenterView)
        topBar.addView(closeView)

        statusView = TextView(this).apply {
            text = "—"
            setTextColor(Color.parseColor("#555555"))
            textSize = 12f
            setPadding(dp(12), 0, dp(12), dp(8))
        }

        // OSMDroid
        val base = File(filesDir, "osmdroid").apply { mkdirs() }
        val tileCache = File(cacheDir, "osmdroid/tiles").apply { mkdirs() }
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = base
            osmdroidTileCache = tileCache
        }

        mapView = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(topBar)
            addView(statusView)
            addView(mapView)
        }

        root.addView(content)
        setContentView(root)

        // Estado inicial
        mapView.controller.setZoom(12.0)
        mapView.controller.setCenter(fallbackLisbon)

        // Primeira oferta
        intent?.let { updateFromExtras(it) }

        // Location infra
        fusedClient = runCatching { LocationServices.getFusedLocationProviderClient(this) }.getOrNull()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        // Pedir permissões / arrancar
        requestLocationPermissionIfNeeded()

        // Atualização periódica do banner
        uiHandler.post(statusTicker)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) updateFromExtras(intent)
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(updateReceiver, IntentFilter(ACTION_UPDATE_MAP))
    }

    override fun onStop() {
        runCatching { unregisterReceiver(updateReceiver) }
        super.onStop()
    }

    // ---------- Ofertas (rota azul) ----------

    private fun updateFromExtras(intent: Intent) {
        val seq = ++lastSeq

        val pLat = intent.getDoubleExtra(EXTRA_PICKUP_LAT, Double.NaN)
        val pLon = intent.getDoubleExtra(EXTRA_PICKUP_LON, Double.NaN)
        val dLat = intent.getDoubleExtra(EXTRA_DEST_LAT,   Double.NaN)
        val dLon = intent.getDoubleExtra(EXTRA_DEST_LON,   Double.NaN)

        val pickupAddr = intent.getStringExtra(EXTRA_PICKUP_ADDRESS)
        val destAddr   = intent.getStringExtra(EXTRA_DEST_ADDRESS)

        thread {
            val geocoder = Geocoder(this, Locale("pt", "PT"))
            val pickup: GeoPoint? = when {
                !pLat.isNaN() && !pLon.isNaN() -> GeoPoint(pLat, pLon)
                !pickupAddr.isNullOrBlank() -> geocode(geocoder, pickupAddr)
                else -> null
            }
            val dest: GeoPoint? = when {
                !dLat.isNaN() && !dLon.isNaN() -> GeoPoint(dLat, dLon)
                !destAddr.isNullOrBlank() -> geocode(geocoder, destAddr)
                else -> null
            }

            if (seq != lastSeq) return@thread

            runOnUiThread { drawMarkersAndCenter(pickup, dest) }

            if (pickup != null && dest != null) {
                val route = routeProvider.getRoute(pickup, dest)
                if (seq != lastSeq) return@thread
                runOnUiThread {
                    if (!route.isNullOrEmpty()) drawPickupDestRoute(route) else drawPickupDestFallback(pickup, dest)
                }
            } else {
                runOnUiThread { clearPickupDestRoute() }
            }

            runOnUiThread {
                maybeRerouteFromCurrent(force = true)
                updateDistanceBanner()
            }
        }
    }

    private fun geocode(geocoder: Geocoder, address: String): GeoPoint? = try {
        geocoder.getFromLocationName(address, 1)?.firstOrNull()?.let { GeoPoint(it.latitude, it.longitude) }
    } catch (e: Exception) {
        Log.e("MapPreviewActivity", "Geocoding falhou: ${e.message}")
        null
    }

    private fun drawMarkersAndCenter(pickup: GeoPoint?, dest: GeoPoint?) {
        pickupMarker?.let { mapView.overlays.remove(it) }
        destMarker?.let { mapView.overlays.remove(it) }

        pickupMarker = pickup?.let {
            Marker(mapView).apply {
                position = it; title = "Recolha"
                setIcon(solidDot(Color.parseColor("#2E7D32")))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }.also { m -> mapView.overlays.add(m) }
        }

        destMarker = dest?.let {
            Marker(mapView).apply {
                position = it; title = "Destino"
                setIcon(solidDot(Color.parseColor("#D32F2F")))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }.also { m -> mapView.overlays.add(m) }
        }

        if (pickup != null && dest != null) {
            val centerLat = (pickup.latitude + dest.latitude) / 2.0
            val centerLon = (pickup.longitude + dest.longitude) / 2.0
            mapView.controller.setCenter(GeoPoint(centerLat, centerLon))
            val approxDistanceDeg = max(abs(pickup.latitude - dest.latitude), abs(pickup.longitude - dest.longitude))
            val zoom = when {
                approxDistanceDeg < 0.01 -> 15.0
                approxDistanceDeg < 0.05 -> 13.5
                approxDistanceDeg < 0.2  -> 12.0
                approxDistanceDeg < 0.5  -> 11.0
                else -> 10.0
            }
            mapView.controller.setZoom(zoom)
        } else {
            val focus = pickup ?: dest ?: fallbackLisbon
            mapView.controller.setZoom(if (focus == fallbackLisbon) 12.0 else 14.0)
            mapView.controller.setCenter(focus)
        }

        mapView.invalidate()
    }

    private fun drawPickupDestRoute(points: List<GeoPoint>) {
        clearPickupDestRoute()
        lastPickupDestPoints = points
        pickupDestRoute = Polyline().apply {
            setPoints(points)
            outlinePaint.strokeWidth = 7f
            outlinePaint.color = Color.parseColor("#2E7D32") // azul
        }.also { mapView.overlays.add(it) }
        mapView.invalidate()
        updateDistanceBanner()
    }

    private fun drawPickupDestFallback(pickup: GeoPoint, dest: GeoPoint) {
        clearPickupDestRoute()
        lastPickupDestPoints = listOf(pickup, dest)
        pickupDestRoute = Polyline().apply {
            setPoints(lastPickupDestPoints)
            outlinePaint.strokeWidth = 6f
            outlinePaint.color = Color.BLUE
        }.also { mapView.overlays.add(it) }
        mapView.invalidate()
        updateDistanceBanner()
    }

    private fun clearPickupDestRoute() {
        pickupDestRoute?.let { mapView.overlays.remove(it) }
        pickupDestRoute = null
        lastPickupDestPoints = null
        mapView.invalidate()
        updateDistanceBanner()
    }

    // ---------- Localização / rota roxa ----------

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun requestLocationPermissionIfNeeded() {
        if (hasLocationPermission()) {
            ensureLocationSettingsAndStart()
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            REQ_LOC
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOC) return

        if (hasLocationPermission()) {
            ensureLocationSettingsAndStart()
            return
        }

        val showFine = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val showCoarse = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        val permanentlyDenied = !(showFine || showCoarse)

        if (permanentlyDenied) {
            showLocationPermissionDialog()
        } else {
            Toast.makeText(this, "Sem permissão de localização. Tenta novamente e aceita.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showLocationPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissão de localização")
            .setMessage("Para mostrar a sua posição no mapa, conceda acesso à localização (precisa ou aproximada).")
            .setPositiveButton("Abrir definições") { _, _ -> openAppSettings() }
            .setNegativeButton("Agora não", null)
            .show()
    }

    private fun openAppSettings() {
        val uri = Uri.parse("package:$packageName")
        val intents = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri),
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(i)
                return
            } catch (e: Exception) {
                // tenta o próximo
            }
        }
        Toast.makeText(this, "Abra as Definições do sistema e dê permissão de localização à app.", Toast.LENGTH_LONG).show()
    }

    /** Verifica definições e inicia updates. */
    private fun ensureLocationSettingsAndStart() {
        if (!hasLocationPermission()) {
            requestLocationPermissionIfNeeded()
            return
        }

        if (fusedClient == null) {
            startLocationManagerUpdates()
            return
        }

        val settingsClient = LocationServices.getSettingsClient(this)
        val settingsReq = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
            .build()

        settingsClient.checkLocationSettings(settingsReq)
            .addOnSuccessListener { startFusedUpdates() }
            .addOnFailureListener { ex ->
                if (ex is ResolvableApiException) {
                    try { ex.startResolutionForResult(this, REQ_RESOLVE_LOC) }
                    catch (_: Exception) { /* ignorar */ }
                }
                // Em qualquer caso, arrancar fallback também
                startLocationManagerUpdates()
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_RESOLVE_LOC) {
            // Tentar ambos; o que responder fica
            startFusedUpdates()
            startLocationManagerUpdates()
        }
    }

    // Fused
    @SuppressLint("MissingPermission")
    private fun startFusedUpdates() {
        val client = fusedClient ?: return
        if (!hasLocationPermission()) return

        if (fusedCallback == null) {
            fusedCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    onNewLocation(loc)
                }
            }
        }

        client.requestLocationUpdates(locationRequest, fusedCallback!!, mainLooper)

        // Arranque rápido
        val token = CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { it?.let { onNewLocation(it) } }

        client.lastLocation.addOnSuccessListener { it?.let { onNewLocation(it) } }
    }

    private fun stopFusedUpdates() {
        fusedCallback?.let { cb -> runCatching { fusedClient?.removeLocationUpdates(cb) } }
    }

    // LocationManager
    @SuppressLint("MissingPermission")
    private fun startLocationManagerUpdates() {
        if (!hasLocationPermission()) return

        if (locationManager == null) locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        // Não abrir definições automaticamente — apenas informar
        if (!providersEnabled()) {
            Toast.makeText(this, "Ative Localização (GPS/Wi-Fi) para melhor precisão.", Toast.LENGTH_SHORT).show()
        }

        locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 8f, locListener, mainLooper)
        locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4000L, 15f, locListener, mainLooper)

        tryLastKnownLM() || tryGetCurrentLM()
    }

    private fun stopLocationManagerUpdates() {
        runCatching { locationManager?.removeUpdates(locListener) }
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { onNewLocation(location) }
        @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun tryLastKnownLM(): Boolean {
        val gps = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (gps != null) { onNewLocation(gps); return true }
        val net = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (net != null) { onNewLocation(net); return true }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun tryGetCurrentLM(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            val provider = when {
                isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            } ?: return false
            locationManager?.getCurrentLocation(provider, null as CancellationSignal?, mainExecutor) { loc ->
                if (loc != null) onNewLocation(loc)
            }
            return true
        }
        return false
    }

    private fun providersEnabled(): Boolean =
        isProviderEnabled(LocationManager.GPS_PROVIDER) || isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    private fun isProviderEnabled(provider: String): Boolean = try {
        locationManager?.isProviderEnabled(provider) == true
    } catch (_: Exception) { false }

    private fun onNewLocation(location: Location) {
        val seq = ++lastLocSeq
        val here = GeoPoint(location.latitude, location.longitude)

        runOnUiThread {
            val wasNull = currentMarker == null
            if (currentMarker == null) {
                currentMarker = Marker(mapView).apply {
                    position = here
                    title = "Posição atual"
                    setIcon(solidDot(Color.parseColor("#8E24AA"))) // roxo para combinar com a rota
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }.also { mapView.overlays.add(it) }
            } else {
                currentMarker?.position = here
            }

            if (wasNull && !firstFixCentered) {
                firstFixCentered = true
                mapView.controller.setZoom(15.0)
                mapView.controller.setCenter(here)
            }

            mapView.invalidate()
        }

        val now = System.currentTimeMillis()
        val distOk = lastReroutePoint?.let { distanceMeters(it, here) > 30.0 } ?: true
        val timeOk = (now - lastRerouteTimeMs) > 2500
        if (!distOk || !timeOk) return

        lastRerouteTimeMs = now
        lastReroutePoint = here

        val pickup = pickupMarker?.position
        val target = pickup ?: destMarker?.position ?: return
        currentRouteTargetIsPickup = (pickup != null)

        thread {
            val route = routeProvider.getRoute(here, target)
            if (seq != lastLocSeq) return@thread
            runOnUiThread { drawCurrentToTargetRoute(route ?: listOf(here, target)) }
        }
    }

    private fun maybeRerouteFromCurrent(force: Boolean) {
        val here = currentMarker?.position ?: return
        val pickup = pickupMarker?.position
        val target = pickup ?: destMarker?.position ?: return
        currentRouteTargetIsPickup = (pickup != null)

        if (!force) {
            val now = System.currentTimeMillis()
            val distOk = lastReroutePoint?.let { distanceMeters(it, here) > 30.0 } ?: true
            val timeOk = (now - lastRerouteTimeMs) > 2500
            if (!distOk || !timeOk) return
            lastRerouteTimeMs = now
            lastReroutePoint = here
        } else {
            lastRerouteTimeMs = System.currentTimeMillis()
            lastReroutePoint = here
        }

        thread {
            val route = routeProvider.getRoute(here, target)
            runOnUiThread { drawCurrentToTargetRoute(route ?: listOf(here, target)) }
        }
    }

    private fun drawCurrentToTargetRoute(points: List<GeoPoint>) {
        currentToTargetRoute?.let { mapView.overlays.remove(it) }
        lastCurrentToTargetPoints = points
        currentToTargetRoute = Polyline().apply {
            setPoints(points)
            outlinePaint.strokeWidth = 7f
            // Purple if target is pickup; Green if target is destination
            outlinePaint.color = if (currentRouteTargetIsPickup) Color.parseColor("#8E24AA") else Color.parseColor("#2E7D32")
        }.also { mapView.overlays.add(it) }
        mapView.invalidate()
        updateDistanceBanner()
    }

    // ---------- Distâncias ----------

    private fun updateDistanceBanner() {
        val here = currentMarker?.position
        val pickup = pickupMarker?.position
        val dest = destMarker?.position

        // Distância pickup↔destino (independente da posição atual)
        val dPickDestMeters: Double? = if (pickup != null && dest != null) {
            lastPickupDestPoints?.let { polylineLengthMeters(it) } ?: distanceMeters(pickup, dest)
        } else null

        // Distância atual→pickup (preferir rota roxa quando o alvo é pickup)
        val dCurToPickMeters: Double? = if (here != null && pickup != null) {
            if (currentRouteTargetIsPickup && !lastCurrentToTargetPoints.isNullOrEmpty())
                polylineLengthMeters(lastCurrentToTargetPoints!!)
            else
                distanceMeters(here, pickup)
        } else null

        // Total:
        // - com pickup: soma (atual→pickup) + (pickup→destino)
        // - sem pickup: atual→destino (preferir rota roxa se o alvo for o destino)
        val totalMeters: Double? = if (pickup != null) {
            val a = dCurToPickMeters; val b = dPickDestMeters
            if (a != null && b != null) a + b else null
        } else {
            if (here != null && dest != null) {
                if (!currentRouteTargetIsPickup && !lastCurrentToTargetPoints.isNullOrEmpty())
                    polylineLengthMeters(lastCurrentToTargetPoints!!)
                else
                    distanceMeters(here, dest)
            } else null
        }

        // O que mostrar em “Até destino”:
        // - com pickup: SEMPRE pickup→destino
        // - sem pickup: posição→destino (igual ao total)
        val destDisplayMeters: Double? = if (pickup != null) dPickDestMeters else totalMeters

        statusView.text = android.text.Html.fromHtml(
            "Até recolha: <b><font color='#8E24AA'>" + formatKm(dCurToPickMeters) + "</font></b> &nbsp;&nbsp; " +
                    "Até destino: <b><font color='#2E7D32'>" + formatKm(destDisplayMeters) + "</font></b> &nbsp;&nbsp; " +
                    "Total: <b>" + formatKm(totalMeters) + "</b>",
            android.text.Html.FROM_HTML_MODE_LEGACY
        )
    }

    private fun polylineLengthMeters(points: List<GeoPoint>): Double {
        var sum = 0.0
        var prev: GeoPoint? = null
        for (p in points) {
            val pr = prev
            if (pr != null) sum += distanceMeters(pr, p)
            prev = p
        }
        return sum
    }

    private fun formatKm(meters: Double?): String {
        if (meters == null || meters.isNaN()) return "—"
        val km = meters / 1000.0
        return String.format(Locale.US, "%.1f km", km)
    }

    // ---------- helpers ----------

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val sinDLat = kotlin.math.sin(dLat / 2)
        val sinDLon = kotlin.math.sin(dLon / 2)
        val h = sinDLat * sinDLat + kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * sinDLon * sinDLon
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1 - h))
        return R * c
    }

    private fun roundedCardBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(Color.WHITE)
        setStroke(dp(1), Color.parseColor("#22000000"))
    }
    private fun pill(bg: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(18).toFloat()
        setColor(bg)
    }

    private fun solidDot(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(dp(16), dp(16))
        setStroke(dp(2), Color.WHITE)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()


    // ---------- UI action ----------
    private fun centerOnMe() {
        val here = currentMarker?.position
        if (here != null) {
            mapView.controller.setZoom(16.0)
            mapView.controller.setCenter(here)
        } else {
            Toast.makeText(this, "Sem posição ainda. A tentar obter…", Toast.LENGTH_SHORT).show()
            ensureLocationSettingsAndStart()
        }
    }
    // --- ciclo de vida

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (hasLocationPermission()) {
            ensureLocationSettingsAndStart()
        } else {
            requestLocationPermissionIfNeeded()
        }
    }

    override fun onPause() {
        stopLocationUpdates()
        mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        mapView.onDetach()
        uiHandler.removeCallbacks(statusTicker)
        super.onDestroy()
    }

    /** ÚNICA função para parar *todos* os updates de localização. */
    private fun stopLocationUpdates() {
        fusedCallback?.let { runCatching { fusedClient?.removeLocationUpdates(it) } }
        runCatching { locationManager?.removeUpdates(locListener) }
    }
}
