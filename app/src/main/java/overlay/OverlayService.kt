package com.example.smartdriver.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.smartdriver.R
import com.example.smartdriver.SettingsActivity // Import necessário para ler limiares
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.EvaluationResult
import com.example.smartdriver.utils.IndividualRating // Import necessário para classificação
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val CHANNEL_NAME = "Overlay Service"

        // --- AÇÕES ---
        const val ACTION_SHOW_OVERLAY = "com.example.smartdriver.overlay.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.example.smartdriver.overlay.HIDE_OVERLAY"
        const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.overlay.UPDATE_SETTINGS"
        const val ACTION_START_TRACKING = "com.example.smartdriver.overlay.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.smartdriver.overlay.STOP_TRACKING"

        // --- EXTRAS ---
        const val EXTRA_EVALUATION_RESULT = "evaluation_result"
        const val EXTRA_OFFER_DATA = "offer_data"
        const val EXTRA_FONT_SIZE = "font_size"
        const val EXTRA_TRANSPARENCY = "transparency"

        // --- Constantes de Tracking ---
        private const val TRACKING_UPDATE_INTERVAL_MS = 1000L
        private const val MIN_TRACKING_TIME_SEC = 1L

        @JvmStatic
        val isRunning = AtomicBoolean(false)
    }

    // --- Gerenciadores e Views ---
    private var windowManager: WindowManager? = null
    private var mainOverlayView: OverlayView? = null
    private var trackingOverlayView: TrackingOverlayView? = null
    private lateinit var mainLayoutParams: WindowManager.LayoutParams
    private lateinit var trackingLayoutParams: WindowManager.LayoutParams

    // --- Estado ---
    private var isMainOverlayAdded = false
    private var isTrackingOverlayAdded = false
    private var lastEvaluationResult: EvaluationResult? = null
    private var lastOfferData: OfferData? = null

    // --- Estado de Acompanhamento ---
    private var isCurrentlyTracking = false
    private var trackingStartTimeMs: Long = 0L
    private var trackedOfferValue: Double = 0.0
    private val trackingUpdateHandler = Handler(Looper.getMainLooper())
    private lateinit var trackingUpdateRunnable: Runnable

    // <<< Variáveis para guardar limiares (inicializadas com valores hardcoded iguais aos defaults) >>>
    private var goodHourThreshold: Double = 15.0 // Valor default diretamente
    private var poorHourThreshold: Double = 8.0  // Valor default diretamente

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Serviço overlay criado")
        isRunning.set(true)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadTrackingThresholds() // Carrega limiares das SharedPreferences ao criar
        initializeMainLayoutParams()
        initializeTrackingLayoutParams()
        setupTrackingRunnable()
        startForeground(NOTIFICATION_ID, createNotification("Overlay pronto", false))
        Log.d(TAG, "Serviço iniciado em foreground.")
    }

    // Função para carregar/recarregar limiares das SharedPreferences
    private fun loadTrackingThresholds() {
        try {
            goodHourThreshold = SettingsActivity.getGoodHourThreshold(this)
            poorHourThreshold = SettingsActivity.getPoorHourThreshold(this)
            Log.d(TAG, "Limiares de €/h para tracking carregados: Bom≥$goodHourThreshold, Mau≤$poorHourThreshold")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ler limiares das SettingsActivity. Usando defaults internos.", e)
            // Mantém os valores hardcoded definidos na declaração das variáveis
        }
    }

    // --- Layout Params ---
    private fun initializeMainLayoutParams() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        mainLayoutParams = WindowManager.LayoutParams( WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType, flags, PixelFormat.TRANSLUCENT ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (50 * resources.displayMetrics.density).toInt()
        }
    }
    private fun initializeTrackingLayoutParams() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        trackingLayoutParams = WindowManager.LayoutParams( WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType, flags, PixelFormat.TRANSLUCENT ).apply {
            gravity = Gravity.TOP or Gravity.END; x = (10 * resources.displayMetrics.density).toInt(); y = (10 * resources.displayMetrics.density).toInt()
        }
    }

    // --- Inicialização do Runnable de Tracking ---
    private fun setupTrackingRunnable() {
        trackingUpdateRunnable = object : Runnable {
            override fun run() {
                if (isCurrentlyTracking) {
                    val elapsedMillis = System.currentTimeMillis() - trackingStartTimeMs
                    val elapsedSeconds = max(MIN_TRACKING_TIME_SEC, elapsedMillis / 1000)
                    var currentVph: Double? = null
                    if (trackedOfferValue > 0) {
                        val elapsedHours = elapsedSeconds / 3600.0
                        if (elapsedHours > 0) { val calculatedVph = trackedOfferValue / elapsedHours; if (calculatedVph.isFinite()) { currentVph = calculatedVph } }
                    }
                    // Classifica o €/h atual
                    val currentHourRating = when {
                        currentVph == null -> IndividualRating.UNKNOWN
                        currentVph >= goodHourThreshold -> IndividualRating.GOOD
                        currentVph <= poorHourThreshold -> IndividualRating.POOR
                        else -> IndividualRating.MEDIUM
                    }
                    // Passa a classificação para a view
                    trackingOverlayView?.updateRealTimeData(currentVph, currentHourRating, elapsedSeconds)
                    trackingUpdateHandler.postDelayed(this, TRACKING_UPDATE_INTERVAL_MS)
                }
            }
        }
    }

    // --- Notificação ---
    private fun createNotification(contentText: String, isTracking: Boolean): Notification {
        createNotificationChannel(); val smallIconResId = R.mipmap.ic_launcher
        val builder = NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("SmartDriver").setContentText(contentText).setSmallIcon(smallIconResId).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW)
        // TODO (Fase 3): Adicionar Ação "Parar Acompanhamento" se isTracking for true
        return builder.build()
    }
    private fun updateNotification(contentText: String, isTracking: Boolean) {
        val notification = createNotification(contentText, isTracking); val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { notificationManager.notify(NOTIFICATION_ID, notification) } catch (e: Exception) { Log.e(TAG, "Erro ao atualizar notificação: ${e.message}") }
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel( CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW ).apply { description = "Notificação do Serviço de Overlay SmartDriver"; enableLights(false); enableVibration(false); setShowBadge(false) }; val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; try { notificationManager.createNotificationChannel(channel) } catch (e: Exception) { Log.e(TAG, "Erro ao criar canal de notificação: ${e.message}") }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand recebido: Action=${intent?.action}")
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                val evaluationResult: EvaluationResult? = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java)
                val offerData: OfferData? = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java)
                if (evaluationResult != null && offerData != null) {
                    lastEvaluationResult = evaluationResult; lastOfferData = offerData
                    showMainOverlay(evaluationResult, offerData)
                    updateNotification("Oferta: ${offerData.value}€", isCurrentlyTracking)
                } else { Log.e(TAG, "ACTION_SHOW_OVERLAY sem dados válidos."); hideMainOverlay() }
            }
            ACTION_HIDE_OVERLAY -> {
                Log.d(TAG, "Solicitado esconder TODOS os overlays."); stopTrackingInternal()
                hideMainOverlay(); hideTrackingOverlay(); updateNotification("Overlay pronto", false)
            }
            ACTION_START_TRACKING -> {
                val offerDataToTrack: OfferData? = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java)
                val evalResultToTrack: EvaluationResult? = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java)
                if (offerDataToTrack != null && evalResultToTrack != null) {
                    if (!isCurrentlyTracking) {
                        Log.i(TAG, "Iniciando Acompanhamento para oferta: ${offerDataToTrack.value}€")
                        hideMainOverlay()
                        isCurrentlyTracking = true; trackingStartTimeMs = System.currentTimeMillis()
                        trackedOfferValue = offerDataToTrack.value.replace(",", ".").toDoubleOrNull() ?: 0.0
                        loadTrackingThresholds() // Garante limiares atualizados

                        val initialVph = offerDataToTrack.calculateValuePerHour()
                        val initialVpk = offerDataToTrack.calculateProfitability()
                        val initialDist = offerDataToTrack.calculateTotalDistance().takeIf { it > 0 }
                        val initialDur = offerDataToTrack.calculateTotalTimeMinutes().takeIf { it > 0 }
                        val offerValStr = offerDataToTrack.value
                        val initialHourRating = evalResultToTrack.hourRating

                        showTrackingOverlay(initialVph, initialHourRating, initialVpk, initialDist, initialDur, offerValStr, 0)

                        trackingUpdateHandler.removeCallbacks(trackingUpdateRunnable)
                        trackingUpdateHandler.postDelayed(trackingUpdateRunnable, TRACKING_UPDATE_INTERVAL_MS)
                        updateNotification("Acompanhando Viagem...", true)
                    } else { Log.w(TAG, "Pedido START_TRACKING ignorado: Acompanhamento já está ativo.") }
                } else { Log.e(TAG, "ACTION_START_TRACKING sem dados válidos no Intent.") }
            }
            ACTION_STOP_TRACKING -> {
                Log.i(TAG, "Recebido comando para PARAR acompanhamento.")
                stopTrackingAndSaveToHistory()
            }
            ACTION_UPDATE_SETTINGS -> {
                Log.d(TAG, "Solicitado atualizar configurações de aparência")
                loadTrackingThresholds() // Recarrega limiares
                val defaultFontSize = SettingsActivity.getFontSize(this); val defaultTransparency = SettingsActivity.getTransparency(this)
                val fontSizePercent = intent.getIntExtra(EXTRA_FONT_SIZE, defaultFontSize)
                val transparencyPercent = intent.getIntExtra(EXTRA_TRANSPARENCY, defaultTransparency)
                applyAppearanceSettings(fontSizePercent, transparencyPercent); updateLayouts()
            }
            else -> { Log.w(TAG, "Ação desconhecida ou nula recebida: ${intent?.action}") }
        }
        return START_REDELIVER_INTENT
    }

    private fun <T : Any> getParcelableExtraCompat(intent: Intent?, key: String, clazz: Class<T>): T? {
        return intent?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(key, clazz)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra(key) as? T
            }
        }
    }

    // --- Gestão Overlays ---
    private fun showMainOverlay(evaluationResult: EvaluationResult, offerData: OfferData) {
        if (windowManager == null) return
        if (mainOverlayView == null) { mainOverlayView = OverlayView(this); applyAppearanceSettingsToView(mainOverlayView) }
        mainOverlayView?.updateState(evaluationResult, offerData)
        try {
            if (!isMainOverlayAdded && mainOverlayView != null) { windowManager?.addView(mainOverlayView, mainLayoutParams); isMainOverlayAdded = true; /* Log.d(TAG, "Overlay Principal ADICIONADO.") */ } // Menos verboso
            else if (isMainOverlayAdded && mainOverlayView != null) { windowManager?.updateViewLayout(mainOverlayView, mainLayoutParams); /* Log.d(TAG, "Overlay Principal ATUALIZADO.") */ }
        } catch (e: Exception) { Log.e(TAG, "Erro showMainOverlay: ${e.message}"); isMainOverlayAdded = false }
    }

    private fun hideMainOverlay() {
        if (isMainOverlayAdded && mainOverlayView != null && windowManager != null) {
            try { windowManager?.removeView(mainOverlayView); /* Log.d(TAG, "Overlay Principal REMOVIDO.") */ } // Menos verboso
            catch (e: Exception) { Log.w(TAG, "Erro hideMainOverlay: ${e.message}") }
            finally { isMainOverlayAdded = false }
        }
    }

    private fun showTrackingOverlay( initialVph: Double?, initialHourRating: IndividualRating, initialVpk: Double?, initialDistance: Double?, initialDuration: Int?, offerVal: String?, initialElapsedTimeSec: Long ) {
        if (windowManager == null) return
        if (trackingOverlayView == null) { trackingOverlayView = TrackingOverlayView(this); applyAppearanceSettingsToView(trackingOverlayView) }
        trackingOverlayView?.updateInitialData(initialVph, initialVpk, initialDistance, initialDuration, offerVal)
        trackingOverlayView?.updateRealTimeData(initialVph, initialHourRating, initialElapsedTimeSec)
        try {
            if (!isTrackingOverlayAdded && trackingOverlayView != null) { windowManager?.addView(trackingOverlayView, trackingLayoutParams); isTrackingOverlayAdded = true; Log.i(TAG, "Overlay Acompanhamento ADICIONADO.") }
            else if (isTrackingOverlayAdded && trackingOverlayView != null) { windowManager?.updateViewLayout(trackingOverlayView, trackingLayoutParams); /* Log.d(TAG, "Overlay Acompanhamento ATUALIZADO.") */ }
        } catch (e: Exception) { Log.e(TAG, "Erro showTrackingOverlay: ${e.message}"); isTrackingOverlayAdded = false }
    }

    private fun hideTrackingOverlay() {
        if (isTrackingOverlayAdded && trackingOverlayView != null && windowManager != null) {
            try { windowManager?.removeView(trackingOverlayView); Log.i(TAG, "Overlay Acompanhamento REMOVIDO.") }
            catch (e: Exception) { Log.w(TAG, "Erro hideTrackingOverlay: ${e.message}") }
            finally { isTrackingOverlayAdded = false }
        }
    }

    // --- Funções de Tracking ---
    private fun stopTrackingInternal() {
        if (isCurrentlyTracking) {
            Log.d(TAG, "Parando timer de acompanhamento...")
            isCurrentlyTracking = false
            trackingUpdateHandler.removeCallbacks(trackingUpdateRunnable)
        }
    }

    private fun stopTrackingAndSaveToHistory() {
        if (!isCurrentlyTracking) { Log.w(TAG, "Tentativa de parar tracking, mas já não estava ativo."); hideTrackingOverlay(); updateNotification("Overlay pronto", false); return }
        stopTrackingInternal()
        // TODO (Fase 3): Salvar no histórico
        val finalElapsedTimeMs = System.currentTimeMillis() - trackingStartTimeMs; val finalElapsedTimeSec = max(MIN_TRACKING_TIME_SEC, finalElapsedTimeMs / 1000); var finalVph : Double? = null
        if (trackedOfferValue > 0) { val finalElapsedHours = finalElapsedTimeSec / 3600.0; if (finalElapsedHours > 0) { val calculatedVph = trackedOfferValue / finalElapsedHours; if (calculatedVph.isFinite()) { finalVph = calculatedVph } } }
        Log.i(TAG, "Acompanhamento FINALIZADO. Valor Oferta: $trackedOfferValue, Tempo Final: $finalElapsedTimeSec s, €/h Final: $finalVph")
        // FIM TODO (Fase 3)
        hideTrackingOverlay(); updateNotification("Overlay pronto", false)
    }

    // --- Gestão Aparência ---
    private fun applyAppearanceSettings(fontSizePercent: Int, transparencyPercent: Int) {
        applyAppearanceSettingsToView(mainOverlayView, fontSizePercent, transparencyPercent)
        applyAppearanceSettingsToView(trackingOverlayView, fontSizePercent, transparencyPercent)
    }

    private fun applyAppearanceSettingsToView(view: View?, fontSizePercent: Int? = null, transparencyPercent: Int? = null) {
        view ?: return; val actualFontSize = fontSizePercent ?: SettingsActivity.getFontSize(this); val actualTransparency = transparencyPercent ?: SettingsActivity.getTransparency(this); val fontScale = actualFontSize / 100f; val alpha = 1.0f - (actualTransparency / 100f);
        // Log.d(TAG, "Aplicando aparência à view ${view.javaClass.simpleName}: Scale=$fontScale, Alpha=$alpha")
        when (view) { is OverlayView -> { view.updateFontSize(fontScale); view.updateAlpha(alpha) }; is TrackingOverlayView -> { view.alpha = alpha } }
    }

    private fun updateLayouts() {
        if (isMainOverlayAdded && mainOverlayView != null && windowManager != null) { try { windowManager?.updateViewLayout(mainOverlayView, mainLayoutParams) } catch (e: Exception) { Log.e(TAG,"Erro ao atualizar layout principal: ${e.message}") } }
        if (isTrackingOverlayAdded && trackingOverlayView != null && windowManager != null) { try { windowManager?.updateViewLayout(trackingOverlayView, trackingLayoutParams) } catch (e: Exception) { Log.e(TAG,"Erro ao atualizar layout de acompanhamento: ${e.message}") } }
    }

    override fun onBind(intent: Intent?): IBinder? { return null }

    override fun onDestroy() {
        super.onDestroy(); Log.w(TAG, "Serviço overlay destruído"); isRunning.set(false); stopTrackingInternal(); hideMainOverlay(); hideTrackingOverlay()
        mainOverlayView = null; trackingOverlayView = null; windowManager = null
        try { val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; notificationManager.cancel(NOTIFICATION_ID); Log.d(TAG,"Notificação do OverlayService cancelada.") }
        catch (e: Exception){ Log.e(TAG, "Erro ao cancelar notificação no onDestroy: ${e.message}") }
    }

} // Fim da classe OverlayService