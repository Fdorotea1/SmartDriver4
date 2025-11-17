package com.example.smartdriver.map

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.Manifest
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.*
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.smartdriver.R
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

// Zonas
import com.example.smartdriver.zones.Zone
import com.example.smartdriver.zones.ZoneRuntime
import com.example.smartdriver.zones.ZoneType
import com.example.smartdriver.zones.ZoneRepository

// JSON (leitura direta do zones.json)
import com.google.gson.Gson

class MapPreviewActivity : AppCompatActivity(), OnMapReadyCallback {

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

        // MOSTRAR OU NÃO O HEADER (para preview do semáforo sem métricas)
        const val EXTRA_SHOW_HEADER        = "extra_show_header"

        // Broadcast para avisar o semáforo de alterações de zonas
        const val ACTION_ZONES_UPDATED     = "com.example.smartdriver.ZONES_UPDATED"

        // ---- dados do card (vindos do TrackingOverlayView / semáforo) ----
        const val EXTRA_CARD_OFFER_VALUE           = "extra_card_offer_value"
        const val EXTRA_CARD_EUR_PER_KM            = "extra_card_eur_per_km"
        const val EXTRA_CARD_TOTAL_KM              = "extra_card_total_km"
        const val EXTRA_CARD_EUR_PER_HOUR_PLANNED  = "extra_card_eur_per_hour_plANNED"

        // kms e/ou texto de indicação vindos do semáforo (ex: "3.4 km · 16 min")
        const val EXTRA_PICKUP_KM_FROM_CARD       = "EXTRA_PICKUP_KM_FROM_CARD"
        const val EXTRA_DEST_KM_FROM_CARD         = "EXTRA_DEST_KM_FROM_CARD"
        const val EXTRA_TOTAL_KM_FROM_CARD        = "EXTRA_TOTAL_KM_FROM_CARD"
        const val EXTRA_PICKUP_SUFFIX_FROM_CARD   = "EXTRA_PICKUP_SUFFIX_FROM_CARD"
        const val EXTRA_DEST_SUFFIX_FROM_CARD     = "EXTRA_DEST_SUFFIX_FROM_CARD"

