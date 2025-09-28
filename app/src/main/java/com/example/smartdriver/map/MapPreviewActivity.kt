package com.example.smartdriver.map

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import java.io.File
import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.Html
import android.util.Log
import android.view.Gravity
import android.view.View
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
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

// Zonas
import com.example.smartdriver.zones.Zone
import com.example.smartdriver.zones.ZoneRuntime
import com.example.smartdriver.zones.ZoneType
import com.example.smartdriver.zones.ZoneRepository
import com.example.smartdriver.zones.ZonesRenderOverlay

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

        const val ACTION_SEMAFORO_SHOW_MAP = "com.example.smartdriver.map.ACTION_SEMAFORO_SHOW_MAP"
        const val ACTION_SEMAFORO_ALPHA    = "com.example.smartdriver.map.ACTION_SEMAFORO_ALPHA"
        const val ACTION_SEMAFORO_HIDE_MAP = "com.example.smartdriver.map.ACTION_SEMAFORO_HIDE_MAP"
        const val EXTRA_AUTO_HIDE_MS       = "auto_hide_ms"
        const val EXTRA_FADE_MS            = "fade_ms"
        const val EXTRA_ALPHA              = "alpha"
        const val EXTRA_FULLSCREEN         = "extra_fullscreen"
    }

    private lateinit var mapView: MapView
    private lateinit var rootCard: FrameLayout
    private lateinit var statusView: TextView
    private lateinit var recenterView: TextView

    private lateinit var zoneBadge: TextView
    private var lastZoneId: String? = null

    private lateinit var zonesOverlay: ZonesRenderOverlay

    private var pickupMarker: Marker? = null
    private var destMarker: Marker? = null
    private var currentMarker: Marker? = null
    private var pickupDestRoute: Polyline? = null
    private var currentToTargetRoute: Polyline? = null
    private var lastPickupDestPoints: List<GeoPoint>? = null
    private var lastCurrentToTargetPoints: List<GeoPoint>? = null
    private var currentRouteTargetIsPickup: Boolean = false

    private var fusedClient: FusedLocationProviderClient? = null
    private var fusedCallback: LocationCallback? = null
    private var locationManager: LocationManager? = null
    private var locationRequest: LocationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 2000L
    ).setMinUpdateIntervalMillis(1200L)
        .setMinUpdateDistanceMeters(5f)
        .setWaitForAccurateLocation(true)
        .build()

    private val uiHandler = Handler(Looper.getMainLooper())
    private var fadeAnimator: ValueAnimator? = null
    private var autoHideRunnable: Runnable? = null

    private var firstFixCentered = false
    private var lastRerouteTimeMs = 0L
    private var lastReroutePoint: GeoPoint? = null
    @Volatile private var lastSeq = 0
    @Volatile private var lastLocSeq = 0

    private val fallbackLisbon = GeoPoint(38.7223, -9.1393)

    private var isFullscreen = false

    private val repoListener = object : ZoneRepository.SaveListener {
        override fun onDirty() { mapView.postInvalidate() }
        override fun onSaved(success: Boolean) { mapView.postInvalidate() }
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            updateFromExtras(intent)
        }
    }

    private val semaforoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                ACTION_SEMAFORO_SHOW_MAP -> {
                    val full = intent.getBooleanExtra(EXTRA_FULLSCREEN, false)

                    if (full) {
                        applyFullscreenWindow()
                    } else {
                        applyDefaultWindow()
                    }

                    val autoMs = intent.getLongExtra(EXTRA_AUTO_HIDE_MS, 10_000L)
                    val fadeMs = intent.getLongExtra(EXTRA_FADE_MS, 400L)
                    showAndAutoHide(autoMs, fadeMs)
                }
                ACTION_SEMAFORO_ALPHA -> {
                    val a = intent.getFloatExtra(EXTRA_ALPHA, 1f).coerceIn(0f, 1f)
                    setOverlayAlpha(a)
                }
                ACTION_SEMAFORO_HIDE_MAP -> {
                    val fadeMs = intent.getLongExtra(EXTRA_FADE_MS, 400L)
                    fadeOutAndFinish(fadeMs)
                }
            }
        }
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            updateDistanceBanner()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Verifica ANTES de criar a UI se deve ser fullscreen
        isFullscreen = intent?.getBooleanExtra(EXTRA_FULLSCREEN, false) ?: false

        if (isFullscreen) {
            applyFullscreenWindow()
        } else {
            val lp = WindowManager.LayoutParams().also {
                it.copyFrom(window.attributes)
                it.width = dp(360)
                it.height = dp(300)
                it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                it.y = dp(180)
            }
            window.attributes = lp
        }

        setFinishOnTouchOutside(true)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)

        rootCard = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            if (!isFullscreen) {
                elevation = dp(12).toFloat()
                background = roundedCardBackground()
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        val topBar = LinearLayout(this).apply {
            setPadding(dp(12), dp(10), dp(12), dp(6))
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val title = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#222222"))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
        }
        recenterView = TextView(this).apply {
            text = "Centrar"
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
        topBar.addView(title)
        topBar.addView(recenterView)
        topBar.addView(closeView)

        statusView = TextView(this).apply {
            text = "—"
            setTextColor(Color.parseColor("#555555"))
            textSize = 12f
            setPadding(dp(12), 0, dp(12), dp(6))
        }

        val base = getDir("osmdroid", MODE_PRIVATE)
        val tileCache = File(cacheDir, "osmdroid/tiles").apply { mkdirs() }
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = base
            osmdroidTileCache = tileCache
        }

        mapView = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
        }

        val mapFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        mapFrame.addView(
            mapView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        zoneBadge = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = pill(Color.parseColor("#B71C1C"))
        }
        mapFrame.addView(
            zoneBadge,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(10); topMargin = dp(10)
            }
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(topBar)
            addView(statusView)
            addView(mapFrame)
        }

        rootCard.addView(content)
        setContentView(rootCard)

        mapView.controller.setZoom(12.0)
        mapView.controller.setCenter(fallbackLisbon)

        // ZONAS (cores)
        ZoneRepository.init(applicationContext)
        ZoneRepository.addListener(repoListener)
        zonesOverlay = ZonesRenderOverlay(this)
        mapView.overlays.add(0, zonesOverlay)

        intent?.let { updateFromExtras(it) }

        fusedClient = runCatching { LocationServices.getFusedLocationProviderClient(this) }.getOrNull()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        requestLocationPermissionIfNeeded()
        uiHandler.post(statusTicker)
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(updateReceiver, IntentFilter(ACTION_UPDATE_MAP))
        val f = IntentFilter().apply {
            addAction(ACTION_SEMAFORO_SHOW_MAP)
            addAction(ACTION_SEMAFORO_ALPHA)
            addAction(ACTION_SEMAFORO_HIDE_MAP)
        }
        registerReceiver(semaforoReceiver, f)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(updateReceiver) }
        runCatching { unregisterReceiver(semaforoReceiver) }
        super.onStop()
    }

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
                val points = requestRoutePoints(pickup, dest)
                if (seq != lastSeq) return@thread
                runOnUiThread {
                    if (points != null) drawPickupDestRoute(points) else drawPickupDestFallback(pickup, dest)
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
            outlinePaint.color = Color.parseColor("#2E7D32")
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
            outlinePaint.color = Color.GREEN
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
            } catch (e: Exception) { }
        }
        Toast.makeText(this, "Abra as Definições do sistema e dê permissão de localização à app.", Toast.LENGTH_LONG).show()
    }

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
                    try { ex.startResolutionForResult(this, REQ_RESOLVE_LOC) } catch (_: Exception) {}
                }
                startLocationManagerUpdates()
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_RESOLVE_LOC) {
            startFusedUpdates()
            startLocationManagerUpdates()
        }
    }

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

        val token = CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { it?.let { onNewLocation(it) } }

        client.lastLocation.addOnSuccessListener { it?.let { onNewLocation(it) } }
    }

    private fun stopFusedUpdates() {
        fusedCallback?.let { cb -> runCatching { fusedClient?.removeLocationUpdates(cb) } }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationManagerUpdates() {
        if (!hasLocationPermission()) return
        if (locationManager == null) locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

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
                    setIcon(solidDot(Color.parseColor("#8E24AA")))
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

            ZoneRuntime.updatePosition(here.latitude, here.longitude)
            updateZoneBadge(ZoneRuntime.current())

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
            val points = requestRoutePoints(here, target) ?: listOf(here, target)
            if (seq != lastLocSeq) return@thread
            runOnUiThread { drawCurrentToTargetRoute(points) }
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
            val points = requestRoutePoints(here, target) ?: listOf(here, target)
            runOnUiThread { drawCurrentToTargetRoute(points) }
        }
    }

    private fun drawCurrentToTargetRoute(points: List<GeoPoint>) {
        currentToTargetRoute?.let { mapView.overlays.remove(it) }
        lastCurrentToTargetPoints = points
        currentToTargetRoute = Polyline().apply {
            setPoints(points)
            outlinePaint.strokeWidth = 7f
            outlinePaint.color = if (currentRouteTargetIsPickup) Color.parseColor("#8E24AA") else Color.parseColor("#2E7D32")
        }.also { mapView.overlays.add(it) }
        mapView.invalidate()
        updateDistanceBanner()
    }

    // ---------- badge nome da zona ----------
    private fun updateZoneBadge(zone: Zone?) {
        if (zone == null) {
            if (zoneBadge.visibility != View.GONE) zoneBadge.visibility = View.GONE
            lastZoneId = null

            // Persistir & notificar semáforo (UNKNOWN)
            getSharedPreferences("smartdriver_map_state", MODE_PRIVATE)
                .edit().putString("last_zone_kind", "UNKNOWN").apply()
            sendBroadcast(
                Intent("com.example.smartdriver.overlay.ACTION_ZONE_HINT").apply {
                    setPackage(packageName)
                    putExtra("zone_kind", "UNKNOWN")
                }
            )
            return
        }
        if (zone.id == lastZoneId && zoneBadge.visibility == View.VISIBLE) return

        zoneBadge.text = when (zone.type) {
            ZoneType.NO_GO      -> "🚫 ${zone.name}"
            ZoneType.SOFT_AVOID -> "⚠️ ${zone.name}"
            ZoneType.PREFERRED  -> "✅ ${zone.name}"
        }
        val bg = when (zone.type) {
            ZoneType.NO_GO      -> Color.parseColor("#CCB71C1C")
            ZoneType.SOFT_AVOID -> Color.parseColor("#CCE65100")
            ZoneType.PREFERRED  -> Color.parseColor("#CC2E7D32")
        }
        zoneBadge.background = pill(bg)
        zoneBadge.visibility = View.VISIBLE
        lastZoneId = zone.id

        // Persistir & notificar semáforo (mapa → overlay)
        // Nota: o Overlay atual interpreta "NO_GO" / "PREFERRED" / "NEUTRAL".
        val kind = when (zone.type) {
            ZoneType.NO_GO      -> "NO_GO"
            ZoneType.SOFT_AVOID -> "NEUTRAL"   // mapeado para neutro no semáforo atual
            ZoneType.PREFERRED  -> "PREFERRED"
        }
        getSharedPreferences("smartdriver_map_state", MODE_PRIVATE)
            .edit().putString("last_zone_kind", kind).apply()

        sendBroadcast(
            Intent("com.example.smartdriver.overlay.ACTION_ZONE_HINT").apply {
                setPackage(packageName)
                putExtra("zone_kind", kind)
            }
        )
    }

    // ================== Distâncias / banner ==================
    private fun updateDistanceBanner() {
        val here = currentMarker?.position
        val pickup = pickupMarker?.position
        val dest = destMarker?.position

        // zona do pickup/destino (para mostrar os emojis nas labels)
        val pickupZone = pickup?.let { ZoneRuntime.firstZoneMatch(it.latitude, it.longitude) }
        val destZone   = dest?.let   { ZoneRuntime.firstZoneMatch(it.latitude, it.longitude) }

        val dPickDestMeters: Double? = if (pickup != null && dest != null) {
            lastPickupDestPoints?.let { polylineLengthMeters(it) } ?: distanceMeters(pickup, dest)
        } else null

        val dCurToPickMeters: Double? = if (here != null && pickup != null) {
            if (currentRouteTargetIsPickup && !lastCurrentToTargetPoints.isNullOrEmpty())
                polylineLengthMeters(lastCurrentToTargetPoints!!)
            else
                distanceMeters(here, pickup)
        } else null

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

        val destDisplayMeters: Double? = if (pickup != null) dPickDestMeters else totalMeters

        val pickupEmoji = emojiForZone(pickupZone)
        val destEmoji   = emojiForZone(destZone)

        statusView.text = Html.fromHtml(
            // emojis junto às labels:
            "$pickupEmoji Até recolha: <b><font color='#8E24AA'>${formatKm(dCurToPickMeters)}</font></b> &nbsp;&nbsp; " +
                    "$destEmoji Até destino: <b><font color='#2E7D32'>${formatKm(destDisplayMeters)}</font></b> &nbsp;&nbsp; " +
                    "Total: <b>${formatKm(totalMeters)}</b>",
            Html.FROM_HTML_MODE_LEGACY
        )
    }

    private fun emojiForZone(zone: Zone?): String = when (zone?.type) {
        ZoneType.NO_GO      -> "🚫"
        ZoneType.SOFT_AVOID -> "⚠️"
        ZoneType.PREFERRED  -> "✅"
        else -> ""
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

    private fun formatKm(meters: Double?): String {
        if (meters == null || meters.isNaN()) return "—"
        if (meters < 1000.0) return "${meters.toInt()} m"
        val km = meters / 1000.0
        return String.format(Locale.US, "%.1f km", km)
    }

    private fun requestRoutePoints(from: GeoPoint, to: GeoPoint): List<GeoPoint>? {
        val url = "https://router.project-osrm.org/route/v1/driving/" +
                "${from.longitude},${from.latitude};${to.longitude},${to.latitude}" +
                "?alternatives=false&steps=false&overview=full&geometries=polyline6"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", packageName)
                connectTimeout = 8000
                readTimeout = 8000
            }
            conn.inputStream.use { `is` ->
                val text = BufferedReader(InputStreamReader(`is`)).readText()
                val json = JSONObject(text)
                if (json.optString("code") != "Ok") return null
                val routes = json.getJSONArray("routes")
                if (routes.length() == 0) return null
                val r0 = routes.getJSONObject(0)
                val geometry = r0.getString("geometry")
                decodePolyline6(geometry)
            }
        } catch (e: Exception) {
            Log.e("MapPreviewActivity", "OSRM falhou: ${e.message}")
            null
        }
    }

    private fun decodePolyline6(encoded: String): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var index = 0
        val len = encoded.length
        var lat = 0L
        var lon = 0L
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0L
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1F).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1L) != 0L) -(result shr 1) else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1F).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlon = if ((result and 1L) != 0L) -(result shr 1) else (result shr 1)
            lon += dlon

            out.add(GeoPoint(lat / 1e6, lon / 1e6))
        }
        return out
    }

    private fun setOverlayAlpha(a: Float) {
        fadeAnimator?.cancel()
        autoHideRunnable?.let { uiHandler.removeCallbacks(it) }
        rootCard.alpha = a.coerceIn(0f, 1f)
    }

    // ===== Tamanho da janela (mini vs fullscreen) =====
    private fun applyFullscreenWindow() {
        isFullscreen = true

        val lp = WindowManager.LayoutParams().also {
            it.copyFrom(window.attributes)
            it.width = ViewGroup.LayoutParams.MATCH_PARENT
            it.height = ViewGroup.LayoutParams.MATCH_PARENT
            it.gravity = Gravity.TOP or Gravity.START
            it.x = 0
            it.y = 0
            // Adiciona flags importantes para fullscreen
            it.flags = it.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        window.attributes = lp

        // Modo fullscreen mais agressivo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }

        // Remove o background arredondado quando fullscreen
        rootCard.background = null
        rootCard.elevation = 0f
    }

    private fun applyDefaultWindow() {
        isFullscreen = false

        val lp = WindowManager.LayoutParams().also {
            it.copyFrom(window.attributes)
            it.width = dp(360)
            it.height = dp(300)
            it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            it.x = 0
            it.y = dp(180)
            // Remove flags de fullscreen
            it.flags = it.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
        }
        window.attributes = lp

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }

        // Restaura o background arredondado
        rootCard.background = roundedCardBackground()
        rootCard.elevation = dp(12).toFloat()
    }

    private fun showAndAutoHide(visibleMs: Long, fadeMs: Long) {
        fadeAnimator?.cancel()
        autoHideRunnable?.let { uiHandler.removeCallbacks(it) }
        rootCard.alpha = 1f
        rootCard.visibility = View.VISIBLE
        autoHideRunnable = Runnable { fadeOutAndFinish(fadeMs) }
        uiHandler.postDelayed(autoHideRunnable!!, visibleMs.coerceAtLeast(0L))
    }

    private fun fadeOutAndFinish(fadeMs: Long) {
        fadeAnimator?.cancel()
        if (fadeMs <= 0L) { finish(); return }
        val start = rootCard.alpha
        val anim = ValueAnimator.ofFloat(start, 0f).apply {
            duration = fadeMs
            addUpdateListener { va -> rootCard.alpha = (va.animatedValue as Float) }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) {}
                override fun onAnimationEnd(animation: android.animation.Animator) { finish() }
                override fun onAnimationCancel(animation: android.animation.Animator) {}
                override fun onAnimationRepeat(animation: android.animation.Animator) {}
            })
        }
        fadeAnimator = anim
        anim.start()
    }

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
        runCatching { ZoneRepository.removeListener(repoListener) }
        super.onDestroy()
    }

    private fun stopLocationUpdates() {
        fusedCallback?.let { runCatching { fusedClient?.removeLocationUpdates(it) } }
        runCatching { locationManager?.removeUpdates(locListener) }
    }
}
