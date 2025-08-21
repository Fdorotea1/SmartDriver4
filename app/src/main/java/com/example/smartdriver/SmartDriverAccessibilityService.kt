package com.example.smartdriver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.example.smartdriver.overlay.OverlayService
import java.text.Normalizer
import java.util.Locale

class SmartDriverAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SmartDriverAccess"
        private const val TREE_TAG = "SmartDriverAccessTree"

        @Volatile var isServiceRunning = false

        private const val UBER_DRIVER_PACKAGE = "com.ubercab.driver"

        // Throttling
        private const val MIN_DUMP_INTERVAL_MS = 500L
        private const val MIN_CAPTURE_INTERVAL_MS = 1500L
        private const val CAPTURE_DELAY_MS = 200L
        private const val MIN_KEYWORD_SCAN_INTERVAL_MS = 400L

        // -------- Keyword ÚNICA (com normalização) --------
        private val FINISH_KEYWORDS_RAW = arrayOf("Concluir")
        private val FINISH_KEYWORDS_NORM: List<String> =
            FINISH_KEYWORDS_RAW.map { normalize(it) }

        private fun normalize(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            val n = Normalizer.normalize(s, Normalizer.Form.NFD)
            return n.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
                .lowercase(Locale.ROOT)
                .trim()
        }
    }

    // Handlers
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var accessibilityThread: HandlerThread
    private lateinit var accessibilityHandler: Handler

    // Estado
    private var lastFullDumpTime = 0L
    private var lastCaptureRequestTime = 0L
    private var captureRequestPending = false
    private var isTargetAppInForeground = false

    // Estado da keyword "Concluir"
    private var lastKeywordScanTime = 0L
    @Volatile private var lastFinishVisible: Boolean = false
    @Volatile private var keywordScanPending = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate - Accessibility worker thread")
        accessibilityThread = HandlerThread(
            "AccessibilityWorkerThread",
            Process.THREAD_PRIORITY_BACKGROUND
        ).apply { start() }
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
            info.eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            info.packageNames = arrayOf(UBER_DRIVER_PACKAGE)
            info.notificationTimeout = 100
            serviceInfo = info
            Log.d(TAG, "ServiceInfo configurado (pkgs=${info.packageNames?.joinToString()})")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao aplicar ServiceInfo: ${e.message}")
        }

        mainHandler.postDelayed({ checkIfTargetAppIsForeground() }, 500)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isServiceRunning) return

        val eventType = event.eventType
        val isWindowEvent =
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        val isContentEvent = eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        val now = System.currentTimeMillis()

        if (isWindowEvent) {
            // Dump (para debug) com throttling – corre na background thread
            if (now - lastFullDumpTime > MIN_DUMP_INTERVAL_MS) {
                lastFullDumpTime = now
                accessibilityHandler.post { executeTreeDumpAllAttempt() }
            }
            // Reavaliar foreground + pedir captura
            mainHandler.post {
                checkIfTargetAppIsForeground()
                if (isTargetAppInForeground) requestScreenCapture(CAPTURE_DELAY_MS)
            }
            // Agendar varredura de keyword
            scheduleKeywordScan()
        } else if (isContentEvent) {
            if (isTargetAppInForeground) {
                requestScreenCapture(CAPTURE_DELAY_MS)
                scheduleKeywordScan()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Serviço de Acessibilidade INTERROMPIDO")
        isServiceRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        if (::accessibilityThread.isInitialized && accessibilityThread.isAlive) {
            accessibilityHandler.removeCallbacksAndMessages(null)
        }
        captureRequestPending = false
        // Ao interromper, considera a keyword como não visível
        if (lastFinishVisible) {
            lastFinishVisible = false
            broadcastFinishVisible(false, "interrupt")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.w(TAG, ">>> Serviço de Acessibilidade DESTRUÍDO <<<")
        mainHandler.removeCallbacksAndMessages(null)
        if (::accessibilityThread.isInitialized && accessibilityThread.isAlive) {
            accessibilityHandler.removeCallbacksAndMessages(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                accessibilityThread.quitSafely()
            } else {
                @Suppress("DEPRECATION")
                accessibilityThread.quit()
            }
            Log.d(TAG, "AccessibilityWorkerThread finalizada.")
        }
        captureRequestPending = false
        if (lastFinishVisible) {
            lastFinishVisible = false
            broadcastFinishVisible(false, "destroy")
        }
    }

    // ---------- Foreground / Janela alvo ----------
    private fun checkIfTargetAppIsForeground() {
        if (!isServiceRunning) return
        val activeUberAppWindow = findActiveUberAppWindow()
        val nowForeground = activeUberAppWindow != null

        if (nowForeground && !isTargetAppInForeground) {
            Log.i(TAG, ">>> Uber em primeiro plano <<<")
            isTargetAppInForeground = true
            // reset de estado visual (o Overlay gere o resto)
        } else if (!nowForeground && isTargetAppInForeground) {
            Log.i(TAG, "<<< Uber saiu de primeiro plano >>>")
            isTargetAppInForeground = false
            // Se sair do app, marca keyword como não visível
            if (lastFinishVisible) {
                lastFinishVisible = false
                broadcastFinishVisible(false, "bg-exit")
            }
        } else {
            isTargetAppInForeground = nowForeground
        }
    }

    private fun findActiveUberAppWindow(): AccessibilityWindowInfo? {
        val currentWindows: List<AccessibilityWindowInfo>? =
            try { this.windows } catch (e: Exception) { null }

        return currentWindows?.find { window ->
            var root: AccessibilityNodeInfo? = null
            var ok = false
            try {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive) {
                    root = window.root
                    ok = (root?.packageName == UBER_DRIVER_PACKAGE)
                }
            } catch (_: Exception) {
            } finally {
                try { root?.recycle() } catch (_: Exception) {}
            }
            ok
        }
    }

    // ---------- Captura (apenas trigger; não mexe em permissões) ----------
    private fun requestScreenCapture(delayMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureRequestTime >= MIN_CAPTURE_INTERVAL_MS && !captureRequestPending) {
            lastCaptureRequestTime = now
            captureRequestPending = true
            mainHandler.postDelayed({
                captureRequestPending = false
                if (isTargetAppInForeground && isServiceRunning) {
                    Log.i(TAG, "[CAPTURE TRIGGER] ACTION_CAPTURE_NOW")
                    val intent = Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_CAPTURE_NOW
                    }
                    try { startService(intent) } catch (e: Exception) {
                        Log.e(TAG, "Erro a iniciar ScreenCaptureService: ${e.message}", e)
                    }
                }
            }, delayMs)
        }
    }

    // ---------- Varredura por keyword "Concluir" ----------
    private fun scheduleKeywordScan() {
        val now = System.currentTimeMillis()
        if (keywordScanPending || now - lastKeywordScanTime < MIN_KEYWORD_SCAN_INTERVAL_MS) return
        keywordScanPending = true
        lastKeywordScanTime = now
        accessibilityHandler.post {
            try {
                val visible = scanWindowsForFinishKeyword()
                if (visible != lastFinishVisible) {
                    lastFinishVisible = visible
                    broadcastFinishVisible(visible, "scan")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro no scan de keyword: ${e.message}")
            } finally {
                keywordScanPending = false
            }
        }
    }

    private fun scanWindowsForFinishKeyword(): Boolean {
        val windows = try { this.windows } catch (e: Exception) { null } ?: return false
        val bounds = Rect()

        for (window in windows) {
            val typeOk = window.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            if (!typeOk) continue

            var root: AccessibilityNodeInfo? = null
            try {
                root = window.root ?: continue
                // opcional: ignorar janelas de outros pacotes
                val pkg = root.packageName?.toString() ?: ""
                if (pkg.isNotEmpty() && pkg != UBER_DRIVER_PACKAGE &&
                    window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                    continue
                }

                if (nodeTreeContainsFinishKeyword(root, bounds)) {
                    return true
                }
            } catch (_: Exception) {
            } finally {
                try { root?.recycle() } catch (_: Exception) {}
            }
        }
        return false
    }

    private fun nodeTreeContainsFinishKeyword(
        node: AccessibilityNodeInfo?,
        tmpBounds: Rect
    ): Boolean {
        if (node == null) return false

        // Verificação neste nó
        try {
            val visible = node.isVisibleToUser
            if (visible) {
                val txt = normalize(
                    try { node.text?.toString() } catch (_: Exception) { null }
                )
                val desc = normalize(
                    try { node.contentDescription?.toString() } catch (_: Exception) { null }
                )
                val hit = FINISH_KEYWORDS_NORM.any { k ->
                    (txt.contains(k)) || (desc.contains(k))
                }
                if (hit) return true
            }
        } catch (_: Exception) { }

        // Recursão nos filhos
        for (i in 0 until node.childCount) {
            var child: AccessibilityNodeInfo? = null
            try {
                child = node.getChild(i)
                if (nodeTreeContainsFinishKeyword(child, tmpBounds)) return true
            } catch (_: Exception) {
            } finally {
                try { child?.recycle() } catch (_: Exception) {}
            }
        }
        return false
    }

    private fun broadcastFinishVisible(visible: Boolean, reason: String) {
        val i = Intent(OverlayService.ACTION_EVT_FINISH_KEYWORD_VISIBLE)
            .putExtra(OverlayService.EXTRA_FINISH_VISIBLE, visible)
        try {
            sendBroadcast(i)
            Log.d(TAG, "FinishKeyword visible=$visible ($reason)")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar broadcast finishVisible=$visible: ${e.message}")
        }
    }

    // ---------- Dump (debug, opcional) ----------
    private fun executeTreeDumpAllAttempt() {
        if (!isServiceRunning) return
        val currentWindows: List<AccessibilityWindowInfo>? =
            try { this.windows } catch (e: Exception) {
                Log.e(TAG, "[BG] Erro ao obter janelas: ${e.message}"); null
            }

        if (currentWindows.isNullOrEmpty()) {
            Log.v(TREE_TAG, "[BG] Nenhuma janela para dump.")
            return
        }

        Log.v(TREE_TAG, "[BG] ${currentWindows.size} janelas para análise")
        val windowBounds = Rect()

        for ((index, window) in currentWindows.withIndex()) {
            if (!isServiceRunning) break
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

                Log.v(
                    TREE_TAG,
                    "[BG] Janela ${index + 1}/${currentWindows.size}: " +
                            "ID=$windowId, Pkg=$packageName, Tipo=$windowType, " +
                            "Ativa=$isActive, Focada=$isFocused, Bounds=${windowBounds.toShortString()}"
                )

                if (rootNode != null &&
                    (packageName == UBER_DRIVER_PACKAGE || windowType == "A11Y_OVERLAY")) {
                    logNodeInfoRecursive(rootNode, 0)
                }
            } catch (e: Exception) {
                Log.e(TREE_TAG, "[BG] Erro processando window $windowId: ${e.message}")
            } finally {
                try { rootNode?.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun logNodeInfoRecursive(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || !isServiceRunning) return
        if (depth > 25) return

        val indent = "  ".repeat(depth)
        try {
            val className = node.className?.toString()?.substringAfterLast('.') ?: "N/A"
            val viewId = node.viewIdResourceName ?: "no_id"
            val nodeText = try { node.text?.toString()?.replace("\n", "\\n")?.take(120) ?: "null" } catch (_: Exception) { "null" }
            val contentDesc = try { node.contentDescription?.toString()?.replace("\n", "\\n")?.take(120) ?: "null" } catch (_: Exception) { "null" }
            val bounds = Rect()
            var boundsStr = "[N/A]"
            try { node.getBoundsInScreen(bounds); boundsStr = bounds.toShortString() } catch (_: Exception) {}

            Log.v(TREE_TAG, "$indent- Class:$className, ID:$viewId, T:\"$nodeText\", D:\"$contentDesc\", B:$boundsStr, V:${node.isVisibleToUser}")
            for (i in 0 until node.childCount) {
                var child: AccessibilityNodeInfo? = null
                try {
                    child = node.getChild(i)
                    logNodeInfoRecursive(child, depth + 1)
                } catch (_: Exception) {
                } finally {
                    try { child?.recycle() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TREE_TAG, "$indent erro no node: ${e.message}")
        }
    }

    private fun windowTypeToString(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYS"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVERLAY"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT"
        else -> "UNKNOWN($type)"
    }
}
