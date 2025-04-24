package com.example.smartdriver // <<< VERIFIQUE O PACKAGE

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.example.smartdriver.overlay.OverlayService // Import para interagir com o overlay

class SmartDriverAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SmartDriverAccess"
        @Volatile var isServiceRunning = false

        private const val UBER_DRIVER_PACKAGE = "com.ubercab.driver"
        private val TARGET_PACKAGES = listOf(UBER_DRIVER_PACKAGE)

        // --- AJUSTES NOS INTERVALOS ---
        private const val MIN_CAPTURE_INTERVAL_MS = 1500L // Intervalo mínimo AUMENTADO
        private const val CAPTURE_DELAY_MS = 300L        // Delay ligeiramente REDUZIDO
        private const val WINDOW_CHANGE_CAPTURE_DELAY_MS = 250L // Delay ligeiramente REDUZIDO
        // ----------------------------
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastCaptureRequestTime = 0L
    private var captureRequestPending = false // Flag para evitar agendar múltiplos delays
    private var lastTargetPackageName: String = "unknown"
    private var isTargetAppInForeground = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Log.i(TAG, ">>> Serviço de Acessibilidade CONECTADO <<<")
        Toast.makeText(this, "SmartDriver Ativado", Toast.LENGTH_SHORT).show()

        // Aplicar configurações extras (código inalterado)
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            info.notificationTimeout = 80
            serviceInfo = info
            Log.d(TAG, "Configurações extras do serviço aplicadas.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao aplicar configurações extras: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        val eventType = event.eventType

        // Verifica se o evento é de um app alvo
        if (packageName in TARGET_PACKAGES) {
            lastTargetPackageName = packageName

            // Detecção de entrada no app alvo
            if (!isTargetAppInForeground &&
                (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
                Log.i(TAG, "App alvo ($packageName) em primeiro plano.")
                isTargetAppInForeground = true
                // Limpa estado do OfferManager e overlays ao (re)entrar no app
                OfferManager.getInstance(applicationContext).clearLastOfferState()
                hideOverlayIndicator()
            }

            // Gatilho simplificado: Apenas mudanças de conteúdo/janela disparam
            if (isTargetAppInForeground && (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) ) {
                Log.d(TAG, "Evento relevante (${AccessibilityEvent.eventTypeToString(eventType)}) detectado. Tentando agendar captura...")
                requestScreenCapture(WINDOW_CHANGE_CAPTURE_DELAY_MS) // Usa delay menor para mudanças
            }
            // Pode adicionar logs para outros eventos se precisar depurar
            // else if (isTargetAppInForeground) {
            //     Log.v(TAG, "Evento ignorado (${AccessibilityEvent.eventTypeToString(eventType)}) em $packageName.")
            // }

        } else {
            // Se saiu do app alvo
            if (isTargetAppInForeground) {
                Log.i(TAG, "App alvo ($lastTargetPackageName) saiu de primeiro plano (Evento em: $packageName).")
                isTargetAppInForeground = false
                hideOverlayIndicator() // Esconde overlays
                // Cancela qualquer captura pendente
                handler.removeCallbacksAndMessages(null) // Remove CÓDIGOS pendentes associados a este handler
                captureRequestPending = false
                Log.d(TAG, "Capturas pendentes canceladas ao sair do app.")
            }
        }
    }

    // --- Função requestScreenCapture MODIFICADA com flag 'pending' ---
    private fun requestScreenCapture(delayMs: Long) {
        val currentTime = System.currentTimeMillis()

        // Verifica intervalo mínimo E se já não há um pedido pendente
        if (currentTime - lastCaptureRequestTime >= MIN_CAPTURE_INTERVAL_MS && !captureRequestPending) {
            lastCaptureRequestTime = currentTime // Atualiza tempo do ÚLTIMO pedido bem-sucedido
            captureRequestPending = true // Marca que um pedido está agendado
            Log.d(TAG,"Agendando captura em ${delayMs}ms...")

            handler.postDelayed({
                captureRequestPending = false // Libera para futuros pedidos AGORA que este vai ser enviado

                // Verifica se ainda estamos no app alvo antes de enviar
                if (isTargetAppInForeground) {
                    Log.i(TAG, "[CAPTURE TRIGGER] Delay (${delayMs}ms) concluído. Enviando ACTION_CAPTURE_NOW...")
                    val intent = Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_CAPTURE_NOW
                    }
                    try { startService(intent) }
                    catch (e: Exception) { Log.e(TAG, "Erro ao iniciar ScreenCaptureService: ${e.message}", e) }
                } else {
                    Log.w(TAG, "[CAPTURE TRIGGER] Delay concluído, mas app alvo não está mais em primeiro plano. Captura cancelada.")
                }

            }, delayMs)

        } else {
            // Loga porque ignorou
            if(captureRequestPending) {
                Log.d(TAG, "Solicitação de captura ignorada: pedido já agendado.")
            } else {
                Log.d(TAG, "Solicitação de captura ignorada (throttling): ${currentTime - lastCaptureRequestTime}ms < ${MIN_CAPTURE_INTERVAL_MS}ms")
            }
        }
    }
    // ---------------------------------------------------------------

    // Esconde overlays do OverlayService
    private fun hideOverlayIndicator() {
        if (OverlayService.isRunning.get()) {
            // Log.d(TAG, "Enviando HIDE_OVERLAY para OverlayService.") // Log opcional
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_HIDE_OVERLAY
            }
            try { startService(intent) }
            catch (e: Exception) { Log.e(TAG, "Erro ao enviar HIDE_OVERLAY: ${e.message}") }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Serviço de Acessibilidade INTERROMPIDO")
        isServiceRunning = false
        handler.removeCallbacksAndMessages(null) // Cancela posts pendentes
        captureRequestPending = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.w(TAG, ">>> Serviço de Acessibilidade DESTRUÍDO <<<")
        handler.removeCallbacksAndMessages(null) // Cancela posts pendentes
        captureRequestPending = false
        hideOverlayIndicator() // Tenta esconder overlays ao destruir
    }
}