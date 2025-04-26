package com.example.smartdriver // <<< VERIFIQUE O PACKAGE

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread // <<< NOVO IMPORT
import android.os.Looper
import android.os.Process // <<< NOVO IMPORT
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.example.smartdriver.overlay.OverlayService // Para interagir com o OverlayService

class SmartDriverAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SmartDriverAccess"
        private const val TREE_TAG = "SmartDriverAccessTree"
        private const val EVENT_LOGGER_TAG = "AccessibilityEventLogger"

        @Volatile var isServiceRunning = false

        private const val UBER_DRIVER_PACKAGE = "com.ubercab.driver"
        // Throttling para captura
        private const val MIN_CAPTURE_INTERVAL_MS = 1500L
        private const val CAPTURE_DELAY_MS = 200L
        // Throttling para DUMPS COMPLETOS (evitar spam)
        private const val MIN_DUMP_INTERVAL_MS = 500L // Intervalo razoável entre dumps
    }

    // --- Handler principal (para UI e agendamento) ---
    private val mainHandler = Handler(Looper.getMainLooper())
    // --- HandlerThread e Handler para tarefas de acessibilidade pesadas (dump) ---
    private lateinit var accessibilityThread: HandlerThread
    private lateinit var accessibilityHandler: Handler

    private var lastCaptureRequestTime = 0L
    private var captureRequestPending = false
    private var isTargetAppInForeground = false // Janela PRINCIPAL do Uber ativa
    private var lastFullDumpTime = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate - Iniciando HandlerThread de Acessibilidade")
        // Cria e inicia a thread de background para tarefas pesadas
        accessibilityThread = HandlerThread("AccessibilityWorkerThread", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        // Cria um Handler associado ao Looper dessa thread
        accessibilityHandler = Handler(accessibilityThread.looper)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Log.i(TAG, ">>> Serviço de Acessibilidade CONECTADO <<<")
        Toast.makeText(this, "SmartDriver Ativado", Toast.LENGTH_SHORT).show()

        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            // Focar em eventos de mudança de janela/estado e conteúdo DO UBER
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            // Ouvir apenas Uber PODE ser suficiente se a janela overlay pertencer a ele
            info.packageNames = arrayOf(UBER_DRIVER_PACKAGE)
            // Se ainda houver problemas, voltar a 'null' para ouvir tudo.
            info.notificationTimeout = 100
            serviceInfo = info
            Log.d(TAG, "Configurações extras aplicadas. Flags: ${info.flags}, Pkgs: ${info.packageNames?.joinToString()}, EventTypes: STATE/WINDOWS/CONTENT")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao aplicar configurações extras: ${e.message}")
        }
        // Verifica estado inicial após um pequeno delay
        mainHandler.postDelayed({ checkIfTargetAppIsForeground() }, 500)
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isServiceRunning) return // Ignora se serviço não está ativo
        val packageName = event.packageName?.toString() ?: "UnknownPackage"
        val eventType = event.eventType

        // Log básico do evento (apenas para pacotes Uber agora)
        // Log.v(EVENT_LOGGER_TAG, "[${AccessibilityEvent.eventTypeToString(eventType)}] Pkg=$packageName, Class=${event.className ?: "N/A"}, WinID=${event.windowId}")

        // Eventos que disparam dump (mudança de janela/estado)
        val isWindowEvent = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

        // Evento de conteúdo (apenas para captura)
        val isUberContentEvent = eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        val currentTime = System.currentTimeMillis()

        if (isWindowEvent) {
            Log.w(TAG, ">>> Evento ${AccessibilityEvent.eventTypeToString(eventType)} detectado! Agendando DUMP COMPLETO (Throttled) na background thread. <<<")
            // Tenta fazer dump completo, respeitando o throttling, NA BACKGROUND THREAD
            if (currentTime - lastFullDumpTime > MIN_DUMP_INTERVAL_MS) {
                lastFullDumpTime = currentTime
                // Posta a tarefa de dump para a thread de acessibilidade
                accessibilityHandler.post { executeTreeDumpAllAttempt() }
            } else {
                Log.v(TREE_TAG, "Dump completo ignorado para ${AccessibilityEvent.eventTypeToString(eventType)} (Throttling)")
            }
            // Reavalia o estado do foreground após mudança de janela
            mainHandler.post { checkIfTargetAppIsForeground() }
            // Agenda captura se necessário (após verificar foreground)
            mainHandler.post { if (isTargetAppInForeground) requestScreenCapture(CAPTURE_DELAY_MS) }

        } else if (isUberContentEvent) {
            // Evento de conteúdo apenas dispara captura (sem dump)
            if (isTargetAppInForeground) {
                // Log.v(TAG, "Content change, requesting capture...")
                requestScreenCapture(CAPTURE_DELAY_MS)
            }
        }
        // Não precisamos mais verificar saída aqui se ouvimos apenas Uber,
        // a lógica em checkIfTargetAppIsForeground trata disso.
    }

    /** Verifica se a janela principal do Uber está ativa e atualiza estado */
    private fun checkIfTargetAppIsForeground() {
        if (!isServiceRunning) return // Não faz nada se o serviço foi destruído

        val activeUberAppWindow = findActiveUberAppWindow()
        val uberIsNowForeground = activeUberAppWindow != null

        // Loga apenas na transição de estado
        if (uberIsNowForeground && !isTargetAppInForeground) {
            Log.i(TAG, ">>> App Uber (Janela Principal) ENTROU em primeiro plano <<<")
            isTargetAppInForeground = true
            // Limpa estado/overlay SÓ se o estado anterior era 'fora'
            OfferManager.getInstance(applicationContext).clearLastOfferState()
            // Não esconder overlay aqui automaticamente, deixa o OfferManager controlar
            // hideOverlayIndicator()
        } else if (!uberIsNowForeground && isTargetAppInForeground) {
            Log.i(TAG, "<<< App Uber (Janela Principal) SAIU de primeiro plano >>>")
            isTargetAppInForeground = false
            handleAppExitActions()
        } else {
            // Apenas atualiza o estado se não houve transição
            isTargetAppInForeground = uberIsNowForeground
        }
    }

    /** Ações de limpeza ao sair do app */
    private fun handleAppExitActions() {
        hideOverlayIndicator() // Esconde overlay ao sair do app
        // Limpa posts pendentes nos handlers
        mainHandler.removeCallbacksAndMessages(null)
        if (::accessibilityHandler.isInitialized) {
            accessibilityHandler.removeCallbacksAndMessages(null)
        }
        captureRequestPending = false
        Log.d(TAG, "Ações pendentes (captura, dump) canceladas ao sair do app.")
    }

    /** Encontra a primeira janela ATIVA do Uber que seja do tipo APP */
    private fun findActiveUberAppWindow(): AccessibilityWindowInfo? {
        // Lógica interna inalterada...
        val currentWindows: List<AccessibilityWindowInfo>? = try { this.windows } catch (e: Exception) { null }
        return currentWindows?.find { window ->
            var root: AccessibilityNodeInfo? = null
            var isUberAppActive = false
            try {
                if(window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive) {
                    root = window.root
                    isUberAppActive = root?.packageName == UBER_DRIVER_PACKAGE
                }
            } catch (e: Exception) {}
            finally { try { root?.recycle() } catch (e: Exception) {} }
            isUberAppActive
        }
    }

    /** Executa UMA tentativa de dump da árvore iterando sobre TODAS as janelas (NA BACKGROUND THREAD) */
    private fun executeTreeDumpAllAttempt() {
        // Verifica se o serviço ainda está ativo antes de começar
        if (!isServiceRunning) {
            Log.w(TREE_TAG, "executeTreeDumpAllAttempt: Serviço não está mais ativo, abortando dump.")
            return
        }
        Log.d(TREE_TAG, "--- [BG Thread] Iniciando Tentativa de Dump de TODAS as Janelas ---")
        val currentWindows: List<AccessibilityWindowInfo>? = try { this.windows } catch (e: Exception) { Log.e(TAG, "[BG Thread] Erro ao obter janelas: ${e.message}"); null }

        if (currentWindows == null || currentWindows.isEmpty()) {
            Log.w(TREE_TAG, "[BG Thread] Nenhuma janela encontrada para fazer o dump.")
            return
        }

        Log.i(TREE_TAG, "[BG Thread] Encontradas ${currentWindows.size} janelas para analisar:")
        val windowBounds = Rect()

        for ((index, window) in currentWindows.withIndex()) {
            if (!isServiceRunning) break // Aborta loop se serviço parou

            var rootNode: AccessibilityNodeInfo? = null
            val windowId = window.id
            val windowType = windowTypeToString(window.type)
            val isActive = window.isActive
            val isFocused = window.isFocused
            var packageName = "N/A"

            try {
                window.getBoundsInScreen(windowBounds)
                rootNode = window.root
                packageName = rootNode?.packageName?.toString() ?: "N/A"

                Log.i(TREE_TAG, "[BG Thread] Analisando Janela ${index + 1}/${currentWindows.size}: ID=$windowId, Pkg=$packageName, Tipo=$windowType, Ativa=$isActive, Focada=$isFocused, Layer=${window.layer}, Bounds=${windowBounds.toShortString()}")

                if (rootNode != null) {
                    // Só faz o dump detalhado se for do Uber ou um Overlay de Acessibilidade (pode ser o popup)
                    if (packageName == UBER_DRIVER_PACKAGE || windowType == "A11Y_OVERLAY") {
                        Log.d(TREE_TAG, "+++ [BG Thread] Dumping Tree for Relevant Window (ID: $windowId, Pkg: $packageName) +++")
                        logNodeInfoRecursive(rootNode, 0) // Loga a árvore
                        Log.d(TREE_TAG, "--- [BG Thread] End Tree Dump for Relevant Window (ID: $windowId) ---")

                        // <<< Poderíamos adicionar a busca por keywords aqui, se necessário no futuro >>>
                        // if (packageName == UBER_DRIVER_PACKAGE && containsOfferKeywords(rootNode)) { ... }

                    } else {
                        Log.v(TREE_TAG, "[BG Thread] Ignorando dump detalhado para janela não relevante: $packageName")
                    }
                } else {
                    Log.w(TREE_TAG, "[BG Thread] Janela (ID: $windowId, Pkg: $packageName) tem root nulo.")
                }
            } catch (e: Exception) {
                Log.e(TREE_TAG, "[BG Thread] Erro ao obter/processar root da janela ID $windowId (Pkg: $packageName): ${e.message}")
            } finally {
                // Recicla o nó raiz APÓS o uso
                try { rootNode?.recycle() } catch (e: Exception) { /* ignore */ }
            }
            // Não reciclar 'window' aqui
        } // Fim do loop for windows

        Log.d(TREE_TAG, "--- [BG Thread] Fim da Tentativa de Dump de TODAS as Janelas ---")
    }


    // --- Função requestScreenCapture (Agendada no mainHandler, executa na sua própria thread) ---
    private fun requestScreenCapture(delayMs: Long) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCaptureRequestTime >= MIN_CAPTURE_INTERVAL_MS && !captureRequestPending) {
            lastCaptureRequestTime = currentTime
            captureRequestPending = true
            // Posta no mainHandler para agendar, a execução do startService é rápida
            mainHandler.postDelayed({
                captureRequestPending = false
                if (isTargetAppInForeground && isServiceRunning) {
                    Log.i(TAG, "[CAPTURE TRIGGER] Enviando ACTION_CAPTURE_NOW...")
                    val intent = Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_CAPTURE_NOW
                    }
                    try { startService(intent) }
                    catch (e: Exception) { Log.e(TAG, "Erro ao iniciar ScreenCaptureService: ${e.message}", e) }
                }
            }, delayMs)
        }
    }
    // ---------------------------------------------------------------

    // --- hideOverlayIndicator (Executa no mainHandler) ---
    private fun hideOverlayIndicator() {
        mainHandler.post { // Garante execução na thread principal
            if (OverlayService.isRunning.get()) {
                val intent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_HIDE_OVERLAY
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao enviar HIDE_OVERLAY: ${e.message}")
                }
            }
        }
    }

    // --- onInterrupt, onDestroy ---
    override fun onInterrupt() {
        Log.w(TAG, "Serviço de Acessibilidade INTERROMPIDO")
        isServiceRunning = false
        // Limpa handlers
        mainHandler.removeCallbacksAndMessages(null)
        if (::accessibilityThread.isInitialized && accessibilityThread.isAlive) {
            accessibilityHandler.removeCallbacksAndMessages(null)
        }
        captureRequestPending = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.w(TAG, ">>> Serviço de Acessibilidade DESTRUÍDO <<<")
        // Para e limpa handlers e a thread
        mainHandler.removeCallbacksAndMessages(null)
        if (::accessibilityThread.isInitialized && accessibilityThread.isAlive) {
            accessibilityHandler.removeCallbacksAndMessages(null)
            // Finaliza a thread de background de forma segura
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                accessibilityThread.quitSafely()
            } else {
                @Suppress("DEPRECATION")
                accessibilityThread.quit()
            }
            Log.d(TAG, "AccessibilityWorkerThread finalizada.")
        }
        captureRequestPending = false
        try { hideOverlayIndicator() } catch (e: Exception) { /* Ignorar */ }
    }

    // --- FUNÇÕES PARA LOG DA ÁRVORE DE NÓS (Executa na accessibilityHandler thread) ---
    private fun logNodeInfoRecursive(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || !isServiceRunning) return // Verifica se serviço ainda corre
        if (depth > 25) return

        val indent = "  ".repeat(depth)
        try {
            val className = node.className?.toString()?.substringAfterLast('.') ?: "N/A"
            val viewId = node.viewIdResourceName ?: "no_id"
            val nodeText = try { node.text?.toString()?.replace("\n", "\\n")?.take(120) ?: "null" } catch (e: Exception) { "[Error text]" }
            val contentDesc = try { node.contentDescription?.toString()?.replace("\n", "\\n")?.take(120) ?: "null" } catch (e: Exception) { "[Error desc]" }
            val bounds = Rect()
            var boundsStr = "[N/A]"
            try { node.getBoundsInScreen(bounds); boundsStr = bounds.toShortString() } catch (e: Exception) {}
            val isClickable = node.isClickable
            val isVisible = node.isVisibleToUser

            Log.d(TREE_TAG, "$indent- Class: $className, ID: $viewId, T:\"$nodeText\", D:\"$contentDesc\", B:$boundsStr, C:$isClickable, V:$isVisible")

            // Recursão para filhos
            for (i in 0 until node.childCount) {
                if (!isServiceRunning) break // Sai do loop se serviço parou
                var childNode: AccessibilityNodeInfo? = null
                try {
                    childNode = node.getChild(i)
                    logNodeInfoRecursive(childNode, depth + 1)
                } catch (e: Exception) {
                    // Log.e(TREE_TAG, "$indent  Error processing child $i: ${e.message}")
                } finally {
                    try { childNode?.recycle() } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TREE_TAG, "$indent Error processing node: ${e.message}")
        }
        // Nó `node` será reciclado por quem chamou
    }

    /** Converte tipo de janela para String legível */
    private fun windowTypeToString(type: Int): String {
        return when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "SYS"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVERLAY"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT"
            else -> "UNKNOWN($type)"
        }
    }

} // Fim da classe SmartDriverAccessibilityService