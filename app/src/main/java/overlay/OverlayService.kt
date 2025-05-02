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
// import android.graphics.Color // Não usado diretamente, pode remover se quiser
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log // <<< Garantir que o import está presente
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
import java.text.DecimalFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1002; private const val CHANNEL_ID = "overlay_service_channel"; private const val CHANNEL_NAME = "Overlay Service"
        // Ações Overlay/Menu/Tracking
        const val ACTION_SHOW_OVERLAY = "com.example.smartdriver.overlay.SHOW_OVERLAY";
        const val ACTION_HIDE_OVERLAY = "com.example.smartdriver.overlay.HIDE_OVERLAY";
        const val ACTION_DISMISS_MAIN_OVERLAY_ONLY = "com.example.smartdriver.overlay.DISMISS_MAIN_ONLY"
        const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.overlay.UPDATE_SETTINGS";
        const val ACTION_START_TRACKING = "com.example.smartdriver.overlay.START_TRACKING";
        const val ACTION_STOP_TRACKING = "com.example.smartdriver.overlay.STOP_TRACKING";
        const val ACTION_SHOW_QUICK_MENU = "com.example.smartdriver.overlay.SHOW_QUICK_MENU";
        const val ACTION_DISMISS_MENU = "com.example.smartdriver.overlay.DISMISS_MENU";
        const val ACTION_REQUEST_SHUTDOWN = "com.example.smartdriver.overlay.REQUEST_SHUTDOWN";
        // Ações Turno
        const val ACTION_TOGGLE_SHIFT_STATE = "com.example.smartdriver.overlay.TOGGLE_SHIFT_STATE"
        const val ACTION_END_SHIFT = "com.example.smartdriver.overlay.END_SHIFT"
        // Extras
        const val EXTRA_EVALUATION_RESULT = "evaluation_result";
        const val EXTRA_OFFER_DATA = "offer_data";
        const val EXTRA_FONT_SIZE = "font_size";
        const val EXTRA_TRANSPARENCY = "transparency";
        // Constantes Tracking/Histórico/Turno
        private const val TRACKING_UPDATE_INTERVAL_MS = 1000L;
        private const val MIN_TRACKING_TIME_SEC = 1L;
        const val HISTORY_PREFS_NAME = "SmartDriverHistoryPrefs";
        const val KEY_TRIP_HISTORY = "trip_history_list_json";
        const val SHIFT_STATE_PREFS_NAME = "SmartDriverShiftStatePrefs"
        private const val KEY_SHIFT_ACTIVE = "shift_active"
        private const val KEY_SHIFT_PAUSED = "shift_paused"
        private const val KEY_SHIFT_START_TIME = "shift_start_time"
        private const val KEY_SHIFT_LAST_PAUSE_OR_RESUME_TIME = "shift_last_pause_resume_time"
        private const val KEY_SHIFT_ACCUMULATED_WORKED_TIME = "shift_accumulated_worked_time"
        private const val KEY_SHIFT_TOTAL_EARNINGS = "shift_total_earnings"

        @JvmStatic val isRunning = AtomicBoolean(false)
    }

    // Views e Layouts
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

    // Estado Tracking Viagem
    private var isCurrentlyTracking = false; private var trackingStartTimeMs: Long = 0L
    private var trackedOfferData: OfferData? = null; private var trackedInitialVph: Double? = null
    private var trackedInitialVpk: Double? = null; private var trackedOfferValue: Double = 0.0
    private var trackedInitialKmRating: IndividualRating = IndividualRating.UNKNOWN
    private var trackedCombinedBorderRating: BorderRating = BorderRating.GRAY
    private val trackingUpdateHandler = Handler(Looper.getMainLooper()); private lateinit var trackingUpdateRunnable: Runnable

    // Estado do Turno
    private var isShiftActive = false
    private var isShiftPaused = false
    private var shiftStartTimeMillis = 0L
    private var shiftLastPauseOrResumeTimeMillis = 0L
    private var shiftAccumulatedWorkedTimeMillis = 0L
    private var shiftTotalEarnings = 0.0
    private val shiftTimerHandler = Handler(Looper.getMainLooper())
    private var shiftTimerRunnable: Runnable? = null
    private lateinit var shiftPrefs: SharedPreferences

    // Outros
    private var goodHourThreshold: Double = 15.0; private var poorHourThreshold: Double = 8.0
    private val gson = Gson(); private lateinit var historyPrefs: SharedPreferences
    private var touchSlop: Int = 0
    private val decimalFormat = DecimalFormat("0.0")

    override fun onCreate() {
        super.onCreate(); Log.i(TAG, "Serviço Overlay CRIADO"); isRunning.set(true)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        historyPrefs = getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
        shiftPrefs = getSharedPreferences(SHIFT_STATE_PREFS_NAME, Context.MODE_PRIVATE)
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        loadTrackingThresholds()
        loadShiftState()
        initializeMainLayoutParams(); initializeTrackingLayoutParams()
        initializeMenuLayoutParams(); initializeFloatingIconLayoutParams();
        setupTrackingRunnable()
        setupShiftTimerRunnable()
        startForeground(NOTIFICATION_ID, createNotification("Overlay pronto", false))
        addFloatingIconOverlay()
        if (isShiftActive && !isShiftPaused) { startShiftTimer() }
    }

    // --- Funções de Inicialização (Quebrando linhas para clareza) ---
    private fun loadTrackingThresholds() {
        try {
            goodHourThreshold = SettingsActivity.getGoodHourThreshold(this)
            poorHourThreshold = SettingsActivity.getPoorHourThreshold(this)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao carregar limiares, usando defaults.", e)
            goodHourThreshold = 15.0
            poorHourThreshold = 8.0
        }
    }

    private fun initializeMainLayoutParams() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        mainLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (50 * resources.displayMetrics.density).toInt()
        }
    }

    private fun initializeTrackingLayoutParams() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        trackingLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (10 * resources.displayMetrics.density).toInt()
            y = (80 * resources.displayMetrics.density).toInt()
        }
    }

    private fun initializeMenuLayoutParams() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        menuLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (10 * resources.displayMetrics.density).toInt()
            y = (80 * resources.displayMetrics.density).toInt()
        }
    }

    private fun initializeFloatingIconLayoutParams() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val iconSizeDp = 60
        val iconSizePx = (iconSizeDp * resources.displayMetrics.density).toInt()
        floatingIconLayoutParams = WindowManager.LayoutParams(
            iconSizePx,
            iconSizePx,
            overlayType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (10 * resources.displayMetrics.density).toInt()
            y = (10 * resources.displayMetrics.density).toInt()
        }
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
                        if (elapsedHours > 0) {
                            val calc = trackedOfferValue / elapsedHours
                            if (calc.isFinite()) currentVph = calc
                        }
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

    private fun setupShiftTimerRunnable() {
        shiftTimerRunnable = object : Runnable {
            override fun run() {
                if (isShiftActive && !isShiftPaused && shiftTimerRunnable != null) {
                    val workedTimeMillis = calculateCurrentWorkedTimeMillis()
                    val formattedTime = formatDuration(workedTimeMillis)
                    val averagePerHour = calculateCurrentShiftAveragePerHour()
                    quickMenuView?.updateShiftTimer(formattedTime)
                    quickMenuView?.updateShiftAverage(averagePerHour)
                    shiftTimerHandler.postDelayed(this, 1000L)
                } else {
                    Log.d(TAG, "Shift timer runnable: Condição não cumprida ou runnable é nulo. Parando.")
                }
            }
        }
    }

    private fun createNotification(contentText: String, isTrackingOrActive: Boolean): Notification {
        createNotificationChannel()
        val smallIconResId = try {
            // Usar ícone de tracking se estiver a rastrear OU se o turno estiver ativo e não pausado
            if (isTrackingOrActive) R.drawable.ic_stat_tracking else R.mipmap.ic_launcher
        } catch (e: Resources.NotFoundException) {
            Log.e(TAG, "Erro ao encontrar ícone para notificação", e)
            R.mipmap.ic_launcher // Fallback
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartDriver")
            .setContentText(contentText)
            .setSmallIcon(smallIconResId)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Ou PRIORITY_MIN se não quiser que apareça tão proeminentemente
            .build()
    }

    private fun updateNotification(contentText: String, isTrackingOrActive: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, createNotification(contentText, isTrackingOrActive))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao atualizar notificação.", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notificação do Serviço SmartDriver Overlay"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                nm.createNotificationChannel(channel)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao criar canal de notificação.", e)
            }
        }
    }

    // --- Tratamento de Intents ---
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand recebido: Action=${intent?.action}")
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> handleShowOverlay(intent)
            ACTION_HIDE_OVERLAY -> handleHideOverlay()
            ACTION_DISMISS_MAIN_OVERLAY_ONLY -> handleDismissMainOverlayOnly()
            ACTION_START_TRACKING -> handleStartTracking(intent)
            ACTION_STOP_TRACKING -> handleStopTracking()
            ACTION_UPDATE_SETTINGS -> handleUpdateSettings(intent)
            ACTION_SHOW_QUICK_MENU -> handleShowQuickMenu()
            ACTION_DISMISS_MENU -> handleDismissMenu()
            ACTION_REQUEST_SHUTDOWN -> handleShutdownRequest()
            ACTION_TOGGLE_SHIFT_STATE -> handleToggleShiftState()
            ACTION_END_SHIFT -> handleEndShift() // O parâmetro default saveSummary=true será usado
            else -> Log.w(TAG, "Ação desconhecida ou nula recebida: ${intent?.action}")
        }
        // Use START_STICKY se quiser que o Android tente reiniciar o serviço se for morto
        // Use START_REDELIVER_INTENT se precisar que o último intent seja reentregue ao reiniciar
        return START_REDELIVER_INTENT
    }

    // Handlers
    private fun handleShowOverlay(intent: Intent?) {
        val evalResult: EvaluationResult? = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java)
        val offerData: OfferData? = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java)
        if (evalResult != null && offerData != null) {
            Log.d(TAG, "Mostrando overlay principal com dados.")
            showMainOverlay(evalResult, offerData)
            updateShiftNotification() // Atualiza notificação após mostrar overlay
        } else {
            Log.w(TAG, "Pedido para mostrar overlay principal, mas sem dados válidos. Escondendo.")
            hideMainOverlay() // Garante que fica escondido se os dados forem inválidos
        }
    }

    private fun handleHideOverlay() {
        Log.d(TAG, "$ACTION_HIDE_OVERLAY recebido.")
        if (isCurrentlyTracking) {
            Log.i(TAG, "Escondendo overlay geral, parando tracking ativo.")
            stopTrackingAndSaveToHistory()
        }
        hideMainOverlay()
        hideTrackingOverlay()
        removeQuickMenuOverlay()
        updateShiftNotification() // Atualiza notificação após esconder tudo
    }

    private fun handleDismissMainOverlayOnly() {
        Log.d(TAG, "$ACTION_DISMISS_MAIN_OVERLAY_ONLY recebido.")
        hideMainOverlay()
    }

    private fun handleStartTracking(intent: Intent?) {
        val offerDataToTrack: OfferData? = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java)
        val initialEvaluationResult: EvaluationResult? = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java)

        if (offerDataToTrack != null && initialEvaluationResult != null) {
            if (!isCurrentlyTracking) {
                Log.i(TAG, "Iniciando Tracking para oferta: Valor=${offerDataToTrack.value}€")
                hideMainOverlay() // Esconde overlay principal se estiver visível
                removeQuickMenuOverlay() // Esconde menu se estiver visível

                isCurrentlyTracking = true
                trackingStartTimeMs = System.currentTimeMillis()
                trackedOfferData = offerDataToTrack
                // Tenta converter valor com segurança, tratando vírgula e erro
                trackedOfferValue = try { offerDataToTrack.value.replace(",", ".").toDouble() } catch (e: NumberFormatException) { 0.0 }
                trackedInitialVph = offerDataToTrack.calculateValuePerHour()
                trackedInitialVpk = offerDataToTrack.calculateProfitability()
                trackedInitialKmRating = initialEvaluationResult.kmRating
                trackedCombinedBorderRating = initialEvaluationResult.combinedBorderRating

                loadTrackingThresholds() // Recarrega caso tenham mudado

                val initialDist = offerDataToTrack.calculateTotalDistance()?.takeIf { it > 0 }
                val initialDur = offerDataToTrack.calculateTotalTimeMinutes()?.takeIf { it > 0 }
                val offerValStr = offerDataToTrack.value

                showTrackingOverlay(
                    trackedInitialVpk,
                    initialDist,
                    initialDur,
                    offerValStr,
                    trackedInitialKmRating,
                    trackedCombinedBorderRating
                )

                // Inicia o runnable de atualização
                trackingUpdateHandler.removeCallbacks(trackingUpdateRunnable) // Garante que não há runnables pendentes
                trackingUpdateHandler.post(trackingUpdateRunnable)
                updateShiftNotification() // Atualiza notificação para estado de tracking
            } else {
                Log.w(TAG, "$ACTION_START_TRACKING ignorado porque já existe um tracking em andamento.")
            }
        } else {
            Log.e(TAG, "$ACTION_START_TRACKING recebido mas faltam dados essenciais (OfferData ou EvaluationResult).")
        }
    }

    private fun handleStopTracking() {
        Log.i(TAG, "$ACTION_STOP_TRACKING recebido.")
        if (isCurrentlyTracking) {
            stopTrackingAndSaveToHistory()
        } else {
            Log.w(TAG, "$ACTION_STOP_TRACKING recebido, mas nenhum tracking estava ativo.")
        }
    }

    private fun handleUpdateSettings(intent: Intent?) {
        Log.d(TAG, "$ACTION_UPDATE_SETTINGS recebido.")
        loadTrackingThresholds() // Recarrega limiares de €/h

        // Obtém valores atuais como default
        val defaultFontSize = SettingsActivity.getFontSize(this)
        val defaultTransparency = SettingsActivity.getTransparency(this)

        // Tenta obter novos valores do intent, usa default se não vierem
        val newFontSize = intent?.getIntExtra(EXTRA_FONT_SIZE, defaultFontSize) ?: defaultFontSize
        val newTransparency = intent?.getIntExtra(EXTRA_TRANSPARENCY, defaultTransparency) ?: defaultTransparency

        Log.d(TAG, "Aplicando novas definições: FontSize=$newFontSize%, Transparency=$newTransparency%")
        applyAppearanceSettings(newFontSize, newTransparency)
        updateLayouts() // Atualiza layouts caso a aparência tenha mudado dimensões (raro, mas seguro)
    }

    private fun handleShowQuickMenu() {
        Log.d(TAG, "$ACTION_SHOW_QUICK_MENU recebido.")
        addQuickMenuOverlay()
    }

    private fun handleDismissMenu() {
        Log.d(TAG, "$ACTION_DISMISS_MENU recebido.")
        removeQuickMenuOverlay()
    }

    private fun handleShutdownRequest() {
        Log.w(TAG, "$ACTION_REQUEST_SHUTDOWN recebido! Desligando o serviço e recursos...")
        handleEndShift(saveSummary = false) // Termina turno sem salvar estado final (será desligado)
        removeQuickMenuOverlay()
        stopTrackingAndSaveToHistory() // Salva viagem atual se houver
        hideMainOverlay()
        hideTrackingOverlay()
        removeFloatingIconOverlay()

        // Tenta parar o serviço de captura
        try {
            Log.d(TAG, "Tentando parar ScreenCaptureService...")
            stopService(Intent(this, ScreenCaptureService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao tentar parar ScreenCaptureService.", e)
        }

        MediaProjectionData.clear() // Limpa dados da projeção

        // Envia broadcast para a MainActivity (se existir) se desligar
        val shutdownIntent = Intent(MainActivity.ACTION_SHUTDOWN_APP)
        try {
            Log.d(TAG, "Enviando broadcast ACTION_SHUTDOWN_APP.")
            sendBroadcast(shutdownIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar broadcast de shutdown.", e)
        }

        stopSelf() // Para este serviço
    }

    // Helper para obter Parcelable de forma compatível com versões do Android
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

    // --- Gestão das Views Overlay ---
    private fun showMainOverlay(evaluationResult: EvaluationResult, offerData: OfferData) {
        if (windowManager == null) {
            Log.e(TAG, "WindowManager nulo, impossível mostrar MainOverlay.")
            return
        }
        if (mainOverlayView == null) {
            Log.d(TAG, "Criando nova instância de OverlayView.")
            mainOverlayView = OverlayView(this)
            // Aplica definições atuais ao criar
            applyAppearanceSettingsToView(mainOverlayView)
        }

        mainOverlayView?.updateState(evaluationResult, offerData)

        try {
            if (!isMainOverlayAdded) {
                windowManager?.addView(mainOverlayView, mainLayoutParams)
                isMainOverlayAdded = true
                Log.i(TAG, "Overlay Principal adicionado à janela.")
            } else {
                // Se já está adicionado, apenas atualiza o layout (posição/tamanho podem ter mudado)
                windowManager?.updateViewLayout(mainOverlayView, mainLayoutParams)
                Log.v(TAG, "Overlay Principal atualizado na janela.")
            }
        } catch (e: Exception) {
            Log.e(TAG,"Erro ao adicionar/atualizar MainOverlay na janela: ${e.message}", e)
            // Reset em caso de erro
            isMainOverlayAdded = false
            mainOverlayView = null
        }
    }

    private fun hideMainOverlay() {
        if (isMainOverlayAdded && mainOverlayView != null && windowManager != null) {
            try {
                Log.d(TAG, "Removendo Overlay Principal da janela.")
                windowManager?.removeViewImmediate(mainOverlayView) // Usa removeViewImmediate para ser síncrono
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Falha ao remover MainOverlay (já pode ter sido removido): ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Erro inesperado ao remover MainOverlay: ${e.message}", e)
            } finally {
                // Garante que o estado é resetado mesmo se houver exceção
                isMainOverlayAdded = false
                mainOverlayView = null
            }
        } else {
            Log.v(TAG, "Pedido para esconder MainOverlay, mas não estava adicionado ou era nulo.")
        }
    }

    private fun showTrackingOverlay(
        initialVpk: Double?,
        initialDistance: Double?,
        initialDuration: Int?,
        offerVal: String?,
        initialKmRating: IndividualRating,
        combinedBorderRating: BorderRating
    ) {
        val currentWindowManager = windowManager
        if (currentWindowManager == null) {
            Log.e(TAG, "WindowManager nulo, impossível mostrar TrackingOverlay.")
            return
        }

        if (trackingOverlayView == null) {
            Log.d(TAG, "Criando nova instância de TrackingOverlayView.")
            trackingOverlayView = TrackingOverlayView(this, currentWindowManager, trackingLayoutParams)
            // Aplica definições atuais ao criar
            applyAppearanceSettingsToView(trackingOverlayView)
        }

        // Define os dados iniciais na view
        trackingOverlayView?.updateInitialData(initialVpk, initialDistance, initialDuration, offerVal, initialKmRating, combinedBorderRating)

        try {
            if (!isTrackingOverlayAdded && trackingOverlayView != null) {
                currentWindowManager.addView(trackingOverlayView, trackingLayoutParams)
                isTrackingOverlayAdded = true
                Log.i(TAG, "Overlay de Acompanhamento adicionado à janela.")
            } else if (isTrackingOverlayAdded && trackingOverlayView != null) {
                // Se já estava adicionado, apenas atualiza o layout
                currentWindowManager.updateViewLayout(trackingOverlayView, trackingLayoutParams)
                Log.v(TAG, "Overlay de Acompanhamento atualizado na janela.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao adicionar/atualizar TrackingOverlay na janela: ${e.message}", e)
            // Reset em caso de erro
            isTrackingOverlayAdded = false
            trackingOverlayView = null
        }
    }

    private fun hideTrackingOverlay() {
        if (isTrackingOverlayAdded && trackingOverlayView != null && windowManager != null) {
            try {
                Log.d(TAG, "Removendo Overlay de Acompanhamento da janela.")
                windowManager?.removeViewImmediate(trackingOverlayView)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Falha ao remover TrackingOverlay (já pode ter sido removido): ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Erro inesperado ao remover TrackingOverlay: ${e.message}", e)
            } finally {
                isTrackingOverlayAdded = false
                trackingOverlayView = null
            }
        } else {
            Log.v(TAG, "Pedido para esconder TrackingOverlay, mas não estava adicionado ou era nulo.")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addFloatingIconOverlay() {
        if (windowManager == null) {
            Log.e(TAG, "WindowManager nulo, impossível adicionar FloatingIcon.")
            return
        }
        if (isFloatingIconAdded) {
            Log.d(TAG, "FloatingIcon já está adicionado, ignorando pedido.")
            return
        }

        if (floatingIconView == null) {
            Log.d(TAG, "Criando nova instância de ImageButton para FloatingIcon.")
            floatingIconView = ImageButton(this).apply {
                setImageResource(R.drawable.smartdriver) // Use seu ícone
                setBackgroundResource(R.drawable.fab_background) // Use seu background circular
                scaleType = ScaleType.CENTER_INSIDE
                setOnTouchListener(createFloatingIconTouchListener())
            }
        }

        try {
            windowManager?.addView(floatingIconView, floatingIconLayoutParams)
            isFloatingIconAdded = true
            Log.i(TAG, "Ícone Flutuante adicionado à janela.")
        } catch (e: Exception) {
            Log.e(TAG,"Erro ao adicionar FloatingIcon na janela: ${e.message}", e)
            // Reset em caso de erro
            isFloatingIconAdded = false
            floatingIconView = null
        }
    }

    private fun removeFloatingIconOverlay() {
        if (isFloatingIconAdded && floatingIconView != null && windowManager != null) {
            try {
                Log.d(TAG, "Removendo Ícone Flutuante da janela.")
                windowManager?.removeViewImmediate(floatingIconView)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Falha ao remover FloatingIcon (já pode ter sido removido): ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Erro inesperado ao remover FloatingIcon: ${e.message}", e)
            } finally {
                isFloatingIconAdded = false
                floatingIconView = null
            }
        } else {
            Log.v(TAG, "Pedido para remover FloatingIcon, mas não estava adicionado ou era nulo.")
        }
    }

    private fun addQuickMenuOverlay() {
        if (windowManager == null) {
            Log.e(TAG, "WindowManager nulo, impossível adicionar QuickMenu.")
            return
        }
        if (isQuickMenuAdded) {
            Log.d(TAG, "QuickMenu já está adicionado, ignorando pedido.")
            return
        }

        if (quickMenuView == null) {
            Log.d(TAG, "Criando nova instância de MenuView.")
            quickMenuView = MenuView(this)
            updateMenuViewShiftUI() // Atualiza a UI do menu com o estado atual do turno ao criar
        }

        try {
            // Usa a posição atual do ícone flutuante para posicionar o menu
            // (ajustar x/y se necessário para ficar ao lado/abaixo do ícone)
            menuLayoutParams.x = floatingIconLayoutParams.x // Mesma posição X
            menuLayoutParams.y = floatingIconLayoutParams.y + floatingIconLayoutParams.height + (5 * resources.displayMetrics.density).toInt() // Abaixo do ícone com margem

            windowManager?.addView(quickMenuView, menuLayoutParams)
            isQuickMenuAdded = true
            Log.i(TAG, "Menu Rápido adicionado à janela.")
        } catch (e: Exception) {
            Log.e(TAG,"Erro ao adicionar QuickMenu na janela: ${e.message}", e)
            // Reset em caso de erro
            isQuickMenuAdded = false
            quickMenuView = null
        }
    }

    private fun removeQuickMenuOverlay() {
        if (isQuickMenuAdded && quickMenuView != null && windowManager != null) {
            try {
                Log.d(TAG, "Removendo Menu Rápido da janela.")
                windowManager?.removeViewImmediate(quickMenuView)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Falha ao remover QuickMenu (já pode ter sido removido): ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Erro inesperado ao remover QuickMenu: ${e.message}", e)
            } finally {
                isQuickMenuAdded = false
                quickMenuView = null
            }
        } else {
            Log.v(TAG, "Pedido para remover QuickMenu, mas não estava adicionado ou era nulo.")
        }
    }

    // --- Listener do Ícone Flutuante ---
    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingIconTouchListener(): View.OnTouchListener {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var startTime: Long = 0
        var isDragging = false // Controla se o toque atual é um arraste

        return View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingIconLayoutParams.x
                    initialY = floatingIconLayoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    startTime = System.currentTimeMillis()
                    isDragging = false // Reset no início do toque
                    true // Consome o evento DOWN
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)

                    // Se o movimento exceder o 'slop', considera como arraste
                    if (deltaX > touchSlop || deltaY > touchSlop) {
                        isDragging = true
                    }

                    // Só atualiza a posição se for um arraste
                    if (isDragging) {
                        val newX = initialX + (event.rawX - initialTouchX).toInt()
                        val newY = initialY + (event.rawY - initialTouchY).toInt()

                        // Limita a posição dentro da tela
                        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
                        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
                        val iconWidth = view.width
                        val iconHeight = view.height

                        floatingIconLayoutParams.x = newX.coerceIn(0, screenWidth - iconWidth)
                        floatingIconLayoutParams.y = newY.coerceIn(0, screenHeight - iconHeight)

                        try {
                            if (isFloatingIconAdded && floatingIconView != null && windowManager != null) {
                                windowManager?.updateViewLayout(floatingIconView, floatingIconLayoutParams)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Erro ao atualizar posição do ícone durante MOVE: ${e.message}")
                        }
                    }
                    true // Consome o evento MOVE
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - startTime

                    // Se não foi considerado arraste E a duração foi curta, é um clique
                    if (!isDragging && duration < ViewConfiguration.getTapTimeout()) {
                        Log.d(TAG, "Floating icon CLICKED (isDragging=false, duration=${duration}ms)")
                        if (!isQuickMenuAdded) {
                            handleShowQuickMenu()
                        } else {
                            handleDismissMenu()
                        }
                        // Chama performClick para acessibilidade e efeitos visuais (ripple)
                        view.performClick()
                    } else {
                        // Se foi arraste, pode querer fazer algo ao soltar (ex: snap to edge),
                        // mas aqui apenas logamos.
                        Log.d(TAG, "Floating icon DRAGGED and released (isDragging=${isDragging}, duration=${duration}ms)")
                        // Atualiza a posição do menu se ele estiver visível após o arraste
                        if(isQuickMenuAdded && quickMenuView != null && windowManager != null) {
                            try {
                                menuLayoutParams.x = floatingIconLayoutParams.x
                                menuLayoutParams.y = floatingIconLayoutParams.y + floatingIconLayoutParams.height + (5 * resources.displayMetrics.density).toInt()
                                windowManager?.updateViewLayout(quickMenuView, menuLayoutParams)
                            } catch (e: Exception) {
                                Log.w(TAG, "Erro ao atualizar posição do menu após drag: ${e.message}")
                            }
                        }
                    }
                    // Reset explícito de isDragging pode ser redundante devido ao ACTION_DOWN, mas seguro.
                    isDragging = false
                    true // Consome o evento UP
                }
                else -> false // Não consome outros eventos
            }
        }
    }


    // --- Funções de Tracking e Salvamento ---
    private fun stopTrackingTimer() {
        trackingUpdateHandler.removeCallbacks(trackingUpdateRunnable)
        Log.d(TAG, "Timer de atualização do tracking parado.")
    }

    private fun stopTrackingAndSaveToHistory() {
        if (!isCurrentlyTracking) {
            Log.d(TAG, "Pedido para parar tracking, mas nenhum estava ativo.")
            return
        }

        Log.i(TAG, "Finalizando Tracking e Salvando no Histórico...")
        val endTimeMs = System.currentTimeMillis()
        stopTrackingTimer() // Para as atualizações em tempo real

        val finalElapsedTimeMs = endTimeMs - trackingStartTimeMs
        // Garante que a duração mínima é respeitada para evitar divisão por zero ou valores irreais
        val finalElapsedTimeSec = max(MIN_TRACKING_TIME_SEC, finalElapsedTimeMs / 1000)

        var finalVph: Double? = null
        if (trackedOfferValue > 0) {
            val finalHours = finalElapsedTimeSec / 3600.0 // Converte segundos para horas (double)
            if (finalHours > 0) {
                val calculatedVph = trackedOfferValue / finalHours
                // Verifica se o resultado é finito (evita Infinito ou NaN)
                if (calculatedVph.isFinite()) {
                    finalVph = calculatedVph
                } else {
                    Log.w(TAG, "Cálculo do VPH final resultou em valor não finito.")
                }
            }
        }

        Log.i(TAG, "Dados Finais da Viagem: Duração=${finalElapsedTimeSec}s, €/h Final=${finalVph?.let { decimalFormat.format(it) } ?: "--"}")

        // Cria a entrada do histórico
        val entry = TripHistoryEntry(
            startTimeMillis = trackingStartTimeMs,
            endTimeMillis = endTimeMs,
            durationSeconds = finalElapsedTimeSec,
            offerValue = trackedOfferValue.takeIf { it > 0 }, // Salva null se valor for 0
            initialVph = trackedInitialVph,
            finalVph = finalVph,
            initialVpk = trackedInitialVpk,
            initialDistanceKm = trackedOfferData?.calculateTotalDistance()?.takeIf { it > 0 },
            initialDurationMinutes = trackedOfferData?.calculateTotalTimeMinutes()?.takeIf { it > 0 },
            serviceType = trackedOfferData?.serviceType?.takeIf { it.isNotEmpty() },
            originalBorderRating = this.trackedCombinedBorderRating
        )

        // Salva a entrada
        saveHistoryEntry(entry)

        // Adiciona ganhos ao turno, se ativo
        if (isShiftActive) {
            val offerVal = entry.offerValue ?: 0.0 // Usa 0.0 se offerValue for null
            if (offerVal > 0) {
                shiftTotalEarnings += offerVal
                Log.i(TAG, "Ganhos do turno atualizados: +${String.format(Locale.US,"%.2f",offerVal)}€. Total Turno: ${String.format(Locale.US,"%.2f",shiftTotalEarnings)}€")
                saveShiftState() // Salva o novo total do turno
                updateMenuViewShiftUI() // Atualiza a UI do menu se estiver visível
            }
        }

        // Limpa o estado de tracking atual
        isCurrentlyTracking = false
        trackingStartTimeMs = 0L
        trackedOfferData = null
        trackedOfferValue = 0.0
        trackedInitialVph = null
        trackedInitialVpk = null
        trackedInitialKmRating = IndividualRating.UNKNOWN
        trackedCombinedBorderRating = BorderRating.GRAY

        hideTrackingOverlay() // Esconde a view de tracking
        updateShiftNotification() // Atualiza a notificação para o estado pós-tracking
    }

    private fun saveHistoryEntry(newEntry: TripHistoryEntry) {
        try {
            // Converte a nova entrada para JSON
            val newJson = gson.toJson(newEntry)

            // Obtém a lista JSON atual das SharedPreferences
            val currentJson = historyPrefs.getString(KEY_TRIP_HISTORY, "[]") // Default é lista vazia

            // Define o tipo para deserialização (Lista de Strings JSON)
            val listType = object : TypeToken<MutableList<String>>() {}.type

            // Tenta deserializar a lista atual, ou cria uma nova se houver erro ou for nula
            val list: MutableList<String> = try {
                gson.fromJson(currentJson, listType) ?: mutableListOf()
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao deserializar histórico existente, criando nova lista.", e)
                mutableListOf()
            }

            // Adiciona o JSON da nova entrada à lista
            list.add(newJson)

            // Converte a lista atualizada de volta para JSON
            val updatedJson = gson.toJson(list)

            // Salva a lista JSON atualizada nas SharedPreferences
            historyPrefs.edit().putString(KEY_TRIP_HISTORY, updatedJson).apply()
            Log.i(TAG,"Entrada de histórico salva com sucesso. Total de entradas: ${list.size}")

        } catch (e: Exception) {
            // Captura qualquer exceção durante o processo (toJson, fromJson, SharedPreferences)
            Log.e(TAG, "ERRO CRÍTICO ao salvar entrada no histórico: ${e.message}", e)
        }
    }


    // --- Gestão Aparência ---
    private fun applyAppearanceSettings(fontSizePercent: Int, transparencyPercent: Int) {
        Log.d(TAG, "Aplicando aparência: Fonte=$fontSizePercent%, Transparência=$transparencyPercent%")
        // Aplica a todas as views relevantes
        applyAppearanceSettingsToView(mainOverlayView, fontSizePercent, transparencyPercent)
        applyAppearanceSettingsToView(trackingOverlayView, null, transparencyPercent) // Tracking não muda fonte, só transparência
        // Adicionar outras views aqui se necessário (quickMenuView, etc.)
    }

    // Aplica configurações a uma view específica
    private fun applyAppearanceSettingsToView(view: View?, fontSizePercent: Int? = null, transparencyPercent: Int? = null) {
        view ?: return // Retorna se a view for nula

        // Calcula alpha (0.0 = totalmente transparente, 1.0 = totalmente opaco)
        val finalTransparency = transparencyPercent ?: SettingsActivity.getTransparency(this)
        val alpha = (1.0f - (finalTransparency / 100f)).coerceIn(0.0f, 1.0f) // Garante entre 0 e 1

        when (view) {
            is OverlayView -> {
                // Calcula escala da fonte (100% = 1.0f)
                val finalFontSizePercent = fontSizePercent ?: SettingsActivity.getFontSize(this)
                val scale = finalFontSizePercent / 100f
                view.updateFontSize(scale)
                view.updateAlpha(alpha)
                Log.v(TAG, "Aplicado a OverlayView: Scale=$scale, Alpha=$alpha")
            }
            is TrackingOverlayView -> {
                // TrackingOverlayView pode usar alpha diretamente na view raiz
                view.alpha = alpha
                Log.v(TAG, "Aplicado a TrackingOverlayView: Alpha=$alpha")
            }
            // Adicionar outros tipos de view aqui se tiverem métodos específicos
            // Ex: is MenuView -> { view.alpha = alpha }
            else -> {
                // Aplica alpha genérico se não for tipo específico
                view.alpha = alpha
                Log.v(TAG, "Aplicado alpha genérico a ${view::class.java.simpleName}: Alpha=$alpha")
            }
        }
    }

    // Atualiza o layout das views (útil após mudança de settings ou drag)
    private fun updateLayouts() {
        if (isMainOverlayAdded && mainOverlayView != null && windowManager != null) {
            try { windowManager?.updateViewLayout(mainOverlayView, mainLayoutParams) }
            catch (e: Exception) { Log.w(TAG, "Erro ao atualizar layout MainOverlay: ${e.message}") }
        }
        if (isTrackingOverlayAdded && trackingOverlayView != null && windowManager != null) {
            try { windowManager?.updateViewLayout(trackingOverlayView, trackingLayoutParams) }
            catch (e: Exception) { Log.w(TAG, "Erro ao atualizar layout TrackingOverlay: ${e.message}") }
        }
        if (isFloatingIconAdded && floatingIconView != null && windowManager != null) {
            try { windowManager?.updateViewLayout(floatingIconView, floatingIconLayoutParams) }
            catch (e: Exception) { Log.w(TAG, "Erro ao atualizar layout FloatingIcon: ${e.message}") }
        }
        if (isQuickMenuAdded && quickMenuView != null && windowManager != null) {
            try { windowManager?.updateViewLayout(quickMenuView, menuLayoutParams) }
            catch (e: Exception) { Log.w(TAG, "Erro ao atualizar layout QuickMenu: ${e.message}") }
        }
        Log.v(TAG, "Layouts atualizados.")
    }

    // --- Funções de Gestão do Turno ---
    private fun loadShiftState() {
        isShiftActive = shiftPrefs.getBoolean(KEY_SHIFT_ACTIVE, false)
        isShiftPaused = shiftPrefs.getBoolean(KEY_SHIFT_PAUSED, false)
        shiftStartTimeMillis = shiftPrefs.getLong(KEY_SHIFT_START_TIME, 0L)
        shiftLastPauseOrResumeTimeMillis = shiftPrefs.getLong(KEY_SHIFT_LAST_PAUSE_OR_RESUME_TIME, 0L)
        shiftAccumulatedWorkedTimeMillis = shiftPrefs.getLong(KEY_SHIFT_ACCUMULATED_WORKED_TIME, 0L)
        // Lê como Float e converte para Double para evitar perda de precisão se usar Double ao salvar
        shiftTotalEarnings = shiftPrefs.getFloat(KEY_SHIFT_TOTAL_EARNINGS, 0f).toDouble()

        Log.i(TAG, "Estado do turno carregado: Ativo=$isShiftActive, Pausado=$isShiftPaused, Inicio=$shiftStartTimeMillis, UltimaAcao=$shiftLastPauseOrResumeTimeMillis, Acumulado=$shiftAccumulatedWorkedTimeMillis, Ganhos=$shiftTotalEarnings")

        // Lógica para tratar estado inconsistente se o serviço foi morto enquanto o turno estava ativo
        if (isShiftActive && !isShiftPaused && shiftLastPauseOrResumeTimeMillis > 0) {
            // Calcula tempo desde a última ação registrada (pausa/resume/inicio)
            val timeSinceLastAction = System.currentTimeMillis() - shiftLastPauseOrResumeTimeMillis
            // Considera um tempo limite razoável (ex: 1 minuto) para evitar adicionar horas se ficou parado muito tempo
            val maxTimeToAssumeWorked = 60 * 1000L
            if (timeSinceLastAction > 0 && timeSinceLastAction < maxTimeToAssumeWorked ) {
                Log.w(TAG, "Serviço reiniciado com turno ativo. Adicionando ${timeSinceLastAction}ms ao tempo acumulado e iniciando PAUSADO para segurança.")
                shiftAccumulatedWorkedTimeMillis += timeSinceLastAction
                isShiftPaused = true // Inicia pausado para o utilizador retomar conscientemente
                shiftLastPauseOrResumeTimeMillis = System.currentTimeMillis() // Atualiza tempo da "pausa" forçada
                saveShiftState() // Salva o estado corrigido
            } else if (timeSinceLastAction >= maxTimeToAssumeWorked) {
                Log.w(TAG, "Serviço reiniciado com turno ativo, mas última ação foi há muito tempo (${timeSinceLastAction}ms). Iniciando PAUSADO sem adicionar tempo.")
                isShiftPaused = true
                shiftLastPauseOrResumeTimeMillis = System.currentTimeMillis()
                saveShiftState()
            }
        } else if (isShiftActive && !isShiftPaused && shiftLastPauseOrResumeTimeMillis == 0L && shiftStartTimeMillis > 0L) {
            // Caso raro: ativo, não pausado, mas sem tempo de última ação (pode acontecer no primeiro início?)
            Log.w(TAG, "Estado do turno inconsistente (ativo, não pausado, mas sem tempo de última ação). Definindo tempo da última ação como agora.")
            shiftLastPauseOrResumeTimeMillis = System.currentTimeMillis()
            saveShiftState()
        }
    }

    private fun saveShiftState() {
        // Se o turno não está ativo, limpa as prefs relacionadas ao turno
        if (!isShiftActive) {
            Log.d(TAG, "Turno inativo. Limpando SharedPreferences do estado do turno.")
            shiftPrefs.edit().clear().apply()
            return
        }

        // Se o turno está ativo, salva o estado atual
        Log.d(TAG,"Salvando estado do turno: Ativo=$isShiftActive, Pausado=$isShiftPaused, Inicio=$shiftStartTimeMillis, UltimaAcao=$shiftLastPauseOrResumeTimeMillis, Acumulado=$shiftAccumulatedWorkedTimeMillis, Ganhos=${shiftTotalEarnings.toFloat()}")
        shiftPrefs.edit().apply {
            putBoolean(KEY_SHIFT_ACTIVE, isShiftActive)
            putBoolean(KEY_SHIFT_PAUSED, isShiftPaused)
            putLong(KEY_SHIFT_START_TIME, shiftStartTimeMillis)
            putLong(KEY_SHIFT_LAST_PAUSE_OR_RESUME_TIME, shiftLastPauseOrResumeTimeMillis)
            putLong(KEY_SHIFT_ACCUMULATED_WORKED_TIME, shiftAccumulatedWorkedTimeMillis)
            // Salva como Float para consistência com a leitura (evita problemas de precisão Double vs Float)
            putFloat(KEY_SHIFT_TOTAL_EARNINGS, shiftTotalEarnings.toFloat())
            apply() // Usa apply() para escrita assíncrona
        }
    }

    private fun handleToggleShiftState() {
        val currentTime = System.currentTimeMillis()

        if (!isShiftActive) {
            // --- INICIAR TURNO ---
            isShiftActive = true
            isShiftPaused = false
            shiftStartTimeMillis = currentTime
            shiftLastPauseOrResumeTimeMillis = currentTime // Marca o início como a última ação
            shiftAccumulatedWorkedTimeMillis = 0L // Zera acumulado
            shiftTotalEarnings = 0.0 // Zera ganhos
            Log.i(TAG, ">>> TURNO INICIADO <<< em $currentTime")
            startShiftTimer() // Inicia o contador de tempo
        } else {
            // --- PAUSAR/RETOMAR TURNO ---
            if (isShiftPaused) {
                // --- RETOMAR ---
                isShiftPaused = false
                // Atualiza o tempo da última ação (o tempo pausado não conta)
                shiftLastPauseOrResumeTimeMillis = currentTime
                Log.i(TAG, ">>> TURNO RETOMADO <<< em $currentTime. Tempo acumulado anterior: ${shiftAccumulatedWorkedTimeMillis}ms")
                startShiftTimer() // Reinicia o contador de tempo
            } else {
                // --- PAUSAR ---
                isShiftPaused = true
                // Calcula o tempo trabalhado desde a última ação (início ou último resume)
                val workedMillisSinceLastAction = currentTime - shiftLastPauseOrResumeTimeMillis
                if (workedMillisSinceLastAction > 0) {
                    shiftAccumulatedWorkedTimeMillis += workedMillisSinceLastAction
                }
                // Atualiza o tempo da última ação para o momento da pausa
                shiftLastPauseOrResumeTimeMillis = currentTime
                Log.i(TAG, ">>> TURNO PAUSADO <<< em $currentTime. Tempo trabalhado adicionado: ${workedMillisSinceLastAction}ms. Novo acumulado: ${shiftAccumulatedWorkedTimeMillis}ms")
                stopShiftTimer() // Para o contador de tempo
            }
        }
        // Após qualquer alteração, atualiza a UI, notificação e salva o estado
        updateMenuViewShiftUI()
        updateShiftNotification()
        saveShiftState()
    }

    // *** FUNÇÃO CORRIGIDA ***
    private fun handleEndShift(saveSummary: Boolean = true) {
        if (!isShiftActive) {
            Log.d(TAG, "Nenhum turno ativo para terminar.")
            return // Retorna aqui se não houver turno ativo
        }
        val endTime = System.currentTimeMillis()
        Log.i(TAG, ">>> TURNO TERMINADO <<< em $endTime")
        stopShiftTimer() // Para o timer imediatamente

        // Calcula o tempo final trabalhado ANTES de resetar as variáveis
        val finalWorkedTimeMillis = calculateCurrentWorkedTimeMillis()
        val finalFormattedTime = formatDuration(finalWorkedTimeMillis)
        // Calcula a média final usando o tempo final calculado
        val finalAveragePerHour = calculateCurrentShiftAveragePerHour(finalWorkedTimeMillis)
        Log.i(TAG, "Resumo do Turno: Duração Total Trabalhada=$finalFormattedTime (${finalWorkedTimeMillis}ms), Ganhos=${String.format(Locale.US,"%.2f", shiftTotalEarnings)}€, Média=$finalAveragePerHour")

        // --- CORREÇÃO APLICADA AQUI ---
        // Resetar estado (cada um na sua linha, sem ';')
        isShiftActive = false
        isShiftPaused = false
        shiftStartTimeMillis = 0L
        shiftLastPauseOrResumeTimeMillis = 0L
        shiftAccumulatedWorkedTimeMillis = 0L
        // Mantém shiftTotalEarnings para o log acima, zera depois se não for salvar
        // shiftTotalEarnings = 0.0 // Zerar só depois de decidir salvar ou não

        // Atualizar UI e Notificação (cada um na sua linha, sem ';')
        updateMenuViewShiftUI() // Reflete o estado "não ativo"
        updateShiftNotification() // Reflete o estado "não ativo"

        // Salvar ou limpar estado
        if (saveSummary) {
            // Como isShiftActive é false, saveShiftState vai limpar as prefs
            Log.d(TAG, "Terminando turno com saveSummary=true. Chamando saveShiftState() que limpará as prefs.")
            saveShiftState() // Limpará as SharedPreferences porque isShiftActive é false
        } else {
            // Limpa explicitamente as prefs (redundante se saveShiftState já limpa, mas seguro)
            shiftPrefs.edit().clear().apply()
            Log.d(TAG, "Terminando turno com saveSummary=false. Estado do turno limpo sem salvar.")
        }
        // Zera a variável local de ganhos após a decisão de salvar/limpar
        shiftTotalEarnings = 0.0
        // --- FIM DA CORREÇÃO ---
    }


    private fun startShiftTimer() {
        shiftTimerRunnable?.let {
            // Remove callbacks pendentes e posta novamente
            shiftTimerHandler.removeCallbacks(it)
            shiftTimerHandler.post(it)
            Log.d(TAG, "Timer do turno iniciado/retomado.")
        } ?: run {
            // Se for nulo (primeira vez ou após erro), recria e posta
            Log.w(TAG, "ShiftTimerRunnable era nulo. Recriando e iniciando.")
            setupShiftTimerRunnable() // Garante que está inicializado
            shiftTimerHandler.post(shiftTimerRunnable!!) // Posta o runnable recém-criado
        }
        updateShiftNotification() // Atualiza notificação para estado ativo
    }

    private fun stopShiftTimer() {
        shiftTimerRunnable?.let {
            shiftTimerHandler.removeCallbacks(it)
            Log.d(TAG, "Timer do turno parado.")
        }
        updateShiftNotification() // Atualiza notificação para estado pausado/inativo
    }

    private fun calculateCurrentWorkedTimeMillis(): Long {
        if (!isShiftActive) return 0L // Se não está ativo, tempo é 0

        // Começa com o tempo já acumulado de períodos anteriores
        var currentWorkedTime = shiftAccumulatedWorkedTimeMillis

        // Se não estiver pausado, adiciona o tempo desde a última ação (início ou resume)
        if (!isShiftPaused) {
            val timeSinceLastAction = System.currentTimeMillis() - shiftLastPauseOrResumeTimeMillis
            if (timeSinceLastAction > 0) {
                currentWorkedTime += timeSinceLastAction
            }
        }
        // Garante que não retorna valor negativo (pouco provável, mas seguro)
        return max(0L, currentWorkedTime)
    }

    private fun formatDuration(millis: Long): String {
        if (millis < 0) return "00:00:00" // Trata caso negativo

        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60 // Minutos restantes após tirar horas
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60 // Segundos restantes após tirar minutos

        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun calculateCurrentShiftAveragePerHour(currentWorkedMillis: Long? = null): String {
        // Se turno inativo ou sem tempo de início, retorna placeholder
        if (!isShiftActive || shiftStartTimeMillis == 0L) return "-- €/h"

        val totalEarnings = shiftTotalEarnings
        // Usa o tempo fornecido (para cálculo final) ou calcula o atual
        val workedMillis = currentWorkedMillis ?: calculateCurrentWorkedTimeMillis()

        // Se tempo trabalhado for muito curto (ex: < 5 seg), evita cálculo e retorna placeholder
        if (workedMillis <= 5000) return "-- €/h"

        // Converte milissegundos para horas (double para precisão)
        val workedHours = workedMillis / (1000.0 * 60.0 * 60.0)

        return if (workedHours > 0 && totalEarnings > 0) {
            val average = totalEarnings / workedHours
            // Formata para uma casa decimal
            "${decimalFormat.format(average)} €/h"
        } else {
            // Se não há ganhos ou tempo é zero/negativo, retorna placeholder
            "-- €/h"
        }
    }

    // Atualiza a UI do MenuView (se existir e estiver visível)
    private fun updateMenuViewShiftUI() {
        quickMenuView?.let { menu ->
            val statusResId: Int
            val workedTimeMillis = calculateCurrentWorkedTimeMillis()
            val formattedTime = formatDuration(workedTimeMillis)
            val averagePerHour = calculateCurrentShiftAveragePerHour(workedTimeMillis) // Usa o tempo calculado

            statusResId = if (!isShiftActive) {
                R.string.shift_status_none // "Turno: Nenhum"
            } else if (isShiftPaused) {
                R.string.shift_status_paused // "Turno: Pausado"
            } else {
                R.string.shift_status_active // "Turno: Ativo"
            }

            // Atualiza os TextViews no menu
            menu.updateShiftStatus(getString(statusResId), isShiftActive, isShiftPaused)
            menu.updateShiftTimer(formattedTime)
            menu.updateShiftAverage(averagePerHour)
            Log.v(TAG,"UI do menu de turno atualizada: Status=${getString(statusResId)}, Tempo=$formattedTime, Média=$averagePerHour")
        } ?: Log.v(TAG, "Menu não está visível, UI do turno não atualizada.")
    }

    // Atualiza o texto e ícone da notificação persistente
    private fun updateShiftNotification() {
        val notificationText = when {
            isCurrentlyTracking -> "Acompanhando Viagem..." // Prioridade máxima
            isShiftActive && !isShiftPaused -> "Turno ativo (${formatDuration(calculateCurrentWorkedTimeMillis())})"
            isShiftActive && isShiftPaused -> "Turno pausado"
            else -> "SmartDriver pronto" // Estado idle
        }
        // O ícone deve indicar "atividade" se estiver rastreando OU se o turno estiver ativo e não pausado
        val isTrackingOrActive = isCurrentlyTracking || (isShiftActive && !isShiftPaused)
        updateNotification(notificationText, isTrackingOrActive)
        Log.v(TAG,"Notificação atualizada: '$notificationText', isTrackingOrActive=$isTrackingOrActive")
    }

    // --- onBind e onDestroy ---
    override fun onBind(intent: Intent?): IBinder? {
        // Não usamos binding neste serviço
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "====== Serviço Overlay DESTRUÍDO ======")
        isRunning.set(false)

        // Para timers para evitar leaks ou processamento desnecessário
        stopTrackingTimer()
        stopShiftTimer()

        // Lógica para salvar estado final do turno se estava ativo ao ser destruído
        if (isShiftActive) {
            val currentTime = System.currentTimeMillis()
            // Se não estava pausado, calcula o último trecho trabalhado
            if (!isShiftPaused && shiftLastPauseOrResumeTimeMillis > 0) {
                val workedMillisSinceLastAction = currentTime - shiftLastPauseOrResumeTimeMillis
                if (workedMillisSinceLastAction > 0) {
                    shiftAccumulatedWorkedTimeMillis += workedMillisSinceLastAction
                    Log.d(TAG, "onDestroy: Adicionando último trecho trabalhado (${workedMillisSinceLastAction}ms) ao acumulado.")
                }
            }
            // Atualiza o tempo da última ação para o momento da destruição (para cálculo correto se for reiniciado)
            shiftLastPauseOrResumeTimeMillis = currentTime
            // Força o estado para pausado ao salvar, para segurança no reinício
            isShiftPaused = true
            saveShiftState()
            Log.i(TAG,"Estado final do turno (forçado para pausado) salvo no onDestroy.")
        } else {
            // Se o turno já estava inativo, garante que as prefs estão limpas
            shiftPrefs.edit().clear().apply()
            Log.d(TAG,"onDestroy: Turno inativo, prefs limpas (ou já estavam).")
        }

        // Remove todas as views da janela para evitar WindowLeaked
        hideMainOverlay()
        hideTrackingOverlay()
        removeQuickMenuOverlay()
        removeFloatingIconOverlay()

        // Limpa referências para ajudar o Garbage Collector
        mainOverlayView = null
        trackingOverlayView = null
        windowManager = null
        quickMenuView = null
        floatingIconView = null
        shiftTimerRunnable = null // Remove referência ao runnable

        // Tenta cancelar a notificação
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
            Log.d(TAG,"Notificação $NOTIFICATION_ID cancelada.")
        } catch (e: Exception){
            Log.e(TAG, "Erro ao cancelar notificação no onDestroy", e)
        }
    }

} // Fim da classe OverlayService