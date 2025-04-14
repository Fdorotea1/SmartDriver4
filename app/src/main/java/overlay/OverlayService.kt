package com.example.smartdriver.overlay

// --- IMPORTS ADICIONADOS ---
import android.app.Notification // Necessário para Notification
import android.app.NotificationChannel // Necessário para NotificationChannel
import android.app.NotificationManager // Necessário para NotificationManager
import androidx.core.app.NotificationCompat // Necessário para NotificationCompat
// --- FIM IMPORTS ADICIONADOS ---
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
import com.example.smartdriver.R // <<< Import para R.drawable.ic_stat_name
import com.example.smartdriver.SettingsActivity
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.OfferRating
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
        const val EXTRA_RATING = "rating"; const val EXTRA_OFFER_DATA = "offer_data"
        const val EXTRA_FONT_SIZE = "font_size"; const val EXTRA_TRANSPARENCY = "transparency"
        private const val AUTO_HIDE_DELAY_MS = 8000L

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
        // --- CHAMAR startForeground ---
        startForeground(NOTIFICATION_ID, createNotification("Overlay pronto"))
        Log.d(TAG, "Serviço iniciado em foreground.")
        // -----------------------------
    }

    // --- Métodos de Notificação (semelhantes ao ScreenCaptureService) ---
    private fun createNotification(contentText: String): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartDriver")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_name) // Usa o mesmo ícone
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Baixa prioridade é suficiente
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
                NotificationManager.IMPORTANCE_LOW // Baixa importância para não incomodar
            ).apply {
                description = "Notificação do Serviço de Overlay SmartDriver"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG,"Canal de Notificação do Overlay criado.")
        }
    }
    // --- Fim dos Métodos de Notificação ---


    private fun initializeLayoutParams() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS //or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        overlayLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType, flags, PixelFormat.TRANSLUCENT
        )

        overlayLayoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        val density = resources.displayMetrics.density
        overlayLayoutParams.y = (50 * density).toInt() // 50dp do topo

        Log.d(TAG, "Layout Params inicializados: WRAP_CONTENT, Type=$overlayType, Flags=$flags")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val timeCmdStart = System.currentTimeMillis()
        Log.d(TAG, "[TIME] onStartCommand: ${intent?.action} at $timeCmdStart")
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                val rating = intent.getSerializableExtra(EXTRA_RATING) as? OfferRating ?: OfferRating.UNKNOWN
                val offerData: OfferData? = getOfferDataFromIntent(intent)
                Log.d(TAG, "Solicitado mostrar overlay: $rating");
                updateNotification("Exibindo oferta: $rating") // Atualiza notificação
                showOverlay(rating, offerData);
                resetHideTimer()
                val timeShowEnd = System.currentTimeMillis(); Log.d(TAG, "[TIME] Processamento SHOW demorou ${timeShowEnd - timeCmdStart}ms")
            }
            ACTION_HIDE_OVERLAY -> {
                Log.d(TAG, "Solicitado esconder");
                updateNotification("Overlay pronto") // Volta notificação padrão
                hideOverlay();
                val timeHideEnd = System.currentTimeMillis(); Log.d(TAG, "[TIME] Processamento HIDE demorou ${timeHideEnd - timeCmdStart}ms") }
            ACTION_UPDATE_SETTINGS -> {
                Log.d(TAG, "Solicitado atualizar configs")
                val defaultFontSize = SettingsActivity.getFontSize(this); val defaultTransparency = SettingsActivity.getTransparency(this)
                val fontSizePercent = intent.getIntExtra(EXTRA_FONT_SIZE, defaultFontSize); val transparencyPercent = intent.getIntExtra(EXTRA_TRANSPARENCY, defaultTransparency)
                updateOverlayAppearance(fontSizePercent, transparencyPercent); if (isOverlayAdded) { resetHideTimer() }
                val timeUpdateEnd = System.currentTimeMillis(); Log.d(TAG, "[TIME] Processamento UPDATE demorou ${timeUpdateEnd - timeCmdStart}ms")
            }
            else -> { Log.w(TAG, "Ação desconhecida: ${intent?.action}") }
        }
        return START_REDELIVER_INTENT
    }

    private fun getOfferDataFromIntent(intent: Intent): OfferData? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_OFFER_DATA, OfferData::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_OFFER_DATA)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter OfferData da Intent: ${e.message}")
            null
        }
    }

    private fun showOverlay(rating: OfferRating, offerData: OfferData?) {
        val funcStartTime = System.currentTimeMillis(); if (windowManager == null) { Log.e(TAG, "WindowManager nulo."); return }

        if (overlayView == null) {
            overlayView = OverlayView(this); Log.d(TAG,"[TIME] OverlayView criada.")
            val initialFontSize = SettingsActivity.getFontSize(this); val initialTransparency = SettingsActivity.getTransparency(this)
            Log.d(TAG, "Aplicando configs iniciais: Fonte=$initialFontSize%, Transp=$initialTransparency%")
            applyAppearanceSettings(initialFontSize, initialTransparency)
        }

        val timeUpdateStateStart = System.currentTimeMillis(); overlayView?.updateState(rating, offerData); val timeUpdateStateEnd = System.currentTimeMillis(); Log.d(TAG,"[TIME] updateState demorou ${timeUpdateStateEnd - timeUpdateStateStart}ms")

        try {
            val timeAddViewStart = System.currentTimeMillis()
            if (!isOverlayAdded) {
                windowManager?.addView(overlayView, overlayLayoutParams); isOverlayAdded = true; Log.i(TAG, "Overlay adicionado à janela.")
            } else {
                windowManager?.updateViewLayout(overlayView, overlayLayoutParams); Log.d(TAG, "Overlay existente, layout atualizado.")
            }
            val timeAddViewEnd = System.currentTimeMillis(); Log.d(TAG,"[TIME] addView/updateViewLayout demorou ${timeAddViewEnd - timeAddViewStart}ms")
        } catch (e: Exception) { Log.e(TAG, "Erro add/update overlay view: ${e.message}", e); if (e is WindowManager.BadTokenException || e is IllegalStateException ) { isOverlayAdded = false; Log.e(TAG,"Erro crítico ao adicionar/atualizar view, marcando como não adicionada.") } }

        val funcEndTime = System.currentTimeMillis(); Log.d(TAG,"[TIME] showOverlay completo demorou ${funcEndTime-funcStartTime}ms")
    }

    private fun hideOverlay() {
        hideHandler.removeCallbacks(hideRunnable)
        if (isOverlayAdded && overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
                isOverlayAdded = false
                updateNotification("Overlay pronto") // Atualiza notificação ao esconder
                Log.i(TAG, "Overlay removido da janela.")
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao remover overlay: ${e.message}")
                isOverlayAdded = false
            }
        }
    }

    private fun forceRemoveOverlay() { Log.w(TAG,"Forçando remoção."); isOverlayAdded = false; if (overlayView != null && windowManager != null) { try { windowManager?.removeViewImmediate(overlayView) } catch (e: Exception) { Log.e(TAG,"Erro forceRemove: ${e.message}") } } }

    private fun updateOverlayAppearance(fontSizePercent: Int, transparencyPercent: Int) {
        applyAppearanceSettings(fontSizePercent, transparencyPercent)
        if (isOverlayAdded && overlayView != null && windowManager != null) {
            try {
                windowManager?.updateViewLayout(overlayView, overlayLayoutParams)
                Log.d(TAG,"Layout do overlay atualizado após mudança de aparência.")
            } catch (e: Exception) { Log.e(TAG,"Erro updateViewLayout após mudança de aparência: ${e.message}") }
        }
    }
    private fun applyAppearanceSettings(fontSizePercent: Int, transparencyPercent: Int) {
        overlayView?.let { view ->
            val fontScale = fontSizePercent / 100f; val alpha = 1.0f - (transparencyPercent / 100f)
            Log.d(TAG, "Aplicando configs à view: Scale=$fontScale, Alpha=$alpha")
            view.updateFontSize(fontScale); view.updateAlpha(alpha)
        }
    }
    private fun resetHideTimer() { hideHandler.removeCallbacks(hideRunnable); hideHandler.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS); Log.d(TAG, "Timer auto-hide reiniciado (${AUTO_HIDE_DELAY_MS}ms).") }
    override fun onBind(intent: Intent?): IBinder? { return null }
    override fun onDestroy() {
        super.onDestroy();
        Log.w(TAG, "Serviço overlay destruído")
        isRunning.set(false)
        hideOverlay();
        overlayView = null;
        windowManager = null;
        hideHandler.removeCallbacksAndMessages(null)
        // Remove a notificação quando o serviço é destruído
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
}