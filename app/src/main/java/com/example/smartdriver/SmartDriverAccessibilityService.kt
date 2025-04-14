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
        // Adicione outros pacotes aqui se quiser monitorar outros apps, ex: listOf(UBER_DRIVER_PACKAGE, "com.bolt.driverapp")
        private val TARGET_PACKAGES = listOf(UBER_DRIVER_PACKAGE)

        // Delays AJUSTADOS
        private const val MIN_CAPTURE_INTERVAL_MS = 500L // Mantido - Intervalo mínimo entre pedidos
        private const val CAPTURE_DELAY_MS = 300L        // Aumentado - Delay para eventos de interação
        private const val WINDOW_CHANGE_CAPTURE_DELAY_MS = 250L // Aumentado - Delay para mudanças de janela/conteúdo
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastCaptureRequestTime = 0L
    private var isTargetAppInForeground = false // Flag para saber se o app alvo está ativo
    // <<< CORREÇÃO: Declaração da variável que faltava >>>
    private var lastTargetPackageName: String = "unknown" // Guarda o último pacote visto (útil para logs)


    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Log.i(TAG, ">>> Serviço de Acessibilidade CONECTADO e rodando <<<")
        Toast.makeText(this, "SmartDriver Ativado", Toast.LENGTH_SHORT).show()

        // Tenta aplicar configurações adicionais para melhorar a detecção
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK // Ouve todos os tipos de evento
            info.notificationTimeout = 80 // Tempo curto

            serviceInfo = info
            Log.d(TAG, "Configurações extras do serviço de acessibilidade aplicadas.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao tentar aplicar configurações extras ao serviço: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        val eventType = event.eventType // Tipo do evento
        val eventTime = event.eventTime // Tempo em que o evento ocorreu

        // Verifica se o evento veio de um dos aplicativos alvo
        if (packageName in TARGET_PACKAGES) {
            lastTargetPackageName = packageName // Atualiza o último pacote alvo visto
            // Se o app alvo acabou de entrar em primeiro plano, marca o flag e loga
            if (!isTargetAppInForeground) {
                if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                    Log.i(TAG, "App alvo ($packageName) entrou em primeiro plano. Iniciando monitoramento ativo.")
                    isTargetAppInForeground = true
                    // Pode ser útil limpar o estado da última oferta aqui:
                    // OfferManager.getInstance(applicationContext).clearLastOfferState()
                }
            }

            // Log verboso para depuração
            Log.v(TAG, "Evento [${System.currentTimeMillis()}]: Pacote=$packageName, Tipo=${AccessibilityEvent.eventTypeToString(eventType)}, Classe=${event.className}")

            // Decide se solicita captura baseado no tipo de evento
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                Log.d(TAG, "Evento de mudança de janela/conteúdo detectado em $packageName. Solicitando captura...")
                requestScreenCapture(WINDOW_CHANGE_CAPTURE_DELAY_MS, eventTime, eventType) // Passa o tipo de evento original
            }
            else if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                Log.d(TAG, "Evento de interação/notificação (${AccessibilityEvent.eventTypeToString(eventType)}) detectado em $packageName. Solicitando captura...")
                requestScreenCapture(CAPTURE_DELAY_MS, eventTime, eventType) // Passa o tipo de evento original
            }

        } else {
            // Se um evento ocorreu fora do app alvo E o app alvo estava em primeiro plano antes
            if (isTargetAppInForeground) {
                Log.i(TAG, "App alvo ($lastTargetPackageName) saiu de primeiro plano (Evento agora em: $packageName). Parando monitoramento ativo.")
                isTargetAppInForeground = false
                hideOverlayIndicator() // Esconde o semáforo ao sair do app alvo
            }
            // Não precisamos atualizar lastTargetPackageName aqui, pois só nos importa o último *alvo*
        }
    }

    /**
     * Solicita uma captura de tela ao ScreenCaptureService, respeitando o intervalo mínimo.
     * @param delayMs O delay (em ms) antes de efetivamente enviar o comando de captura.
     * @param originalEventTime O timestamp (em ms) do evento de acessibilidade original.
     * @param originalEventType O tipo do evento original (para logging).
     */
    // <<< CORREÇÃO: Adicionado originalEventType como parâmetro >>>
    private fun requestScreenCapture(delayMs: Long, originalEventTime: Long, originalEventType: Int) {
        val requestTime = System.currentTimeMillis() // Tempo atual do pedido
        if (requestTime - lastCaptureRequestTime > MIN_CAPTURE_INTERVAL_MS) {
            lastCaptureRequestTime = requestTime // Atualiza o timestamp

            handler.postDelayed({
                val postTime = System.currentTimeMillis() // Tempo exato da execução
                val totalDelay = postTime - originalEventTime // Delay total
                // <<< CORREÇÃO: Log corrigido para usar originalEventType passado como parâmetro >>>
                Log.i(TAG, "[TIME] Captura solicitada: ${postTime - requestTime}ms postDelay (Total ${totalDelay}ms desde evento ${AccessibilityEvent.eventTypeToString(originalEventType)}). Enviando CAPTURE_NOW...")

                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_CAPTURE_NOW
                    // Opcional: pode passar info extra se necessário no futuro
                    // putExtra("originalEventType", originalEventType)
                }
                try {
                    startService(intent)
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Erro ao iniciar ScreenCaptureService (App em background restrito?): ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Erro desconhecido ao iniciar ScreenCaptureService: ${e.message}", e)
                }

            }, delayMs)

        } else {
            Log.d(TAG, "Solicitação de captura ignorada (throttling ativo). Tempo desde última: ${requestTime - lastCaptureRequestTime}ms < ${MIN_CAPTURE_INTERVAL_MS}ms")
        }
    }


    /** Envia comando para esconder o overlay */
    private fun hideOverlayIndicator() {
        if (OverlayService.isRunning.get()) {
            Log.d(TAG, "App alvo saiu do foco. Enviando comando HIDE_OVERLAY para OverlayService.")
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_HIDE_OVERLAY
            }
            try {
                startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao enviar HIDE_OVERLAY para OverlayService: ${e.message}")
            }
        } else {
            Log.d(TAG, "App alvo saiu do foco, mas OverlayService não está rodando.")
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
        handler.removeCallbacksAndMessages(null)
        hideOverlayIndicator()
    }
}