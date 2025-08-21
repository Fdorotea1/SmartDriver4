package com.example.smartdriver.overlay

import com.example.smartdriver.data.TelemetryStore
import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.*
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ImageView.ScaleType
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.smartdriver.*
import com.example.smartdriver.SettingsActivity
import com.example.smartdriver.OfferManager
import com.example.smartdriver.utils.*
import com.example.smartdriver.ui.widgets.DonutProgressView
import com.example.smartdriver.data.GoalStore
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class OverlayService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val CHANNEL_NAME = "Overlay Service"

        const val ACTION_SHOW_DROP_ZONE = "com.example.smartdriver.overlay.SHOW_DROP_ZONE"
        const val ACTION_HIDE_DROP_ZONE_AND_CHECK_DROP = "com.example.smartdriver.overlay.HIDE_DROP_ZONE_AND_CHECK_DROP"
        const val EXTRA_UP_X = "up_x_extra"
        const val EXTRA_UP_Y = "up_y_extra"

        const val ACTION_SHOW_OVERLAY = "com.example.smartdriver.overlay.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.example.smartdriver.overlay.HIDE_OVERLAY"
        const val ACTION_DISMISS_MAIN_OVERLAY_ONLY = "com.example.smartdriver.overlay.DISMISS_MAIN_ONLY"
        const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.overlay.UPDATE_SETTINGS"

        const val ACTION_START_TRACKING = "com.example.smartdriver.overlay.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.smartdriver.overlay.STOP_TRACKING"
        const val ACTION_DISCARD_CURRENT_TRACKING = "com.example.smartdriver.overlay.DISCARD_CURRENT_TRACKING"

        const val ACTION_SHOW_QUICK_MENU = "com.example.smartdriver.overlay.SHOW_QUICK_MENU"
        const val ACTION_DISMISS_MENU = "com.example.smartdriver.overlay.DISMISS_MENU"

        const val ACTION_REQUEST_SHUTDOWN = "com.example.smartdriver.overlay.REQUEST_SHUTDOWN"

        const val ACTION_TOGGLE_SHIFT_STATE = "com.example.smartdriver.overlay.TOGGLE_SHIFT_STATE"
        const val ACTION_END_SHIFT = "com.example.smartdriver.overlay.END_SHIFT"

        const val EXTRA_EVALUATION_RESULT = "evaluation_result"
        const val EXTRA_OFFER_DATA = "offer_data"
        const val EXTRA_FONT_SIZE = "font_size"
        const val EXTRA_TRANSPARENCY = "transparency"
        const val EXTRA_SHIFT_TARGET = "shift_target_extra"
        const val EXTRA_OVERLAY_Y_OFFSET = "extra_overlay_y_offset"

        const val HISTORY_PREFS_NAME = "SmartDriverHistoryPrefs"
        const val KEY_TRIP_HISTORY = "trip_history_list_json"

        // Delta vindo do histórico
        const val ACTION_APPLY_SHIFT_DELTA = "com.example.smartdriver.overlay.APPLY_SHIFT_DELTA"
        const val EXTRA_TRIP_START_MS = "extra_trip_start_ms"
        const val EXTRA_OLD_EFFECTIVE = "extra_old_effective_value"
        const val EXTRA_NEW_EFFECTIVE = "extra_new_effective_value"

        // Persist do início do turno
        private const val KEY_SHIFT_START_MS = "key_shift_start_ms"

        // Aplicação do “já faturado hoje” (uma vez/dia)
        private const val KEY_BASE_APPLIED_DATE = "key_base_applied_date"

        // ---------- Eventos p/ ScreenCaptureService ----------
        const val ACTION_EVT_TRACKING_STARTED = "com.example.smartdriver.EVT_TRACKING_STARTED"
        const val ACTION_EVT_TRACKING_ENDED   = "com.example.smartdriver.EVT_TRACKING_ENDED"
        const val ACTION_EVT_MAIN_OVERLAY_SHOWN = "com.example.smartdriver.EVT_MAIN_OVERLAY_SHOWN"
        const val EXTRA_OFFER_SIGNATURE = "extra_offer_signature"

        // Arranque de tracking a partir do último semáforo mostrado
        const val ACTION_START_TRACKING_FROM_LAST = "com.example.smartdriver.overlay.START_TRACKING_FROM_LAST"

        // ---------- NEW: Broadcast do serviço de Acessibilidade ----------
        const val ACTION_EVT_FINISH_KEYWORD_VISIBLE = "com.example.smartdriver.EVT_FINISH_KEYWORD_VISIBLE"
        const val EXTRA_FINISH_VISIBLE = "extra_finish_visible"

        @JvmStatic val isRunning = AtomicBoolean(false)
    }

    private lateinit var shiftSession: ShiftSession
    private lateinit var tripTracker: TripTracker

    private var windowManager: WindowManager? = null
    private var mainOverlayView: OverlayView? = null
    private var trackingOverlayView: TrackingOverlayView? = null
    private var dropZoneView: ImageView? = null
    private var quickMenuView: MenuView? = null
    private var floatingIconView: ImageButton? = null

    private lateinit var mainLayoutParams: WindowManager.LayoutParams
    private lateinit var trackingLayoutParams: WindowManager.LayoutParams
    private var dropZoneLayoutParams: WindowManager.LayoutParams? = null
    private lateinit var menuLayoutParams: WindowManager.LayoutParams
    private lateinit var floatingIconLayoutParams: WindowManager.LayoutParams

    private var isMainOverlayAdded = false
    private var isTrackingOverlayAdded = false
    private var isDropZoneViewAdded = false
    private var isQuickMenuAdded = false
    private var isFloatingIconAdded = false

    private val shiftTimerHandler = Handler(Looper.getMainLooper())
    private var shiftTimerRunnable: Runnable? = null

    private var goodHourThreshold: Double = 15.0
    private var poorHourThreshold: Double = 8.0
    private var touchSlop: Int = 0

    private val currencyFormatter: NumberFormat =
        NumberFormat.getCurrencyInstance(Locale("pt", "PT")).apply {
            minimumFractionDigits = 2; maximumFractionDigits = 2
        }

    private var dropZoneRect: Rect? = null
    private var hasShownTargetReachedBanner = false

    // Fade do semáforo
    private val overlayFadeHandler = Handler(Looper.getMainLooper())
    private val fadeToken = Any()
    private var baseOverlayAlpha: Float = 1.0f
    private var fadedAlpha: Float = 0.15f
    private var fadeDelayMs: Long = 10_000L
    private var fadeAnimMs: Long = 400L

    // Banners topo
    private lateinit var bannerManager: BannerManager

    // Indicador "Oferta em fila"
    private var offerIndicatorView: View? = null
    private var hasActiveOffer: Boolean = false
    private var isMainOverlayTranslucent: Boolean = false

    // Offset dinâmico
    private var baseOverlayYOffsetPx: Int = 0
    private var extraTopInsetPx: Int = 0

    // Início do turno
    private var shiftStartTimeMs: Long = 0L

    // --- HUD donuts / slider / labels ---
    private var donutGoalView: DonutProgressView? = null
    private var seekBarGoalView: SeekBar? = null
    private var textGoalValueView: TextView? = null

    private var donutTimeView: DonutProgressView? = null
    private var donutAverageView: DonutProgressView? = null
    private var donutEtaView: DonutProgressView? = null
    private var tvShiftTimerView: TextView? = null
    private var tvShiftStateChip: TextView? = null

    private var goalOverrideEuro: Int? = null
    private var earningsCorrection: Double = 0.0

    private var goalStore: GoalStore? = null

    // ---------- Guardar o último semáforo mostrado ----------
    private var lastShownEval: EvaluationResult? = null
    private var lastShownOffer: OfferData? = null
    // --------------------------------------------------------

    // ---------- NEW: Faixa de gesto inferior (toque / swipe) ----------
    private var finishGestureView: View? = null
    private var isFinishGestureAdded = false
    @Volatile private var finishKeywordVisible: Boolean = false

    private val finishKeywordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_EVT_FINISH_KEYWORD_VISIBLE) {
                val visible = intent.getBooleanExtra(EXTRA_FINISH_VISIBLE, false)
                finishKeywordVisible = visible
                updateFinishGestureVisibility()
            }
        }
    }
    // -------------------------------------------------------------------

    // --- NEW: Confirmação de fecho ---
    private var confirmCloseView: View? = null
    private var isConfirmCloseAdded = false
    private val confirmHandler = Handler(Looper.getMainLooper())
    private var confirmDismissRunnable: Runnable? = null
    // ----------------------------------

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        shiftSession = ShiftSession(this)
        setupTripTracker()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        loadTrackingThresholds()
        initializeAllLayoutParams()
        setupRunnables()
        bannerManager = BannerManager(this)
        goalStore = GoalStore(this)

        // Recuperar início do turno persistido
        val prefs = getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
        shiftStartTimeMs = prefs.getLong(KEY_SHIFT_START_MS, 0L)
        if (shiftSession.isActive && shiftStartTimeMs == 0L) {
            shiftStartTimeMs = System.currentTimeMillis()
            prefs.edit().putLong(KEY_SHIFT_START_MS, shiftStartTimeMs).apply()
        }

        // ===== IMPORTANTE =====
        // Não “empurrar” ScreenCaptureService aqui para evitar duplicações.
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_overlay_ready)))
        addFloatingIconOverlay()
        if (shiftSession.isActive && !shiftSession.isPaused) startShiftTimer()

        // Registar receiver do “keyword visível”
        val flt = IntentFilter(ACTION_EVT_FINISH_KEYWORD_VISIBLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishKeywordReceiver, flt, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(finishKeywordReceiver, flt)
        }

        // Estado inicial da faixa
        updateFinishGestureVisibility()
    }

    private fun setupTripTracker() {
        tripTracker = TripTracker(this, object : TripTracker.Listener {
            override fun onTripUpdate(elapsedSeconds: Long, currentVph: Double?, rating: IndividualRating) {
                val currentHourRating = when {
                    currentVph == null -> IndividualRating.UNKNOWN
                    currentVph >= goodHourThreshold -> IndividualRating.GOOD
                    currentVph <= poorHourThreshold -> IndividualRating.POOR
                    else -> IndividualRating.MEDIUM
                }
                trackingOverlayView?.updateRealTimeData(currentVph, currentHourRating, elapsedSeconds)
            }
            override fun onTripFinished(historyEntry: TripHistoryEntry) {
                OfferManager.getInstance(applicationContext).setTrackingActive(false)

                val effective = historyEntry.effectiveValue ?: 0.0
                shiftSession.addTripEarnings(effective)

                hideTrackingOverlay()
                removeDropZoneView()
                hideOfferIndicator()
                hideFinishGestureOverlay() // NEW
                hideConfirmCloseOverlay()   // NEW
                updateMenuViewShiftUI()
                updateShiftNotification()

                val inc = if (effective > 0.0) currencyFormatter.format(effective) else null
                showOverlayBanner(
                    if (inc != null) "Viagem registada: +$inc" else "Viagem registada",
                    OverlayView.BannerType.SUCCESS,
                    2500
                )
                sendBroadcast(Intent(ACTION_EVT_TRACKING_STARTED).apply { /* compat */ })
                sendBroadcast(Intent(ACTION_EVT_TRACKING_ENDED))
            }
            override fun onTripDiscarded() {
                OfferManager.getInstance(applicationContext).setTrackingActive(false)
                hideTrackingOverlay()
                removeDropZoneView()
                hideOfferIndicator()
                hideFinishGestureOverlay() // NEW
                hideConfirmCloseOverlay()   // NEW
                updateShiftNotification()
                Toast.makeText(this@OverlayService, getString(R.string.toast_tracking_discarded), Toast.LENGTH_SHORT).show()
                showOverlayBanner("Viagem descartada", OverlayView.BannerType.WARNING, 2000)
                sendBroadcast(Intent(ACTION_EVT_TRACKING_ENDED))
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_SHIFT_STATE -> {
                if (intent.hasExtra(EXTRA_SHIFT_TARGET)) {
                    shiftSession.start(intent.getDoubleExtra(EXTRA_SHIFT_TARGET, 0.0))
                    if (shiftSession.isActive) {
                        shiftStartTimeMs = System.currentTimeMillis()
                        getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putLong(KEY_SHIFT_START_MS, shiftStartTimeMs).apply()

                        hasShownTargetReachedBanner = false
                        earningsCorrection = 0.0
                        startShiftTimer(); updateMenuViewShiftUI(); updateShiftNotification()
                        showOverlayBanner("Turno iniciado", OverlayView.BannerType.INFO, 1500)
                    } else {
                        Toast.makeText(this, getString(R.string.toast_invalid_target_value), Toast.LENGTH_LONG).show()
                    }
                } else handleToggleShiftState()
                return START_REDELIVER_INTENT
            }
            ACTION_SHOW_OVERLAY -> handleShowOverlay(intent)
            ACTION_HIDE_OVERLAY -> handleHideOverlay()
            ACTION_DISMISS_MAIN_OVERLAY_ONLY -> handleDismissMainOverlayOnly()
            ACTION_START_TRACKING -> handleStartTracking(intent)
            ACTION_STOP_TRACKING -> handleStopTracking()
            ACTION_DISCARD_CURRENT_TRACKING -> handleDiscardCurrentTracking()
            ACTION_UPDATE_SETTINGS -> handleUpdateSettings(intent)
            ACTION_SHOW_QUICK_MENU -> handleShowQuickMenu()
            ACTION_DISMISS_MENU -> handleDismissMenu()
            ACTION_SHOW_DROP_ZONE -> addDropZoneView()
            ACTION_HIDE_DROP_ZONE_AND_CHECK_DROP -> checkDropAndHideZone(
                intent.getFloatExtra(EXTRA_UP_X, -1f),
                intent.getFloatExtra(EXTRA_UP_Y, -1f)
            )
            ACTION_REQUEST_SHUTDOWN -> handleShutdownRequest()
            ACTION_END_SHIFT -> handleEndShift()
            ACTION_APPLY_SHIFT_DELTA -> handleApplyShiftDelta(intent)
            ACTION_START_TRACKING_FROM_LAST -> handleStartTrackingFromLast()
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        if (tripTracker.isTracking) tripTracker.discard()
        shiftSession.onServiceDestroyed()
        removeAllOverlays()
        isRunning.set(false)
        try { unregisterReceiver(finishKeywordReceiver) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleStartTrackingFromLast() {
        if (isTrackingOverlayAdded || tripTracker.isTracking) {
            showOverlayBanner("Acompanhamento já em curso", OverlayView.BannerType.INFO, 1500)
            return
        }
        val offer = lastShownOffer
        val eval = lastShownEval
        if (offer == null || eval == null) {
            showOverlayBanner("Sem dados recentes para acompanhar", OverlayView.BannerType.WARNING, 1600)
            return
        }
        hideMainOverlay()
        removeQuickMenuOverlay()

        val initialVpk = offer.calculateProfitability()
        showTrackingOverlay(
            initialVpk,
            offer.calculateTotalDistance()?.takeIf { it > 0 },
            offer.calculateTotalTimeMinutes()?.takeIf { it > 0 },
            offer.value,
            eval.kmRating,
            eval.combinedBorderRating
        )
        trackingOverlayView?.post { trackingOverlayView?.snapToTopRight() }

        tripTracker.start(offer, eval)
        OfferManager.getInstance(applicationContext).setTrackingActive(true)
        hideOfferIndicator()
        updateShiftNotification()
        showOverlayBanner("Tracking iniciado", OverlayView.BannerType.INFO, 1200)
        sendBroadcast(Intent(ACTION_EVT_TRACKING_STARTED))

        // NEW: atualizar faixa
        updateFinishGestureVisibility()
    }

    private fun handleStartTracking(intent: Intent?) {
        if (isTrackingOverlayAdded && trackingOverlayView != null) {
            showOverlayBanner("Já existe uma viagem em acompanhamento", OverlayView.BannerType.INFO, 1800)
            return
        }
        if (tripTracker.isTracking) {
            showOverlayBanner("Acompanhamento já em curso", OverlayView.BannerType.INFO, 1800)
            return
        }

        val offerData = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java)
        val initialEval = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java)
        if (offerData != null && initialEval != null) {
            hideMainOverlay()
            removeQuickMenuOverlay()

            val initialVpk = offerData.calculateProfitability()
            showTrackingOverlay(
                initialVpk,
                offerData.calculateTotalDistance()?.takeIf { it > 0 },
                offerData.calculateTotalTimeMinutes()?.takeIf { it > 0 },
                offerData.value,
                initialEval.kmRating,
                initialEval.combinedBorderRating
            )

            trackingOverlayView?.post { trackingOverlayView?.snapToTopRight() }

            tripTracker.start(offerData, initialEval)
            OfferManager.getInstance(applicationContext).setTrackingActive(true)
            hideOfferIndicator()
            updateShiftNotification()
            showOverlayBanner("Tracking iniciado", OverlayView.BannerType.INFO, 1500)
            sendBroadcast(Intent(ACTION_EVT_TRACKING_STARTED))

            // NEW: atualizar faixa
            updateFinishGestureVisibility()
        }
    }

    private fun handleStopTracking() {
        tripTracker.stopAndSave()
        OfferManager.getInstance(applicationContext).setTrackingActive(false)
        removeDropZoneView()
        hideFinishGestureOverlay() // NEW
        hideConfirmCloseOverlay()   // NEW
        sendBroadcast(Intent(ACTION_EVT_TRACKING_ENDED))
    }

    private fun handleDiscardCurrentTracking() {
        tripTracker.discard()
        OfferManager.getInstance(applicationContext).setTrackingActive(false)
        removeDropZoneView()
        hideFinishGestureOverlay() // NEW
        hideConfirmCloseOverlay()   // NEW
        sendBroadcast(Intent(ACTION_EVT_TRACKING_ENDED))
    }

    private fun handleHideOverlay() {
        if (tripTracker.isTracking) tripTracker.stopAndSave()
        OfferManager.getInstance(applicationContext).setTrackingActive(false)
        removeAllOverlays()
        updateShiftNotification()
        sendBroadcast(Intent(ACTION_EVT_TRACKING_ENDED))
    }

    private fun handleShowOverlay(intent: Intent?) {
        val evalResult = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java)
        val offerData = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java)
        if (evalResult != null && offerData != null) {
            // Guarda último semáforo mostrado
            lastShownEval = evalResult
            lastShownOffer = offerData

            showMainOverlay(evalResult, offerData)
            updateShiftNotification()

            overlayFadeHandler.removeCallbacksAndMessages(fadeToken)
            mainOverlayView?.alpha = baseOverlayAlpha
            isMainOverlayTranslucent = false
            hasActiveOffer = true
            hideOfferIndicator()

            startOverlayFadeTimer()
            updateMainOverlayY()

            sendBroadcast(Intent(ACTION_EVT_MAIN_OVERLAY_SHOWN).apply {
                putExtra(EXTRA_OFFER_SIGNATURE, createOfferSignature(offerData))
            })
        }
    }
    private fun handleDismissMainOverlayOnly() { hideMainOverlay() }

    private fun handleUpdateSettings(intent: Intent?) {
        loadTrackingThresholds()

        val nFS = intent?.getIntExtra(EXTRA_FONT_SIZE, SettingsActivity.getFontSize(this))
            ?: SettingsActivity.getFontSize(this)
        val nT = intent?.getIntExtra(EXTRA_TRANSPARENCY, SettingsActivity.getTransparency(this))
            ?: SettingsActivity.getTransparency(this)

        applyAppearanceSettings(nFS, nT)
        updateLayouts()

        baseOverlayAlpha = (1.0f - (nT / 100f)).coerceIn(0f, 1f)
        fadedAlpha = (baseOverlayAlpha * 0.60f).coerceAtLeast(0.25f)
        mainOverlayView?.alpha = baseOverlayAlpha

        val yFromIntent = intent?.getIntExtra(EXTRA_OVERLAY_Y_OFFSET, -1) ?: -1
        val yDp = if (yFromIntent >= 0) yFromIntent else SettingsActivity.getOverlayYOffsetDp(this)
        val density = resources.displayMetrics.density
        mainLayoutParams.y = (yDp * density).toInt()
        baseOverlayYOffsetPx = mainLayoutParams.y
        updateMainOverlayY()

        if (isMainOverlayAdded && mainOverlayView != null) {
            try { windowManager?.updateViewLayout(mainOverlayView, mainLayoutParams) } catch (_: Exception) {}
        }

        updateShiftStateChip()

        // NEW: manter coerência da faixa
        updateFinishGestureVisibility()
    }

    private fun handleShowQuickMenu() { addQuickMenuOverlay() }
    private fun handleDismissMenu() { removeQuickMenuOverlay() }
    private fun handleShutdownRequest() {
        shiftSession.end(saveSummary = false)
        hasShownTargetReachedBanner = false

        shiftStartTimeMs = 0L
        getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_SHIFT_START_MS, 0L).apply()

        if (tripTracker.isTracking) tripTracker.stopAndSave()
        OfferManager.getInstance(applicationContext).setTrackingActive(false)
        removeAllOverlays()
        stopService(Intent(this, ScreenCaptureService::class.java))
        MediaProjectionData.clear()
        sendBroadcast(Intent(MainActivity.ACTION_SHUTDOWN_APP))
        stopSelf()
    }

    private fun handleToggleShiftState() {
        if (!shiftSession.isActive) {
            val target = (goalOverrideEuro ?: goalStore?.getGoalEuro() ?: 130)
                .toDouble()
            shiftSession.start(target)

            earningsCorrection = 0.0
            maybeApplyTodayBaseEarnings()

            shiftStartTimeMs = System.currentTimeMillis()
            getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_SHIFT_START_MS, shiftStartTimeMs).apply()

            hasShownTargetReachedBanner = false
            startShiftTimer()
            updateMenuViewShiftUI()
            updateShiftNotification()
            updateShiftStateChip()
            showOverlayBanner("Turno iniciado", OverlayView.BannerType.INFO, 1500)
        } else {
            shiftSession.togglePauseResume()
            if (!shiftSession.isPaused) {
                startShiftTimer()
                showOverlayBanner("Turno retomado", OverlayView.BannerType.INFO, 1500)
            } else {
                stopShiftTimer()
                showOverlayBanner("Em pausa", OverlayView.BannerType.INFO, 1500)
            }
            updateMenuViewShiftUI()
            updateShiftNotification()
            updateShiftStateChip()
        }
    }

    private fun handleEndShift() {
        if (shiftSession.isActive) {
            shiftSession.end(saveSummary = true)
            hasShownTargetReachedBanner = false
            earningsCorrection = 0.0

            shiftStartTimeMs = 0L
            getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_SHIFT_START_MS, 0L).apply()

            updateMenuViewShiftUI(); updateShiftNotification()
            updateShiftStateChip()
            showOverlayBanner("Turno terminado", OverlayView.BannerType.INFO, 1500)
        }
    }

    // ---------- LayoutParams ----------
    private fun initializeAllLayoutParams() {
        val density = resources.displayMetrics.density
        val oT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val bF = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        val yDp = SettingsActivity.getOverlayYOffsetDp(this)

        mainLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            oT, bF or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = ((yDp + 28) * density).toInt()
        }
        baseOverlayYOffsetPx = mainLayoutParams.y

        trackingLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            oT, bF or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (10 * density).toInt(); y = (80 * density).toInt() }

        dropZoneLayoutParams = WindowManager.LayoutParams(
            (72 * density).toInt(), (72 * density).toInt(),
            oT, bF, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }

        menuLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            oT, bF, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (10 * density).toInt()
            y = (80 * density).toInt()
            width = (360 * density).toInt()
        }

        floatingIconLayoutParams = WindowManager.LayoutParams(
            (60 * density).toInt(), (60 * density).toInt(),
            oT, bF or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (10 * density).toInt(); y = (48 * density).toInt() }
    }

    private fun removeAllOverlays() {
        hideMainOverlay()
        hideTrackingOverlay()
        removeDropZoneView()
        removeQuickMenuOverlay()
        removeFloatingIconOverlay()
        hideOfferIndicator()
        hideFinishGestureOverlay()
        hideConfirmCloseOverlay() // NEW
    }

    private fun showMainOverlay(eR: EvaluationResult, oD: OfferData) {
        val wm = windowManager ?: return
        if (mainOverlayView == null) { mainOverlayView = OverlayView(this); applyAppearanceSettingsToView(mainOverlayView) }

        val tPercent = SettingsActivity.getTransparency(this)
        baseOverlayAlpha = (1.0f - (tPercent / 100f)).coerceIn(0f, 1.0f)
        fadedAlpha = (baseOverlayAlpha * 0.25f).coerceAtLeast(0.08f)

        mainOverlayView?.alpha = baseOverlayAlpha
        mainOverlayView?.updateState(eR, oD)

        mainOverlayView?.setOnClickListener {
            overlayFadeHandler.removeCallbacksAndMessages(fadeToken)
            mainOverlayView?.alpha = baseOverlayAlpha
            isMainOverlayTranslucent = false
            hideOfferIndicator()
            startOverlayFadeTimer()
        }
        mainOverlayView?.setOnTouchListener { _, _ ->
            overlayFadeHandler.removeCallbacksAndMessages(fadeToken)
            mainOverlayView?.alpha = baseOverlayAlpha
            isMainOverlayTranslucent = false
            hideOfferIndicator()
            startOverlayFadeTimer()
            false
        }

        try {
            if (!isMainOverlayAdded) { wm.addView(mainOverlayView, mainLayoutParams); isMainOverlayAdded = true }
            else wm.updateViewLayout(mainOverlayView, mainLayoutParams)
        } catch (_: Exception) { isMainOverlayAdded = false; mainOverlayView = null }

        updateMainOverlayY()
    }

    private fun hideMainOverlay() {
        hasActiveOffer = false
        isMainOverlayTranslucent = false
        hideOfferIndicator()

        if (isMainOverlayAdded && mainOverlayView != null && windowManager != null) {
            try { windowManager?.removeViewImmediate(mainOverlayView) } catch (_: Exception) {} finally {
                isMainOverlayAdded = false; mainOverlayView = null
            }
        }
    }

    private fun showTrackingOverlay(
        iV: Double?, iD: Double?, iDu: Int?, oV: String?, iKR: IndividualRating, cB: BorderRating
    ) {
        val wm = windowManager ?: return
        if (trackingOverlayView == null) { trackingOverlayView = TrackingOverlayView(this, wm, trackingLayoutParams); applyAppearanceSettingsToView(trackingOverlayView) }
        trackingOverlayView?.updateInitialData(iV, iD, iDu, oV, iKR, cB)
        try {
            if (!isTrackingOverlayAdded && trackingOverlayView != null) { wm.addView(trackingOverlayView, trackingLayoutParams); isTrackingOverlayAdded = true }
            else if (isTrackingOverlayAdded && trackingOverlayView != null) { wm.updateViewLayout(trackingOverlayView, trackingLayoutParams) }
        } catch (_: Exception) { isTrackingOverlayAdded = false; trackingOverlayView = null }
    }
    private fun hideTrackingOverlay() {
        if (isTrackingOverlayAdded && trackingOverlayView != null && windowManager != null) {
            try { windowManager?.removeViewImmediate(trackingOverlayView) } catch (_: Exception) {} finally {
                isTrackingOverlayAdded = false; trackingOverlayView = null
            }
        }
    }

    // ---------- Floating icon ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun addFloatingIconOverlay() {
        if (windowManager == null || isFloatingIconAdded) return
        if (floatingIconView == null) {
            floatingIconView = ImageButton(this).apply {
                setImageResource(R.drawable.smartdriver)
                setBackgroundResource(R.drawable.fab_background)
                scaleType = ScaleType.CENTER_INSIDE
                setOnTouchListener(createFloatingIconTouchListener())
            }
        }
        try { windowManager?.addView(floatingIconView, floatingIconLayoutParams); isFloatingIconAdded = true }
        catch (_: Exception) { isFloatingIconAdded = false; floatingIconView = null }
    }
    private fun removeFloatingIconOverlay() {
        if (isFloatingIconAdded && floatingIconView != null && windowManager != null) {
            try { windowManager?.removeViewImmediate(floatingIconView) } catch (_: Exception) {} finally {
                isFloatingIconAdded = false; floatingIconView = null
            }
        }
    }

    private fun bringFloatingIconToFront() {
        val wm = windowManager ?: return
        val view = floatingIconView ?: return
        if (!isFloatingIconAdded) return
        try {
            wm.removeViewImmediate(view)
            isFloatingIconAdded = false
        } catch (_: Exception) { }
        try {
            wm.addView(view, floatingIconLayoutParams)
            isFloatingIconAdded = true
        } catch (_: Exception) {
            isFloatingIconAdded = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addQuickMenuOverlay() {
        if (windowManager == null || isQuickMenuAdded) return
        if (quickMenuView == null) {
            quickMenuView = MenuView(this)
            bindQuickMenu(quickMenuView!!)
            updateMenuViewShiftUI()
            updateShiftStateChip()
        }
        try {
            menuLayoutParams.x = floatingIconLayoutParams.x
            menuLayoutParams.y = floatingIconLayoutParams.y + floatingIconLayoutParams.height + (5 * resources.displayMetrics.density).toInt()
            windowManager?.addView(quickMenuView, menuLayoutParams); isQuickMenuAdded = true
            bringFloatingIconToFront()
        } catch (_: Exception) { isQuickMenuAdded = false; quickMenuView = null }
    }
    private fun removeQuickMenuOverlay() {
        if (isQuickMenuAdded && quickMenuView != null && windowManager != null) {
            try { windowManager?.removeViewImmediate(quickMenuView) } catch (_: Exception) {} finally {
                isQuickMenuAdded = false; quickMenuView = null
                donutGoalView = null; seekBarGoalView = null; textGoalValueView = null
                donutTimeView = null; donutAverageView = null; donutEtaView = null
                tvShiftTimerView = null; tvShiftStateChip = null
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingIconTouchListener(): View.OnTouchListener {
        var iX = 0; var iY = 0; var iTX = 0f; var iTY = 0f; var sT = 0L; var isD = false
        return View.OnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    iX = floatingIconLayoutParams.x; iY = floatingIconLayoutParams.y
                    iTX = e.rawX; iTY = e.rawY; sT = System.currentTimeMillis(); isD = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dX = abs(e.rawX - iTX); val dY = abs(e.rawY - iTY)
                    if (dX > touchSlop || dY > touchSlop) isD = true
                    if (isD) {
                        val nX = iX + (e.rawX - iTX).toInt(); val nY = iY + (e.rawY - iTY).toInt()
                        val sW = Resources.getSystem().displayMetrics.widthPixels
                        val sH = Resources.getSystem().displayMetrics.heightPixels
                        floatingIconLayoutParams.x = nX.coerceIn(0, sW - v.width)
                        floatingIconLayoutParams.y = nY.coerceIn(0, sH - v.height)
                        try { if (isFloatingIconAdded) windowManager?.updateViewLayout(floatingIconView, floatingIconLayoutParams) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isD && (System.currentTimeMillis() - sT) < ViewConfiguration.getTapTimeout()) {
                        if (!isQuickMenuAdded) handleShowQuickMenu() else handleDismissMenu()
                        v.performClick()
                    } else if (isD && isQuickMenuAdded) {
                        try {
                            menuLayoutParams.x = floatingIconLayoutParams.x
                            menuLayoutParams.y = floatingIconLayoutParams.y + v.height + (5 * resources.displayMetrics.density).toInt()
                            windowManager?.updateViewLayout(quickMenuView, menuLayoutParams)
                        } catch (_: Exception) {}
                    }
                    isD = false; true
                }
                else -> false
            }
        }
    }

    // DROP ZONE
    private fun addDropZoneView() {
        val wm = windowManager ?: return
        val base = dropZoneLayoutParams ?: return

        val density = resources.displayMetrics.density
        val sizePx = (72 * density).toInt()
        val marginPx = (8 * density).toInt()
        val x = marginPx
        val y = marginPx

        val lp = WindowManager.LayoutParams(sizePx, sizePx, base.type, base.flags, base.format).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        if (dropZoneView == null) {
            dropZoneView = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                alpha = 0.9f
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        }

        try {
            if (!isDropZoneViewAdded) {
                wm.addView(dropZoneView, lp)
                isDropZoneViewAdded = true
            } else {
                wm.updateViewLayout(dropZoneView, lp)
            }
            dropZoneView?.visibility = View.VISIBLE
            dropZoneRect = Rect(x, y, x + sizePx, y + sizePx)
        } catch (_: Exception) {
            isDropZoneViewAdded = false
            dropZoneRect = null
        }
    }

    private fun removeDropZoneView() {
        if (!isDropZoneViewAdded || dropZoneView == null || windowManager == null) { dropZoneRect = null; return }
        try { windowManager?.removeViewImmediate(dropZoneView) } catch (_: Exception) {} finally {
            isDropZoneViewAdded = false; dropZoneView = null; dropZoneRect = null
        }
    }

    private fun checkDropAndHideZone(touchUpX: Float, touchUpY: Float) {
        val rect = dropZoneRect
        if (rect != null && tripTracker.isTracking && touchUpX >= 0 && touchUpY >= 0) {
            if (rect.contains(touchUpX.toInt(), touchUpY.toInt())) {
                tripTracker.discard()
                OfferManager.getInstance(applicationContext).setTrackingActive(false)
                removeDropZoneView()
                hideFinishGestureOverlay() // NEW
                hideConfirmCloseOverlay()   // NEW
                sendBroadcast(Intent(ACTION_EVT_TRACKING_ENDED))
                return
            }
        }
        removeDropZoneView()
    }

    // Aparência / timers / util
    private fun applyAppearanceSettings(fSPercent: Int, tPercent: Int) {
        applyAppearanceSettingsToView(mainOverlayView, fSPercent, tPercent)
        applyAppearanceSettingsToView(trackingOverlayView, null, tPercent)
        applyAppearanceSettingsToView(quickMenuView, null, tPercent)
    }

    private fun applyAppearanceSettingsToView(v: View?, fSPercent: Int? = null, tPercent: Int? = null) {
        v ?: return
        val fT = tPercent ?: SettingsActivity.getTransparency(this)
        val a = (1.0f - (fT / 100f)).coerceIn(0.0f, 1.0f)
        when (v) {
            is OverlayView -> {
                val fFS = fSPercent ?: SettingsActivity.getFontSize(this)
                v.updateFontSize(fFS / 100f)
                v.updateAlpha(a)
            }
            is TrackingOverlayView, is MenuView -> v.alpha = a
            else -> v.alpha = a
        }
    }

    private fun updateLayouts() {
        try { if (isMainOverlayAdded && mainOverlayView != null) windowManager?.updateViewLayout(mainOverlayView, mainLayoutParams) } catch (_: Exception) {}
        try { if (isTrackingOverlayAdded && trackingOverlayView != null) windowManager?.updateViewLayout(trackingOverlayView, trackingLayoutParams) } catch (_: Exception) {}
        try { if (isFloatingIconAdded && floatingIconView != null) windowManager?.updateViewLayout(floatingIconView, floatingIconLayoutParams) } catch (_: Exception) {}
        try { if (isQuickMenuAdded && quickMenuView != null) windowManager?.updateViewLayout(quickMenuView, menuLayoutParams) } catch (_: Exception) {}
        // NEW: faixa e confirmação acompanham
        try { if (isFinishGestureAdded && finishGestureView != null) windowManager?.updateViewLayout(finishGestureView, buildFinishGestureLayoutParams()) } catch (_: Exception) {}
        try { if (isConfirmCloseAdded && confirmCloseView != null) windowManager?.updateViewLayout(confirmCloseView, buildConfirmCloseLayoutParams()) } catch (_: Exception) {}
    }

    private fun setupRunnables() { setupShiftTimerRunnable() }
    private fun setupShiftTimerRunnable() {
        shiftTimerRunnable = Runnable {
            if (shiftSession.isActive && !shiftSession.isPaused && shiftTimerRunnable != null) {
                updateMenuViewShiftUI()
                shiftTimerHandler.postDelayed(shiftTimerRunnable!!, 1000L)
            }
        }
    }
    private fun startShiftTimer() { shiftTimerRunnable?.let { shiftTimerHandler.removeCallbacks(it); shiftTimerHandler.post(it) } }
    private fun stopShiftTimer() { shiftTimerRunnable?.let { shiftTimerHandler.removeCallbacks(it) } }

    private fun updateMenuViewShiftUI() {
        quickMenuView?.let { menu ->
            val sTK = if (!shiftSession.isActive) R.string.shift_status_none
            else if (shiftSession.isPaused) R.string.shift_status_paused
            else R.string.shift_status_active

            val targetDisplay = (goalOverrideEuro?.toDouble() ?: shiftSession.targetEarnings)
            val fT = currencyFormatter.format(targetDisplay)

            menu.updateShiftTarget(fT)
            menu.updateShiftStatus(getString(sTK), shiftSession.isActive, shiftSession.isPaused)
            menu.updateShiftTimer(shiftSession.getFormattedWorkedTime())
            menu.updateShiftAverage(shiftSession.getFormattedAveragePerHour())
            menu.updateTimeToTarget(shiftSession.getFormattedTimeToTarget())
            menu.updateExpectedEndTime(calculateAndFormatExpectedEndTime())
        }

        val goalNow = (goalOverrideEuro ?: seekBarGoalView?.progress ?: goalStore?.getGoalEuro() ?: 130)
        refreshGoalUI(goalNow)
        refreshTimeAvgEta()
        updateShiftStateChip()

        if (shiftSession.isActive && !hasShownTargetReachedBanner) {
            val target = (goalOverrideEuro?.toDouble() ?: shiftSession.targetEarnings)
            val remaining = target - currentEarnings()
            if (target > 0.0 && remaining <= 0.0) {
                hasShownTargetReachedBanner = true
                showOverlayBanner("Meta atingida", OverlayView.BannerType.SUCCESS, 2500)
            }
        }

        // --- NOVO: publicar telemetria leve p/ o avaliador dinâmico (OfferManager)
        try {
            val avgNow = shiftSession.getAveragePerHourValue() ?: 0.0
            TelemetryStore.write(this, currentEarnings(), avgNow)
        } catch (_: Exception) {
            // best-effort; nunca quebrar a UI
        }
    }


    private fun updateShiftNotification() {
        val cT = when {
            tripTracker.isTracking -> getString(R.string.notification_tracking_trip)
            shiftSession.isActive && !shiftSession.isPaused -> getString(R.string.notification_shift_active, shiftSession.getFormattedWorkedTime())
            shiftSession.isActive && shiftSession.isPaused -> getString(R.string.shift_status_paused)
            else -> getString(R.string.notification_overlay_ready)
        }
        val important = tripTracker.isTracking || (shiftSession.isActive && !shiftSession.isPaused)
        updateNotification(cT, important)
    }

    private fun createNotification(contentText: String, isTrackingOrActive: Boolean = false): Notification {
        createNotificationChannel()
        val sI = try { if (isTrackingOrActive) R.drawable.ic_stat_tracking else R.mipmap.ic_launcher }
        catch (_: Resources.NotFoundException) { R.mipmap.ic_launcher }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(sI)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String, isTrackingOrActive: Boolean = false) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, createNotification(contentText, isTrackingOrActive))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notification_channel_description)
                enableLights(false); enableVibration(false); setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(c)
        }
    }

    private fun calculateAndFormatExpectedEndTime(): String {
        if (!shiftSession.isActive) return getString(R.string.expected_end_time_placeholder)
        val avgPH = shiftSession.getAveragePerHourValue() ?: return getString(R.string.expected_end_time_placeholder)
        if (avgPH <= 0.0) return getString(R.string.expected_end_time_placeholder)

        val target = (goalOverrideEuro?.toDouble() ?: shiftSession.targetEarnings)
        val remaining = (target - currentEarnings())
        if (remaining <= 0.0) return getString(R.string.shift_target_reached)

        val ms = ((remaining / avgPH) * 3_600_000.0).toLong()
        if (ms < 0) return getString(R.string.shift_target_reached)
        return try { android.text.format.DateFormat.getTimeFormat(this).format(Date(System.currentTimeMillis() + ms)) }
        catch (_: Exception) { "--:--" }
    }

    private fun loadTrackingThresholds() {
        try {
            goodHourThreshold = SettingsActivity.getGoodHourThreshold(this)
            poorHourThreshold = SettingsActivity.getPoorHourThreshold(this)
        } catch (_: Exception) {
            goodHourThreshold = 15.0; poorHourThreshold = 13.5
        }
    }

    private fun <T : Parcelable> getParcelableExtraCompat(intent: Intent?, key: String, clazz: Class<T>): T? {
        return intent?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) it.getParcelableExtra(key, clazz)
            else @Suppress("DEPRECATION") it.getParcelableExtra(key) as? T
        }
    }

    // Banners
    private fun showOverlayBanner(text: String, type: OverlayView.BannerType, durationMs: Long) {
        bannerManager.show(text, type, durationMs)
    }

    // Fade
    private fun startOverlayFadeTimer() {
        overlayFadeHandler.postAtTime({
            val v = mainOverlayView ?: return@postAtTime
            v.animate().alpha(fadedAlpha).setDuration(fadeAnimMs).start()
            isMainOverlayTranslucent = true
            if (hasActiveOffer) showOfferIndicator()
        }, fadeToken, SystemClock.uptimeMillis() + fadeDelayMs)
    }

    // Indicador "Oferta em fila"
    private fun showOfferIndicator() {
        if (offerIndicatorView != null || windowManager == null) return
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.banner_center_queued_offer, null) as LinearLayout

        view.findViewById<TextView>(R.id.tvTitle)?.text = getString(R.string.queued_offer)

        view.setOnClickListener {
            overlayFadeHandler.removeCallbacksAndMessages(fadeToken)
            mainOverlayView?.alpha = baseOverlayAlpha
            isMainOverlayTranslucent = false
            hideOfferIndicator()
            startOverlayFadeTimer()
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val density = resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (24 * density).toInt()
        }

        try {
            windowManager?.addView(view, lp)
            offerIndicatorView = view
        } catch (_: Exception) {
            offerIndicatorView = null
        }
    }

    private fun hideOfferIndicator() {
        val v = offerIndicatorView ?: return
        try { windowManager?.removeViewImmediate(v) } catch (_: Exception) {}
        offerIndicatorView = null
    }

    private fun updateMainOverlayY() { /* no-op */ }

    // ---------- Aplicar delta ao turno / geral ----------
    private fun handleApplyShiftDelta(intent: Intent?) {
        val oldEff = intent?.getDoubleExtra(EXTRA_OLD_EFFECTIVE, Double.NaN) ?: Double.NaN
        val newEff = intent?.getDoubleExtra(EXTRA_NEW_EFFECTIVE, Double.NaN) ?: Double.NaN
        if (oldEff.isNaN() || newEff.isNaN()) return

        val delta = newEff - oldEff
        if (delta == 0.0) return

        if (shiftSession.isActive) {
            val before = shiftSession.totalEarnings
            shiftSession.addTripEarnings(delta)
            val after = shiftSession.totalEarnings
            val applied = after - before
            val missing = delta - applied
            if (kotlin.math.abs(missing) > 0.0001) {
                earningsCorrection += missing
            }
        } else {
            val store = goalStore ?: GoalStore(this).also { goalStore = it }
            val base = store.getTodayInvoicedEuro()
            store.setTodayInvoicedEuro((base + delta).coerceAtLeast(0.0))
        }

        updateMenuViewShiftUI()
        updateShiftNotification()

        val sign = if (delta >= 0.0) "+" else ""
        showOverlayBanner("Ajuste: $sign${currencyFormatter.format(delta)}",
            OverlayView.BannerType.INFO, 1500)
    }

    // ====================== HUD: donuts + slider + chip ======================
    private fun bindQuickMenu(root: View) {
        donutGoalView = root.findViewById(R.id.donutGoal)
        seekBarGoalView = root.findViewById(R.id.seekBarGoal)
        textGoalValueView = root.findViewById(R.id.textGoalValue)

        donutTimeView = root.findViewById(R.id.donutTime)
        donutAverageView = root.findViewById(R.id.donutAverage)
        donutEtaView = root.findViewById(R.id.donutEta)

        tvShiftTimerView = root.findViewById(R.id.textViewShiftTimer)
        tvShiftStateChip = root.findViewById(R.id.textShiftStateChip)

        val tfMain = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val tfSub  = Typeface.create("sans-serif", Typeface.NORMAL)

        donutTimeView?.apply {
            setTypeface(tfMain, tfSub)
            setStrokeWidthDp(12f)
            setColors(
                track = Color.parseColor("#22000000"),
                progressC = Color.parseColor("#1976D2"),
                secondaryC = Color.parseColor("#8033B5E5"),
                haloC = Color.parseColor("#661976D2")
            )
        }
        donutGoalView?.apply {
            setTypeface(tfMain, tfSub)
            setStrokeWidthDp(12f)
            setColors(
                track = Color.parseColor("#22000000"),
                progressC = Color.parseColor("#2E7D32"),
                secondaryC = Color.parseColor("#8033B5E5"),
                haloC = Color.parseColor("#AA2E7D32")
            )
        }
        donutAverageView?.apply {
            setTypeface(tfMain, tfSub)
            setStrokeWidthDp(12f)
            setColors(
                track = Color.parseColor("#22000000"),
                progressC = Color.parseColor("#F57C00"),
                secondaryC = Color.parseColor("#80FFA000"),
                haloC = Color.parseColor("#66F57C00")
            )
        }
        donutEtaView?.apply {
            setTypeface(tfMain, tfSub)
            setStrokeWidthDp(12f)
            setColors(
                track = Color.parseColor("#22000000"),
                progressC = Color.parseColor("#7B1FA2"),
                secondaryC = Color.parseColor("#807B1FA2"),
                haloC = Color.parseColor("#667B1FA2")
            )
        }

        val store = goalStore ?: GoalStore(this).also { goalStore = it }

        seekBarGoalView?.max = 500
        val savedGoal = store.getGoalEuro().coerceIn(0, 500)
        goalOverrideEuro = savedGoal
        seekBarGoalView?.progress = savedGoal

        refreshGoalUI(savedGoal)
        refreshTimeAvgEta()
        updateShiftStateChip()

        seekBarGoalView?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                goalOverrideEuro = value
                refreshGoalUI(value)
                refreshTimeAvgEta()
                updateMenuViewShiftUI()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val v = seekBarGoalView?.progress ?: savedGoal
                goalOverrideEuro = v
                store.setGoalEuro(v)
                try {
                    val m = shiftSession.javaClass.methods.firstOrNull { it.name in listOf(
                        "setTargetEarnings","setTarget","updateTarget","setTargetEuro"
                    ) && it.parameterTypes.size == 1 }
                    m?.invoke(shiftSession, v.toDouble())
                } catch (_: Throwable) { }
            }
        })
    }

    private fun updateShiftStateChip() {
        val v = tvShiftStateChip ?: return
        when {
            !shiftSession.isActive -> {
                v.text = "Turno inativo"
                v.setBackgroundColor(0x803A3A3A.toInt())
                v.visibility = View.VISIBLE
            }
            shiftSession.isPaused -> {
                v.text = "Em pausa"
                v.setBackgroundColor(0x80F57C00.toInt())
                v.visibility = View.VISIBLE
            }
            else -> {
                v.text = "Ativo"
                v.setBackgroundColor(0x802E7D32.toInt())
                v.visibility = View.VISIBLE
            }
        }
    }

    private fun currentEarnings(): Double = (shiftSession.totalEarnings + earningsCorrection).coerceAtLeast(0.0)

    private fun refreshGoalUI(goalEuro: Int) {
        val donut = donutGoalView ?: return
        val tv = textGoalValueView
        val earnings = currentEarnings()
        val goalF = goalEuro.toDouble().coerceAtLeast(0.0)
        val progress = if (goalF <= 0.0) 0f else (earnings / goalF).toFloat().coerceIn(0f, 1f)

        tv?.text = currencyFormatter.format(goalF)

        donut.setCenterText(currencyFormatter.format(earnings), "de ${currencyFormatter.format(goalF)}")
        donut.setProgress(progress)

        if (earnings > goalF && goalF > 0.0) {
            val excessFrac = ((earnings - goalF) / goalF).toFloat().coerceAtMost(1f)
            donut.setHaloExcess(excessFrac)
        } else {
            donut.setHaloExcess(0f)
        }
    }

    private fun refreshTimeAvgEta() {
        val donutTime = donutTimeView ?: return
        val donutAvg  = donutAverageView ?: return
        val donutEta  = donutEtaView ?: return

        val decorrido = parseHmsToSeconds(tvShiftTimerView?.text?.toString().orEmpty()) ?: 0L

        val earnings = currentEarnings().coerceAtLeast(0.0)
        val avgPH = (shiftSession.getAveragePerHourValue() ?: 0.0).coerceAtLeast(0.0)
        val goalF = (goalOverrideEuro?.toDouble() ?: goalStore?.getGoalEuro()?.toDouble()
        ?: shiftSession.targetEarnings).coerceAtLeast(0.0)

        val remainingEur = (goalF - earnings).coerceAtLeast(0.0)

        val restanteSec: Long? = when {
            remainingEur <= 0.0 -> 0L
            avgPH > 0.0         -> ((remainingEur / avgPH) * 3600.0).toLong()
            else                -> null
        }

        val totalPrevisto = if (restanteSec != null) (decorrido + restanteSec).coerceAtLeast(decorrido) else decorrido
        val fracTempo = if (totalPrevisto > 0) decorrido.toFloat() / totalPrevisto else 0f

        if (restanteSec != null && totalPrevisto > 0) {
            donutTime.setCenterText(formatHms(decorrido), "→ ${formatHms(totalPrevisto)}")
            donutTime.setProgress(fracTempo)
        } else {
            donutTime.setCenterText(formatHms(decorrido), null)
            donutTime.setProgress(if (decorrido > 0) 0.05f else 0f)
        }

        val avgTarget = goodHourThreshold.toFloat()
        val avgCurrent = avgPH.toFloat()
        val ratio = if (avgTarget > 0f) (avgCurrent / avgTarget) else 0f

        val nf = NumberFormat.getNumberInstance(Locale("pt","PT")).apply {
            maximumFractionDigits = 1
            minimumFractionDigits = 0
        }
        donutAvg.setCenterText("${nf.format(avgCurrent)}€", if (avgTarget > 0f) "alvo ${nf.format(avgTarget)}€" else "—")
        donutAvg.setProgress(ratio.coerceIn(0f, 1f))
        donutAvg.setHaloExcess(if (ratio > 1f) (ratio - 1f).coerceAtMost(1f) else 0f)

        val etaText = when {
            remainingEur <= 0.0 -> getString(R.string.shift_target_reached)
            restanteSec == null -> "--:--"
            else -> try {
                val whenMs = System.currentTimeMillis() + restanteSec * 1000
                android.text.format.DateFormat.getTimeFormat(this).format(Date(whenMs))
            } catch (_: Exception) { "--:--" }
        }
        val sub = when {
            remainingEur <= 0.0 -> "—"
            restanteSec == null -> "aguardar dados"
            else -> "falta ${formatHms(restanteSec)}"
        }
        donutEta.setCenterText(etaText, sub)
        donutEta.setProgress(fracTempo)
    }

    private fun parseHmsToSeconds(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty() || !t.contains(":")) return null
        val parts = t.split(":")
        if (parts.size < 2) return null
        val h = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val m = (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
        val s = (parts.getOrNull(2)?.toLongOrNull() ?: 0L)
        return h*3600 + m*60 + s
    }
    private fun formatHms(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun maybeApplyTodayBaseEarnings() {
        val store = goalStore ?: return
        val base = store.getTodayInvoicedEuro()
        if (base <= 0.0) return

        val prefs = getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val last = prefs.getString(KEY_BASE_APPLIED_DATE, null)

        if (last != today) {
            shiftSession.addTripEarnings(base)
            prefs.edit().putString(KEY_BASE_APPLIED_DATE, today).apply()
            showOverlayBanner("Incluído já faturado hoje: +${currencyFormatter.format(base)}",
                OverlayView.BannerType.INFO, 1800)
            updateMenuViewShiftUI()
            updateShiftNotification()
        }
    }

    // ---------- util de assinatura ----------
    private fun createOfferSignature(offerData: OfferData): String {
        val v = offerData.value.replace(",",".")
        val pd = offerData.pickupDistance.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0"
        val td = offerData.tripDistance.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0"
        val pt = offerData.pickupDuration.toIntOrNull()?.toString() ?: "0"
        val tt = offerData.tripDuration.toIntOrNull()?.toString() ?: "0"
        return "v:$v|pd:$pd|td:$td|pt:$pt|tt:$tt"
    }

    // ===================== NEW: Faixa de gesto inferior =====================

    private fun buildFinishGestureLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        val screenH = Resources.getSystem().displayMetrics.heightPixels
        val screenW = Resources.getSystem().displayMetrics.widthPixels
        val heightPx = max((screenH * 0.15f).toInt(), (60 * resources.displayMetrics.density).toInt())

        return WindowManager.LayoutParams(
            screenW,
            heightPx,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureFinishGestureView() {
        if (finishGestureView != null) return
        finishGestureView = View(this).apply {
            // invisível; para depurar, usa alpha = 0.05f e um background
            alpha = 0f
            isClickable = true
            isFocusable = false

            var downX = 0f
            var downY = 0f
            var downT = 0L
            val tapTimeout = ViewConfiguration.getTapTimeout().toLong()
            val maxTapDistance = resources.displayMetrics.density * 24 // ~24dp
            val swipeMinDistance = resources.displayMetrics.density * 48 // ~48dp

            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY; downT = SystemClock.uptimeMillis()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dt = SystemClock.uptimeMillis() - downT
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        val dist = hypot(dx.toDouble(), dy.toDouble())

                        val isTap = (dt <= tapTimeout + 100) && (dist <= maxTapDistance)
                        val isSwipeL2R = (dx >= swipeMinDistance) && (abs(dy) <= swipeMinDistance)

                        if (isTap || isSwipeL2R) {
                            tryFinishTripByGesture(isSwipeL2R)
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
        }
    }

    private fun tryFinishTripByGesture(wasSwipe: Boolean) {
        if (!tripTracker.isTracking) {
            showOverlayBanner("Sem viagem ativa", OverlayView.BannerType.WARNING, 1200)
            return
        }
        // NEW: confirmação em vez de finalizar logo
        showConfirmCloseOverlay()
    }

    private fun addFinishGestureOverlay() {
        val wm = windowManager ?: return
        ensureFinishGestureView()
        val v = finishGestureView ?: return
        val lp = buildFinishGestureLayoutParams()
        try {
            if (!isFinishGestureAdded) {
                wm.addView(v, lp)
                isFinishGestureAdded = true
            } else {
                wm.updateViewLayout(v, lp)
            }
        } catch (_: Exception) {
            isFinishGestureAdded = false
        }
    }

    private fun hideFinishGestureOverlay() {
        val wm = windowManager ?: return
        val v  = finishGestureView ?: return
        if (!isFinishGestureAdded) return
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
        isFinishGestureAdded = false
    }

    /** Liga/desliga a faixa consoante:
     *  - há tracking ativo
     *  - o serviço de Acessibilidade disse que a keyword (Concluir) está visível
     */
    private fun updateFinishGestureVisibility() {
        if (tripTracker.isTracking && finishKeywordVisible) {
            addFinishGestureOverlay()
        } else {
            hideFinishGestureOverlay()
            hideConfirmCloseOverlay() // se a keyword desaparecer, fecha a confirmação também
        }
    }
    // =======================================================================

    // ===================== NEW: Pop-up de confirmação =======================
    private fun buildConfirmCloseLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        val density = resources.displayMetrics.density
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (16 * density).toInt()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showConfirmCloseOverlay() {
        val wm = windowManager ?: return

        if (isConfirmCloseAdded && confirmCloseView != null) {
            // já está visível: renova timeout
            confirmDismissRunnable?.let { confirmHandler.removeCallbacks(it) }
            confirmDismissRunnable = Runnable { hideConfirmCloseOverlay() }
            confirmHandler.postDelayed(confirmDismissRunnable!!, 4000L)
            return
        }

        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val btnPadH = (8 * density).toInt()
        val btnPadV = (6 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xCC000000.toInt())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = 8f
        }

        val tvMsg = TextView(this).apply {
            text = "Fechar acompanhamento?"
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, (12 * density).toInt(), 0)
        }

        val btnCancel = TextView(this).apply {
            text = "Cancelar"
            setTextColor(Color.WHITE)
            setPadding(btnPadH, btnPadV, btnPadH, btnPadV)
            setOnClickListener { hideConfirmCloseOverlay() }
        }

        val sep = View(this).apply {
            setBackgroundColor(0x55FFFFFF)
            val h = (16 * density).toInt()
            layoutParams = LinearLayout.LayoutParams((1 * density).toInt(), h).apply {
                setMargins((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            }
        }

        val btnOk = TextView(this).apply {
            text = "Fechar"
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(btnPadH, btnPadV, btnPadH, btnPadV)
            setOnClickListener {
                hideConfirmCloseOverlay()
                handleStopTracking()
            }
        }

        container.addView(tvMsg)
        container.addView(btnCancel)
        container.addView(sep)
        container.addView(btnOk)

        val lp = buildConfirmCloseLayoutParams()
        try {
            wm.addView(container, lp)
            confirmCloseView = container
            isConfirmCloseAdded = true
            confirmDismissRunnable = Runnable { hideConfirmCloseOverlay() }
            confirmHandler.postDelayed(confirmDismissRunnable!!, 4000L)
        } catch (_: Exception) {
            isConfirmCloseAdded = false
            confirmCloseView = null
        }
    }

    private fun hideConfirmCloseOverlay() {
        val wm = windowManager ?: return
        val v = confirmCloseView ?: return
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
        confirmCloseView = null
        isConfirmCloseAdded = false
        confirmDismissRunnable?.let { confirmHandler.removeCallbacks(it) }
        confirmDismissRunnable = null
    }
    // =======================================================================
}
