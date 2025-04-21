package com.example.smartdriver.overlay // <<< VERIFIQUE O PACKAGE

// <<< Imports Essenciais >>>
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service // <<< IMPORT PARA Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.smartdriver.R
import com.example.smartdriver.SettingsActivity
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.EvaluationResult
import com.example.smartdriver.utils.IndividualRating
import com.example.smartdriver.utils.TripHistoryEntry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
// <<< Fim Imports Essenciais >>>

// --- CORREÇÃO DA DECLARAÇÃO DA CLASSE ---
class OverlayService : Service() { // <<< Adicionado ": Service()"
// ------------------------------------

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val CHANNEL_NAME = "Overlay Service"
        const val ACTION_SHOW_OVERLAY = "com.example.smartdriver.overlay.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.example.smartdriver.overlay.HIDE_OVERLAY"
        const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.overlay.UPDATE_SETTINGS"
        const val ACTION_START_TRACKING = "com.example.smartdriver.overlay.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.smartdriver.overlay.STOP_TRACKING"
        const val EXTRA_EVALUATION_RESULT = "evaluation_result"
        const val EXTRA_OFFER_DATA = "offer_data"
        const val EXTRA_FONT_SIZE = "font_size"
        const val EXTRA_TRANSPARENCY = "transparency"
        private const val TRACKING_UPDATE_INTERVAL_MS = 1000L
        private const val MIN_TRACKING_TIME_SEC = 1L
        const val HISTORY_PREFS_NAME = "SmartDriverHistoryPrefs"
        const val KEY_TRIP_HISTORY = "trip_history_list_json"
        @JvmStatic val isRunning = AtomicBoolean(false)
    }

    private var windowManager: WindowManager? = null
    private var mainOverlayView: OverlayView? = null
    private var trackingOverlayView: TrackingOverlayView? = null
    private lateinit var mainLayoutParams: WindowManager.LayoutParams
    private lateinit var trackingLayoutParams: WindowManager.LayoutParams
    private var isMainOverlayAdded = false
    private var isTrackingOverlayAdded = false
    private var isCurrentlyTracking = false
    private var trackingStartTimeMs: Long = 0L
    private var trackedOfferData: OfferData? = null
    private var trackedInitialVph: Double? = null
    private var trackedInitialVpk: Double? = null
    private var trackedOfferValue: Double = 0.0
    private val trackingUpdateHandler = Handler(Looper.getMainLooper())
    private lateinit var trackingUpdateRunnable: Runnable
    private var goodHourThreshold: Double = 15.0
    private var poorHourThreshold: Double = 8.0
    private val gson = Gson()
    private lateinit var historyPrefs: SharedPreferences

    // --- Métodos do Ciclo de Vida (agora fazem override corretamente) ---
    override fun onCreate() {
        super.onCreate() // <<< Chamada a super.onCreate() é importante
        Log.i(TAG, "Serviço Overlay CRIADO")
        isRunning.set(true)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager // getSystemService agora funciona
        historyPrefs = getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE) // getSharedPreferences agora funciona
        loadTrackingThresholds()
        initializeMainLayoutParams()
        initializeTrackingLayoutParams()
        setupTrackingRunnable()
        startForeground(NOTIFICATION_ID, createNotification("Overlay pronto", false)) // startForeground agora funciona
    }

    private fun loadTrackingThresholds() {
        try {
            // Passa 'this' que agora é um Context válido
            goodHourThreshold = SettingsActivity.getGoodHourThreshold(this)
            poorHourThreshold = SettingsActivity.getPoorHourThreshold(this)
            Log.d(TAG, "Limiares €/h tracking: Bom≥$goodHourThreshold, Mau≤$poorHourThreshold")
        } catch (e: Exception) { Log.e(TAG, "Erro ao ler limiares de €/h. Usando defaults.", e); goodHourThreshold = 15.0; poorHourThreshold = 8.0 }
    }

    private fun initializeMainLayoutParams() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        mainLayoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType, flags, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (50 * resources.displayMetrics.density).toInt() } // resources agora funciona
    }

    private fun initializeTrackingLayoutParams() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        trackingLayoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType, flags, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.END; x = (10 * resources.displayMetrics.density).toInt(); y = (10 * resources.displayMetrics.density).toInt() } // resources agora funciona
    }

    private fun setupTrackingRunnable() {
        trackingUpdateRunnable = object : Runnable {
            override fun run() {
                if (isCurrentlyTracking && trackingOverlayView != null) {
                    val elapsedMillis = System.currentTimeMillis() - trackingStartTimeMs
                    val elapsedSeconds = max(MIN_TRACKING_TIME_SEC, elapsedMillis / 1000)
                    var currentVph: Double? = null
                    if (trackedOfferValue > 0) {
                        val elapsedHours = elapsedSeconds / 3600.0
                        if (elapsedHours > 0) { val calculatedVph = trackedOfferValue / elapsedHours; if (calculatedVph.isFinite()) { currentVph = calculatedVph } }
                    }
                    val currentHourRating = when {
                        currentVph == null -> IndividualRating.UNKNOWN
                        currentVph >= goodHourThreshold -> IndividualRating.GOOD
                        currentVph <= poorHourThreshold -> IndividualRating.POOR
                        else -> IndividualRating.MEDIUM
                    }
                    trackingOverlayView?.updateRealTimeData(currentVph, currentHourRating, elapsedSeconds)
                    trackingUpdateHandler.postDelayed(this, TRACKING_UPDATE_INTERVAL_MS)
                }
            }
        }
    }

    // --- Notificação ---
    private fun createNotification(contentText: String, isTracking: Boolean): Notification {
        createNotificationChannel()
        val smallIconResId = try { if (isTracking) R.drawable.ic_stat_tracking else R.mipmap.ic_launcher } catch (e: Resources.NotFoundException) { Log.w(TAG, "Ícone ic_stat_tracking não encontrado, usando fallback."); R.mipmap.ic_launcher }
        // Passa 'this' que agora é um Context válido
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartDriver").setContentText(contentText).setSmallIcon(smallIconResId)
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW)
        return builder.build()
    }

    private fun updateNotification(contentText: String, isTracking: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager // getSystemService agora funciona
        try { nm.notify(NOTIFICATION_ID, createNotification(contentText, isTracking)) }
        catch (e: Exception) { Log.e(TAG, "Erro ao atualizar notificação: ${e.message}") }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply { description = "Notificação do Serviço de Overlay"; enableLights(false); enableVibration(false); setShowBadge(false) }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager // getSystemService agora funciona
            try { nm.createNotificationChannel(channel) } catch (e: Exception) { Log.e(TAG, "Erro criar canal notificação: ${e.message}") }
        }
    }

    // --- Tratamento de Intents (agora faz override corretamente) ---
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Action=${intent?.action}")
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> handleShowOverlay(intent)
            ACTION_HIDE_OVERLAY -> handleHideOverlay()
            ACTION_START_TRACKING -> handleStartTracking(intent)
            ACTION_STOP_TRACKING -> handleStopTracking()
            ACTION_UPDATE_SETTINGS -> handleUpdateSettings(intent)
            else -> Log.w(TAG, "Ação desconhecida/nula: ${intent?.action}")
        }
        return START_REDELIVER_INTENT // Constante agora é reconhecida
    }

    private fun handleShowOverlay(intent: Intent?) {
        val evaluationResult: EvaluationResult? = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java)
        val offerData: OfferData? = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java)
        if (evaluationResult != null && offerData != null) { showMainOverlay(evaluationResult, offerData); updateNotification("Oferta: ${offerData.value}€", isCurrentlyTracking) }
        else { Log.e(TAG, "$ACTION_SHOW_OVERLAY sem dados válidos."); hideMainOverlay() }
    }

    private fun handleHideOverlay() {
        Log.d(TAG, "Recebido $ACTION_HIDE_OVERLAY.")
        if (isCurrentlyTracking) { Log.w(TAG, "$ACTION_HIDE_OVERLAY durante tracking. Parando..."); stopTrackingAndSaveToHistory() }
        hideMainOverlay(); updateNotification("Overlay pronto", false)
    }

    private fun handleStartTracking(intent: Intent?) {
        val offerDataToTrack: OfferData? = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java)
        if (offerDataToTrack != null) {
            if (!isCurrentlyTracking) {
                Log.i(TAG, ">>> Iniciando Acompanhamento: ${offerDataToTrack.value}€ <<<")
                hideMainOverlay()
                isCurrentlyTracking = true; trackingStartTimeMs = System.currentTimeMillis()
                trackedOfferData = offerDataToTrack
                trackedOfferValue = offerDataToTrack.value.replace(",", ".").toDoubleOrNull() ?: 0.0
                trackedInitialVph = offerDataToTrack.calculateValuePerHour()
                trackedInitialVpk = offerDataToTrack.calculateProfitability()
                loadTrackingThresholds()
                val initialDist = offerDataToTrack.calculateTotalDistance().takeIf { it > 0 }
                val initialDur = offerDataToTrack.calculateTotalTimeMinutes().takeIf { it > 0 }
                val offerValStr = offerDataToTrack.value
                showTrackingOverlay(trackedInitialVpk, initialDist, initialDur, offerValStr) // Chamada corrigida
                trackingUpdateHandler.removeCallbacks(trackingUpdateRunnable)
                trackingUpdateHandler.post(trackingUpdateRunnable)
                updateNotification("Acompanhando Viagem...", true)
            } else { Log.w(TAG, "$ACTION_START_TRACKING ignorado: Já em tracking.") }
        } else { Log.e(TAG, "$ACTION_START_TRACKING sem OfferData válido.") }
    }

    private fun handleStopTracking() {
        Log.i(TAG, "Recebido $ACTION_STOP_TRACKING.")
        stopTrackingAndSaveToHistory()
    }

    private fun handleUpdateSettings(intent: Intent?) {
        Log.d(TAG, "Recebido $ACTION_UPDATE_SETTINGS.")
        loadTrackingThresholds()
        // Passa 'this' (Context) para getFontSize/Transparency
        val defaultFontSize = SettingsActivity.getFontSize(this); val defaultTransparency = SettingsActivity.getTransparency(this)
        val fontSizePercent = intent?.getIntExtra(EXTRA_FONT_SIZE, defaultFontSize) ?: defaultFontSize
        val transparencyPercent = intent?.getIntExtra(EXTRA_TRANSPARENCY, defaultTransparency) ?: defaultTransparency
        applyAppearanceSettings(fontSizePercent, transparencyPercent); updateLayouts()
    }

    private fun <T : Any> getParcelableExtraCompat(intent: Intent?, key: String, clazz: Class<T>): T? {
        return intent?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { it.getParcelableExtra(key, clazz) } else { @Suppress("DEPRECATION") it.getParcelableExtra(key) as? T } }
    }

    // --- Gestão das Views Overlay ---
    private fun showMainOverlay(evaluationResult: EvaluationResult, offerData: OfferData) {
        if (windowManager == null) return; if (mainOverlayView == null) { mainOverlayView = OverlayView(this); applyAppearanceSettingsToView(mainOverlayView) } // Passa 'this' (Context)
        mainOverlayView?.updateState(evaluationResult, offerData)
        try {
            if (!isMainOverlayAdded && mainOverlayView != null) { windowManager?.addView(mainOverlayView, mainLayoutParams); isMainOverlayAdded = true; Log.d(TAG, "Overlay Principal ADD.") }
            else if (isMainOverlayAdded && mainOverlayView != null) { windowManager?.updateViewLayout(mainOverlayView, mainLayoutParams); Log.d(TAG, "Overlay Principal UPD.") }
        } catch (e: Exception) { Log.e(TAG, "Erro add/upd Overlay Principal: ${e.message}"); isMainOverlayAdded = false }
    }

    private fun hideMainOverlay() {
        if (isMainOverlayAdded && mainOverlayView != null && windowManager != null) {
            try { windowManager?.removeView(mainOverlayView) } catch (e: Exception) { Log.w(TAG, "Erro remove Overlay Principal: ${e.message}") }
            finally { isMainOverlayAdded = false; mainOverlayView = null }
            Log.d(TAG, "Overlay Principal REM.")
        }
    }

    private fun showTrackingOverlay(initialVpk: Double?, initialDistance: Double?, initialDuration: Int?, offerVal: String?) {
        if (windowManager == null) return; if (trackingOverlayView == null) { trackingOverlayView = TrackingOverlayView(this); applyAppearanceSettingsToView(trackingOverlayView) } // Passa 'this' (Context)
        trackingOverlayView?.updateInitialData(initialVpk, initialDistance, initialDuration, offerVal)
        try {
            if (!isTrackingOverlayAdded && trackingOverlayView != null) { windowManager?.addView(trackingOverlayView, trackingLayoutParams); isTrackingOverlayAdded = true; Log.i(TAG, "Overlay Acompanhamento ADD.") }
            else if (isTrackingOverlayAdded && trackingOverlayView != null) { windowManager?.updateViewLayout(trackingOverlayView, trackingLayoutParams); Log.d(TAG, "Overlay Acompanhamento UPD.") }
        } catch (e: Exception) { Log.e(TAG, "Erro add/upd Overlay Acompanhamento: ${e.message}"); isTrackingOverlayAdded = false }
    }

    private fun hideTrackingOverlay() {
        if (isTrackingOverlayAdded && trackingOverlayView != null && windowManager != null) {
            try { windowManager?.removeView(trackingOverlayView) } catch (e: Exception) { Log.w(TAG, "Erro remove Overlay Acompanhamento: ${e.message}") }
            finally { isTrackingOverlayAdded = false; trackingOverlayView = null }
            Log.i(TAG, "Overlay Acompanhamento REM.")
        }
    }

    // --- Funções de Tracking e Salvamento ---
    private fun stopTrackingTimer() { Log.d(TAG, "Parando timer tracking..."); trackingUpdateHandler.removeCallbacks(trackingUpdateRunnable) }

    private fun stopTrackingAndSaveToHistory() {
        if (!isCurrentlyTracking) { Log.w(TAG, "Tentativa stopTracking sem tracking ativo."); hideTrackingOverlay(); updateNotification("Overlay pronto", false); return }
        Log.i(TAG, ">>> Finalizando Acompanhamento e Salvando <<<")
        val endTimeMs = System.currentTimeMillis(); stopTrackingTimer()
        val finalElapsedTimeMs = endTimeMs - trackingStartTimeMs; val finalElapsedTimeSec = max(MIN_TRACKING_TIME_SEC, finalElapsedTimeMs / 1000)
        var finalVph : Double? = null
        if (trackedOfferValue > 0) {
            val finalElapsedHours = finalElapsedTimeSec / 3600.0
            if (finalElapsedHours > 0) { val calculatedVph = trackedOfferValue / finalElapsedHours; if (calculatedVph.isFinite()) { finalVph = calculatedVph } }
        }
        Log.i(TAG, "Dados Finais: Duração=${finalElapsedTimeSec}s, €/h Final=${finalVph?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"}")
        val entry = TripHistoryEntry(
            startTimeMillis = trackingStartTimeMs, endTimeMillis = endTimeMs, durationSeconds = finalElapsedTimeSec,
            offerValue = trackedOfferValue.takeIf { it > 0 }, initialVph = trackedInitialVph, finalVph = finalVph,
            initialVpk = trackedInitialVpk, initialDistanceKm = trackedOfferData?.calculateTotalDistance()?.takeIf { it > 0 },
            initialDurationMinutes = trackedOfferData?.calculateTotalTimeMinutes()?.takeIf { it > 0 },
            serviceType = trackedOfferData?.serviceType?.takeIf { it.isNotEmpty() }
        )
        saveHistoryEntry(entry)
        isCurrentlyTracking = false; trackingStartTimeMs = 0L; trackedOfferData = null; trackedOfferValue = 0.0; trackedInitialVph = null; trackedInitialVpk = null
        hideTrackingOverlay(); updateNotification("Overlay pronto", false)
    }

    private fun saveHistoryEntry(newEntry: TripHistoryEntry) {
        try {
            val newEntryJson = gson.toJson(newEntry)
            val currentHistoryJson = historyPrefs.getString(KEY_TRIP_HISTORY, "[]")
            val listType = object : TypeToken<MutableList<String>>() {}.type
            val mutableHistoryList: MutableList<String> = gson.fromJson(currentHistoryJson, listType) ?: mutableListOf()
            mutableHistoryList.add(newEntryJson)
            val updatedHistoryJson = gson.toJson(mutableHistoryList)
            historyPrefs.edit().putString(KEY_TRIP_HISTORY, updatedHistoryJson).apply()
            Log.i(TAG,"Histórico salvo. Total: ${mutableHistoryList.size}")
        } catch (e: Exception) { Log.e(TAG, "ERRO CRÍTICO ao salvar histórico: ${e.message}", e) }
    }

    // --- Gestão Aparência ---
    private fun applyAppearanceSettings(fontSizePercent: Int, transparencyPercent: Int) {
        applyAppearanceSettingsToView(mainOverlayView, fontSizePercent, transparencyPercent)
        applyAppearanceSettingsToView(trackingOverlayView, fontSizePercent, transparencyPercent)
    }

    private fun applyAppearanceSettingsToView(view: View?, fontSizePercent: Int? = null, transparencyPercent: Int? = null) {
        view ?: return
        // Passa 'this' (Context) para getFontSize/Transparency
        val actualFontSize = fontSizePercent ?: SettingsActivity.getFontSize(this)
        val actualTransparency = transparencyPercent ?: SettingsActivity.getTransparency(this)
        val fontScale = actualFontSize / 100f
        val alpha = (1.0f - (actualTransparency / 100f)).coerceIn(0.0f, 1.0f)
        when (view) {
            is OverlayView -> { view.updateFontSize(fontScale); view.updateAlpha(alpha) }
            is TrackingOverlayView -> { view.alpha = alpha }
        }
    }

    private fun updateLayouts() {
        if (isMainOverlayAdded && mainOverlayView != null && windowManager != null) { try { windowManager?.updateViewLayout(mainOverlayView, mainLayoutParams) } catch (e: Exception) { Log.e(TAG,"Erro upd layout principal: ${e.message}") } }
        if (isTrackingOverlayAdded && trackingOverlayView != null && windowManager != null) { try { windowManager?.updateViewLayout(trackingOverlayView, trackingLayoutParams) } catch (e: Exception) { Log.e(TAG,"Erro upd layout acompanhamento: ${e.message}") } }
    }

    // --- onBind e onDestroy (agora fazem override corretamente) ---
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy() // <<< Chamada a super.onDestroy()
        Log.w(TAG, "Serviço Overlay DESTRUÍDO")
        isRunning.set(false); stopTrackingTimer()
        hideMainOverlay(); hideTrackingOverlay()
        mainOverlayView = null; trackingOverlayView = null; windowManager = null
        try {
            // getSystemService agora funciona
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID); Log.d(TAG,"Notificação cancelada.")
        }
        catch (e: Exception){ Log.e(TAG, "Erro cancel notification onDestroy: ${e.message}") }
    }
}