        const val ACTION_UPDATE_CARD_METRICS        = "com.example.smartdriver.map.ACTION_UPDATE_CARD_METRICS"
        const val EXTRA_METRIC_EUR_PER_HOUR_CURRENT = "extra_metric_eur_per_hour_current"
    }

    // --- Google Map / UI ---
    private var gmap: GoogleMap? = null
    private lateinit var rootCard: FrameLayout

    // Header card
    private lateinit var headerCard: LinearLayout
    private lateinit var offerLineView: TextView
    private lateinit var eurKmView: TextView
    private lateinit var hourMainView: TextView
    private lateinit var hourCurrentView: TextView
    private lateinit var pickupLineView: TextView
    private lateinit var destLineView: TextView
    private lateinit var totalKmView: TextView
    private lateinit var zoneBadge: TextView
    private lateinit var orientationToggle: ImageView

    private var mapContainerId: Int = View.generateViewId()

    private var lastZoneId: String? = null

    // Marcadores/linhas (Google Maps)
    private var pickupMarker: Marker? = null
    private var destMarker: Marker? = null
    private var currentMarker: Marker? = null  // posição atual (triângulo)
    private var pickupDestRouteOuter: Polyline? = null
    private var pickupDestRouteInner: Polyline? = null
    private var currentToTargetRoute: Polyline? = null
    private var lastPickupDestPoints: List<LatLng>? = null
    private var lastCurrentToTargetPoints: List<LatLng>? = null
    private var currentRouteTargetIsPickup: Boolean = false

    // Polígonos das zonas desenhados no mapa
    private val zonePolygons = mutableListOf<Polygon>()
    private var zonesRedrawPosted = false

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

    private val uiHandler = Handler(Looper.getMainLooper())
    private var fadeAnimator: ValueAnimator? = null
    private var autoHideRunnable: Runnable? = null

    private var firstFixCentered = false
    @Volatile private var lastSeq = 0
    @Volatile private var lastLocSeq = 0

    private val fallbackLisbon = LatLng(38.7223, -9.1393)
    private var isFullscreen = false

    // mostrar ou não o header com as métricas
    private var showHeader: Boolean = true

    // ---- cache dos dados do card para o header ----
    private var lastOfferValue: String? = null
    private var lastEurPerKm: String? = null
    private var lastTotalKm: String? = null
    private var lastEurPerHourPlanned: String? = null
    private var lastEurPerHourCurrent: String? = null
    private var lastPickupAddress: String? = null
    private var lastDestAddress: String? = null

    // kms/sufixos vindos diretamente do semáforo (ex: "3.4 km · 16 min")
    private var cardPickupKm: String? = null
    private var cardDestKm: String? = null
    private var cardTotalKm: String? = null
    private var cardPickupSuffix: String? = null
    private var cardDestSuffix: String? = null

    // última posição atual
    private var lastHere: LatLng? = null

    // últimas coordenadas de pickup/destino para bounding box
    private var lastPickupLatLng: LatLng? = null
    private var lastDestLatLng: LatLng? = null

    // modo de orientação: false = norte para cima; true = segue direção
    private var followHeading: Boolean = false

    // dia/noite
    private var isNightMode: Boolean = false
    private var twilightUpdatedFromLocation = false

    // cores da rota (ajustadas pelo modo dia/noite)
    private var routeOuterColor: Int = Color.BLACK
    private var routeInnerColor: Int = Color.parseColor("#B0B0B0")

    private val repoListener = object : ZoneRepository.SaveListener {
        override fun onDirty() {
            scheduleZonesRedraw()
        }
        override fun onSaved(success: Boolean) {
            scheduleZonesRedraw()
            try {
                sendBroadcast(Intent(ACTION_ZONES_UPDATED).setPackage(packageName))
            } catch (_: Exception) {}
        }
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
                    if (full) applyFullscreenWindow() else applyDefaultWindow()
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
                ACTION_UPDATE_CARD_METRICS -> {
                    if (!showHeader) return
                    val curr = intent.getStringExtra(EXTRA_METRIC_EUR_PER_HOUR_CURRENT)
                    if (!curr.isNullOrBlank()) {
                        lastEurPerHourCurrent = curr
                        updateHeaderTexts()
                    }
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

        // Decide o modo de janela primeiro
        isFullscreen = intent?.getBooleanExtra(EXTRA_FULLSCREEN, false) ?: false
        showHeader = intent?.getBooleanExtra(EXTRA_SHOW_HEADER, true) ?: true
        if (isFullscreen) applyFullscreenWindow() else applyDefaultWindow()

        // Fallback inicial de dia/noite pela hora local (corrigido depois com API de nascer/pôr-do-sol)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val fallbackNight = hour < 7 || hour >= 19
        applyDayNightMode(fallbackNight)

        setFinishOnTouchOutside(true)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)

        // ---------- UI ROOT ----------
        rootCard = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            if (!isFullscreen) {
                elevation = dp(12).toFloat()
                background = roundedCardBackground()
            } else {
                elevation = 0f
                background = null
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        // ---------- MAP FRAME ----------
        val mapFrame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val mapContainer = FrameLayout(this).apply {
            id = mapContainerId
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        mapFrame.addView(mapContainer)

        // Clique no mapa → fechar com fade
        mapFrame.setOnClickListener {
            fadeOutAndFinish(250L)
        }

        // ---------- HEADER CARD ----------
        headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(8))
            background = roundedCardBackground()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                leftMargin = dp(10)
                rightMargin = dp(10)
                topMargin = dp(40)   // afastado do topo
            }
            isClickable = true
        }

        // ---- TOPO: [oferta]  [€/h coluna centrada]  [€/km] ----
        val rowTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        // Esquerda: oferta
        val leftContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

        offerLineView = TextView(this).apply {
            text = "€ —"
            setTextColor(Color.WHITE)
            textSize = 22f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        leftContainer.addView(offerLineView)

        // Centro: €/h planeado + €/h atual
        hourMainView = TextView(this).apply {
            text = "€ —/h"
            setTextColor(Color.parseColor("#4CAF50")) // verde mais claro
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(2))
        }

        hourCurrentView = TextView(this).apply {
            text = "€/h atual —"
            setTextColor(Color.parseColor("#81C784"))
            textSize = 18f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(2))
        }

        val centerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            gravity = Gravity.CENTER_HORIZONTAL
            addView(hourMainView)
            addView(hourCurrentView)
        }

        // Direita: €/km
        eurKmView = TextView(this).apply {
            text = "€/km —"
            setTextColor(Color.WHITE)
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(eurKmView)
        }

        rowTop.addView(leftContainer)
        rowTop.addView(centerContainer)
        rowTop.addView(rightContainer)

        // Morada de recolha
        pickupLineView = TextView(this).apply {
            text = "—"
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(2))
        }

        // Morada de destino
        destLineView = TextView(this).apply {
            text = "—"
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(2))
        }

        // Bolinhas pequenas tipo mapa
        val pickupDot = smallDot(Color.parseColor("#2E7D32"))
        val destDot   = smallDot(Color.parseColor("#D32F2F"))
        pickupLineView.setCompoundDrawablesWithIntrinsicBounds(pickupDot, null, null, null)
        pickupLineView.compoundDrawablePadding = dp(6)
        destLineView.setCompoundDrawablesWithIntrinsicBounds(destDot, null, null, null)
        destLineView.compoundDrawablePadding = dp(6)

        // Fundo: Total kms + Fechar
        val rowBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
            gravity = Gravity.CENTER_VERTICAL
        }

        totalKmView = TextView(this).apply {
            text = "Total kms —"
            setTextColor(Color.WHITE)
            textSize = 22f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val closeView = TextView(this).apply {
            text = "Fechar"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = pill(Color.parseColor("#424242")) // cinzento
            setOnClickListener { fadeOutAndFinish(250L) }
        }

        rowBottom.addView(totalKmView)
        rowBottom.addView(closeView)

        // Ordem no card
        headerCard.addView(rowTop)
        headerCard.addView(pickupLineView)
        headerCard.addView(destLineView)
        headerCard.addView(rowBottom)

        if (!showHeader) {
            headerCard.visibility = View.GONE
        }

        // Badge de zona
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
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(10); topMargin = dp(10)
            }
        )

        // Toggle de orientação (bússola) — meio do lado direito
        orientationToggle = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56))
            background = pill(Color.parseColor("#424242"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setImageDrawable(createCompassNorthIcon())
            setOnClickListener { toggleOrientationMode() }
        }
        mapFrame.addView(
            orientationToggle,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                rightMargin = dp(10)
            }
        )

        rootCard.addView(mapFrame)
        rootCard.addView(headerCard)

        rootCard.setOnClickListener {
            fadeOutAndFinish(250L)
        }

        setContentView(rootCard)

        val frag = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(mapContainerId, frag)
            .commitNow()
        frag.getMapAsync(this)

        ZoneRepository.init(applicationContext)
        ZoneRepository.addListener(repoListener)

        intent?.let { updateFromExtras(it) }

        fusedClient = runCatching { LocationServices.getFusedLocationProviderClient(this) }.getOrNull()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        requestLocationPermissionIfNeeded()
        uiHandler.post(statusTicker)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        gmap = googleMap

        gmap!!.uiSettings.isMapToolbarEnabled = false
        gmap!!.uiSettings.isZoomControlsEnabled = false
        gmap!!.uiSettings.isCompassEnabled = true
        gmap!!.uiSettings.isMyLocationButtonEnabled = false

        // Limites de zoom para a linha não ficar “mais fina” que a estrada
        gmap!!.setMinZoomPreference(10f)
        gmap!!.setMaxZoomPreference(18f)

        // aplicar estilo do mapa conforme modo dia/noite atual
        applyDayNightMode(isNightMode)

        gmap!!.moveCamera(CameraUpdateFactory.newLatLngZoom(fallbackLisbon, 12f))

        renderZonesOnMap()

        intent?.let { updateFromExtras(it) }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(updateReceiver, IntentFilter(ACTION_UPDATE_MAP))
        val f = IntentFilter().apply {
            addAction(ACTION_SEMAFORO_SHOW_MAP)
            addAction(ACTION_SEMAFORO_ALPHA)
            addAction(ACTION_SEMAFORO_HIDE_MAP)
            addAction(ACTION_UPDATE_CARD_METRICS)
        }
        registerReceiver(semaforoReceiver, f)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(updateReceiver) }
        runCatching { unregisterReceiver(semaforoReceiver) }
        super.onStop()
    }

    // ===== HEADER =====
    private fun updateHeaderTexts() {
        if (!showHeader) return

        val offer = lastOfferValue ?: "€ —"
        val eurKm = lastEurPerKm ?: "—"

        // valores brutos que vêm do semáforo (podem vir com "€", "€/h", etc.)
        val eurHourPrevRaw = lastEurPerHourPlanned
        val eurHourCurrRaw = lastEurPerHourCurrent

        // helper para limpar "€", "€/h", "/h", espaços, etc.
        fun cleanHour(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val base = raw
                .replace("€/h", "", ignoreCase = true)
                .replace("€ /h", "", ignoreCase = true)
                .replace("€", "", ignoreCase = true)
                .replace("/h", "", ignoreCase = true)
                .trim()
            return base.ifBlank { null }
        }

        val eurHourPrevClean = cleanHour(eurHourPrevRaw)
        val eurHourCurrClean = cleanHour(eurHourCurrRaw)

        offerLineView.text = offer
        eurKmView.text = "€/km $eurKm"

        // €/hora planeado → "€/hora 12.0" ou "€/hora —"
        hourMainView.text = eurHourPrevClean?.let { "€/hora $it" } ?: "€/hora —"

        // €/hora atual → "€/hora atual 9.5" ou "€/hora atual —"
        hourCurrentView.text = eurHourCurrClean?.let { "€/hora atual $it" } ?: "€/hora atual —"

        val pickupAddr = lastPickupAddress ?: "—"
        val pickupSuffix = when {
            !cardPickupSuffix.isNullOrBlank() -> " · ${cardPickupSuffix!!.trim()}"
            !cardPickupKm.isNullOrBlank()     -> " · ${cardPickupKm!!.trim()}"
            else                              -> ""
        }
        pickupLineView.text = "$pickupAddr$pickupSuffix"

        val destAddr = lastDestAddress ?: "—"
        val destSuffix = when {
            !cardDestSuffix.isNullOrBlank() -> " · ${cardDestSuffix!!.trim()}"
            !cardDestKm.isNullOrBlank()     -> " · ${cardDestKm!!.trim()}"
            else                            -> ""
        }
        destLineView.text = "$destAddr$destSuffix"

        val totalFromCard = cardTotalKm?.takeIf { it.isNotBlank() }
        val fallbackTotal = lastTotalKm?.takeIf { it.isNotBlank() }

        fun label(t: String): String {
            val lower = t.lowercase(Locale.ROOT)
            return if (lower.contains("km")) "Total $t" else "Total ${t} km"
        }

        totalKmView.text = when {
            totalFromCard != null -> label(totalFromCard)
            fallbackTotal != null -> label(fallbackTotal)
            else                  -> "Total kms —"
        }
    }



    private fun updateFromExtras(intent: Intent) {
        val seq = ++lastSeq

        // ---- extras do card / métricas ----
        val cardOffer           = intent.getStringExtra(EXTRA_CARD_OFFER_VALUE)
        val cardEurKm           = intent.getStringExtra(EXTRA_CARD_EUR_PER_KM)
        val cardTotalKmLower    = intent.getStringExtra(EXTRA_CARD_TOTAL_KM)
        val cardTotalKmUpper    = intent.getStringExtra(EXTRA_TOTAL_KM_FROM_CARD)
        val cardEurHourPrev     = intent.getStringExtra(EXTRA_CARD_EUR_PER_HOUR_PLANNED)
        val cardPickupKmExtra   = intent.getStringExtra(EXTRA_PICKUP_KM_FROM_CARD)
        val cardDestKmExtra     = intent.getStringExtra(EXTRA_DEST_KM_FROM_CARD)
        val cardPickupSufExtra  = intent.getStringExtra(EXTRA_PICKUP_SUFFIX_FROM_CARD)
        val cardDestSufExtra    = intent.getStringExtra(EXTRA_DEST_SUFFIX_FROM_CARD)

        if (!cardOffer.isNullOrBlank())       lastOfferValue        = cardOffer
        if (!cardEurKm.isNullOrBlank())       lastEurPerKm          = cardEurKm
        if (!cardEurHourPrev.isNullOrBlank()) lastEurPerHourPlanned = cardEurHourPrev

        val totalFromCard = cardTotalKmUpper ?: cardTotalKmLower
        if (!totalFromCard.isNullOrBlank()) {
            cardTotalKm = totalFromCard
            lastTotalKm = totalFromCard
        }

        if (!cardPickupKmExtra.isNullOrBlank())  cardPickupKm   = cardPickupKmExtra
        if (!cardDestKmExtra.isNullOrBlank())    cardDestKm     = cardDestKmExtra
        if (!cardPickupSufExtra.isNullOrBlank()) cardPickupSuffix = cardPickupSufExtra
        if (!cardDestSufExtra.isNullOrBlank())   cardDestSuffix   = cardDestSufExtra

        val pickupAddrExtra = intent.getStringExtra(EXTRA_PICKUP_ADDRESS)
        val destAddrExtra   = intent.getStringExtra(EXTRA_DEST_ADDRESS)
        if (!pickupAddrExtra.isNullOrBlank()) lastPickupAddress = pickupAddrExtra
        if (!destAddrExtra.isNullOrBlank())   lastDestAddress   = destAddrExtra

        runOnUiThread { updateHeaderTexts() }

        val pLat = intent.getDoubleExtra(EXTRA_PICKUP_LAT, Double.NaN)
        val pLon = intent.getDoubleExtra(EXTRA_PICKUP_LON, Double.NaN)
        val dLat = intent.getDoubleExtra(EXTRA_DEST_LAT,   Double.NaN)
        val dLon = intent.getDoubleExtra(EXTRA_DEST_LON,   Double.NaN)

        val pickupAddr = pickupAddrExtra
        val destAddr   = destAddrExtra

        thread {
            val geocoder = Geocoder(this, Locale("pt", "PT"))
            val pickup: LatLng? = when {
                !pLat.isNaN() && !pLon.isNaN() -> LatLng(pLat, pLon)
                !pickupAddr.isNullOrBlank()    -> geocode(geocoder, pickupAddr)
                else                           -> null
            }
            val dest: LatLng? = when {
                !dLat.isNaN() && !dLon.isNaN() -> LatLng(dLat, dLon)
                !destAddr.isNullOrBlank()      -> geocode(geocoder, destAddr)
                else                           -> null
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
                updateDistanceBanner()
                renderZonesOnMap()
            }
        }
    }

    private fun geocode(geocoder: Geocoder, address: String): LatLng? = try {
        geocoder.getFromLocationName(address, 1)?.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
    } catch (e: Exception) {
        Log.e("MapPreviewActivity", "Geocoding falhou: ${e.message}")
        null
    }

    private fun drawMarkersAndCenter(pickup: LatLng?, dest: LatLng?) {
        val map = gmap ?: return

        pickupMarker?.remove(); pickupMarker = null
        destMarker?.remove(); destMarker = null

        pickupMarker = pickup?.let {
            map.addMarker(
                MarkerOptions()
                    .position(it)
                    .title("Recolha")
                    .icon(dotDescriptor(Color.parseColor("#2E7D32")))
                    .anchor(0.5f, 0.5f)
            )
        }

        destMarker = dest?.let {
            map.addMarker(
                MarkerOptions()
                    .position(it)
                    .title("Destino")
                    .icon(dotDescriptor(Color.parseColor("#D32F2F")))
                    .anchor(0.5f, 0.5f)
            )
        }

        lastPickupLatLng = pickup
        lastDestLatLng = dest

        // centra para mostrar pickup/destino (sem posição atual)
        recenterOnPoints(includeCurrent = false)
    }

    private fun drawPickupDestRoute(points: List<LatLng>) {
        clearPickupDestRoute()
        val map = gmap ?: return
        lastPickupDestPoints = points

        // outline bem grosso
        pickupDestRouteOuter = map.addPolyline(
            PolylineOptions()
                .addAll(points)
                .width(30f)
                .color(routeOuterColor)
        )
        // traço por cima
        pickupDestRouteInner = map.addPolyline(
            PolylineOptions()
                .addAll(points)
                .width(20f)
                .color(routeInnerColor)
        )

        updateDistanceBanner()
    }

    private fun drawPickupDestFallback(pickup: LatLng, dest: LatLng) {
        clearPickupDestRoute()
        val map = gmap ?: return

        val pts = listOf(pickup, dest)
        lastPickupDestPoints = pts

        pickupDestRouteOuter = map.addPolyline(
            PolylineOptions()
                .addAll(pts)
                .width(30f)
                .color(routeOuterColor)
        )
        pickupDestRouteInner = map.addPolyline(
            PolylineOptions()
                .addAll(pts)
                .width(20f)
                .color(routeInnerColor)
        )

        updateDistanceBanner()
    }

    private fun clearPickupDestRoute() {
        pickupDestRouteOuter?.remove()
        pickupDestRouteInner?.remove()
        pickupDestRouteOuter = null
        pickupDestRouteInner = null
        lastPickupDestPoints = null
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
            fusedClient = LocationServices.getFusedLocationProviderClient(this)
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
        val here = LatLng(location.latitude, location.longitude)
        val bearing = location.bearing

        runOnUiThread {
            if (seq != lastLocSeq) return@runOnUiThread

            val map = gmap

            if (map != null) {
                if (currentMarker == null) {
                    currentMarker = map.addMarker(
                        MarkerOptions()
                            .position(here)
                            .title("Posição atual")
                            .icon(arrowDescriptor(Color.BLACK))
                            .anchor(0.5f, 0.5f)
                            .flat(true)
                            .rotation(bearing)
                    )
                } else {
                    currentMarker?.position = here
                    currentMarker?.rotation = bearing
                }

                // Se estiver em modo "direção", roda o mapa conforme o bearing
                if (followHeading) {
                    val cam = map.cameraPosition
                    val newPos = CameraPosition.Builder(cam)
                        .target(here)
                        .bearing(bearing)
                        .tilt(0f)
                        .build()
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(newPos))
                }
            }

            lastHere = here

            // Primeira vez com localização válida → usar API de nascer/pôr-do-sol
            if (!twilightUpdatedFromLocation) {
                twilightUpdatedFromLocation = true
                updateDayNightFromLocation(here)
            }

            if (!firstFixCentered) {
                firstFixCentered = true
                recenterOnPoints(includeCurrent = true)
            }

            ZoneRuntime.updatePosition(here.latitude, here.longitude)
            updateZoneBadge(ZoneRuntime.current())

            currentToTargetRoute?.remove()
            currentToTargetRoute = null
            lastCurrentToTargetPoints = null
        }
    }

    private fun updateDayNightFromLocation(latLng: LatLng) {
        thread {
            var nightResult: Boolean? = null
            try {
                val url = URL(
                    "https://api.sunrise-sunset.org/json" +
                            "?lat=${latLng.latitude}&lng=${latLng.longitude}&formatted=0"
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                }
                val jsonText = conn.inputStream.use { `is` ->
                    BufferedReader(InputStreamReader(`is`)).readText()
                }

                val root = JSONObject(jsonText)
                val status = root.optString("status")
                if (status == "OK") {
                    val results = root.getJSONObject("results")
                    val sunriseStr = results.getString("sunrise")
                    val sunsetStr  = results.getString("sunset")

                    val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val sunrise = df.parse(sunriseStr)?.time
                    val sunset  = df.parse(sunsetStr)?.time
                    val nowUtc  = System.currentTimeMillis()

                    if (sunrise != null && sunset != null) {
                        nightResult = nowUtc < sunrise || nowUtc > sunset
                    }
                } else {
                    Log.w("MapPreviewActivity", "sunrise-sunset status=$status")
                }
            } catch (e: Exception) {
                Log.e("MapPreviewActivity", "Erro a obter nascer/pôr do sol: ${e.message}")
            }

            val night = nightResult ?: return@thread
            runOnUiThread { applyDayNightMode(night) }
        }
    }

    private fun updateZoneBadge(zone: Zone?) {
        if (zone == null) {
            if (zoneBadge.visibility != View.GONE) zoneBadge.visibility = View.GONE
            lastZoneId = null

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

        val kind = when (zone.type) {
            ZoneType.NO_GO      -> "NO_GO"
            ZoneType.SOFT_AVOID -> "NEUTRAL"
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

    private fun updateDistanceBanner() {
        updateHeaderTexts()
    }

    private fun polylineLengthMeters(points: List<LatLng>): Double {
        var sum = 0.0
        var prev: LatLng? = null
        for (p in points) {
            val pr = prev
            if (pr != null) sum += distanceMeters(pr, p)
            prev = p
        }
        return sum
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
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

    // --- Directions API (Google, com key própria em strings.xml) ---
    private fun requestRoutePoints(from: LatLng, to: LatLng): List<LatLng>? {
        val apiKey = try {
            val key = getString(R.string.google_directions_key)
            if (key.isBlank()) {
                Log.e("MapPreviewActivity", "google_directions_key está vazio")
                return null
            }
            key
        } catch (e: Exception) {
            Log.e("MapPreviewActivity", "Não consegui ler google_directions_key: ${e.message}")
            return null
        }

        val url = ("https://maps.googleapis.com/maps/api/directions/json" +
                "?origin=${from.latitude},${from.longitude}" +
                "&destination=${to.latitude},${to.longitude}" +
                "&mode=driving" +
                "&key=$apiKey")

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

                val status = json.optString("status")
                Log.d("MapPreviewActivity", "Directions status = $status")

                if (status != "OK") {
                    val msg = json.optString("error_message")
                    Log.w("MapPreviewActivity", "Directions falhou: $status – $msg")
                    return null
                }

                val routes = json.getJSONArray("routes")
                if (routes.length() == 0) return null
                val r0 = routes.getJSONObject(0)

                val overview = r0.getJSONObject("overview_polyline")
                val encoded = overview.getString("points")

                decodePolylineGoogle(encoded)
            }
        } catch (e: Exception) {
            Log.e("MapPreviewActivity", "Google Directions falhou: ${e.message}")
            null
        }
    }

    // Decoder de polylines no formato Google (factor 1e5)
    private fun decodePolylineGoogle(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0

            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            val latD = lat / 1E5
            val lngD = lng / 1E5
            poly.add(LatLng(latD, lngD))
        }

        return poly
    }

    private fun setOverlayAlpha(a: Float) {
        fadeAnimator?.cancel()
        autoHideRunnable?.let { uiHandler.removeCallbacks(it) }
        rootCard.alpha = a.coerceIn(0f, 1f)
    }

    private fun applyFullscreenWindow() {
        isFullscreen = true

        val lp = WindowManager.LayoutParams().also {
            it.copyFrom(window.attributes)
            it.width = ViewGroup.LayoutParams.MATCH_PARENT
            it.height = ViewGroup.LayoutParams.MATCH_PARENT
            it.gravity = Gravity.TOP or Gravity.START
            it.x = 0
            it.y = 0
            it.flags = it.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        window.attributes = lp

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
    }

    private fun applyDefaultWindow() {
        isFullscreen = false

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val targetW = 2 * dp(360)
        val targetH = 2 * dp(300)

        val margin = dp(16)
        val maxW = screenW - margin * 2
        val maxH = screenH - margin * 2

        val winW = targetW.coerceAtMost(maxW)
        val winH = targetH.coerceAtMost(maxH)

        var topY = screenH / 3
        if (topY + winH + margin > screenH) {
            topY = (screenH - winH - margin).coerceAtLeast(margin)
        }

        val lp = WindowManager.LayoutParams().also {
            it.copyFrom(window.attributes)
            it.width = winW
            it.height = winH
            it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            it.x = 0
            it.y = topY
            it.flags = it.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
        }
        window.attributes = lp

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
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

    private fun roundedCardBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(Color.BLACK)
        setStroke(dp(1), Color.parseColor("#444444"))
    }

    private fun pill(bg: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(18).toFloat()
        setColor(bg)
    }

    private fun smallDot(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(dp(10), dp(10))
    }

    private fun dotDescriptor(color: Int): BitmapDescriptor {
        val size = dp(16)
        val stroke = dp(2)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = stroke.toFloat()
        }
        val r = size / 2f
        c.drawCircle(r, r, r - stroke, pFill)
        c.drawCircle(r, r, r - stroke / 2f, pStroke)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    // bola branca com aro preto e triângulo preto no centro
    private fun arrowDescriptor(@Suppress("UNUSED_PARAMETER") color: Int): BitmapDescriptor {
        val size = dp(32)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val r = size / 2f

        val circleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.FILL
        }
        val circleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        c.drawCircle(r, r, r - dp(3), circleFill)
        c.drawCircle(r, r, r - dp(3), circleStroke)

        val triPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.BLACK
            style = Paint.Style.FILL
        }
        val path = Path().apply {
            moveTo(r, r - dp(8))                 // topo
            lineTo(r - dp(7), r + dp(6))        // canto inf esquerdo
            lineTo(r + dp(7), r + dp(6))        // canto inf direito
            close()
        }
        c.drawPath(path, triPaint)

        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    // Ícone de bússola: modo Norte
    private fun createCompassNorthIcon(): BitmapDrawable {
        val size = dp(32)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        c.drawCircle(r, r, r - dp(3), fill)
        c.drawCircle(r, r, r - dp(3), stroke)

        val triPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.FILL
        }
        val tri = Path().apply {
            moveTo(r, r - dp(9))
            lineTo(r - dp(6), r + dp(6))
            lineTo(r + dp(6), r + dp(6))
            close()
        }
        c.drawPath(tri, triPaint)

        return BitmapDrawable(resources, bmp)
    }

    // Ícone de bússola: modo Direção
    private fun createCompassDirIcon(): BitmapDrawable {
        val size = dp(32)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        c.drawCircle(r, r, r - dp(3), fill)
        c.drawCircle(r, r, r - dp(3), stroke)

        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val path = Path().apply {
            moveTo(r, r - dp(9))              // ponta
            lineTo(r - dp(5), r + dp(9))      // base esq
            lineTo(r, r + dp(5))              // recuo
            lineTo(r + dp(5), r + dp(9))      // base dir
            close()
        }
        c.drawPath(path, arrowPaint)

        return BitmapDrawable(resources, bmp)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            ensureLocationSettingsAndStart()
        } else {
            requestLocationPermissionIfNeeded()
        }
        renderZonesOnMap()
    }

    override fun onPause() {
        stopLocationUpdates()
        super.onPause()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        uiHandler.removeCallbacks(statusTicker)
        runCatching { ZoneRepository.removeListener(repoListener) }
        super.onDestroy()
    }

    private fun stopLocationUpdates() {
        fusedCallback?.let { runCatching { fusedClient?.removeLocationUpdates(it) } }
        runCatching { locationManager?.removeUpdates(locListener) }
    }

    private fun scheduleZonesRedraw() {
        if (zonesRedrawPosted) return
        zonesRedrawPosted = true
        uiHandler.postDelayed({
            zonesRedrawPosted = false
            renderZonesOnMap()
        }, 120L)
    }

    private fun renderZonesOnMap() {
        val map = gmap ?: return

        for (p in zonePolygons) runCatching { p.remove() }
        zonePolygons.clear()

        val zones = safeLoadZonesFromDisk()
        if (zones.isEmpty()) return

        zones.forEach { z ->
            if (z.points.isEmpty()) return@forEach
            val (fill, stroke) = when (z.type) {
                "NO_GO"                 -> Color.argb(60, 183, 28, 28)  to Color.parseColor("#B71C1C")
                "PREFERRED"             -> Color.argb(55, 46, 125, 50)  to Color.parseColor("#2E7D32")
                "SOFT_AVOID", "NEUTRAL" -> Color.argb(55, 230, 81, 0)   to Color.parseColor("#E65100")
                else                    -> Color.argb(40, 120, 120, 120) to Color.parseColor("#616161")
            }
            val poly = map.addPolygon(
                PolygonOptions()
                    .addAll(z.points)
                    .strokeColor(stroke)
                    .strokeWidth(3f)
                    .fillColor(fill)
                    .zIndex(0.0f)
            )
            zonePolygons += poly
        }
    }

    private data class ZonePointJson(
        val latitude: Double? = null,
        val longitude: Double? = null,
        val lat: Double? = null,
        val lon: Double? = null
    )
    private data class ZoneJson(
        val id: String? = null,
        val name: String? = null,
        val type: String? = null,
        val active: Boolean? = null,
        val points: List<ZonePointJson>? = null,
        val pontos: List<ZonePointJson>? = null
    )
    private data class ZoneDraw(val type: String, val points: List<LatLng>)

    private fun safeLoadZonesFromDisk(): List<ZoneDraw> {
        return try {
            val file = File(filesDir, "zones.json")
            if (!file.exists()) {
                emptyList()
            } else {
                val text = file.readText()
                val listType = object : com.google.gson.reflect.TypeToken<List<ZoneJson>>() {}.type
                val items: List<ZoneJson> = Gson().fromJson(text, listType) ?: emptyList()
                items.asSequence()
                    .filter { it.active != false }
                    .mapNotNull { z ->
                        val ptsSrc = z.points ?: z.pontos
                        val pts = ptsSrc?.mapNotNull { pj ->
                            val la = pj.latitude ?: pj.lat
                            val lo = pj.longitude ?: pj.lon
                            if (la != null && lo != null) LatLng(la, lo) else null
                        }.orEmpty()
                        val t = (z.type ?: "").uppercase(Locale.ROOT)
                        if (pts.size >= 3 && t.isNotBlank()) ZoneDraw(t, pts) else null
                    }
                    .toList()
            }
        } catch (t: Throwable) {
            Log.e("MapPreviewActivity", "Falha a ler zones.json: ${t.message}")
            emptyList()
        }
    }

    // centra o mapa para incluir pickup/destino e opcionalmente posição atual
    private fun recenterOnPoints(includeCurrent: Boolean) {
        val map = gmap ?: return
        val pts = mutableListOf<LatLng>()
        lastPickupLatLng?.let { pts.add(it) }
        lastDestLatLng?.let { pts.add(it) }
        if (includeCurrent) lastHere?.let { pts.add(it) }

        if (pts.isEmpty()) return

        if (pts.size == 1) {
            val p = pts[0]
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    p,
                    if (p == fallbackLisbon) 12f else 14f
                )
            )
        } else {
            val builder = LatLngBounds.Builder()
            pts.forEach { builder.include(it) }
            val bounds = builder.build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(40)))
        }
    }

    // alterna entre norte para cima e seguir direção
    private fun toggleOrientationMode() {
        followHeading = !followHeading
        orientationToggle.setImageDrawable(
            if (followHeading) createCompassDirIcon() else createCompassNorthIcon()
        )

        val map = gmap ?: return
        val here = lastHere ?: return
        val cam = map.cameraPosition
        val newBearing = if (followHeading) {
            currentMarker?.rotation ?: cam.bearing
        } else {
            0f
        }

        val newPos = CameraPosition.Builder(cam)
            .target(here)
            .bearing(newBearing)
            .tilt(0f)
            .build()

        map.animateCamera(CameraUpdateFactory.newCameraPosition(newPos))
    }

    // aplica cores de rota + estilo de mapa conforme dia/noite
    private fun applyDayNightMode(isNight: Boolean) {
        isNightMode = isNight

        if (isNight) {
            // noite: azul tipo navegação
            routeOuterColor = Color.parseColor("#0D47A1")
            routeInnerColor = Color.parseColor("#42A5F5")
        } else {
            // dia: preto + cinzento
            routeOuterColor = Color.BLACK
            routeInnerColor = Color.parseColor("#B0B0B0")
        }

        // atualizar cor de polylines já desenhadas
        pickupDestRouteOuter?.color = routeOuterColor
        pickupDestRouteInner?.color = routeInnerColor

        // atualizar estilo do mapa
        val map = gmap ?: return
        try {
            val styleRes = if (isNight) {
                // tenta usar sd_dark_style.json; se não existir, cai no sd_light_style
                val darkId = resources.getIdentifier("sd_dark_style", "raw", packageName)
                if (darkId != 0) darkId else R.raw.sd_light_style
            } else {
                R.raw.sd_light_style
            }
            val ok = map.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, styleRes))
            if (!ok) Log.w("MapPreview", "Falha a aplicar estilo (isNight=$isNight)")
        } catch (e: Exception) {
            Log.e("MapPreview", "Erro a aplicar estilo (isNight=$isNight): ${e.message}")
        }
    }
}
