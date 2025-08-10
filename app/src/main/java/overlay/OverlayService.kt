package com.example.smartdriver.overlay

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.*
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ImageView.ScaleType
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.smartdriver.*
import com.example.smartdriver.utils.*
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

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

        const val HISTORY_PREFS_NAME = "SmartDriverHistoryPrefs"
        const val KEY_TRIP_HISTORY = "trip_history_list_json"

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

    // Rect real da drop zone (coordenadas absolutas de ecrã)
    private var dropZoneRect: Rect? = null

    // ---- NOVO: evita repetir banner de "Meta atingida" no mesmo turno
    private var hasShownTargetReachedBanner = false

    override fun onCreate() {
        super.onCreate()
        shiftSession = ShiftSession(this)
        setupTripTracker()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        loadTrackingThresholds()
        initializeAllLayoutParams()
        setupRunnables()
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_overlay_ready)))
        addFloatingIconOverlay()
        if (shiftSession.isActive && !shiftSession.isPaused) startShiftTimer()
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
                shiftSession.addTripEarnings(historyEntry.offerValue ?: 0.0)
                hideTrackingOverlay()
                removeDropZoneView()
                updateMenuViewShiftUI()
                updateShiftNotification()
                // --- NOVO: banner “Viagem registada”
                val inc = historyEntry.offerValue?.takeIf { it > 0.0 }?.let { currencyFormatter.format(it) }
                showOverlayBanner(if (inc != null) "Viagem registada: +$inc" else "Viagem registada", OverlayView.BannerType.SUCCESS, 2500)
            }
            override fun onTripDiscarded() {
                hideTrackingOverlay()
                removeDropZoneView()
                updateShiftNotification()
                Toast.makeText(this@OverlayService, getString(R.string.toast_tracking_discarded), Toast.LENGTH_SHORT).show()
                // --- NOVO: banner “Viagem descartada”
                showOverlayBanner("Viagem descartada", OverlayView.BannerType.WARNING, 2000)
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_SHIFT_STATE -> {
                if (intent.hasExtra(EXTRA_SHIFT_TARGET)) {
                    shiftSession.start(intent.getDoubleExtra(EXTRA_SHIFT_TARGET, 0.0))
                    if (shiftSession.isActive) {
                        hasShownTargetReachedBanner = false
                        startShiftTimer(); updateMenuViewShiftUI(); updateShiftNotification()
                        // --- NOVO: banner ao iniciar turno
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
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        if (tripTracker.isTracking) tripTracker.discard()
        shiftSession.onServiceDestroyed()
        removeAllOverlays()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleStartTracking(intent: Intent?) {
        val offerData = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java)
        val initialEval = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java)
        if (offerData != null && initialEval != null) {
            hideMainOverlay(); removeQuickMenuOverlay()
            val initialVpk = offerData.calculateProfitability()
            showTrackingOverlay(
                initialVpk,
                offerData.calculateTotalDistance()?.takeIf { it > 0 },
                offerData.calculateTotalTimeMinutes()?.takeIf { it > 0 },
                offerData.value,
                initialEval.kmRating,
                initialEval.combinedBorderRating
            )
            tripTracker.start(offerData, initialEval)
            updateShiftNotification()
            // --- NOVO: banner informativo (aparece no próximo showMainOverlay; aqui pode não haver overlay principal visível)
            showOverlayBanner("Tracking iniciado", OverlayView.BannerType.INFO, 1500)
        }
    }
    private fun handleStopTracking() { tripTracker.stopAndSave(); removeDropZoneView() }
    private fun handleDiscardCurrentTracking() { tripTracker.discard(); removeDropZoneView() }
    private fun handleHideOverlay() { if (tripTracker.isTracking) tripTracker.stopAndSave(); removeAllOverlays(); updateShiftNotification() }
    private fun handleShowOverlay(intent: Intent?) {
        val evalResult = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java)
        val offerData = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java)
        if (evalResult != null && offerData != null) { showMainOverlay(evalResult, offerData); updateShiftNotification() }
    }
    private fun handleDismissMainOverlayOnly() { hideMainOverlay() }
    private fun handleUpdateSettings(intent: Intent?) {
        loadTrackingThresholds()
        val nFS = intent?.getIntExtra(EXTRA_FONT_SIZE, SettingsActivity.getFontSize(this))
            ?: SettingsActivity.getFontSize(this)
        val nT = intent?.getIntExtra(EXTRA_TRANSPARENCY, SettingsActivity.getTransparency(this))
            ?: SettingsActivity.getTransparency(this)
        applyAppearanceSettings(nFS, nT); updateLayouts(); updateMenuViewShiftUI()
    }
    private fun handleShowQuickMenu() { addQuickMenuOverlay() }
    private fun handleDismissMenu() { removeQuickMenuOverlay() }
    private fun handleShutdownRequest() {
        shiftSession.end(saveSummary = false)
        hasShownTargetReachedBanner = false
        if (tripTracker.isTracking) tripTracker.stopAndSave()
        removeAllOverlays()
        stopService(Intent(this, ScreenCaptureService::class.java))
        MediaProjectionData.clear()
        sendBroadcast(Intent(MainActivity.ACTION_SHUTDOWN_APP))
        stopSelf()
    }
    private fun handleToggleShiftState() {
        if (!shiftSession.isActive) {
            val intent = Intent(this, SetShiftTargetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent); removeQuickMenuOverlay()
        } else {
            shiftSession.togglePauseResume()
            if (!shiftSession.isPaused) {
                startShiftTimer()
                // --- NOVO: retoma
                showOverlayBanner("Turno retomado", OverlayView.BannerType.INFO, 1500)
            } else {
                stopShiftTimer()
                // --- NOVO: pausa
                showOverlayBanner("Em pausa", OverlayView.BannerType.INFO, 1500)
            }
            updateMenuViewShiftUI(); updateShiftNotification()
        }
    }
    private fun handleEndShift() {
        if (shiftSession.isActive) {
            shiftSession.end(saveSummary = true)
            hasShownTargetReachedBanner = false
            updateMenuViewShiftUI(); updateShiftNotification()
            // --- NOVO: fim de turno
            showOverlayBanner("Turno terminado", OverlayView.BannerType.INFO, 1500)
        }
    }

    private fun initializeAllLayoutParams() {
        val density = resources.displayMetrics.density
        val oT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val bF = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        mainLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            oT, bF or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (50 * density).toInt() }

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
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (10 * density).toInt(); y = (80 * density).toInt() }

        floatingIconLayoutParams = WindowManager.LayoutParams(
            (60 * density).toInt(), (60 * density).toInt(),
            oT, bF or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (10 * density).toInt(); y = (10 * density).toInt() }
    }

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

    @SuppressLint("ClickableViewAccessibility")
    private fun addQuickMenuOverlay() {
        if (windowManager == null || isQuickMenuAdded) return
        if (quickMenuView == null) { quickMenuView = MenuView(this); updateMenuViewShiftUI() }
        try {
            menuLayoutParams.x = floatingIconLayoutParams.x
            menuLayoutParams.y = floatingIconLayoutParams.y + floatingIconLayoutParams.height + (5 * resources.displayMetrics.density).toInt()
            windowManager?.addView(quickMenuView, menuLayoutParams); isQuickMenuAdded = true
        } catch (_: Exception) { isQuickMenuAdded = false; quickMenuView = null }
    }
    private fun removeQuickMenuOverlay() {
        if (isQuickMenuAdded && quickMenuView != null && windowManager != null) {
            try { windowManager?.removeViewImmediate(quickMenuView) } catch (_: Exception) {} finally {
                isQuickMenuAdded = false; quickMenuView = null
            }
        }
    }

    private fun removeAllOverlays() {
        hideMainOverlay(); hideTrackingOverlay(); removeDropZoneView(); removeQuickMenuOverlay(); removeFloatingIconOverlay()
    }

    private fun showMainOverlay(eR: EvaluationResult, oD: OfferData) {
        val wm = windowManager ?: return
        if (mainOverlayView == null) { mainOverlayView = OverlayView(this); applyAppearanceSettingsToView(mainOverlayView) }
        mainOverlayView?.updateState(eR, oD)
        try {
            if (!isMainOverlayAdded) { wm.addView(mainOverlayView, mainLayoutParams); isMainOverlayAdded = true }
            else wm.updateViewLayout(mainOverlayView, mainLayoutParams)
        } catch (_: Exception) { isMainOverlayAdded = false; mainOverlayView = null }
    }
    private fun hideMainOverlay() {
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

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingIconTouchListener(): View.OnTouchListener {
        var iX = 0; var iY = 0; var iTX = 0f; var iTY = 0f; var sT = 0L; var isD = false
        return View.OnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { iX = floatingIconLayoutParams.x; iY = floatingIconLayoutParams.y; iTX = e.rawX; iTY = e.rawY; sT = System.currentTimeMillis(); isD = false; true }
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

    // ---------- DROP ZONE ----------
    private fun addDropZoneView() {
        val wm = windowManager ?: return
        val base = dropZoneLayoutParams ?: return

        val density = resources.displayMetrics.density
        val sizePx = (72 * density).toInt()
        val bottomMarginPx = (24 * density).toInt()

        val dm = Resources.getSystem().displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val x = (screenW - sizePx) / 2
        val y = screenH - sizePx - bottomMarginPx

        val lp = WindowManager.LayoutParams(sizePx, sizePx, base.type, base.flags, base.format).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        if (dropZoneView == null) {
            dropZoneView = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                alpha = 0.9f
                scaleType = ScaleType.CENTER_INSIDE
            }
        }

        try {
            if (!isDropZoneViewAdded) { wm.addView(dropZoneView, lp); isDropZoneViewAdded = true }
            else { wm.updateViewLayout(dropZoneView, lp) }
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
                removeDropZoneView()
                return
            }
        }
        removeDropZoneView()
    }

    // ---------- Aparência / timers / util ----------

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

            val fT = currencyFormatter.format(shiftSession.targetEarnings)
            menu.updateShiftTarget(fT)
            menu.updateShiftStatus(getString(sTK), shiftSession.isActive, shiftSession.isPaused)
            menu.updateShiftTimer(shiftSession.getFormattedWorkedTime())
            menu.updateShiftAverage(shiftSession.getFormattedAveragePerHour())
            menu.updateTimeToTarget(shiftSession.getFormattedTimeToTarget())
            menu.updateExpectedEndTime(calculateAndFormatExpectedEndTime())
        }

        // ---- NOVO: detetar meta atingida e mostrar banner (uma vez)
        if (shiftSession.isActive && !hasShownTargetReachedBanner) {
            val remaining = shiftSession.targetEarnings - shiftSession.totalEarnings
            if (shiftSession.targetEarnings > 0.0 && remaining <= 0.0) {
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

    private fun calculateAndFormatExpectedEndTime(): String {
        if (!shiftSession.isActive || shiftSession.targetEarnings <= 0.0) return getString(R.string.expected_end_time_placeholder)
        val eTG = shiftSession.targetEarnings - shiftSession.totalEarnings
        if (eTG <= 0.0) return getString(R.string.shift_target_reached)
        val avgPH = shiftSession.getAveragePerHourValue() ?: return getString(R.string.expected_end_time_placeholder)
        if (avgPH <= 0.0) return getString(R.string.expected_end_time_placeholder)
        val ms = ((eTG / avgPH) * 3_600_000.0).toLong()
        if (ms < 0) return getString(R.string.shift_target_reached)
        return try { android.text.format.DateFormat.getTimeFormat(this).format(Date(System.currentTimeMillis() + ms)) }
        catch (_: Exception) { "--:--" }
    }

    private fun loadTrackingThresholds() {
        try {
            goodHourThreshold = SettingsActivity.getGoodHourThreshold(this)
            poorHourThreshold = SettingsActivity.getPoorHourThreshold(this)
        } catch (_: Exception) {
            goodHourThreshold = 15.0; poorHourThreshold = 8.0
        }
    }

    private fun <T : Parcelable> getParcelableExtraCompat(intent: Intent?, key: String, clazz: Class<T>): T? {
        return intent?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) it.getParcelableExtra(key, clazz)
            else @Suppress("DEPRECATION") it.getParcelableExtra(key) as? T
        }
    }

    // -------- NOVO: helper de banner no overlay principal --------
    private fun showOverlayBanner(text: String, type: OverlayView.BannerType, durationMs: Long) {
        // Só mostra se o overlay principal existir/estiver visível
        mainOverlayView?.showBanner(text, type, durationMs)
    }
}
