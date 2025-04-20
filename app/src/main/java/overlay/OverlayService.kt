package com.example.smartdriver.overlay

import android.app.Notification // Necessário para Notification
import android.app.NotificationChannel // Necessário para NotificationChannel
import android.app.NotificationManager // Necessário para NotificationManager
import androidx.core.app.NotificationCompat // Necessário para NotificationCompat
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
// import android.os.Handler // Removido - Não precisamos mais do Handler para auto-hide
import android.os.IBinder
// import android.os.Looper // Removido - Não precisamos mais do Looper para auto-hide
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.example.smartdriver.R // Import para R.drawable.ic_stat_name
import com.example.smartdriver.SettingsActivity
import com.example.smartdriver.utils.OfferData
// import com.example.smartdriver.utils.OfferRating // Removido
import com.example.smartdriver.utils.EvaluationResult // <<< NOVO IMPORT
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        // --- NOTIFICAÇÃO ---
        private const val NOTIFICATION_ID = 1002 // ID diferente do ScreenCaptureService
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val CHANNEL_NAME = "Overlay Service"

        // --- AÇÕES E EXTRAS ---
        const val ACTION_SHOW_OVERLAY = "com.example.smartdriver.overlay.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.example.smartdriver.overlay.HIDE_OVERLAY" // Este comando ainda é usado externamente e pelo duplo toque
        const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.overlay.UPDATE_SETTINGS"
        const val EXTRA_EVALUATION_RESULT = "evaluation_result"
        const val EXTRA_OFFER_DATA = "offer_data"
        const val EXTRA_FONT_SIZE = "font_size"
        const val EXTRA_TRANSPARENCY = "transparency"

        // --- REMOVIDO AUTO_HIDE_DELAY_MS ---

        // Estado do serviço
        @JvmStatic
        val isRunning = AtomicBoolean(false)
    }

    private var windowManager: WindowManager? = null
    private var overlayView: OverlayView? = null
    private var isOverlayAdded = false
    // --- REMOVIDO hideHandler e hideRunnable ---
    private lateinit var overlayLayoutParams: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Serviço overlay criado")
        isRunning.set(true)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        initializeLayoutParams()
        startForeground(NOTIFICATION_ID, createNotification("Overlay pronto")) // Inicia em foreground
        Log.d(TAG, "Serviço iniciado em foreground.")
    }

    // --- Métodos de Notificação (inalterados) ---
    private fun createNotification(contentText: String): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartDriver")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_name) // <<< USA ÍCONE DE NOTIFICAÇÃO (Verifica se tens este icone)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação do Serviço de Overlay SmartDriver"
                enableLights(false); enableVibration(false); setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    // --- Fim Métodos de Notificação ---


    private fun initializeLayoutParams() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // Mantém FLAG_NOT_FOCUSABLE para não roubar foco da Uber
        // Mantém FLAG_NOT_TOUCH_MODAL para permitir toques fora do overlay
        // REMOVE FLAG_NOT_TOUCHABLE (se existisse) para permitir toques no overlay
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        overlayLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            val density = resources.displayMetrics.density
            y = (50 * density).toInt() // Posição Y (50dp do topo) - Ajustar se necessário
        }
        Log.d(TAG, "Layout Params inicializados (Touch Habilitado): Type=$overlayType, Flags=$flags")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val timeCmdStart = System.currentTimeMillis()
        Log.d(TAG, "[TIME] onStartCommand: ${intent?.action} at $timeCmdStart")

        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                val evaluationResult: EvaluationResult? = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java)
                val offerData: OfferData? = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java)

                if (evaluationResult != null) {
                    Log.d(TAG, "Solicitado mostrar overlay: Borda=${evaluationResult.combinedBorderRating}, Km=${evaluationResult.kmRating}, Hora=${evaluationResult.hourRating}")
                    updateNotification("Oferta: Borda ${evaluationResult.combinedBorderRating}")
                    showOverlay(evaluationResult, offerData)
                    // --- REMOVIDA chamada a resetHideTimer() ---
                } else {
                    Log.e(TAG, "ACTION_SHOW_OVERLAY recebido sem EvaluationResult válido.")
                    hideOverlay() // Esconde se dados forem inválidos
                }
                val timeShowEnd = System.currentTimeMillis(); Log.d(TAG, "[TIME] Processamento SHOW demorou ${timeShowEnd - timeCmdStart}ms")
            }
            ACTION_HIDE_OVERLAY -> {
                Log.d(TAG, "Solicitado esconder overlay (via Intent ou duplo toque)")
                updateNotification("Overlay pronto")
                hideOverlay()
                val timeHideEnd = System.currentTimeMillis(); Log.d(TAG, "[TIME] Processamento HIDE demorou ${timeHideEnd - timeCmdStart}ms")
            }
            ACTION_UPDATE_SETTINGS -> {
                Log.d(TAG, "Solicitado atualizar configurações de aparência")
                val defaultFontSize = SettingsActivity.getFontSize(this); val defaultTransparency = SettingsActivity.getTransparency(this)
                val fontSizePercent = intent.getIntExtra(EXTRA_FONT_SIZE, defaultFontSize)
                val transparencyPercent = intent.getIntExtra(EXTRA_TRANSPARENCY, defaultTransparency)
                updateOverlayAppearance(fontSizePercent, transparencyPercent)
                // --- REMOVIDA chamada a resetHideTimer() ---
                val timeUpdateEnd = System.currentTimeMillis(); Log.d(TAG, "[TIME] Processamento UPDATE demorou ${timeUpdateEnd - timeCmdStart}ms")
            }
            else -> { Log.w(TAG, "Ação desconhecida ou nula recebida: ${intent?.action}") }
        }
        return START_REDELIVER_INTENT
    }

    // Função auxiliar getParcelableExtraCompat (inalterada)
    private fun <T : Any?> getParcelableExtraCompat(intent: Intent?, key: String, clazz: Class<T>): T? {
        return intent?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(key, clazz)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra(key) as? T // Faz cast seguro
            }
        }
    }


    /**
     * Mostra ou atualiza o overlay na tela.
     * @param evaluationResult O resultado da avaliação da oferta.
     * @param offerData Os dados da oferta (pode ser nulo se apenas atualizando aparência).
     */
    private fun showOverlay(evaluationResult: EvaluationResult, offerData: OfferData?) {
        val funcStartTime = System.currentTimeMillis()
        if (windowManager == null) { Log.e(TAG, "WindowManager nulo, impossível mostrar overlay."); return }

        if (overlayView == null) {
            overlayView = OverlayView(this); Log.d(TAG,"[TIME] OverlayView criada.")
            val initialFontSize = SettingsActivity.getFontSize(this)
            val initialTransparency = SettingsActivity.getTransparency(this)
            Log.d(TAG, "Aplicando configs iniciais na nova view: Fonte=$initialFontSize%, Transp=$initialTransparency%")
            applyAppearanceSettings(initialFontSize, initialTransparency)
        }

        val timeUpdateStateStart = System.currentTimeMillis()
        overlayView?.updateState(evaluationResult, offerData)
        val timeUpdateStateEnd = System.currentTimeMillis(); Log.d(TAG,"[TIME] overlayView.updateState demorou ${timeUpdateStateEnd - timeUpdateStateStart}ms")

        try {
            val timeAddViewStart = System.currentTimeMillis()
            if (!isOverlayAdded) {
                windowManager?.addView(overlayView, overlayLayoutParams)
                isOverlayAdded = true
                Log.i(TAG, "Overlay ADICIONADO à janela.")
            } else {
                windowManager?.updateViewLayout(overlayView, overlayLayoutParams)
                Log.d(TAG, "Overlay já existe, layout ATUALIZADO.")
            }
            val timeAddViewEnd = System.currentTimeMillis(); Log.d(TAG,"[TIME] addView/updateViewLayout demorou ${timeAddViewEnd - timeAddViewStart}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao adicionar/atualizar overlay view: ${e.message}", e)
            if (e is WindowManager.BadTokenException || e is IllegalStateException ) {
                isOverlayAdded = false
                Log.e(TAG,"Erro crítico ao adicionar/atualizar view, marcado como não adicionada.")
            }
        }

        val funcEndTime = System.currentTimeMillis(); Log.d(TAG,"[TIME] showOverlay completo demorou ${funcEndTime-funcStartTime}ms")
    }


    /** Esconde o overlay da tela. */
    // --- REMOVIDO cancelamento do timer daqui ---
    private fun hideOverlay() {
        if (isOverlayAdded && overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
                isOverlayAdded = false
                updateNotification("Overlay pronto")
                Log.i(TAG, "Overlay REMOVIDO da janela.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Erro ao remover overlay (View já removida?): ${e.message}")
                isOverlayAdded = false
            } catch (e: Exception) {
                Log.w(TAG, "Erro genérico ao remover overlay: ${e.message}")
                isOverlayAdded = false
            }
        }
    }

    /** Atualiza a aparência (fonte, transparência) do overlay existente. (inalterado) */
    private fun updateOverlayAppearance(fontSizePercent: Int, transparencyPercent: Int) {
        applyAppearanceSettings(fontSizePercent, transparencyPercent) // Aplica na instância da view
        if (isOverlayAdded && overlayView != null && windowManager != null) {
            try {
                windowManager?.updateViewLayout(overlayView, overlayLayoutParams)
                Log.d(TAG,"Layout do overlay atualizado após mudança de aparência.")
            } catch (e: Exception) {
                Log.e(TAG,"Erro ao atualizar layout do overlay após mudança de aparência: ${e.message}")
            }
        }
    }

    /** Aplica as configurações de fonte e transparência na instância da OverlayView. (inalterado) */
    private fun applyAppearanceSettings(fontSizePercent: Int, transparencyPercent: Int) {
        overlayView?.let { view ->
            val fontScale = fontSizePercent / 100f
            val alpha = 1.0f - (transparencyPercent / 100f)
            Log.d(TAG, "Aplicando configs de aparência na view: Scale=$fontScale, Alpha=$alpha")
            view.updateFontSize(fontScale)
            view.updateAlpha(alpha)
        } ?: Log.w(TAG, "Tentativa de aplicar aparência mas overlayView é nula.")
    }

    // --- REMOVIDO resetHideTimer() ---

    override fun onBind(intent: Intent?): IBinder? {
        return null // Serviço não permite binding
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "Serviço overlay destruído")
        isRunning.set(false)
        hideOverlay() // Garante que o overlay seja removido
        overlayView = null
        windowManager = null
        // --- REMOVIDO limpeza do handler ---
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        Log.d(TAG,"Notificação do OverlayService cancelada.")
    }
}