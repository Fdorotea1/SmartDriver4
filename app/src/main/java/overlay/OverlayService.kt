package com.example.smartdriver.overlay

import android.graphics.Typeface
import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.*
import android.util.Log
import android.view.*
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
import com.example.smartdriver.data.GoalStore
import com.example.smartdriver.ui.widgets.DonutProgressView
import com.example.smartdriver.utils.*
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

// Tag de logs
private const val TAG_HOUR = "SD.HourColor"
private const val TAG_HEALTH = "SD.Health"

class OverlayService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val CHANNEL_NAME = "Overlay Service"

        // Rearm de serviços de deteção
        const val ACTION_REQ_REARM_FINISH_WATCH = "com.example.smartdriver.REQ_REARM_FINISH_WATCH"
        const val ACTION_REQ_RESTART_CAPTURE    = "com.example.smartdriver.screencap.REQ_RESTART_CAPTURE"

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

        // (Opcional) Sinalizador genérico de mudança no histórico
        const val ACTION_HISTORY_CHANGED = "com.example.smartdriver.overlay.HISTORY_CHANGED"
        const val ACTION_HISTORY_TRIP_REMOVED = "com.example.smartdriver.overlay.HISTORY_TRIP_REMOVED"

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

        // ---------- Broadcast do serviço de Acessibilidade ----------
        const val ACTION_EVT_FINISH_KEYWORD_VISIBLE = "com.example.smartdriver.EVT_FINISH_KEYWORD_VISIBLE"
        const val EXTRA_FINISH_VISIBLE = "extra_finish_visible"

        // ---- DEBUG: forçar VPH (apenas em build debug) ----
        const val ACTION_DEBUG_FORCE_VPH = "com.example.smartdriver.overlay.DEBUG_FORCE_VPH"
        const val EXTRA_DEBUG_VPH = "extra_debug_vph"
        // ----------------------------------------------------

        // ---- NOVAS ações de comutação UI (bolha <-> ícone) ----
        const val ACTION_SWITCH_TO_ICON = "com.example.smartdriver.overlay.SWITCH_TO_ICON"
        const val ACTION_SWITCH_TO_BUBBLE = "com.example.smartdriver.overlay.SWITCH_TO_BUBBLE"
        // -------------------------------------------------------

        @JvmStatic val isRunning = AtomicBoolean(false)
    }

    private lateinit var shiftSession: ShiftSession
    private lateinit var tripTracker: TripTracker

    private var windowManager: WindowManager? = null
    private var mainOverlayView: OverlayView? = null
    private var trackingOverlayView: TrackingOverlayView? = null
    private var dropZoneView: ImageView? = null
    private var quickMenuView: MenuView? = null
    private var floatingIconView: FloatingIconView? = null

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

    // --- HUD donuts / slider / labels (menu rápido) ---
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

    // ---------- Popup de confirmação ----------
    @Volatile private var finishKeywordVisible: Boolean = false
    private var lastFinishVisibleState: Boolean = false
    private var finishConfirmDialog: AlertDialog? = null
    private var stopBypassConfirmOnce: Boolean = false

    // ====== Watchdog / Auto-Healing ======
    private val healthHandler = Handler(Looper.getMainLooper())
    private var healthRunnable: Runnable? = null
    private var isFinishReceiverRegistered = false

    private var lastActivityMs: Long = SystemClock.elapsedRealtime()
    private var lastFinishBroadcastMs: Long = 0L
    private var lastOverlayShownMs: Long = 0L
    private var lastHealMs: Long = 0L
    private var lastDateYmd: String = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private val HEALTH_TICK_MS = 30_000L
    private val STALL_MS = 5 * 60_000L
    private val HEAL_THROTTLE_MS = 2 * 60_000L

    private val finishKeywordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_EVT_FINISH_KEYWORD_VISIBLE) {
                val visible = intent.getBooleanExtra(EXTRA_FINISH_VISIBLE, false)
                finishKeywordVisible = visible
                lastFinishBroadcastMs = SystemClock.elapsedRealtime()
                touchActivity("finishKeyword")
                updateFinishPromptForState(edgeOnly = true)
            }
        }
    }

    // ---------- NOVO: suporte a testes do €/h ----------
    private var lastElapsedSecondsFromTrip: Long = 0L
    private var debugForcedVph: Double? = null
    private fun Double.f1(): String = String.format(Locale.US, "%.1f", this)

    // Helper que substitui BuildConfig.DEBUG
    private val isDebugBuild: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    // ----------------------------------------------------

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
        setupHealthMonitor()
        bannerManager = BannerManager(this)
        goalStore = GoalStore(this)

        // Recuperar início do turno persistido
        val prefs = getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
        shiftStartTimeMs = prefs.getLong(KEY_SHIFT_START_MS, 0L)
        if (shiftSession.isActive && shiftStartTimeMs == 0L) {
            shiftStartTimeMs = System.currentTimeMillis()
            prefs.edit().putLong(KEY_SHIFT_START_MS, shiftStartTimeMs).apply()
        }

        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_overlay_ready)))
        addFloatingIconOverlay()
        if (shiftSession.isActive && !shiftSession.isPaused) startShiftTimer()

        // Receiver do “keyword visível”
        safeRegisterFinishReceiver()

        updateFinishPromptForState(edgeOnly = false)
        updateIconPulseColor()
    }

    private fun setupTripTracker() {
        tripTracker = TripTracker(this, object : TripTracker.Listener {
            override fun onTripUpdate(
                elapsedSeconds: Long,
                currentVph: Double?,
                rating: IndividualRating
            ) {
                lastElapsedSecondsFromTrip = elapsedSeconds
                touchActivity("tripUpdate")

                val vph = debugForcedVph ?: currentVph
                val hourRating = when {
                    vph == null -> IndividualRating.UNKNOWN
                    vph >= goodHourThreshold -> IndividualRating.GOOD
                    vph <= poorHourThreshold -> IndividualRating.POOR
                    else -> IndividualRating.MEDIUM
                }

                if (isDebugBuild) {
                    val vphTxt = vph?.f1() ?: "null"
                    Log.d(
                        TAG_HOUR,
                        "onTripUpdate t=${elapsedSeconds}s vph=$vphTxt | thr poor<=${poorHourThreshold.f1()} < MEDIUM < ${goodHourThreshold.f1()}<=good -> $hourRating"
                    )
                }

                trackingOverlayView?.updateRealTimeData(vph, hourRating, elapsedSeconds)
                updateIconPulseColor()
            }

            override fun onTripFinished(historyEntry: TripHistoryEntry) {
                OfferManager.getInstance(applicationContext).setTrackingActive(false)

                val effective = historyEntry.effectiveValue ?: 0.0
                shiftSession.addTripEarnings(effective)

                recalcAvgAfterHistoryChange()

                // Esconde bolha e volta ao ícone no MESMO sítio
                val tx = trackingLayoutParams.x
                val ty = trackingLayoutParams.y
                hideTrackingOverlay()
                removeDropZoneView()
                hideOfferIndicator()
                hideFinishConfirmPrompt()
                showFloatingIconAt(tx, ty)

                updateMenuViewShiftUI()
                updateShiftNotification()
                updateIconPulseColor()

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

                // Voltar ao ícone no mesmo sítio
                val tx = trackingLayoutParams.x
                val ty = trackingLayoutParams.y
                hideTrackingOverlay()
                removeDropZoneView()
                hideOfferIndicator()
                hideFinishConfirmPrompt()
                showFloatingIconAt(tx, ty)

                updateShiftNotification()
                updateIconPulseColor()

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

                        recalcAvgAfterHistoryChange()

                        startShiftTimer(); updateMenuViewShiftUI(); updateShiftNotification()
                        showOverlayBanner("Turno iniciado", OverlayView.BannerType.INFO, 1500)
                    } else {
                        Toast.makeText(this, getString(R.string.toast_invalid_target_value), Toast.LENGTH_LONG).show()
                    }
                } else handleToggleShiftState()
                updateIconPulseColor()
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

            // UI switches (novo)
            ACTION_SWITCH_TO_ICON -> switchBubbleToIconUiOnly()
            ACTION_SWITCH_TO_BUBBLE -> switchIconToBubbleUiOnly()

            ACTION_HISTORY_CHANGED,
            ACTION_HISTORY_TRIP_REMOVED -> {
                recalcAvgAfterHistoryChange()
                updateIconPulseColor()
                return START_REDELIVER_INTENT
            }

            // ---------- DEBUG para forçar VPH ----------
            ACTION_DEBUG_FORCE_VPH -> {
                if (isDebugBuild) {
                    val forcedFromDouble = intent.getDoubleExtra(EXTRA_DEBUG_VPH, Double.NaN)
                    val forcedFromString = intent.getStringExtra(EXTRA_DEBUG_VPH)
                        ?.replace(",", ".")?.toDoubleOrNull()
                    debugForcedVph = when {
                        !forcedFromDouble.isNaN() -> forcedFromDouble
                        forcedFromString != null -> forcedFromString
                        else -> null
                    }

                    val vph = debugForcedVph
                    val ratingNow = when {
                        vph == null -> IndividualRating.UNKNOWN
                        vph >= goodHourThreshold -> IndividualRating.GOOD
                        vph <= poorHourThreshold -> IndividualRating.POOR
                        else -> IndividualRating.MEDIUM
                    }

                    Log.d(
                        TAG_HOUR,
                        "DEBUG_FORCE_VPH vph=${vph?.f1() ?: "null"} | thr poor<=${poorHourThreshold.f1()} < MEDIUM < ${goodHourThreshold.f1()}<=good -> $ratingNow"
                    )

                    trackingOverlayView?.updateRealTimeData(vph, ratingNow, lastElapsedSecondsFromTrip)
                    showOverlayBanner(
                        "DEBUG: VPH ${if (vph != null) vph.f1() + "€/h" else "limpo"}",
                        OverlayView.BannerType.INFO,
                        1200
                    )
                    updateIconPulseColor()
                }
                return START_REDELIVER_INTENT
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        if (tripTracker.isTracking) tripTracker.discard()
        shiftSession.onServiceDestroyed()
        removeAllOverlays()
        isRunning.set(false)
        safeUnregisterFinishReceiver()
        hideFinishConfirmPrompt()

        // parar watchdog / timers
        healthRunnable?.let { healthHandler.removeCallbacks(it) }
        shiftTimerRunnable?.let { shiftTimerHandler.removeCallbacks(it) }
        overlayFadeHandler.removeCallbacksAndMessages(fadeToken)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========================= Handlers de ações =========================

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

        // UI: esconder ícone e menu
        hideMainOverlay()
        removeQuickMenuOverlay()
        removeFloatingIconOverlay()

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

        val hourFromEval = extractHourRatingFrom(eval)
        val baseHour = hourFromEval ?: computePlannedHourRatingFrom(offer)
        trackingOverlayView?.setInitialHourRatingFromSemaphore(baseHour)
        if (isDebugBuild) Log.d(TAG_HOUR, "€/h prev. rating definido a partir de ${if (hourFromEval != null) "EVAL" else "COMPUTE"}: $baseHour")

        tripTracker.start(offer, eval)
        OfferManager.getInstance(applicationContext).setTrackingActive(true)
        hideOfferIndicator()
        updateShiftNotification()
        updateIconPulseColor()
        showOverlayBanner("Tracking iniciado", OverlayView.BannerType.INFO, 1200)
        sendBroadcast(Intent(ACTION_EVT_TRACKING_STARTED))

        updateFinishPromptForState(edgeOnly = false)

        // garante ticker ativo
        startShiftTimer()
        touchActivity("startTrackingFromLast")
    }

    private fun handleStartTracking(intent: Intent?) {
        if (isTrackingOverlayAdded) {
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
            removeFloatingIconOverlay()

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

            val hourFromEval = extractHourRatingFrom(initialEval)
            val baseHour = hourFromEval ?: computePlannedHourRatingFrom(offerData)
            trackingOverlayView?.setInitialHourRatingFromSemaphore(baseHour)
            if (isDebugBuild) Log.d(TAG_HOUR, "€/h prev. rating definido a partir de ${if (hourFromEval != null) "EVAL" else "COMPUTE"}: $baseHour")

            tripTracker.start(offerData, initialEval)
            OfferManager.getInstance(applicationContext).setTrackingActive(true)
            hideOfferIndicator()
            updateShiftNotification()
            updateIconPulseColor()
            showOverlayBanner("Tracking iniciado", OverlayView.BannerType.INFO, 1500)
            sendBroadcast(Intent(ACTION_EVT_TRACKING_STARTED))

            updateFinishPromptForState(edgeOnly = false)
            startShiftTimer()
            touchActivity("startTracking")
        }
    }

    private fun handleStopTracking() {
        if (!stopBypassConfirmOnce) {
            if (finishConfirmDialog?.isShowing == true) return
            showFinishConfirmPrompt()
            return
        }
        stopBypassConfirmOnce = false
        tripTracker.stopAndSave()
        OfferManager.getInstance(applicationContext).setTrackingActive(false)
        removeDropZoneView()
        hideFinishConfirmPrompt()
        updateIconPulseColor()
        sendBroadcast(Intent(ACTION_EVT_TRACKING_ENDED))
    }

    private fun handleDiscardCurrentTracking() {
        tripTracker.discard()
        OfferManager.getInstance(applicationContext).setTrackingActive(false)
        removeDropZoneView()
        hideFinishConfirmPrompt()
        // voltar ao ícone no mesmo sítio
        val tx = trackingLayoutParams.x
        val ty = trackingLayoutParams.y
        hideTrackingOverlay()
        showFloatingIconAt(tx, ty)
        updateIconPulseColor()
        sendBroadcast(Intent(ACTION_EVT_TRACKING_ENDED))
    }

    private fun handleHideOverlay() {
        if (tripTracker.isTracking) tripTracker.stopAndSave()
        OfferManager.getInstance(applicationContext).setTrackingActive(false)
        removeAllOverlays()
        updateShiftNotification()
        updateIconPulseColor()
        sendBroadcast(Intent(ACTION_EVT_TRACKING_ENDED))
    }

    private fun handleShowOverlay(intent: Intent?) {
        val evalResult = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java)
        val offerData = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java)
        if (evalResult != null && offerData != null) {
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

            lastOverlayShownMs = SystemClock.elapsedRealtime()
            touchActivity("showOverlay")
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
        the@ run {
            val yDp = if (yFromIntent >= 0) yFromIntent else SettingsActivity.getOverlayYOffsetDp(this)
            val density = resources.displayMetrics.density
            mainLayoutParams.y = (yDp * density).toInt()
            baseOverlayYOffsetPx = mainLayoutParams.y
        }
        updateMainOverlayY()

        if (isMainOverlayAdded && mainOverlayView != null) {
            try { windowManager?.updateViewLayout(mainOverlayView, mainLayoutParams) } catch (_: Exception) {}
        }

        updateShiftStateChip()
        updateFinishPromptForState(edgeOnly = false)
        refreshTimeAvgEta()
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
            val target = (goalOverrideEuro ?: goalStore?.getGoalEuro() ?: 130).toDouble()
            shiftSession.start(target)

            earningsCorrection = 0.0
            maybeApplyTodayBaseEarnings()

            shiftStartTimeMs = System.currentTimeMillis()
            getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_SHIFT_START_MS, shiftStartTimeMs).apply()

            hasShownTargetReachedBanner = false

            recalcAvgAfterHistoryChange()

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
        updateIconPulseColor()
    }

    private fun handleEndShift() {
        if (shiftSession.isActive) {
            shiftSession.end(saveSummary = true)
            hasShownTargetReachedBanner = false
            earningsCorrection = 0.0

            shiftStartTimeMs = 0L
            getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_SHIFT_START_MS, 0L).apply()

            decisionAvgPerHourRef = null
            refreshTimeAvgEta()

            updateMenuViewShiftUI(); updateShiftNotification()
            updateShiftStateChip()
            updateIconPulseColor()
            showOverlayBanner("Turno terminado", OverlayView.BannerType.INFO, 1500)
        }
    }

    // ---------- LayoutParams ----------
    private fun initializeAllLayoutParams() {
        val density = resources.displayMetrics.density
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // >>> Flags comuns para TODOS (mesmo referencial)
        val commonFlags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        val yDp = SettingsActivity.getOverlayYOffsetDp(this)

        mainLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType, commonFlags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = ((yDp + 28) * density).toInt()
        }
        baseOverlayYOffsetPx = mainLayoutParams.y

        trackingLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType, commonFlags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (10 * density).toInt()
            y = (80 * density).toInt()
        }

        dropZoneLayoutParams = WindowManager.LayoutParams(
            (72 * density).toInt(), (72 * density).toInt(),
            overlayType, commonFlags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }

        menuLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType, commonFlags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (10 * density).toInt()
            y = (80 * density).toInt()
            width = (360 * density).toInt()
        }

        // Ícone flutuante (tamanho “tipo Uber/Bolt”: 88dp)
        val ICON_DP = 88
        floatingIconLayoutParams = WindowManager.LayoutParams(
            (ICON_DP * density).toInt(), (ICON_DP * density).toInt(),
            overlayType, commonFlags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (10 * density).toInt()
            y = (36 * density).toInt()
        }
    }

    private fun removeAllOverlays() {
        hideMainOverlay()
        hideTrackingOverlay()
        removeDropZoneView()
        removeQuickMenuOverlay()
        removeFloatingIconOverlay()
        hideOfferIndicator()
        hideFinishConfirmPrompt()
    }

    private fun showMainOverlay(eR: EvaluationResult, oD: OfferData) {
        val wm = windowManager ?: return
        if (mainOverlayView == null) {
            mainOverlayView = OverlayView(this)
            applyAppearanceSettingsToView(mainOverlayView)
        }

        val tPercent = SettingsActivity.getTransparency(this)
        baseOverlayAlpha = (1.0f - (tPercent / 100f)).coerceIn(0f, 1f)
        fadedAlpha = (baseOverlayAlpha * 0.25f).coerceAtLeast(0.08f)

        mainOverlayView?.alpha = baseOverlayAlpha
        mainOverlayView?.updateState(eR, oD)

        mainOverlayView?.setOnClickListener {
            overlayFadeHandler.removeCallbacksAndMessages(fadeToken)
            mainOverlayView?.alpha = baseOverlayAlpha
            isMainOverlayTranslucent = false
            hideOfferIndicator()
            startOverlayFadeTimer()
            touchActivity("overlayClick")
        }
        mainOverlayView?.setOnTouchListener { _, _ ->
            overlayFadeHandler.removeCallbacksAndMessages(fadeToken)
            mainOverlayView?.alpha = baseOverlayAlpha
            isMainOverlayTranslucent = false
            hideOfferIndicator()
            startOverlayFadeTimer()
            touchActivity("overlayTouch")
            false
        }

        try {
            if (!isMainOverlayAdded) {
                wm.addView(mainOverlayView, mainLayoutParams)
                isMainOverlayAdded = true
            } else {
                wm.updateViewLayout(mainOverlayView, mainLayoutParams)
            }
        } catch (_: Exception) {
            isMainOverlayAdded = false
            mainOverlayView = null
        }

        updateMainOverlayY()
    }

    private fun hideMainOverlay() {
        hasActiveOffer = false
        isMainOverlayTranslucent = false
        hideOfferIndicator()

        if (isMainOverlayAdded && mainOverlayView != null && windowManager != null) {
            try {
                windowManager?.removeViewImmediate(mainOverlayView)
            } catch (_: Exception) {
            } finally {
                isMainOverlayAdded = false
                mainOverlayView = null
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
            floatingIconView = FloatingIconView(this).apply {
                setImageResource(R.drawable.smartdriver)
                // NÃO usar background do FAB (era quadrado):
                // setBackgroundResource(R.drawable.fab_background)
                background = null
                scaleType = ScaleType.CENTER_INSIDE
                setOnTouchListener(createFloatingIconTouchListener())
                setPulseEnabled(true)
            }
        }
        try {
            windowManager?.addView(floatingIconView, floatingIconLayoutParams)
            isFloatingIconAdded = true
            updateIconPulseColor()
        } catch (_: Exception) {
            isFloatingIconAdded = false
            floatingIconView = null
        }
    }

    private fun removeFloatingIconOverlay() {
        if (isFloatingIconAdded && floatingIconView != null && windowManager != null) {
            try { windowManager?.removeViewImmediate(floatingIconView) } catch (_: Exception) {} finally {
                isFloatingIconAdded = false; floatingIconView = null
            }
        }
    }

    private fun showFloatingIconAt(x: Int, y: Int) {
        floatingIconLayoutParams.x = x
        floatingIconLayoutParams.y = y
        if (!isFloatingIconAdded) addFloatingIconOverlay()
        try { if (isFloatingIconAdded) windowManager?.updateViewLayout(floatingIconView, floatingIconLayoutParams) } catch (_: Exception) {}
        updateIconPulseColor()
        bringFloatingIconToFront()
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

    // Cor do halo conforme estado
    private fun updateIconPulseColor() {
        val icon = floatingIconView ?: return
        // Verde se tracking ativo, vermelho caso contrário
        val color = if (tripTracker.isTracking) Color.parseColor("#2E7D32") else Color.parseColor("#C62828")
        icon.setPulseColor(color)
    }

    // Alternâncias UI-only
    private fun switchBubbleToIconUiOnly() {
        if (!tripTracker.isTracking) return
        val tx = trackingLayoutParams.x
        val ty = trackingLayoutParams.y
        hideTrackingOverlay()
        showFloatingIconAt(tx, ty)
        removeDropZoneView()
        hideFinishConfirmPrompt()
    }

    private fun switchIconToBubbleUiOnly() {
        if (!tripTracker.isTracking) {
            Toast.makeText(this, "Sem viagem em andamento", Toast.LENGTH_SHORT).show()
            return
        }
        // Copiar pos do ícone
        trackingLayoutParams.x = floatingIconLayoutParams.x
        trackingLayoutParams.y = floatingIconLayoutParams.y
        removeQuickMenuOverlay()
        removeFloatingIconOverlay()

        // Recriar bolha (dados iniciais podem ser do último semáforo)
        val o = lastShownOffer
        val e = lastShownEval
        val iV = o?.calculateProfitability()
        val iD = o?.calculateTotalDistance()?.takeIf { (it ?: 0.0) > 0.0 }
        val iDu = o?.calculateTotalTimeMinutes()?.takeIf { (it ?: 0) > 0 }
        val oV = o?.value
        val kmR = e?.kmRating ?: IndividualRating.UNKNOWN
        val cB  = e?.combinedBorderRating ?: BorderRating.GRAY
        showTrackingOverlay(iV, iD, iDu, oV, kmR, cB)
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

        val dm = resources.displayMetrics
        val density = dm.density
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val margin = (12 * density).toInt()

        // largura definida nos LP (ex.: 360dp) ou fallback
        val menuW = if (menuLayoutParams.width > 0) menuLayoutParams.width else (360 * density).toInt()
        val estH = (320 * density).toInt() // estimativa inicial antes de medir

        // X centrado; Y fixo em baixo (com margem)
        val x = ((screenW - menuW) / 2).coerceIn(margin, screenW - margin - menuW)
        val y = (screenH - estH - margin).coerceAtLeast(margin)

        menuLayoutParams.x = x
        menuLayoutParams.y = y

        try {
            windowManager?.addView(quickMenuView, menuLayoutParams)
            isQuickMenuAdded = true

            // Garante que o ícone fica por cima
            bringFloatingIconToFront()

            // Ajuste fino após medição real do layout
            quickMenuView?.post {
                val realH = quickMenuView?.height ?: estH
                val fixY = (screenH - realH - margin).coerceAtLeast(margin)
                if (menuLayoutParams.y != fixY) {
                    menuLayoutParams.y = fixY
                    try { windowManager?.updateViewLayout(quickMenuView, menuLayoutParams) } catch (_: Exception) {}
                }
                bringFloatingIconToFront()
            }
        } catch (_: Exception) {
            isQuickMenuAdded = false
            quickMenuView = null
        }
    }

    private fun removeQuickMenuOverlay() {
        if (isQuickMenuAdded && quickMenuView != null && windowManager != null) {
            try { windowManager?.removeViewImmediate(quickMenuView) } catch (_: Exception) { }
            finally {
                isQuickMenuAdded = false
                quickMenuView = null
                donutGoalView = null; seekBarGoalView = null; textGoalValueView = null
                donutTimeView = null; donutAverageView = null; donutEtaView = null
                tvShiftTimerView = null; tvShiftStateChip = null
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingIconTouchListener(): View.OnTouchListener {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var downTs = 0L
        var isDrag = false

        return View.OnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = floatingIconLayoutParams.x
                    startY = floatingIconLayoutParams.y
                    downRawX = e.rawX
                    downRawY = e.rawY
                    downTs = System.currentTimeMillis()
                    isDrag = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dX = e.rawX - downRawX
                    val dY = e.rawY - downRawY
                    if (kotlin.math.abs(dX) > touchSlop || kotlin.math.abs(dY) > touchSlop) isDrag = true
                    if (isDrag) {
                        val screenW = resources.displayMetrics.widthPixels
                        val screenH = resources.displayMetrics.heightPixels
                        val newX = (startX + dX.toInt()).coerceIn(0, screenW - v.width)
                        val newY = (startY + dY.toInt()).coerceIn(0, screenH - v.height)
                        if (newX != floatingIconLayoutParams.x || newY != floatingIconLayoutParams.y) {
                            floatingIconLayoutParams.x = newX
                            floatingIconLayoutParams.y = newY
                            try { if (isFloatingIconAdded) windowManager?.updateViewLayout(floatingIconView, floatingIconLayoutParams) } catch (_: Exception) {}
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val pressDur = System.currentTimeMillis() - downTs
                    val isLong = !isDrag && pressDur > ViewConfiguration.getLongPressTimeout().toLong()

                    if (isLong) {
                        // long-press: trocar para a bolha se estiver a acompanhar viagem
                        if (tripTracker.isTracking) {
                            switchIconToBubbleUiOnly()
                        } else {
                            Toast.makeText(this, "Sem viagem em andamento", Toast.LENGTH_SHORT).show()
                        }
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    } else if (!isDrag && pressDur < ViewConfiguration.getTapTimeout().toLong()) {
                        // tap: abre/fecha menu
                        if (!isQuickMenuAdded) handleShowQuickMenu() else handleDismissMenu()
                        v.performClick()
                    }
                    // manter o ícone por cima, mas só no final (não durante o drag)
                    bringFloatingIconToFront()
                    isDrag = false
                    true
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

                // Voltar ao ícone no mesmo sítio
                val tx = trackingLayoutParams.x
                val ty = trackingLayoutParams.y
                removeDropZoneView()
                hideFinishConfirmPrompt()
                hideTrackingOverlay()
                showFloatingIconAt(tx, ty)

                updateIconPulseColor()

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

            val workedHms = shiftSession.getFormattedWorkedTime()
            val workedSec = parseHmsToSeconds(workedHms) ?: 0L
            val avgNow = effectiveAvgPerHour(workedSec, currentEarnings())
            val nfAvg = NumberFormat.getNumberInstance(Locale("pt","PT")).apply {
                maximumFractionDigits = 1; minimumFractionDigits = 1
            }
            val avgLabel = if (workedSec > 0L) nfAvg.format(avgNow) + " €/h" else "-- €/h"
            menu.updateShiftAverage(avgLabel)

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

    // === ETA usa a média de referência (snapshot) ou a média efetiva como fallback ===
    private fun calculateAndFormatExpectedEndTime(): String {
        if (!shiftSession.isActive) return getString(R.string.expected_end_time_placeholder)

        val workedHms = shiftSession.getFormattedWorkedTime()
        val workedSec = parseHmsToSeconds(workedHms) ?: 0L
        val refAvgPH = (decisionAvgPerHourRef ?: effectiveAvgPerHour(workedSec, currentEarnings()))
        if (refAvgPH <= 0.0) return getString(R.string.expected_end_time_placeholder)

        val target = (goalOverrideEuro?.toDouble() ?: shiftSession.targetEarnings)
        val remaining = (target - currentEarnings())
        if (remaining <= 0.0) return getString(R.string.shift_target_reached)

        val ms = ((remaining / refAvgPH) * 3_600_000.0).toLong()
        if (ms < 0) return getString(R.string.shift_target_reached)
        return try { android.text.format.DateFormat.getTimeFormat(this).format(Date(System.currentTimeMillis() + ms)) }
        catch (_: Exception) { "--:--" }
    }

    // --------- Saneamento + logs dos thresholds ---------
    private fun loadTrackingThresholds() {
        try {
            goodHourThreshold = SettingsActivity.getGoodHourThreshold(this)
            poorHourThreshold = SettingsActivity.getPoorHourThreshold(this)
        } catch (_: Exception) {
            goodHourThreshold = 15.0
            poorHourThreshold = 10.0
        }

        if (poorHourThreshold >= goodHourThreshold) {
            val oldPoor = poorHourThreshold
            val oldGood = goodHourThreshold
            poorHourThreshold = minOf(oldPoor, oldGood) - 0.05
            goodHourThreshold = maxOf(oldPoor, oldGood) + 0.05
            Log.w(TAG_HOUR, "Thresholds invertidos/colados. Corrigido para poor=${poorHourThreshold.f1()} / good=${goodHourThreshold.f1()} (originais: poor=${oldPoor.f1()}, good=${oldGood.f1()})")
        } else {
            Log.d(TAG_HOUR, "Thresholds: poor<=${poorHourThreshold.f1()} < MEDIUM < ${goodHourThreshold.f1()}<=good")
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
            touchActivity("queuedOfferTap")
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
        if (delta == 0.0) {
            recalcAvgAfterHistoryChange()
            return
        }

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

        recalcAvgAfterHistoryChange()

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

        val workedHmsFromUi = tvShiftTimerView?.text?.toString().orEmpty()
        val workedHms = if (workedHmsFromUi.isNotBlank()) workedHmsFromUi else shiftSession.getFormattedWorkedTime()
        val decorrido = parseHmsToSeconds(workedHms) ?: 0L

        val earnings = currentEarnings().coerceAtLeast(0.0)
        val avgPHRef = ((decisionAvgPerHourRef) ?: effectiveAvgPerHour(decorrido, earnings)).coerceAtLeast(0.0)

        val goalF = (goalOverrideEuro?.toDouble() ?: goalStore?.getGoalEuro()?.toDouble()
        ?: shiftSession.targetEarnings).coerceAtLeast(0.0)

        val remainingEur = (goalF - earnings).coerceAtLeast(0.0)

        val restanteSec: Long? = when {
            remainingEur <= 0.0 -> 0L
            avgPHRef > 0.0      -> ((remainingEur / avgPHRef) * 3600.0).toLong()
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
        val avgCurrent = avgPHRef.toFloat()
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

    // €/h efetivo = ganhos visíveis (inclui corrections) / horas trabalhadas
    private fun effectiveAvgPerHour(decorridoSeconds: Long, earningsEuro: Double): Double {
        if (decorridoSeconds <= 0L) return 0.0
        val hours = decorridoSeconds / 3600.0
        return if (hours > 0.0) (earningsEuro / hours).coerceAtLeast(0.0) else 0.0
    }

    /** Recalcula snapshot do donut €/h e refresca UI/notificação, com base em ganhos visíveis. */
    private var decisionAvgPerHourRef: Double? = null
    private fun recalcAvgAfterHistoryChange() {
        val workedHms = shiftSession.getFormattedWorkedTime()
        val workedSec = parseHmsToSeconds(workedHms) ?: 0L
        decisionAvgPerHourRef = effectiveAvgPerHour(workedSec, currentEarnings())

        refreshTimeAvgEta()
        updateMenuViewShiftUI()
        updateShiftNotification()

        if (shiftSession.isActive && !shiftSession.isPaused) startShiftTimer()
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

            recalcAvgAfterHistoryChange()

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

    // ===================== Popup de confirmação =====================

    private fun updateFinishPromptForState(edgeOnly: Boolean) {
        val shouldShow = tripTracker.isTracking && finishKeywordVisible

        if (edgeOnly) {
            if (shouldShow && !lastFinishVisibleState) {
                showFinishConfirmPrompt()
            } else if (!shouldShow && lastFinishVisibleState) {
                hideFinishConfirmPrompt()
            }
            lastFinishVisibleState = shouldShow
        } else {
            lastFinishVisibleState = shouldShow
            if (shouldShow) showFinishConfirmPrompt() else hideFinishConfirmPrompt()
        }
    }

    private fun showFinishConfirmPrompt() {
        if (!tripTracker.isTracking) return
        if (finishConfirmDialog?.isShowing == true) return

        val dialog = AlertDialog.Builder(this)
            .setTitle("Fechar acompanhamento")
            .setMessage("Deseja terminar a viagem?")
            .setPositiveButton("Confirmar") { d, _ ->
                try {
                    stopBypassConfirmOnce = true
                    handleStopTracking()
                } catch (_: Exception) {}
                d.dismiss()
            }
            .setNegativeButton("Cancelar") { d, _ -> d.dismiss() }
            .setOnDismissListener { }
            .create()

        dialog.setCanceledOnTouchOutside(true)

        dialog.window?.let { w ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                w.setType(WindowManager.LayoutParams.TYPE_PHONE)
            }
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        try {
            dialog.show()
            finishConfirmDialog = dialog
        } catch (_: Exception) {
            finishConfirmDialog = null
        }
    }

    private fun hideFinishConfirmPrompt() {
        try { finishConfirmDialog?.dismiss() } catch (_: Exception) { }
        finishConfirmDialog = null
    }
    // =====================================================================

    // ====== obter rating horário do semáforo ou calcular fallback ======
    private fun extractHourRatingFrom(eval: EvaluationResult): IndividualRating? {
        return try {
            val m = eval.javaClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() && it.returnType == IndividualRating::class.java &&
                        (it.name.equals("getHourRating", true) ||
                                it.name.equals("getHourlyRating", true) ||
                                it.name.equals("getHourEval", true) ||
                                it.name.equals("hourRating", true))
            }
            @Suppress("UNCHECKED_CAST")
            (m?.invoke(eval) as? IndividualRating)
        } catch (_: Throwable) { null }
    }

    private fun computePlannedHourRatingFrom(offer: OfferData): IndividualRating {
        val minutes = offer.calculateTotalTimeMinutes() ?: 0
        val valueNum = offer.value
            .replace("€","")
            .replace(",", ".")
            .replace(Regex("[^0-9\\.]"), "")
            .toDoubleOrNull()
        val vph = if (valueNum != null && minutes > 0) valueNum / (minutes / 60.0) else null
        return when {
            vph == null -> IndividualRating.UNKNOWN
            vph >= goodHourThreshold -> IndividualRating.GOOD
            vph <= poorHourThreshold -> IndividualRating.POOR
            else -> IndividualRating.MEDIUM
        }
    }
    // =========================================================================

    // ===================== Watchdog / Auto-Healing ===========================
    private fun setupHealthMonitor() {
        healthRunnable = Runnable {
            val now = SystemClock.elapsedRealtime()

            val today = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            if (today != lastDateYmd) {
                Log.i(TAG_HEALTH, "Novo dia detectado ($lastDateYmd -> $today). Rearmar capturas e watchers.")
                lastDateYmd = today
                requestDetectorsRearm(reason = "newDay")
            }

            val inUse = shiftSession.isActive || tripTracker.isTracking || isMainOverlayAdded || isQuickMenuAdded
            val lastAny = maxOf(lastActivityMs, maxOf(lastFinishBroadcastMs, lastOverlayShownMs))
            val stalled = inUse && (now - lastAny) > STALL_MS

            if (stalled && (now - lastHealMs) > HEAL_THROTTLE_MS) {
                Log.w(TAG_HEALTH, "Stall > ${STALL_MS/60000}min sem atividade. A rearmar serviços…")
                overlayFadeHandler.removeCallbacksAndMessages(fadeToken)
                mainOverlayView?.alpha = baseOverlayAlpha
                isMainOverlayTranslucent = false
                hideOfferIndicator()
                if (shiftSession.isActive && !shiftSession.isPaused) startShiftTimer()
                safeReregisterFinishReceiver()
                requestDetectorsRearm(reason = "stallRecovery")
                lastHealMs = now
            }

            healthHandler.postDelayed(healthRunnable!!, HEALTH_TICK_MS)
        }
        healthHandler.postDelayed(healthRunnable!!, HEALTH_TICK_MS)
    }

    private fun requestDetectorsRearm(reason: String) {
        try {
            sendBroadcast(Intent(ACTION_REQ_REARM_FINISH_WATCH).apply {
                putExtra("reason", reason)
            })
            sendBroadcast(Intent(ACTION_REQ_RESTART_CAPTURE).apply {
                putExtra("reason", reason)
            })
            Log.i(TAG_HEALTH, "Pedidos enviados: REARM_FINISH_WATCH e RESTART_CAPTURE ($reason).")
        } catch (t: Throwable) {
            Log.e(TAG_HEALTH, "Falha ao enviar pedidos de rearm: ${t.message}")
        }
        updateFinishPromptForState(edgeOnly = false)
    }

    private fun touchActivity(tag: String) {
        lastActivityMs = SystemClock.elapsedRealtime()
        if (isDebugBuild) Log.d(TAG_HEALTH, "activityTick <$tag>")
    }

    private fun safeRegisterFinishReceiver() {
        try {
            val flt = IntentFilter(ACTION_EVT_FINISH_KEYWORD_VISIBLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(finishKeywordReceiver, flt, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(finishKeywordReceiver, flt)
            }
            isFinishReceiverRegistered = true
            Log.d(TAG_HEALTH, "finishKeywordReceiver registado.")
        } catch (t: Throwable) {
            isFinishReceiverRegistered = false
            Log.e(TAG_HEALTH, "Falha a registar finishKeywordReceiver: ${t.message}")
        }
    }

    private fun safeUnregisterFinishReceiver() {
        if (!isFinishReceiverRegistered) return
        try {
            unregisterReceiver(finishKeywordReceiver)
            isFinishReceiverRegistered = false
            Log.d(TAG_HEALTH, "finishKeywordReceiver removido.")
        } catch (_: Throwable) {
            isFinishReceiverRegistered = false
        }
    }

    private fun safeReregisterFinishReceiver() {
        safeUnregisterFinishReceiver()
        safeRegisterFinishReceiver()
        updateFinishPromptForState(edgeOnly = false)
    }
    // ========================================================================
}
