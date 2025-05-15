package com.example.smartdriver.overlay // Ou o teu package correto

import android.graphics.Rect // IMPORT NECESSÁRIO
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.PixelFormat
import android.os.*
import android.util.Log
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView // Necessário para DropZoneView
import android.widget.ImageView.ScaleType
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.smartdriver.R
import com.example.smartdriver.SettingsActivity
import com.example.smartdriver.ScreenCaptureService
import com.example.smartdriver.MediaProjectionData
import com.example.smartdriver.MainActivity
import com.example.smartdriver.SetShiftTargetActivity
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.EvaluationResult
import com.example.smartdriver.utils.IndividualRating
import com.example.smartdriver.utils.TripHistoryEntry
import com.example.smartdriver.utils.BorderRating
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Date // IMPORT ADICIONADO
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {

    companion object {
        internal const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val CHANNEL_NAME = "Overlay Service"

        // Ações para Drop Zone
        const val ACTION_SHOW_DROP_ZONE = "com.example.smartdriver.overlay.SHOW_DROP_ZONE"
        const val ACTION_HIDE_DROP_ZONE_AND_CHECK_DROP = "com.example.smartdriver.overlay.HIDE_DROP_ZONE_AND_CHECK_DROP"
        const val EXTRA_UP_X = "up_x_extra"
        const val EXTRA_UP_Y = "up_y_extra"

        // Outras Ações
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

        private const val TRACKING_UPDATE_INTERVAL_MS = 1000L
        private const val MIN_TRACKING_TIME_SEC = 1L
        const val HISTORY_PREFS_NAME = "SmartDriverHistoryPrefs"
        const val KEY_TRIP_HISTORY = "trip_history_list_json"
        const val SHIFT_STATE_PREFS_NAME = "SmartDriverShiftStatePrefs"
        private const val KEY_SHIFT_ACTIVE = "shift_active"; private const val KEY_SHIFT_PAUSED = "shift_paused"
        private const val KEY_SHIFT_START_TIME = "shift_start_time"; private const val KEY_SHIFT_LAST_PAUSE_OR_RESUME_TIME = "shift_last_pause_resume_time"
        private const val KEY_SHIFT_ACCUMULATED_WORKED_TIME = "shift_accumulated_worked_time"; private const val KEY_SHIFT_TOTAL_EARNINGS = "shift_total_earnings"
        private const val KEY_SHIFT_TARGET_EARNINGS = "shift_target_earnings"
        @JvmStatic val isRunning = AtomicBoolean(false)
    }

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

    private var isMainOverlayAdded = false; private var isTrackingOverlayAdded = false
    private var isDropZoneViewAdded = false; private var isQuickMenuAdded = false
    private var isFloatingIconAdded = false

    private var isCurrentlyTracking = false; private var trackingStartTimeMs: Long = 0L
    private var trackedOfferData: OfferData? = null; private var trackedInitialVph: Double? = null
    private var trackedInitialVpk: Double? = null; private var trackedOfferValue: Double = 0.0
    private var trackedInitialKmRating: IndividualRating = IndividualRating.UNKNOWN
    private var trackedCombinedBorderRating: BorderRating = BorderRating.GRAY
    private val trackingUpdateHandler = Handler(Looper.getMainLooper()); private lateinit var trackingUpdateRunnable: Runnable

    private var isShiftActive = false; private var isShiftPaused = false
    private var shiftStartTimeMillis = 0L; private var shiftLastPauseOrResumeTimeMillis = 0L
    private var shiftAccumulatedWorkedTimeMillis = 0L; private var shiftTotalEarnings = 0.0
    private var shiftTargetEarnings: Double = 0.0
    private val shiftTimerHandler = Handler(Looper.getMainLooper()); private var shiftTimerRunnable: Runnable? = null
    private lateinit var shiftPrefs: SharedPreferences

    private var goodHourThreshold: Double = 15.0; private var poorHourThreshold: Double = 8.0
    private val gson = Gson(); private lateinit var historyPrefs: SharedPreferences
    private var touchSlop: Int = 0
    private val averageDecimalFormat = DecimalFormat("0.0")
    private val currencyFormatter: NumberFormat = NumberFormat.getCurrencyInstance(Locale("pt", "PT")).apply {
        minimumFractionDigits = 2; maximumFractionDigits = 2
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Serviço Overlay CRIADO")
        isRunning.set(true)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        historyPrefs = getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
        shiftPrefs = getSharedPreferences(SHIFT_STATE_PREFS_NAME, Context.MODE_PRIVATE)
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        loadTrackingThresholds()
        loadShiftState()
        initializeMainLayoutParams(); initializeTrackingLayoutParams(); initializeDropZoneLayoutParams();
        initializeMenuLayoutParams(); initializeFloatingIconLayoutParams()
        setupTrackingRunnable(); setupShiftTimerRunnable()
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_overlay_ready)))
        addFloatingIconOverlay()
        if (isShiftActive && !isShiftPaused) startShiftTimer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Action=${intent?.action}, HasExtras=${intent?.extras != null && !intent.extras!!.isEmpty}")
        if (intent?.action == ACTION_TOGGLE_SHIFT_STATE && intent.hasExtra(EXTRA_SHIFT_TARGET)) {
            if (!isShiftActive) {
                val target = intent.getDoubleExtra(EXTRA_SHIFT_TARGET, 0.0)
                if (target > 0.0) {
                    shiftTargetEarnings = target
                    Log.i(TAG, "Meta recebida da Activity: ${currencyFormatter.format(shiftTargetEarnings)}")
                    isShiftActive = true; isShiftPaused = false
                    shiftStartTimeMillis = System.currentTimeMillis(); shiftLastPauseOrResumeTimeMillis = shiftStartTimeMillis
                    shiftAccumulatedWorkedTimeMillis = 0L; shiftTotalEarnings = 0.0
                    Log.i(TAG, ">>> TURNO INICIADO COM META <<< Meta: ${currencyFormatter.format(shiftTargetEarnings)}")
                    startShiftTimer(); updateMenuViewShiftUI(); updateShiftNotification(); saveShiftState()
                } else {
                    Log.w(TAG, "Meta recebida da Activity é inválida: $target. Turno não iniciado.")
                    Toast.makeText(this, getString(R.string.toast_invalid_target_value), Toast.LENGTH_LONG).show()
                }
            } else { Log.w(TAG, "Recebida meta para iniciar turno, mas um turno já está ativo.") }
            return START_REDELIVER_INTENT
        }
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> handleShowOverlay(intent)
            ACTION_HIDE_OVERLAY -> handleHideOverlay()
            ACTION_DISMISS_MAIN_OVERLAY_ONLY -> handleDismissMainOverlayOnly()
            ACTION_START_TRACKING -> handleStartTracking(intent)
            ACTION_STOP_TRACKING -> handleStopTracking()
            ACTION_DISCARD_CURRENT_TRACKING -> handleDiscardCurrentTracking()
            ACTION_UPDATE_SETTINGS -> handleUpdateSettings(intent)
            ACTION_SHOW_QUICK_MENU -> handleShowQuickMenu()
            ACTION_DISMISS_MENU -> handleDismissMenu()
            ACTION_SHOW_DROP_ZONE -> {
                Log.d(TAG, "onStartCommand: Recebido ACTION_SHOW_DROP_ZONE")
                addDropZoneView()
            }
            ACTION_HIDE_DROP_ZONE_AND_CHECK_DROP -> {
                val touchUpX = intent.getFloatExtra(EXTRA_UP_X, -1f)
                val touchUpY = intent.getFloatExtra(EXTRA_UP_Y, -1f)
                Log.d(TAG, "onStartCommand: Recebido HIDE_DROP_ZONE_AND_CHECK_DROP. Coords:($touchUpX, $touchUpY)")
                checkDropAndHideZone(touchUpX, touchUpY)
            }
            ACTION_REQUEST_SHUTDOWN -> handleShutdownRequest()
            ACTION_TOGGLE_SHIFT_STATE -> handleToggleShiftState()
            ACTION_END_SHIFT -> handleEndShift()
            else -> Log.w(TAG, "Ação desconhecida ou nula: ${intent?.action}")
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "====== Serviço Overlay DESTRUÍDO ======")
        isRunning.set(false); stopTrackingTimer(); stopShiftTimer()
        if (isShiftActive) {
            val cT = System.currentTimeMillis()
            if (!isShiftPaused && shiftLastPauseOrResumeTimeMillis > 0) { val wMS = cT - shiftLastPauseOrResumeTimeMillis; if (wMS > 0) shiftAccumulatedWorkedTimeMillis += wMS }
            shiftLastPauseOrResumeTimeMillis = cT; isShiftPaused = true; saveShiftState()
            Log.i(TAG, "Estado final do turno (pausado, meta: ${currencyFormatter.format(shiftTargetEarnings)}) salvo ao destruir.")
        } else {
            if (shiftStartTimeMillis == 0L) { shiftPrefs.edit().clear().apply(); Log.i(TAG, "Prefs de turno limpas (turno não ativo e terminado).") }
        }
        hideMainOverlay(); hideTrackingOverlay(); removeDropZoneView(); removeQuickMenuOverlay(); removeFloatingIconOverlay()
        mainOverlayView = null; trackingOverlayView = null; dropZoneView = null; windowManager = null; quickMenuView = null; floatingIconView = null; shiftTimerRunnable = null
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) } catch (e: Exception) {}
    }

    // --- Funções de Inicialização ---
    private fun loadTrackingThresholds() { try { goodHourThreshold = SettingsActivity.getGoodHourThreshold(this); poorHourThreshold = SettingsActivity.getPoorHourThreshold(this); Log.d(TAG, "Limiares: Bom€/h=$goodHourThreshold, Mau€/h=$poorHourThreshold") } catch (e: Exception) { Log.w(TAG, "Erro carregar limiares.", e); goodHourThreshold = 15.0; poorHourThreshold = 8.0 } }
    private fun initializeMainLayoutParams() { val oT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE; val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS; mainLayoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, oT, f, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (50 * resources.displayMetrics.density).toInt() } }
    private fun initializeTrackingLayoutParams() { val oT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE; val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS; trackingLayoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, oT, f, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = (10 * resources.displayMetrics.density).toInt(); y = (80 * resources.displayMetrics.density).toInt() } }
    private fun initializeDropZoneLayoutParams() { val oT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE; val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL; val iSDp = 72; val iSPx = (iSDp * resources.displayMetrics.density).toInt(); dropZoneLayoutParams = WindowManager.LayoutParams( iSPx, iSPx, oT, f, PixelFormat.TRANSLUCENT ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; val mBDp = 24; y = (mBDp * resources.displayMetrics.density).toInt() }; Log.d(TAG, "DropZoneLayoutParams inicializados.") }
    private fun initializeMenuLayoutParams() { val oT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE; val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL; menuLayoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, oT, f, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = (10 * resources.displayMetrics.density).toInt(); y = (80 * resources.displayMetrics.density).toInt() } }
    private fun initializeFloatingIconLayoutParams() { val oT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE; val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN; val iS = (60 * resources.displayMetrics.density).toInt(); floatingIconLayoutParams = WindowManager.LayoutParams(iS, iS, oT, f, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = (10 * resources.displayMetrics.density).toInt(); y = (10 * resources.displayMetrics.density).toInt() } }

    // --- Runnables ---
    private fun setupTrackingRunnable() { trackingUpdateRunnable = object : Runnable { override fun run() { if (isCurrentlyTracking && trackingOverlayView != null) { val elM = System.currentTimeMillis() - trackingStartTimeMs; val elS = max(MIN_TRACKING_TIME_SEC, elM / 1000L); var cV: Double? = null; if (trackedOfferValue > 0) { val elH = elS / 3600.0; if (elH > 0) { val calc = trackedOfferValue / elH; if (calc.isFinite()) cV = calc } }; val cHR = when { cV == null -> IndividualRating.UNKNOWN; cV >= goodHourThreshold -> IndividualRating.GOOD; cV <= poorHourThreshold -> IndividualRating.POOR; else -> IndividualRating.MEDIUM }; trackingOverlayView?.updateRealTimeData(cV, cHR, elS); trackingUpdateHandler.postDelayed(this, TRACKING_UPDATE_INTERVAL_MS) } } } }
    private fun setupShiftTimerRunnable() { shiftTimerRunnable = object : Runnable { override fun run() { if (isShiftActive && !isShiftPaused && shiftTimerRunnable != null) { val wTM = calculateCurrentWorkedTimeMillis(); val fT = formatDuration(wTM); val avgNum = getNumericShiftAveragePerHourValue(wTM); val avgStr = calculateCurrentShiftAveragePerHourString(avgNum); val tTTStr = calculateAndFormatTimeToTarget(); quickMenuView?.updateShiftTimer(fT); quickMenuView?.updateTimeToTarget(tTTStr); quickMenuView?.updateShiftAverage(avgStr); shiftTimerHandler.postDelayed(this, 1000L) } } } }

    // --- Notificações ---
    private fun createNotification(contentText: String, isTrackingOrActive: Boolean = false): Notification { createNotificationChannel(); val sI = try { if (isTrackingOrActive) R.drawable.ic_stat_tracking else R.mipmap.ic_launcher } catch (e: Resources.NotFoundException) { R.mipmap.ic_launcher }; return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(getString(R.string.app_name)).setContentText(contentText).setSmallIcon(sI).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW).build() }
    private fun updateNotification(contentText: String, isTrackingOrActive: Boolean = false) { try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, createNotification(contentText, isTrackingOrActive)) } catch (e: Exception) { Log.e(TAG, "Erro atualizar notificação.", e) } }
    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply { description = getString(R.string.notification_channel_description); enableLights(false); enableVibration(false); setShowBadge(false) }; try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch) } catch (e: Exception) { Log.e(TAG, "Erro criar canal notificação.", e) } } }

    // --- Handlers das Ações ---
    private fun handleShowOverlay(intent: Intent?) { val eR = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java); val oD = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java); if (eR != null && oD != null) { showMainOverlay(eR, oD); updateShiftNotification() } else { Log.w(TAG, "handleShowOverlay: dados nulos."); hideMainOverlay() } }
    private fun handleHideOverlay() { if (isCurrentlyTracking) stopTrackingAndSaveToHistory(); hideMainOverlay(); hideTrackingOverlay(); removeDropZoneView(); removeQuickMenuOverlay(); updateShiftNotification() }
    private fun handleDismissMainOverlayOnly() { hideMainOverlay() }
    private fun handleStartTracking(intent: Intent?) { val oD = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java); val iE = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java); if (oD != null && iE != null && !isCurrentlyTracking) { hideMainOverlay(); removeQuickMenuOverlay(); isCurrentlyTracking = true; trackingStartTimeMs = System.currentTimeMillis(); trackedOfferData = oD; trackedOfferValue = try { oD.value.replace(",", ".").toDouble() } catch (e: NumberFormatException) { 0.0 }; trackedInitialVph = oD.calculateValuePerHour(); trackedInitialVpk = oD.calculateProfitability(); trackedInitialKmRating = iE.kmRating; trackedCombinedBorderRating = iE.combinedBorderRating; loadTrackingThresholds(); val iDt = oD.calculateTotalDistance()?.takeIf { it > 0 }; val iDu = oD.calculateTotalTimeMinutes()?.takeIf { it > 0 }; val oV = oD.value; showTrackingOverlay(trackedInitialVpk, iDt, iDu, oV, trackedInitialKmRating, trackedCombinedBorderRating); trackingUpdateHandler.removeCallbacks(trackingUpdateRunnable); trackingUpdateHandler.post(trackingUpdateRunnable); updateShiftNotification() } else if (isCurrentlyTracking) { Log.w(TAG, "$ACTION_START_TRACKING ignorado: ativo.") } else { Log.e(TAG, "$ACTION_START_TRACKING sem dados.") } }
    private fun handleStopTracking() { if (isCurrentlyTracking) stopTrackingAndSaveToHistory() }
    private fun handleDiscardCurrentTracking() { if (!isCurrentlyTracking) { Log.w(TAG, "handleDiscardCurrentTracking: Não há tracking ativo para descartar."); return }; Log.i(TAG, "handleDiscardCurrentTracking: INICIADO. Descartando tracking..."); stopTrackingTimer(); hideTrackingOverlay(); isCurrentlyTracking = false; trackingStartTimeMs = 0L; trackedOfferData = null; trackedOfferValue = 0.0; trackedInitialVph = null; trackedInitialVpk = null; trackedInitialKmRating = IndividualRating.UNKNOWN; trackedCombinedBorderRating = BorderRating.GRAY; updateShiftNotification(); Toast.makeText(this, getString(R.string.toast_tracking_discarded), Toast.LENGTH_SHORT).show(); Log.i(TAG, "handleDiscardCurrentTracking: Tracking descartado com SUCESSO.") }
    private fun handleUpdateSettings(intent: Intent?) { Log.d(TAG, "$ACTION_UPDATE_SETTINGS recebido."); loadTrackingThresholds(); val nFS = intent?.getIntExtra(EXTRA_FONT_SIZE, SettingsActivity.getFontSize(this)) ?: SettingsActivity.getFontSize(this); val nT = intent?.getIntExtra(EXTRA_TRANSPARENCY, SettingsActivity.getTransparency(this)) ?: SettingsActivity.getTransparency(this); Log.d(TAG, "Aplicando: Fonte=$nFS%, Transp=$nT%"); applyAppearanceSettings(nFS, nT); updateLayouts(); updateMenuViewShiftUI() }
    private fun handleShowQuickMenu() { addQuickMenuOverlay() }
    private fun handleDismissMenu() { removeQuickMenuOverlay() }
    private fun handleShutdownRequest() { Log.i(TAG, "Pedido de encerramento app."); handleEndShift(false); removeQuickMenuOverlay(); if (isCurrentlyTracking) stopTrackingAndSaveToHistory(); hideMainOverlay(); hideTrackingOverlay(); removeDropZoneView(); removeFloatingIconOverlay(); try { stopService(Intent(this, ScreenCaptureService::class.java)) } catch (e: Exception) {}; MediaProjectionData.clear(); try { sendBroadcast(Intent(MainActivity.ACTION_SHUTDOWN_APP)) } catch (e: Exception) {}; stopSelf() }
    private fun handleToggleShiftState() { val cT = System.currentTimeMillis(); if (!isShiftActive) { Log.i(TAG, "Pedido para iniciar novo turno. Lançando SetShiftTargetActivity..."); val intent = Intent(this, SetShiftTargetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { startActivity(intent); removeQuickMenuOverlay() } catch (e: Exception) { Log.e(TAG, "Erro ao iniciar SetShiftTargetActivity: ${e.message}", e); Toast.makeText(this, getString(R.string.toast_error_set_target_activity), Toast.LENGTH_LONG).show() } } else { if (isShiftPaused) { isShiftPaused = false; shiftLastPauseOrResumeTimeMillis = cT; Log.i(TAG, ">>> TURNO RETOMADO <<<"); startShiftTimer() } else { isShiftPaused = true; val wM = cT - shiftLastPauseOrResumeTimeMillis; if (wM > 0) shiftAccumulatedWorkedTimeMillis += wM; shiftLastPauseOrResumeTimeMillis = cT; Log.i(TAG, ">>> TURNO PAUSADO <<< Acum: ${formatDuration(shiftAccumulatedWorkedTimeMillis)}"); stopShiftTimer() }; updateMenuViewShiftUI(); updateShiftNotification(); saveShiftState() } }
    private fun handleEndShift(saveSummary: Boolean = true) { if (!isShiftActive) return; val eT = System.currentTimeMillis(); Log.i(TAG, ">>> TURNO TERMINADO <<<"); stopShiftTimer(); var fWT = shiftAccumulatedWorkedTimeMillis; if (!isShiftPaused) { val lSM = eT - shiftLastPauseOrResumeTimeMillis; if (lSM > 0) fWT += lSM }; val fFT = formatDuration(fWT); val avgNum = getNumericShiftAveragePerHourValue(fWT); val avgStr = calculateCurrentShiftAveragePerHourString(avgNum); Log.i(TAG, "Resumo: Dur=$fFT, Ganhos REAIS=${currencyFormatter.format(shiftTotalEarnings)}, Média REAL=$avgStr, Meta Atingida=${if (shiftTotalEarnings >= shiftTargetEarnings && shiftTargetEarnings > 0.0) "SIM" else "NÃO"} (Meta: ${currencyFormatter.format(shiftTargetEarnings)})"); isShiftActive = false; isShiftPaused = false; if (saveSummary) saveShiftState() else { shiftPrefs.edit().clear().apply(); Log.i(TAG, "Estado turno e meta limpos (sem salvar).") }; updateMenuViewShiftUI(); updateShiftNotification(); shiftTotalEarnings = 0.0; shiftAccumulatedWorkedTimeMillis = 0L; shiftTargetEarnings = 0.0; shiftStartTimeMillis = 0L; shiftLastPauseOrResumeTimeMillis = 0L }

    // --- Helper getParcelableExtraCompat ---
    private fun <T : Parcelable> getParcelableExtraCompat(intent: Intent?, key: String, clazz: Class<T>): T? { return intent?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) it.getParcelableExtra(key, clazz) else @Suppress("DEPRECATION") it.getParcelableExtra(key) as? T } }

    // --- Gestão das Views (Overlay, Tracking, FloatingIcon, Menu, DropZone) ---
    private fun showMainOverlay(eR: EvaluationResult, oD: OfferData) { if (windowManager == null) return; if (mainOverlayView == null) { mainOverlayView = OverlayView(this); applyAppearanceSettingsToView(mainOverlayView) }; mainOverlayView?.updateState(eR, oD); try { if (!isMainOverlayAdded) { windowManager?.addView(mainOverlayView, mainLayoutParams); isMainOverlayAdded = true } else windowManager?.updateViewLayout(mainOverlayView, mainLayoutParams) } catch (e: Exception) { Log.e(TAG, "Erro main overlay: ${e.message}", e); isMainOverlayAdded = false; mainOverlayView = null } }
    private fun hideMainOverlay() { if (isMainOverlayAdded && mainOverlayView != null && windowManager != null) { try { windowManager?.removeViewImmediate(mainOverlayView) } catch (e: Exception) {} finally { isMainOverlayAdded = false; mainOverlayView = null } } }
    private fun showTrackingOverlay(iV: Double?, iD: Double?, iDu: Int?, oV: String?, iKR: IndividualRating, cB: BorderRating) { val wm = windowManager ?: return; if (trackingOverlayView == null) { trackingOverlayView = TrackingOverlayView(this, wm, trackingLayoutParams); applyAppearanceSettingsToView(trackingOverlayView) }; trackingOverlayView?.updateInitialData(iV, iD, iDu, oV, iKR, cB); try { if (!isTrackingOverlayAdded && trackingOverlayView != null) { wm.addView(trackingOverlayView, trackingLayoutParams); isTrackingOverlayAdded = true } else if (isTrackingOverlayAdded && trackingOverlayView != null) wm.updateViewLayout(trackingOverlayView, trackingLayoutParams) } catch (e: Exception) { Log.e(TAG, "Erro tracking overlay: ${e.message}", e); isTrackingOverlayAdded = false; trackingOverlayView = null } }
    private fun hideTrackingOverlay() { if (isTrackingOverlayAdded && trackingOverlayView != null && windowManager != null) { try { windowManager?.removeViewImmediate(trackingOverlayView) } catch (e: Exception) {} finally { isTrackingOverlayAdded = false; trackingOverlayView = null } } }
    @SuppressLint("ClickableViewAccessibility") private fun addFloatingIconOverlay() { if (windowManager == null || isFloatingIconAdded) return; if (floatingIconView == null) { floatingIconView = ImageButton(this).apply { setImageResource(R.drawable.smartdriver); setBackgroundResource(R.drawable.fab_background); scaleType = ScaleType.CENTER_INSIDE; setOnTouchListener(createFloatingIconTouchListener()) } }; try { windowManager?.addView(floatingIconView, floatingIconLayoutParams); isFloatingIconAdded = true } catch (e: Exception) { Log.e(TAG, "Erro add floating icon: ${e.message}", e); isFloatingIconAdded = false; floatingIconView = null } }
    private fun removeFloatingIconOverlay() { if (isFloatingIconAdded && floatingIconView != null && windowManager != null) { try { windowManager?.removeViewImmediate(floatingIconView) } catch (e: Exception) {} finally { isFloatingIconAdded = false; floatingIconView = null } } }
    private fun addQuickMenuOverlay() { if (windowManager == null || isQuickMenuAdded) return; if (quickMenuView == null) { quickMenuView = MenuView(this); updateMenuViewShiftUI() }; try { menuLayoutParams.x = floatingIconLayoutParams.x; menuLayoutParams.y = floatingIconLayoutParams.y + floatingIconLayoutParams.height + (5 * resources.displayMetrics.density).toInt(); windowManager?.addView(quickMenuView, menuLayoutParams); isQuickMenuAdded = true } catch (e: Exception) { Log.e(TAG, "Erro add quick menu: ${e.message}", e); isQuickMenuAdded = false; quickMenuView = null } }
    private fun removeQuickMenuOverlay() { if (isQuickMenuAdded && quickMenuView != null && windowManager != null) { try { windowManager?.removeViewImmediate(quickMenuView) } catch (e: Exception) {} finally { isQuickMenuAdded = false; quickMenuView = null } } }
    @SuppressLint("ClickableViewAccessibility") private fun createFloatingIconTouchListener(): View.OnTouchListener { var iX = 0; var iY = 0; var iTX = 0f; var iTY = 0f; var sT = 0L; var isD = false; return View.OnTouchListener { v, e -> when (e.action) { MotionEvent.ACTION_DOWN -> { iX = floatingIconLayoutParams.x; iY = floatingIconLayoutParams.y; iTX = e.rawX; iTY = e.rawY; sT = System.currentTimeMillis(); isD = false; true } MotionEvent.ACTION_MOVE -> { val dX = abs(e.rawX - iTX); val dY = abs(e.rawY - iTY); if (dX > touchSlop || dY > touchSlop) isD = true; if (isD) { val nX = iX + (e.rawX - iTX).toInt(); val nY = iY + (e.rawY - iTY).toInt(); val sW = Resources.getSystem().displayMetrics.widthPixels; val sH = Resources.getSystem().displayMetrics.heightPixels; floatingIconLayoutParams.x = nX.coerceIn(0, sW - v.width); floatingIconLayoutParams.y = nY.coerceIn(0, sH - v.height); try { if (isFloatingIconAdded) windowManager?.updateViewLayout(floatingIconView, floatingIconLayoutParams) } catch (ex: Exception) {} }; true } MotionEvent.ACTION_UP -> { if (!isD && (System.currentTimeMillis() - sT) < ViewConfiguration.getTapTimeout()) { if (!isQuickMenuAdded) handleShowQuickMenu() else handleDismissMenu(); v.performClick() } else if (isD && isQuickMenuAdded) { try { menuLayoutParams.x = floatingIconLayoutParams.x; menuLayoutParams.y = floatingIconLayoutParams.y + v.height + (5 * resources.displayMetrics.density).toInt(); windowManager?.updateViewLayout(quickMenuView, menuLayoutParams) } catch (ex: Exception) {} }; isD = false; true } else -> false } } }
    @SuppressLint("ClickableViewAccessibility") private fun addDropZoneView() { if (windowManager == null || dropZoneLayoutParams == null) { Log.w(TAG, "DropZone não pode ser adicionada."); return }; Log.d(TAG, "addDropZoneView: Tentando. isAdded=$isDropZoneViewAdded, viewNull=${dropZoneView == null}"); if (isDropZoneViewAdded && dropZoneView != null) { dropZoneView?.visibility = View.VISIBLE; Log.d(TAG, "DropZoneView já estava adicionada, tornando visível."); return }; if (dropZoneView == null) { dropZoneView = ImageView(this).apply { setImageResource(android.R.drawable.ic_menu_close_clear_cancel); alpha = 0.85f; Log.d(TAG, "DropZoneView ImageView criada.") } }; try { dropZoneView?.visibility = View.VISIBLE; windowManager?.addView(dropZoneView, dropZoneLayoutParams); isDropZoneViewAdded = true; Log.i(TAG, "DropZoneView ADICIONADA ao WindowManager.") } catch (e: Exception) { Log.e(TAG, "Erro add DropZoneView: ${e.message}", e); isDropZoneViewAdded = false; } }
    private fun removeDropZoneView() { Log.d(TAG, "removeDropZoneView: Tentando. isAdded=$isDropZoneViewAdded, viewNull=${dropZoneView == null}"); if (!isDropZoneViewAdded || dropZoneView == null || windowManager == null) return; dropZoneView?.visibility = View.GONE; Log.d(TAG, "DropZoneView tornada GONE.") }

    private fun checkDropAndHideZone(touchUpX: Float, touchUpY: Float) {
        var droppedOnTarget = false
        Log.d(TAG, "checkDropAndHideZone: INICIADO. touchUpX=$touchUpX, touchUpY=$touchUpY, isDropZoneAdded=$isDropZoneViewAdded, dropZoneViewNull=${dropZoneView == null}")

        if (isDropZoneViewAdded && dropZoneView != null) {
            dropZoneView?.visibility = View.VISIBLE

            val dropZoneRect = Rect()
            val dropZoneLocation = IntArray(2)
            dropZoneView!!.getLocationOnScreen(dropZoneLocation)

            val dzWidth = dropZoneView!!.width
            val dzHeight = dropZoneView!!.height

            val finalDzWidth = if (dzWidth > 0) dzWidth else dropZoneLayoutParams?.width ?: 0
            val finalDzHeight = if (dzHeight > 0) dzHeight else dropZoneLayoutParams?.height ?: 0

            dropZoneRect.set(
                dropZoneLocation[0],
                dropZoneLocation[1],
                dropZoneLocation[0] + finalDzWidth,
                dropZoneLocation[1] + finalDzHeight
            )

            Log.d(TAG, "checkDropAndHideZone: DropZone LocationOnScreen=(${dropZoneLocation[0]}, ${dropZoneLocation[1]}), Width=$finalDzWidth, Height=$finalDzHeight, Rect Construído: $dropZoneRect")

            if (isTrackingOverlayAdded && trackingOverlayView != null && touchUpX >= 0 && touchUpY >= 0) {
                if (finalDzWidth > 0 && finalDzHeight > 0 && dropZoneRect.contains(touchUpX.toInt(), touchUpY.toInt())) {
                    Log.i(TAG, "PONTO DE SOLTAR ($touchUpX, $touchUpY) ESTÁ DENTRO da DropZone $dropZoneRect. DEScartando tracking!")
                    handleDiscardCurrentTracking()
                    droppedOnTarget = true
                } else {
                    Log.d(TAG, "PONTO DE SOLTAR ($touchUpX, $touchUpY) FORA da DropZone $dropZoneRect.")
                    if (finalDzWidth <= 0 || finalDzHeight <= 0) {
                        Log.w(TAG, "Dimensões da DropZone são zero ou inválidas. Width=$finalDzWidth, Height=$finalDzHeight")
                    }
                }
            } else {
                if (!isTrackingOverlayAdded || trackingOverlayView == null) Log.w(TAG, "checkDropAndHideZone: TrackingOverlayView não adicionado/nulo.")
                if (touchUpX < 0 || touchUpY < 0) Log.w(TAG, "checkDropAndHideZone: Coordenadas de ACTION_UP inválidas.")
            }
        } else {
            Log.w(TAG, "checkDropAndHideZone: DropZone não está adicionada ou é nula.")
        }

        if (!droppedOnTarget) {
            Log.d(TAG, "TrackingOverlayView não foi descartado.")
        }
        Log.d(TAG, "checkDropAndHideZone: Chamando removeDropZoneView.")
        removeDropZoneView()
    }

    // --- Tracking e Histórico ---
    private fun stopTrackingTimer() { trackingUpdateHandler.removeCallbacks(trackingUpdateRunnable) }
    private fun stopTrackingAndSaveToHistory() { if (!isCurrentlyTracking) return; Log.i(TAG, "Finalizando Tracking..."); val eT = System.currentTimeMillis(); stopTrackingTimer(); val fEM = eT - trackingStartTimeMs; val fES = max(MIN_TRACKING_TIME_SEC, fEM / 1000L); var fV: Double? = null; if (trackedOfferValue > 0) { val fH = fES / 3600.0; if (fH > 0) { val c = trackedOfferValue / fH; if (c.isFinite()) fV = c } }; Log.i(TAG, "Dados Finais Viagem: Dur=${fES}s, €/h=${fV?.let { averageDecimalFormat.format(it) } ?: "--"}"); val entry = TripHistoryEntry( startTimeMillis = trackingStartTimeMs, endTimeMillis = eT, durationSeconds = fES, offerValue = trackedOfferValue.takeIf { it > 0 }, initialVph = trackedInitialVph, finalVph = fV, initialVpk = trackedInitialVpk, initialDistanceKm = trackedOfferData?.calculateTotalDistance()?.takeIf { it > 0 }, initialDurationMinutes = trackedOfferData?.calculateTotalTimeMinutes()?.takeIf { it > 0 }, serviceType = trackedOfferData?.serviceType?.takeIf { it.isNotEmpty() }, originalBorderRating = this.trackedCombinedBorderRating ); saveHistoryEntry(entry); if (isShiftActive) { val oV = entry.offerValue ?: 0.0; if (oV > 0.0) { shiftTotalEarnings += oV; Log.i(TAG, "Ganhos REAIS turno: +${currencyFormatter.format(oV)}. Total: ${currencyFormatter.format(shiftTotalEarnings)}"); saveShiftState(); updateMenuViewShiftUI() } }; isCurrentlyTracking = false; trackingStartTimeMs = 0L; trackedOfferData = null; trackedOfferValue = 0.0; trackedInitialVph = null; trackedInitialVpk = null; trackedInitialKmRating = IndividualRating.UNKNOWN; trackedCombinedBorderRating = BorderRating.GRAY; hideTrackingOverlay(); updateShiftNotification() }
    private fun saveHistoryEntry(nE: TripHistoryEntry) { try { val nJ = gson.toJson(nE); val cJ = historyPrefs.getString(KEY_TRIP_HISTORY, "[]"); val lT = object : TypeToken<MutableList<String>>() {}.type; val l: MutableList<String> = try { gson.fromJson(cJ, lT) ?: mutableListOf() } catch (e: Exception) { mutableListOf() }; l.add(nJ); historyPrefs.edit().putString(KEY_TRIP_HISTORY, gson.toJson(l)).apply() } catch (e: Exception) { Log.e(TAG, "ERRO salvar histórico: ${e.message}", e) } }

    // --- Aparência ---
    private fun applyAppearanceSettings(fSPercent: Int, tPercent: Int) { applyAppearanceSettingsToView(mainOverlayView, fSPercent, tPercent); applyAppearanceSettingsToView(trackingOverlayView, null, tPercent); applyAppearanceSettingsToView(quickMenuView, null, tPercent) }
    private fun applyAppearanceSettingsToView(v: View?, fSPercent: Int? = null, tPercent: Int? = null) { v ?: return; val fT = tPercent ?: SettingsActivity.getTransparency(this); val a = (1.0f - (fT / 100f)).coerceIn(0.0f, 1.0f); when (v) { is OverlayView -> { val fFS = fSPercent ?: SettingsActivity.getFontSize(this); v.updateFontSize(fFS / 100f); v.updateAlpha(a) }; is TrackingOverlayView, is MenuView -> v.alpha = a; else -> v.alpha = a } }
    private fun updateLayouts() { if (isMainOverlayAdded && mainOverlayView != null) try { windowManager?.updateViewLayout(mainOverlayView, mainLayoutParams) } catch (e: Exception) {}; if (isTrackingOverlayAdded && trackingOverlayView != null) try { windowManager?.updateViewLayout(trackingOverlayView, trackingLayoutParams) } catch (e: Exception) {}; if (isFloatingIconAdded && floatingIconView != null) try { windowManager?.updateViewLayout(floatingIconView, floatingIconLayoutParams) } catch (e: Exception) {}; if (isQuickMenuAdded && quickMenuView != null) try { windowManager?.updateViewLayout(quickMenuView, menuLayoutParams) } catch (e: Exception) {} }

    // --- Lógica do Turno ---
    private fun loadShiftState() { isShiftActive = shiftPrefs.getBoolean(KEY_SHIFT_ACTIVE, false); isShiftPaused = shiftPrefs.getBoolean(KEY_SHIFT_PAUSED, false); shiftStartTimeMillis = shiftPrefs.getLong(KEY_SHIFT_START_TIME, 0L); shiftLastPauseOrResumeTimeMillis = shiftPrefs.getLong(KEY_SHIFT_LAST_PAUSE_OR_RESUME_TIME, 0L); shiftAccumulatedWorkedTimeMillis = shiftPrefs.getLong(KEY_SHIFT_ACCUMULATED_WORKED_TIME, 0L); shiftTotalEarnings = shiftPrefs.getFloat(KEY_SHIFT_TOTAL_EARNINGS, 0f).toDouble(); shiftTargetEarnings = shiftPrefs.getFloat(KEY_SHIFT_TARGET_EARNINGS, 0f).toDouble(); Log.i(TAG, "Estado Turno Carregado: Ativo=$isShiftActive, Pausado=$isShiftPaused, Meta=${currencyFormatter.format(shiftTargetEarnings)}"); if (isShiftActive && !isShiftPaused && shiftLastPauseOrResumeTimeMillis > 0) { isShiftPaused = true; shiftLastPauseOrResumeTimeMillis = System.currentTimeMillis(); Log.i(TAG, "Turno ativo. Forçando pausa."); saveShiftState() } else if (isShiftActive && !isShiftPaused && shiftLastPauseOrResumeTimeMillis == 0L && shiftStartTimeMillis > 0L) { shiftLastPauseOrResumeTimeMillis = System.currentTimeMillis(); isShiftPaused = true; Log.i(TAG, "Turno iniciado sem eventos. Forçando pausa."); saveShiftState() } }
    private fun saveShiftState() { if (!isShiftActive && shiftStartTimeMillis == 0L) { shiftPrefs.edit().clear().apply(); Log.i(TAG, "Estado turno e meta limpos."); return }; shiftPrefs.edit().apply { putBoolean(KEY_SHIFT_ACTIVE, isShiftActive); putBoolean(KEY_SHIFT_PAUSED, isShiftPaused); putLong(KEY_SHIFT_START_TIME, shiftStartTimeMillis); putLong(KEY_SHIFT_LAST_PAUSE_OR_RESUME_TIME, shiftLastPauseOrResumeTimeMillis); putLong(KEY_SHIFT_ACCUMULATED_WORKED_TIME, shiftAccumulatedWorkedTimeMillis); putFloat(KEY_SHIFT_TOTAL_EARNINGS, shiftTotalEarnings.toFloat()); putFloat(KEY_SHIFT_TARGET_EARNINGS, shiftTargetEarnings.toFloat()); apply() }; Log.d(TAG, "Estado Turno Salvo: Ativo=$isShiftActive, Meta=${currencyFormatter.format(shiftTargetEarnings)}") }
    private fun startShiftTimer() { shiftTimerRunnable?.let { shiftTimerHandler.removeCallbacks(it); shiftTimerHandler.post(it) } ?: run { setupShiftTimerRunnable(); shiftTimerHandler.post(shiftTimerRunnable!!) }; updateShiftNotification() }
    private fun stopShiftTimer() { shiftTimerRunnable?.let { shiftTimerHandler.removeCallbacks(it) }; updateShiftNotification() }
    private fun calculateCurrentWorkedTimeMillis(): Long { if (!isShiftActive) return 0L; var cWT = shiftAccumulatedWorkedTimeMillis; if (!isShiftPaused) { val tS = System.currentTimeMillis() - shiftLastPauseOrResumeTimeMillis; if (tS > 0) cWT += tS }; return max(0L, cWT) }
    private fun formatDuration(millis: Long): String { if (millis < 0) return "00:00:00"; val h = TimeUnit.MILLISECONDS.toHours(millis); val m = TimeUnit.MILLISECONDS.toMinutes(millis) % 60; val s = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;return String.format(Locale.getDefault(), "%02d:%02d",h,m) } // TODO: Rever formatação para incluir segundos se necessário ou HH:mm
    private fun getNumericShiftAveragePerHourValue(cWTMParam: Long? = null): Double? { if (!isShiftActive) return null; val tRE = shiftTotalEarnings; val wM = cWTMParam ?: calculateCurrentWorkedTimeMillis(); if (wM < 5000L) return if (tRE > 0.0) null else 0.0; val wH = wM / 3600000.0; if (wH <= 0.0) return if (tRE > 0.0) null else 0.0; return tRE / wH }
    private fun calculateCurrentShiftAveragePerHourString(avgNum: Double?): String { return when { avgNum != null && avgNum.isFinite() -> "${averageDecimalFormat.format(avgNum)} €/h"; isShiftActive && shiftTotalEarnings > 0.0 && calculateCurrentWorkedTimeMillis() < 5000L -> getString(R.string.shift_calculating_average); isShiftActive && avgNum == 0.0 -> "0.0 €/h"; else -> "-- €/h" } }
    private fun calculateAndFormatTimeToTarget(): String { if (!isShiftActive) return "--:--:--"; if (shiftTargetEarnings <= 0.0) return getString(R.string.shift_target_not_set); val gM = shiftTargetEarnings - shiftTotalEarnings; if (gM <= 0.0) return getString(R.string.shift_target_reached); val cAvgPH = getNumericShiftAveragePerHourValue(); return if (cAvgPH != null && cAvgPH > 0.0 && cAvgPH.isFinite()) { val hTT = gM / cAvgPH; val mTT = (hTT * 3600000.0).toLong(); if (mTT < 0) getString(R.string.shift_target_reached) else formatDuration(mTT) } else { getString(R.string.shift_calculating_time_to_target) } }

    private fun updateMenuViewShiftUI() {
        quickMenuView?.let { menu ->
            val sTK = if (!isShiftActive) R.string.shift_status_none else if (isShiftPaused) R.string.shift_status_paused else R.string.shift_status_active
            val wTM = calculateCurrentWorkedTimeMillis()
            val fT = formatDuration(wTM)
            val avgNum = getNumericShiftAveragePerHourValue(wTM)
            val avgStr = calculateCurrentShiftAveragePerHourString(avgNum)
            val tTTStr = calculateAndFormatTimeToTarget()
            val expectedEndTime: String = calculateAndFormatExpectedEndTime()
            val metaFormatada = currencyFormatter.format(shiftTargetEarnings)
            menu.updateShiftTarget(metaFormatada)
            Log.d(
                TAG,
                "updateMenuViewShiftUI: TMeta=$tTTStr, Meta=${currencyFormatter.format(shiftTargetEarnings)}, GReais=${currencyFormatter.format(shiftTotalEarnings)}, FimPrevisto=$expectedEndTime"
            )
            menu.updateShiftStatus(getString(sTK), isShiftActive, isShiftPaused)
            menu.updateShiftTimer(fT)
            menu.updateTimeToTarget(tTTStr)
            menu.updateShiftAverage(avgStr)
            menu.updateExpectedEndTime(expectedEndTime)
        }
    }

    private fun updateShiftNotification() {
        val txt = when {
            isCurrentlyTracking -> getString(R.string.notification_tracking_trip)
            isShiftActive && !isShiftPaused -> getString(R.string.notification_shift_active, formatDuration(calculateCurrentWorkedTimeMillis()))
            isShiftActive && isShiftPaused -> getString(R.string.notification_shift_paused)
            else -> getString(R.string.notification_overlay_ready)
        }
        updateNotification(txt, isCurrentlyTracking || (isShiftActive && !isShiftPaused))
    }

    // -------- FUNÇÃO MOVIDA PARA DENTRO DA CLASSE --------
    private fun calculateAndFormatExpectedEndTime(): String {
        if (!isShiftActive) return getString(R.string.expected_end_time_placeholder) // Ex: "--:--" ou "Turno não ativo"
        if (shiftTargetEarnings <= 0.0) return getString(R.string.expected_end_time_placeholder) // Ex: "Meta não definida"

        val ganhosFaltam = shiftTargetEarnings - shiftTotalEarnings
        if (ganhosFaltam <= 0.0) return getString(R.string.shift_target_reached) // Ex: "Meta Atingida!"

        val avgPH = getNumericShiftAveragePerHourValue()
        if (avgPH == null || avgPH <= 0.0 || !avgPH.isFinite()) {
            // Se a média não puder ser calculada ou for zero/negativa, não podemos prever.
            return getString(R.string.expected_end_time_placeholder) // Ex: "Calculando..." ou "--:--"
        }

        val horasRestantes = ganhosFaltam / avgPH
        val millisRestantes = (horasRestantes * 3600000.0).toLong()

        // Adicionar uma verificação para millisRestantes, caso seja um valor excessivamente grande ou negativo inesperado
        if (millisRestantes < 0) return getString(R.string.shift_target_reached) // Segurança adicional
        // Poderia adicionar um limite superior para millisRestantes para evitar datas muito distantes se avgPH for muito pequeno.

        val horaPrevistaMillis = System.currentTimeMillis() + millisRestantes

        return try {
            // 'this' aqui é o Contexto do Serviço
            val formatoHora = android.text.format.DateFormat.getTimeFormat(this)
            formatoHora.format(Date(horaPrevistaMillis)) // Usa java.util.Date
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao formatar hora prevista de fim: ${e.message}", e)
            "--:--" // Fallback em caso de erro na formatação
        }
    }
    // -------- FIM DA FUNÇÃO MOVIDA --------
}