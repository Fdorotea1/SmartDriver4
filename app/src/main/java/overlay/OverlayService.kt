package com.example.smartdriver.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.example.smartdriver.SettingsActivity // Import DESCOMENTADO
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.OfferRating

/**
 * Serviço que gerencia a exibição do overlay visual (semáforo)
 * que aparece sobre outros apps para mostrar a classificação da oferta.
 * Este serviço controla a criação, atualização e remoção da OverlayView.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"

        // Ações para Intents
        const val ACTION_SHOW_OVERLAY = "com.example.smartdriver.overlay.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.example.smartdriver.overlay.HIDE_OVERLAY"
        const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.overlay.UPDATE_SETTINGS"

        // Extras para Intents
        const val EXTRA_RATING = "rating"
        const val EXTRA_OFFER_DATA = "offer_data"
        const val EXTRA_FONT_SIZE = "font_size"
        const val EXTRA_TRANSPARENCY = "transparency" // 0-100

        // Tempo padrão para auto-esconder o overlay (em milissegundos)
        private const val AUTO_HIDE_DELAY_MS = 8000L // 8 segundos

        // Constantes padrão removidas daqui, pois SettingsActivity tem os defaults
    }

    private var windowManager: WindowManager? = null
    private var overlayView: OverlayView? = null
    private var isOverlayAdded = false // Controla se a view já foi adicionada ao WindowManager

    // Handler e Runnable para auto-esconder o overlay
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideOverlay() }

    // Parâmetros da janela (definidos uma vez e reutilizados)
    private lateinit var overlayLayoutParams: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Serviço de overlay criado")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        initializeLayoutParams()
    }

    /** Inicializa os parâmetros da janela do overlay */
    private fun initializeLayoutParams() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        overlayLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType, flags, PixelFormat.TRANSLUCENT
        )
        overlayLayoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        val density = resources.displayMetrics.density
        overlayLayoutParams.y = (50 * density).toInt() // 50dp do topo
        val displayMetrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(displayMetrics) // Deprecated mas funciona como fallback
        val width = displayMetrics.widthPixels
        val preferredSize = (width * 0.25).toInt() // 25% da largura
        overlayLayoutParams.width = preferredSize
        overlayLayoutParams.height = preferredSize
        Log.d(TAG, "Layout Params inicializados: Type=$overlayType, Flags=$flags, Size=${preferredSize}x$preferredSize")
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val timeCmdStart = System.currentTimeMillis()
        Log.d(TAG, "[TIME] onStartCommand recebido com ação: ${intent?.action} at $timeCmdStart")

        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                val rating = intent.getSerializableExtra(EXTRA_RATING) as? OfferRating ?: OfferRating.UNKNOWN
                val offerData: OfferData? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_OFFER_DATA, OfferData::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_OFFER_DATA)
                }
                Log.d(TAG, "Solicitado mostrar overlay com classificação: $rating")
                showOverlay(rating, offerData)
                resetHideTimer()
                val timeShowEnd = System.currentTimeMillis()
                Log.d(TAG, "[TIME] Processamento SHOW_OVERLAY demorou ${timeShowEnd - timeCmdStart}ms")
            }
            ACTION_HIDE_OVERLAY -> {
                Log.d(TAG, "Solicitado esconder overlay")
                hideOverlay()
                val timeHideEnd = System.currentTimeMillis()
                Log.d(TAG, "[TIME] Processamento HIDE_OVERLAY demorou ${timeHideEnd - timeCmdStart}ms")
            }
            ACTION_UPDATE_SETTINGS -> {
                Log.d(TAG, "Solicitado atualizar configurações do overlay")
                // Usa os valores padrão de SettingsActivity se não vierem no intent
                val fontSizePercent = intent.getIntExtra(EXTRA_FONT_SIZE, SettingsActivity.getFontSize(this))
                val transparencyPercent = intent.getIntExtra(EXTRA_TRANSPARENCY, SettingsActivity.getTransparency(this))
                updateOverlayAppearance(fontSizePercent, transparencyPercent)
                if (isOverlayAdded) {
                    resetHideTimer()
                }
                val timeUpdateEnd = System.currentTimeMillis()
                Log.d(TAG, "[TIME] Processamento UPDATE_SETTINGS demorou ${timeUpdateEnd - timeCmdStart}ms")
            }
            else -> {
                Log.w(TAG, "Ação desconhecida ou nula recebida: ${intent?.action}")
            }
        }
        return START_REDELIVER_INTENT
    }

    /** Mostra ou atualiza o overlay com a classificação e dados fornecidos */
    private fun showOverlay(rating: OfferRating, offerData: OfferData?) {
        val funcStartTime = System.currentTimeMillis()
        if (windowManager == null) {
            Log.e(TAG, "WindowManager não está disponível.")
            return
        }
        var viewCreated = false
        if (overlayView == null) {
            overlayView = OverlayView(this)
            viewCreated = true
            Log.d(TAG,"[TIME] OverlayView criada.")

            // Linhas DESCOMENTADAS para ler das configs:
            val initialFontSize = SettingsActivity.getFontSize(this)
            val initialTransparency = SettingsActivity.getTransparency(this)
            Log.d(TAG, "Lendo configs iniciais: Fonte=$initialFontSize%, Transp=$initialTransparency%")

            // Aplicar configurações lidas
            applyAppearanceSettings(initialFontSize, initialTransparency)
        }

        val timeUpdateStateStart = System.currentTimeMillis()
        overlayView?.updateState(rating, offerData)
        val timeUpdateStateEnd = System.currentTimeMillis()
        Log.d(TAG,"[TIME] overlayView.updateState demorou ${timeUpdateStateEnd - timeUpdateStateStart}ms")

        try {
            val timeAddViewStart = System.currentTimeMillis()
            if (!isOverlayAdded) {
                windowManager?.addView(overlayView, overlayLayoutParams)
                isOverlayAdded = true
                Log.i(TAG, "Overlay adicionado à janela.")
            } else {
                Log.d(TAG, "Overlay já existente, estado atualizado.")
            }
            val timeAddViewEnd = System.currentTimeMillis()
            // Log do tempo para adicionar/atualizar a view (se ela já existia)
            if (!viewCreated) {
                Log.d(TAG,"[TIME] windowManager.addView/update demorou ${timeAddViewEnd - timeAddViewStart}ms")
            }

        } catch (e: IllegalStateException) { Log.e(TAG, "Erro de estado ilegal ao adicionar/atualizar overlay: ${e.message}"); forceRemoveOverlay() }
        catch (e: WindowManager.BadTokenException) { Log.e(TAG, "Erro de BadToken ao adicionar/atualizar overlay: ${e.message}") }
        catch (e: Exception) { Log.e(TAG, "Erro desconhecido ao adicionar/atualizar overlay: ${e.message}", e) }
        val funcEndTime = System.currentTimeMillis()
        Log.d(TAG,"[TIME] Função showOverlay completa demorou ${funcEndTime-funcStartTime}ms")
    }

    /** Esconde e remove o overlay da janela */
    private fun hideOverlay() {
        hideHandler.removeCallbacks(hideRunnable)
        if (isOverlayAdded && overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
                isOverlayAdded = false
                Log.i(TAG, "Overlay removido da janela.")
            } catch (e: IllegalArgumentException) { Log.w(TAG, "Erro ao remover overlay (view não encontrada?): ${e.message}"); isOverlayAdded = false }
            catch (e: Exception) { Log.e(TAG, "Erro desconhecido ao remover overlay: ${e.message}", e); isOverlayAdded = false }
        } else { Log.d(TAG, "Tentativa de esconder overlay, mas ele não estava adicionado ou era nulo.") }
    }

    /** Força a remoção da view, útil em casos de erro de estado */
    private fun forceRemoveOverlay() {
        Log.w(TAG,"Forçando remoção do Overlay devido a erro anterior.")
        if (overlayView != null && windowManager != null) {
            try { windowManager?.removeViewImmediate(overlayView) }
            catch (e: Exception) { Log.e(TAG,"Erro ao forçar remoção do overlay: ${e.message}") }
        }
        isOverlayAdded = false
    }


    /** Atualiza a aparência do overlay (fonte, transparência) */
    private fun updateOverlayAppearance(fontSizePercent: Int, transparencyPercent: Int) {
        applyAppearanceSettings(fontSizePercent, transparencyPercent)
    }

    /** Aplica as configurações de aparência na OverlayView */
    private fun applyAppearanceSettings(fontSizePercent: Int, transparencyPercent: Int) {
        overlayView?.let { view ->
            val fontScale = fontSizePercent / 100f
            val alpha = 1.0f - (transparencyPercent / 100f)
            Log.d(TAG, "Aplicando configurações: EscalaFonte=$fontScale (Percent=$fontSizePercent), Alpha=$alpha (TranspPercent=$transparencyPercent)")
            view.updateFontSize(fontScale)
            view.updateAlpha(alpha)
        }
    }


    /** Reinicia o timer para esconder o overlay automaticamente */
    private fun resetHideTimer() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS)
        Log.d(TAG, "Timer de auto-hide reiniciado (${AUTO_HIDE_DELAY_MS}ms)")
    }


    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "Serviço de overlay destruído")
        hideOverlay()
        overlayView = null
        windowManager = null
        hideHandler.removeCallbacksAndMessages(null)
    }
}