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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
        const val ACTION_HIDE_OVERLAY = "com.example.smartdriver.overlay.HIDE_OVERLAY"
        const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.overlay.UPDATE_SETTINGS"
        // const val EXTRA_RATING = "rating" // Removido
        // <<< NOVA CHAVE para o resultado da avaliação >>>
        const val EXTRA_EVALUATION_RESULT = "evaluation_result"
        const val EXTRA_OFFER_DATA = "offer_data"
        const val EXTRA_FONT_SIZE = "font_size"
        const val EXTRA_TRANSPARENCY = "transparency"

        // Tempo para esconder automaticamente o overlay
        private const val AUTO_HIDE_DELAY_MS = 8000L // 8 segundos

        // Estado do serviço
        @JvmStatic
        val isRunning = AtomicBoolean(false)
    }

    private var windowManager: WindowManager? = null
    private var overlayView: OverlayView? = null
    private var isOverlayAdded = false
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideOverlay() }
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

    // --- Métodos de Notificação ---
    private fun createNotification(contentText: String): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartDriver")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_name)
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
            // Log.d(TAG,"Canal de Notificação do Overlay criado.") // Menos verboso
        }
    }
    // --- Fim Métodos de Notificação ---


    private fun initializeLayoutParams() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // FLAG_NOT_TOUCHABLE pode ser útil se não quiser interação nenhuma com o overlay
        // val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        overlayLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            val density = resources.displayMetrics.density
            y = (50 * density).toInt() // Posição Y (50dp do topo)
        }
        Log.d(TAG, "Layout Params inicializados: Type=$overlayType, Flags=$flags")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val timeCmdStart = System.currentTimeMillis()
        Log.d(TAG, "[TIME] onStartCommand: ${intent?.action} at $timeCmdStart")

        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                // <<< MUDANÇA: Lê EvaluationResult em vez de OfferRating >>>
                val evaluationResult: EvaluationResult? = getParcelableExtraCompat(intent, EXTRA_EVALUATION_RESULT, EvaluationResult::class.java)
                val offerData: OfferData? = getParcelableExtraCompat(intent, EXTRA_OFFER_DATA, OfferData::class.java)

                if (evaluationResult != null) {
                    Log.d(TAG, "Solicitado mostrar overlay: Borda=${evaluationResult.combinedBorderRating}, Km=${evaluationResult.kmRating}, Hora=${evaluationResult.hourRating}")
                    // Atualiza notificação com mais detalhes (opcional)
                    updateNotification("Oferta: Borda ${evaluationResult.combinedBorderRating}")
                    // <<< MUDANÇA: Passa evaluationResult para showOverlay >>>
                    showOverlay(evaluationResult, offerData)
                    resetHideTimer() // Reinicia timer para esconder automaticamente
                } else {
                    Log.e(TAG, "ACTION_SHOW_OVERLAY recebido sem EvaluationResult válido.")
                    // Considerar esconder o overlay ou mostrar estado de erro
                    hideOverlay()
                }
                val timeShowEnd = System.currentTimeMillis(); Log.d(TAG, "[TIME] Processamento SHOW demorou ${timeShowEnd - timeCmdStart}ms")
            }
            ACTION_HIDE_OVERLAY -> {
                Log.d(TAG, "Solicitado esconder overlay")
                updateNotification("Overlay pronto") // Volta notificação padrão
                hideOverlay()
                val timeHideEnd = System.currentTimeMillis(); Log.d(TAG, "[TIME] Processamento HIDE demorou ${timeHideEnd - timeCmdStart}ms")
            }
            ACTION_UPDATE_SETTINGS -> {
                Log.d(TAG, "Solicitado atualizar configurações de aparência")
                val defaultFontSize = SettingsActivity.getFontSize(this); val defaultTransparency = SettingsActivity.getTransparency(this)
                val fontSizePercent = intent.getIntExtra(EXTRA_FONT_SIZE, defaultFontSize)
                val transparencyPercent = intent.getIntExtra(EXTRA_TRANSPARENCY, defaultTransparency)
                updateOverlayAppearance(fontSizePercent, transparencyPercent)
                if (isOverlayAdded) { // Só reinicia o timer se o overlay estiver visível
                    resetHideTimer()
                }
                val timeUpdateEnd = System.currentTimeMillis(); Log.d(TAG, "[TIME] Processamento UPDATE demorou ${timeUpdateEnd - timeCmdStart}ms")
            }
            else -> { Log.w(TAG, "Ação desconhecida ou nula recebida: ${intent?.action}") }
        }
        // START_REDELIVER_INTENT: Se o serviço for morto pelo sistema, tenta reiniciar com o último Intent
        return START_REDELIVER_INTENT
    }

    // Função auxiliar para obter Parcelable de forma compatível com versões do Android
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
    // <<< MUDANÇA: Aceita EvaluationResult >>>
    private fun showOverlay(evaluationResult: EvaluationResult, offerData: OfferData?) {
        val funcStartTime = System.currentTimeMillis()
        if (windowManager == null) { Log.e(TAG, "WindowManager nulo, impossível mostrar overlay."); return }

        // Cria a OverlayView se ainda não existir
        if (overlayView == null) {
            overlayView = OverlayView(this); Log.d(TAG,"[TIME] OverlayView criada.")
            // Aplica configurações de aparência iniciais
            val initialFontSize = SettingsActivity.getFontSize(this)
            val initialTransparency = SettingsActivity.getTransparency(this)
            Log.d(TAG, "Aplicando configs iniciais na nova view: Fonte=$initialFontSize%, Transp=$initialTransparency%")
            applyAppearanceSettings(initialFontSize, initialTransparency)
        }

        // Atualiza o estado da OverlayView com os novos dados/avaliação
        val timeUpdateStateStart = System.currentTimeMillis()
        // <<< MUDANÇA: Passa evaluationResult para updateState >>>
        overlayView?.updateState(evaluationResult, offerData)
        val timeUpdateStateEnd = System.currentTimeMillis(); Log.d(TAG,"[TIME] overlayView.updateState demorou ${timeUpdateStateEnd - timeUpdateStateStart}ms")

        try {
            val timeAddViewStart = System.currentTimeMillis()
            if (!isOverlayAdded) {
                // Adiciona a view à janela se ainda não estiver adicionada
                windowManager?.addView(overlayView, overlayLayoutParams)
                isOverlayAdded = true
                Log.i(TAG, "Overlay ADICIONADO à janela.")
            } else {
                // Atualiza o layout da view existente (pode ser necessário se o tamanho mudar)
                windowManager?.updateViewLayout(overlayView, overlayLayoutParams)
                Log.d(TAG, "Overlay já existe, layout ATUALIZADO.")
            }
            val timeAddViewEnd = System.currentTimeMillis(); Log.d(TAG,"[TIME] addView/updateViewLayout demorou ${timeAddViewEnd - timeAddViewStart}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao adicionar/atualizar overlay view: ${e.message}", e)
            // Se ocorrer um erro grave (BadToken), marca como não adicionado para tentar de novo na próxima vez
            if (e is WindowManager.BadTokenException || e is IllegalStateException ) {
                isOverlayAdded = false
                Log.e(TAG,"Erro crítico ao adicionar/atualizar view, marcado como não adicionada.")
                // Poderia tentar remover forçadamente se achar necessário
                // forceRemoveOverlay()
            }
        }

        val funcEndTime = System.currentTimeMillis(); Log.d(TAG,"[TIME] showOverlay completo demorou ${funcEndTime-funcStartTime}ms")
    }


    /** Esconde o overlay da tela e cancela o timer de auto-hide. */
    private fun hideOverlay() {
        hideHandler.removeCallbacks(hideRunnable) // Cancela qualquer auto-hide pendente
        if (isOverlayAdded && overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView) // Remove a view da janela
                isOverlayAdded = false
                updateNotification("Overlay pronto") // Atualiza notificação
                Log.i(TAG, "Overlay REMOVIDO da janela.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Erro ao remover overlay (View já removida?): ${e.message}")
                isOverlayAdded = false // Garante que o estado está correto
            } catch (e: Exception) {
                Log.w(TAG, "Erro genérico ao remover overlay: ${e.message}")
                isOverlayAdded = false // Garante que o estado está correto
            }
        } else {
            // Log.d(TAG, "hideOverlay chamado mas overlay não estava adicionado.") // Menos verboso
        }
    }

    /** Tenta remover o overlay imediatamente (usar com cautela). */
    private fun forceRemoveOverlay() {
        Log.w(TAG,"Forçando remoção do overlay.")
        hideHandler.removeCallbacks(hideRunnable)
        isOverlayAdded = false // Assume que será removido
        if (overlayView != null && windowManager != null) {
            try {
                windowManager?.removeViewImmediate(overlayView) // Tentativa mais direta
                Log.i(TAG,"Overlay removido forçadamente.")
            } catch (e: Exception) {
                Log.e(TAG,"Erro na remoção forçada do overlay: ${e.message}")
            }
        }
        overlayView = null // Descarta a view antiga após forçar remoção
    }

    /** Atualiza a aparência (fonte, transparência) do overlay existente. */
    private fun updateOverlayAppearance(fontSizePercent: Int, transparencyPercent: Int) {
        applyAppearanceSettings(fontSizePercent, transparencyPercent) // Aplica na instância da view
        // Se o overlay estiver na tela, atualiza seu layout (pode ser necessário se o tamanho mudar)
        if (isOverlayAdded && overlayView != null && windowManager != null) {
            try {
                windowManager?.updateViewLayout(overlayView, overlayLayoutParams)
                Log.d(TAG,"Layout do overlay atualizado após mudança de aparência.")
            } catch (e: Exception) {
                Log.e(TAG,"Erro ao atualizar layout do overlay após mudança de aparência: ${e.message}")
            }
        }
    }

    /** Aplica as configurações de fonte e transparência na instância da OverlayView. */
    private fun applyAppearanceSettings(fontSizePercent: Int, transparencyPercent: Int) {
        overlayView?.let { view ->
            val fontScale = fontSizePercent / 100f
            val alpha = 1.0f - (transparencyPercent / 100f)
            Log.d(TAG, "Aplicando configs de aparência na view: Scale=$fontScale, Alpha=$alpha")
            view.updateFontSize(fontScale)
            view.updateAlpha(alpha)
        } ?: Log.w(TAG, "Tentativa de aplicar aparência mas overlayView é nula.")
    }

    /** Reinicia o timer para esconder o overlay automaticamente. */
    private fun resetHideTimer() {
        hideHandler.removeCallbacks(hideRunnable) // Remove o callback antigo
        hideHandler.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS) // Agenda o novo
        Log.d(TAG, "Timer auto-hide reiniciado (${AUTO_HIDE_DELAY_MS}ms).")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Serviço não permite binding
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "Serviço overlay destruído")
        isRunning.set(false)
        hideOverlay() // Garante que o overlay seja removido
        overlayView = null // Libera referência
        windowManager = null // Libera referência
        hideHandler.removeCallbacksAndMessages(null) // Limpa o handler
        // Remove a notificação persistente
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        Log.d(TAG,"Notificação do OverlayService cancelada.")
    }
}