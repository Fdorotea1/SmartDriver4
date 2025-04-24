package com.example.smartdriver.overlay // <<< VERIFIQUE O PACKAGE

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView.ScaleType
import androidx.core.app.NotificationCompat
import com.example.smartdriver.R
import com.example.smartdriver.SettingsActivity
import com.example.smartdriver.ScreenCaptureService
import com.example.smartdriver.MediaProjectionData
import com.example.smartdriver.MainActivity
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.EvaluationResult
import com.example.smartdriver.utils.IndividualRating
import com.example.smartdriver.utils.TripHistoryEntry
import com.example.smartdriver.utils.BorderRating
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        // ... (Constantes inalteradas) ...
        private const val NOTIFICATION_ID = 1002; private const val CHANNEL_ID = "overlay_service_channel"; private const val CHANNEL_NAME = "Overlay Service"; const val ACTION_SHOW_OVERLAY = "com.example.smartdriver.overlay.SHOW_OVERLAY"; const val ACTION_HIDE_OVERLAY = "com.example.smartdriver.overlay.HIDE_OVERLAY"; const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.overlay.UPDATE_SETTINGS"; const val ACTION_START_TRACKING = "com.example.smartdriver.overlay.START_TRACKING"; const val ACTION_STOP_TRACKING = "com.example.smartdriver.overlay.STOP_TRACKING"; const val ACTION_SHOW_QUICK_MENU = "com.example.smartdriver.overlay.SHOW_QUICK_MENU"; const val ACTION_DISMISS_MENU = "com.example.smartdriver.overlay.DISMISS_MENU"; const val ACTION_REQUEST_SHUTDOWN = "com.example.smartdriver.overlay.REQUEST_SHUTDOWN"; const val EXTRA_EVALUATION_RESULT = "evaluation_result"; const val EXTRA_OFFER_DATA = "offer_data"; const val EXTRA_FONT_SIZE = "font_size"; const val EXTRA_TRANSPARENCY = "transparency"; private const val TRACKING_UPDATE_INTERVAL_MS = 1000L; private const val MIN_TRACKING_TIME_SEC = 1L; const val HISTORY_PREFS_NAME = "SmartDriverHistoryPrefs"; const val KEY_TRIP_HISTORY = "trip_history_list_json"; @JvmStatic val isRunning = AtomicBoolean(false)
    }

    private var windowManager: WindowManager? = null
    private var mainOverlayView: OverlayView? = null
    private var trackingOverlayView: TrackingOverlayView? = null
    private var quickMenuView: MenuView? = null
    private var floatingIconView: ImageButton? = null
    private lateinit var floatingIconLayoutParams: WindowManager.LayoutParams
    private lateinit var mainLayoutParams: WindowManager.LayoutParams
    private lateinit var trackingLayoutParams: WindowManager.LayoutParams
    private lateinit var menuLayoutParams: WindowManager.LayoutParams
    private var isMainOverlayAdded = false; private var isTrackingOverlayAdded = false
    private var isFloatingIconAdded = false; private var isQuickMenuAdded = false
    private var isCurrentlyTracking = false; private var trackingStartTimeMs: Long = 0L
    private var trackedOfferData: OfferData? = null; private var trackedInitialVph: Double? = null
    private var trackedInitialVpk: Double? = null; private var trackedOfferValue: Double = 0.0
    private var trackedInitialKmRating: IndividualRating = IndividualRating.UNKNOWN
    private var trackedCombinedBorderRating: BorderRating = BorderRating.GRAY
    private val trackingUpdateHandler = Handler(Looper.getMainLooper()); private lateinit var trackingUpdateRunnable: Runnable
    private var goodHourThreshold: Double = 15.0; private var poorHourThreshold: Double = 8.0
    private val gson = Gson(); private lateinit var historyPrefs: SharedPreferences
    private var initialIconX: Int = 0; private var initialIconY: Int = 0
    private var initialTouchX: Float = 0f; private var initialTouchY: Float = 0f
    private var touchSlop: Int = 0
    // --- Variáveis de arrasto da Tracking View REMOVIDAS ---

    override fun onCreate() {
        super.onCreate(); Log.i(TAG, "Serviço Overlay CRIADO"); isRunning.set(true)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        historyPrefs = getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        loadTrackingThresholds(); initializeMainLayoutParams(); initializeTrackingLayoutParams()
        initializeMenuLayoutParams(); initializeFloatingIconLayoutParams(); setupTrackingRunnable()
        startForeground(NOTIFICATION_ID, createNotification("Overlay pronto", false)); addFloatingIconOverlay()
    }

    // --- Funções de Inicialização ---
    private fun loadTrackingThresholds() { try { goodHourThreshold = SettingsActivity.getGoodHourThreshold(this); poorHourThreshold = SettingsActivity.getPoorHourThreshold(this) } catch (e: Exception) { goodHourThreshold = 15.0; poorHourThreshold = 8.0 } }
    private fun initializeMainLayoutParams() { val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE; val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS; mainLayoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType, flags, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (50 * resources.displayMetrics.density).toInt() } }
    private fun initializeTrackingLayoutParams() { val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE; val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS; trackingLayoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType, flags, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = (10 * resources.displayMetrics.density).toInt(); y = (80 * resources.displayMetrics.density).toInt() } }
    private fun initializeMenuLayoutParams() { val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE; val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL; menuLayoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType, flags, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = (10 * resources.displayMetrics.density).toInt(); y = (80 * resources.displayMetrics.density).toInt() } }
    private fun initializeFloatingIconLayoutParams() { val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE; val flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN); val iconSizeDp = 60; val iconSizePx = (iconSizeDp * resources.displayMetrics.density).toInt(); floatingIconLayoutParams = WindowManager.LayoutParams(iconSizePx, iconSizePx, overlayType, flags, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = (10 * resources.displayMetrics.density).toInt(); y = (10 * resources.displayMetrics.density).toInt() } }
    private fun setupTrackingRunnable() { trackingUpdateRunnable = object : Runnable { override fun run() { if (isCurrentlyTracking && trackingOverlayView != null) { val elapsedMillis = System.currentTimeMillis() - trackingStartTimeMs; val elapsedSeconds = max(MIN_TRACKING_TIME_SEC, elapsedMillis / 1000); var currentVph: Double? = null; if (trackedOfferValue > 0) { val elapsedHours = elapsedSeconds / 3600.0; if (elapsedHours > 0) { val calc = trackedOfferValue / elapsedHours; if (calc.isFinite()) currentVph = calc } }; val currentHourRating = when { currentVph == null -> IndividualRating.UNKNOWN; currentVph >= goodHourThreshold -> IndividualRating.GOOD; currentVph <= poorHourThreshold -> IndividualRating.POOR; else -> IndividualRating.MEDIUM }; trackingOverlayView?.updateRealTimeData(currentVph, currentHourRating, elapsedSeconds); trackingUpdateHandler.postDelayed(this, TRACKING_UPDATE_INTERVAL_MS) } } } }
    private fun createNotification(contentText: String, isTracking: Boolean): Notification { createNotificationChannel(); val smallIconResId = try { if (isTracking) R.drawable.ic_stat_tracking else R.mipmap.ic_launcher } catch (e: Resources.NotFoundException) { R.mipmap.ic_launcher }; return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("SmartDriver").setContentText(contentText).setSmallIcon(smallIconResId).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW).build() }
    private fun updateNotification(contentText: String, isTracking: Boolean) { val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; try { nm.notify(NOTIFICATION_ID, createNotification(contentText, isTracking)) } catch (e: Exception) {} }
    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply { description = "Overlay Service Notification"; enableLights(false); enableVibration(false); setShowBadge(false) }; val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; try { nm.createNotificationChannel(channel) } catch (e: Exception) {} } }

    // --- Tratamento de Intents ---
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { Log.d(TAG, "onStartCommand: Action=${intent?.action}"); when (intent?.action) { ACTION_SHOW_OVERLAY -> handleShowOverlay(intent); ACTION_HIDE_OVERLAY -> handleHideOverlay(); ACTION_START_TRACKING -> handleStartTracking(intent); ACTION_STOP_TRACKING -> handleStopTracking(); ACTION_UPDATE_SETTINGS -> handleUpdateSettings(intent); ACTION_SHOW_QUICK_MENU -> handleShowQuickMenu(); ACTION_DISMISS_MENU -> handleDismissMenu(); ACTION_REQUEST_SHUTDOWN -> handleShutdownRequest(); else -> Log.w(TAG, "Ação desconhecida/nula: ${intent?.action}") }; return START_REDELIVER_INTENT }
    private fun handleShowOverlay(intent: Intent?) { val evalResult: EvaluationResult? = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java); val offerData: OfferData? = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java); if (evalResult != null && offerData != null) { showMainOverlay(evalResult, offerData); updateNotification("Oferta: ${offerData.value}€", isCurrentlyTracking) } else { hideMainOverlay() } }
    private fun handleHideOverlay() { Log.d(TAG, "$ACTION_HIDE_OVERLAY recebido."); if (isCurrentlyTracking) { stopTrackingAndSaveToHistory() }; hideMainOverlay(); removeQuickMenuOverlay(); updateNotification("Overlay pronto", false) }
    private fun handleStartTracking(intent: Intent?) { val offerDataToTrack: OfferData? = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java); val initialEvaluationResult: EvaluationResult? = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java); if (offerDataToTrack != null && initialEvaluationResult != null) { if (!isCurrentlyTracking) { Log.i(TAG, "Iniciando Tracking: ${offerDataToTrack.value}€"); hideMainOverlay(); removeQuickMenuOverlay(); isCurrentlyTracking = true; trackingStartTimeMs = System.currentTimeMillis(); trackedOfferData = offerDataToTrack; trackedOfferValue = offerDataToTrack.value.replace(",", ".").toDoubleOrNull() ?: 0.0; trackedInitialVph = offerDataToTrack.calculateValuePerHour(); trackedInitialVpk = offerDataToTrack.calculateProfitability(); trackedInitialKmRating = initialEvaluationResult.kmRating; trackedCombinedBorderRating = initialEvaluationResult.combinedBorderRating; loadTrackingThresholds(); val initialDist = offerDataToTrack.calculateTotalDistance()?.let { if (it > 0) it else null }; val initialDur = offerDataToTrack.calculateTotalTimeMinutes()?.let { if (it > 0) it else null }; val offerValStr = offerDataToTrack.value; showTrackingOverlay(trackedInitialVpk, initialDist, initialDur, offerValStr, trackedInitialKmRating, trackedCombinedBorderRating); trackingUpdateHandler.removeCallbacks(trackingUpdateRunnable); trackingUpdateHandler.post(trackingUpdateRunnable); updateNotification("Acompanhando Viagem...", true) } else { Log.w(TAG, "$ACTION_START_TRACKING ignorado.") } } else { Log.e(TAG, "$ACTION_START_TRACKING sem dados.") } }
    private fun handleStopTracking() { Log.i(TAG, "$ACTION_STOP_TRACKING recebido."); stopTrackingAndSaveToHistory() }
    private fun handleUpdateSettings(intent: Intent?) { Log.d(TAG, "$ACTION_UPDATE_SETTINGS recebido."); loadTrackingThresholds(); val defSize = SettingsActivity.getFontSize(this); val defTrans = SettingsActivity.getTransparency(this); val size = intent?.getIntExtra(EXTRA_FONT_SIZE, defSize) ?: defSize; val trans = intent?.getIntExtra(EXTRA_TRANSPARENCY, defTrans) ?: defTrans; applyAppearanceSettings(size, trans); updateLayouts() }
    private fun handleShowQuickMenu() { Log.d(TAG, "$ACTION_SHOW_QUICK_MENU recebido."); addQuickMenuOverlay() }
    private fun handleDismissMenu() { Log.d(TAG, "$ACTION_DISMISS_MENU recebido."); removeQuickMenuOverlay() }
    private fun handleShutdownRequest() { Log.w(TAG, "$ACTION_REQUEST_SHUTDOWN recebido! Desligando..."); removeQuickMenuOverlay(); stopTrackingAndSaveToHistory(); hideMainOverlay(); removeFloatingIconOverlay(); try { stopService(Intent(this, ScreenCaptureService::class.java)) } catch (e: Exception) { }; MediaProjectionData.clear(); val shutdownIntent = Intent(MainActivity.ACTION_SHUTDOWN_APP); try { sendBroadcast(shutdownIntent) } catch (e: Exception) { }; stopSelf() }
    private fun <T : Any> getParcelableExtraCompat(intent: Intent?, key: String, clazz: Class<T>): T? { return intent?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { it.getParcelableExtra(key, clazz) } else { @Suppress("DEPRECATION") it.getParcelableExtra(key) as? T } } }

    // --- Gestão das Views Overlay ---
    private fun showMainOverlay(evaluationResult: EvaluationResult, offerData: OfferData) { if (windowManager == null) return; if (mainOverlayView == null) { mainOverlayView = OverlayView(this); applyAppearanceSettingsToView(mainOverlayView) }; mainOverlayView?.updateState(evaluationResult, offerData); try { if (!isMainOverlayAdded) { windowManager?.addView(mainOverlayView, mainLayoutParams); isMainOverlayAdded = true } else { windowManager?.updateViewLayout(mainOverlayView, mainLayoutParams) } } catch (e: Exception) { isMainOverlayAdded = false } }
    private fun hideMainOverlay() { if (isMainOverlayAdded && mainOverlayView != null) { try { windowManager?.removeView(mainOverlayView) } catch (_: Exception) {} finally { isMainOverlayAdded = false; mainOverlayView = null } } }

    // --- showTrackingOverlay MODIFICADO para passar WM e Params ---
    private fun showTrackingOverlay( initialVpk: Double?, initialDistance: Double?, initialDuration: Int?, offerVal: String?, initialKmRating: IndividualRating, combinedBorderRating: BorderRating ) {
        val currentWindowManager = windowManager ?: return
        if (trackingOverlayView == null) {
            Log.d(TAG, "Criando nova TrackingOverlayView...")
            // <<< PASSA WM e Params >>>
            trackingOverlayView = TrackingOverlayView(this, currentWindowManager, trackingLayoutParams)
            applyAppearanceSettingsToView(trackingOverlayView)
            // <<< Listener REMOVIDO daqui >>>
        }
        trackingOverlayView?.updateInitialData(initialVpk, initialDistance, initialDuration, offerVal, initialKmRating, combinedBorderRating)
        try {
            if (!isTrackingOverlayAdded && trackingOverlayView != null) {
                currentWindowManager.addView(trackingOverlayView, trackingLayoutParams)
                isTrackingOverlayAdded = true; Log.i(TAG, "Overlay Acompanhamento ADD.")
            } else {
                Log.d(TAG, "TrackingOverlay já existe, dados atualizados.")
            }
        } catch (e: Exception) { Log.e(TAG, "Erro add/upd TrackingOverlay: ${e.message}"); isTrackingOverlayAdded = false }
    }
    // --- Fim showTrackingOverlay ---

    private fun hideTrackingOverlay() { if (isTrackingOverlayAdded && trackingOverlayView != null) { try { windowManager?.removeView(trackingOverlayView) } catch (_: Exception) {} finally { isTrackingOverlayAdded = false; trackingOverlayView = null } } }
    @SuppressLint("ClickableViewAccessibility") private fun addFloatingIconOverlay() { if (windowManager == null || isFloatingIconAdded) return; if (floatingIconView == null) { floatingIconView = ImageButton(this).apply { setImageResource(R.drawable.smartdriver); setBackgroundResource(R.drawable.fab_background); scaleType = ScaleType.CENTER_INSIDE; setOnTouchListener(createFloatingIconTouchListener()) } }; try { windowManager?.addView(floatingIconView, floatingIconLayoutParams); isFloatingIconAdded = true } catch (e: Exception) { isFloatingIconAdded = false; floatingIconView = null } }
    private fun removeFloatingIconOverlay() { if (isFloatingIconAdded && floatingIconView != null) { try { windowManager?.removeView(floatingIconView) } catch (_: Exception) {} finally { isFloatingIconAdded = false; floatingIconView = null } } }
    private fun addQuickMenuOverlay() { if (windowManager == null || isQuickMenuAdded) return; if (quickMenuView == null) { quickMenuView = MenuView(this) }; try { windowManager?.addView(quickMenuView, menuLayoutParams); isQuickMenuAdded = true } catch (e: Exception) { isQuickMenuAdded = false; quickMenuView = null } }
    private fun removeQuickMenuOverlay() { if (isQuickMenuAdded && quickMenuView != null) { try { windowManager?.removeView(quickMenuView) } catch (_: Exception) {} finally { isQuickMenuAdded = false; quickMenuView = null } } }

    // --- REMOVIDA a função createTrackingViewTouchListener ---

    // --- Listener do Ícone Flutuante ---
    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingIconTouchListener(): View.OnTouchListener { var startTime: Long = 0; return View.OnTouchListener { view, event -> val currentRawX = event.rawX; val currentRawY = event.rawY; val action = event.action; when (action) { MotionEvent.ACTION_DOWN -> { startTime = System.currentTimeMillis(); initialIconX = floatingIconLayoutParams.x; initialIconY = floatingIconLayoutParams.y; initialTouchX = currentRawX; initialTouchY = currentRawY; /*Log*/; return@OnTouchListener true }; MotionEvent.ACTION_MOVE -> { val dX = currentRawX - initialTouchX; val dY = currentRawY - initialTouchY; val newX = initialIconX + dX.toInt(); val newY = initialIconY + dY.toInt(); /*Log*/; floatingIconLayoutParams.x = newX; floatingIconLayoutParams.y = newY; try { if (isFloatingIconAdded) windowManager?.updateViewLayout(floatingIconView, floatingIconLayoutParams) } catch (e: Exception) { }; return@OnTouchListener true }; MotionEvent.ACTION_UP -> { val duration = System.currentTimeMillis() - startTime; val dX = currentRawX - initialTouchX; val dY = currentRawY - initialTouchY; val isClick = abs(dX) < touchSlop && abs(dY) < touchSlop && duration < ViewConfiguration.getTapTimeout(); if (isClick) { /*Log*/; if (!isQuickMenuAdded) { handleShowQuickMenu() } else { handleDismissMenu() }; view.performClick() } else { /*Log*/ }; return@OnTouchListener true } }; return@OnTouchListener false } }

    // --- Funções de Tracking e Salvamento ---
    private fun stopTrackingTimer() { trackingUpdateHandler.removeCallbacks(trackingUpdateRunnable) }
    private fun stopTrackingAndSaveToHistory() { if (!isCurrentlyTracking) { return }; Log.i(TAG, "Finalizando Tracking e Salvando"); val endTimeMs = System.currentTimeMillis(); stopTrackingTimer(); val finalElapsedTimeMs = endTimeMs - trackingStartTimeMs; val finalElapsedTimeSec = max(MIN_TRACKING_TIME_SEC, finalElapsedTimeMs / 1000); var finalVph : Double? = null; if (trackedOfferValue > 0) { val finalHours = finalElapsedTimeSec / 3600.0; if (finalHours > 0) { val calc = trackedOfferValue / finalHours; if (calc.isFinite()) { finalVph = calc } } }; Log.i(TAG, "Dados Finais: Duração=${finalElapsedTimeSec}s, €/h Final=${finalVph?.let { String.format(Locale.US, "%.1f", it) } ?: "--"}"); val entry = TripHistoryEntry(startTimeMillis = trackingStartTimeMs, endTimeMillis = endTimeMs, durationSeconds = finalElapsedTimeSec, offerValue = trackedOfferValue.takeIf { it > 0 }, initialVph = trackedInitialVph, finalVph = finalVph, initialVpk = trackedInitialVpk, initialDistanceKm = trackedOfferData?.calculateTotalDistance()?.let { if (it > 0) it else null }, initialDurationMinutes = trackedOfferData?.calculateTotalTimeMinutes()?.let { if (it > 0) it else null }, serviceType = trackedOfferData?.serviceType?.takeIf { it.isNotEmpty() }); saveHistoryEntry(entry); isCurrentlyTracking = false; trackingStartTimeMs = 0L; trackedOfferData = null; trackedOfferValue = 0.0; trackedInitialVph = null; trackedInitialVpk = null; trackedInitialKmRating = IndividualRating.UNKNOWN; trackedCombinedBorderRating = BorderRating.GRAY; hideTrackingOverlay(); updateNotification("Overlay pronto", false) }
    private fun saveHistoryEntry(newEntry: TripHistoryEntry) { try { val newJson = gson.toJson(newEntry); val currentJson = historyPrefs.getString(KEY_TRIP_HISTORY, "[]"); val listType = object : TypeToken<MutableList<String>>() {}.type; val list: MutableList<String> = gson.fromJson(currentJson, listType) ?: mutableListOf(); list.add(newJson); val updatedJson = gson.toJson(list); historyPrefs.edit().putString(KEY_TRIP_HISTORY, updatedJson).apply(); Log.i(TAG,"Histórico salvo. Total: ${list.size}") } catch (e: Exception) { Log.e(TAG, "ERRO salvar histórico: ${e.message}", e) } }

    // --- Gestão Aparência ---
    private fun applyAppearanceSettings(fontSizePercent: Int, transparencyPercent: Int) { applyAppearanceSettingsToView(mainOverlayView, fontSizePercent, transparencyPercent); applyAppearanceSettingsToView(trackingOverlayView, null, transparencyPercent) }
    private fun applyAppearanceSettingsToView(view: View?, fontSizePercent: Int? = null, transparencyPercent: Int? = null) { view ?: return; val trans = transparencyPercent ?: SettingsActivity.getTransparency(this); val alpha = (1.0f - (trans / 100f)).coerceIn(0.0f, 1.0f); when (view) { is OverlayView -> { val size = fontSizePercent ?: SettingsActivity.getFontSize(this); val scale = size / 100f; view.updateFontSize(scale); view.updateAlpha(alpha) }; is TrackingOverlayView -> { view.alpha = alpha } } }
    private fun updateLayouts() { if (isMainOverlayAdded) { try { windowManager?.updateViewLayout(mainOverlayView, mainLayoutParams) } catch (_: Exception) {} }; if (isFloatingIconAdded) { try { windowManager?.updateViewLayout(floatingIconView, floatingIconLayoutParams) } catch (_: Exception) {} }; if (isQuickMenuAdded) { try { windowManager?.updateViewLayout(quickMenuView, menuLayoutParams) } catch (_: Exception) {} } /* Tracking view atualiza-se sozinha */ }

    // --- onBind e onDestroy ---
    override fun onBind(intent: Intent?): IBinder? { return null }
    override fun onDestroy() { super.onDestroy(); Log.w(TAG, "Serviço Overlay DESTRUÍDO"); isRunning.set(false); stopTrackingTimer(); hideMainOverlay(); hideTrackingOverlay(); removeQuickMenuOverlay(); removeFloatingIconOverlay(); mainOverlayView = null; trackingOverlayView = null; windowManager = null; quickMenuView = null; floatingIconView = null; try { val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; nm.cancel(NOTIFICATION_ID) } catch (_: Exception){} }

} // Fim da classe