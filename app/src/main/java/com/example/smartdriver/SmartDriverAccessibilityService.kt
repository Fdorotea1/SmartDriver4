package com.example.smartdriver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.example.smartdriver.overlay.OverlayService // Import para interagir com o overlay

/**
 * Serviço de Acessibilidade que monitora eventos nos aplicativos de TVDE (Uber, etc.)
 * para detectar potenciais ofertas de viagem e solicitar capturas de tela.
 */
class SmartDriverAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SmartDriverAccess" // Tag de Log atualizada
        @Volatile var isServiceRunning = false

        private const val UBER_DRIVER_PACKAGE = "com.ubercab.driver"
        private val TARGET_PACKAGES = listOf(UBER_DRIVER_PACKAGE)

        private const val MIN_CAPTURE_INTERVAL_MS = 500L
        private const val CAPTURE_DELAY_MS = 150L
        private const val WINDOW_CHANGE_CAPTURE_DELAY_MS = 100L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastCaptureRequestTime = 0L

    private var isTargetAppInForeground = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Log.i(TAG, ">>> Serviço de Acessibilidade CONECTADO e rodando <<<")
        Toast.makeText(this, "SmartDriver Ativado", Toast.LENGTH_SHORT).show()

        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            info.notificationTimeout = 80

            serviceInfo = info
            Log.d(TAG, "Configurações extras do serviço aplicadas.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao tentar aplicar configurações extras ao serviço: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        val eventType = event.eventType
        val eventTime = event.eventTime // Tempo do evento original

        if (packageName in TARGET_PACKAGES) {
            if (!isTargetAppInForeground) {
                if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                    Log.i(TAG, "App alvo ($packageName) entrou em primeiro plano. Iniciando monitoramento ativo.")
                    isTargetAppInForeground = true
                }
            }

            Log.v(TAG, "Evento [${System.currentTimeMillis()}]: Pacote=$packageName, Tipo=${AccessibilityEvent.eventTypeToString(eventType)}, Classe=${event.className}")

            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                Log.d(TAG, "Evento de mudança de janela/conteúdo detectado. Solicitando captura...")
                requestScreenCapture(WINDOW_CHANGE_CAPTURE_DELAY_MS, eventTime) // Passa tempo do evento
            }
            else if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                Log.d(TAG, "Evento de interação (${AccessibilityEvent.eventTypeToString(eventType)}) detectado. Solicitando captura...")
                requestScreenCapture(CAPTURE_DELAY_MS, eventTime) // Passa tempo do evento
            }

        } else {
            if (isTargetAppInForeground) {
                Log.i(TAG, "App alvo saiu de primeiro plano. Parando monitoramento ativo.")
                isTargetAppInForeground = false
                hideOverlayIndicator()
            }
        }
    }

    /**
     * Solicita uma captura de tela ao ScreenCaptureService, respeitando o intervalo mínimo.
     * @param delayMs O delay antes de efetivamente enviar o comando de captura.
     * @param originalEventTime O timestamp do evento de acessibilidade original.
     */
    private fun requestScreenCapture(delayMs: Long, originalEventTime: Long) {
        val requestTime = System.currentTimeMillis()
        if (requestTime - lastCaptureRequestTime > MIN_CAPTURE_INTERVAL_MS) {
            lastCaptureRequestTime = requestTime

            handler.postDelayed({
                val postTime = System.currentTimeMillis()
                val totalDelay = postTime - originalEventTime // Delay total desde o evento original
                Log.i(TAG, "[TIME] ${postTime - requestTime}ms postDelay (Total ${totalDelay}ms desde evento). Enviando CAPTURE_NOW...") // Loga o delay real
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_CAPTURE_NOW
                }
                try {
                    startService(intent)
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Erro ao iniciar ScreenCaptureService (app em background?): ${e.message}")
                    Toast.makeText(this,"Erro ao iniciar captura (SmartDriver em background?)", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Erro desconhecido ao iniciar ScreenCaptureService: ${e.message}")
                }

            }, delayMs)

        } else {
            Log.d(TAG, "Solicitação de captura ignorada (throttling). Tempo desde última: ${requestTime - lastCaptureRequestTime}ms")
        }
    }

    /** Envia comando para esconder o overlay */
    private fun hideOverlayIndicator() {
        Log.d(TAG, "Enviando comando HIDE_OVERLAY para OverlayService.")
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_HIDE_OVERLAY
        }
        try { // Adicionar try-catch aqui também
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar HIDE_OVERLAY: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Serviço de Acessibilidade INTERROMPIDO")
        isServiceRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.w(TAG, ">>> Serviço de Acessibilidade DESTRUÍDO <<<")
        Toast.makeText(this, "SmartDriver Desativado", Toast.LENGTH_SHORT).show()
        handler.removeCallbacksAndMessages(null)
        hideOverlayIndicator()
    }
}