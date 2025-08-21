package com.example.smartdriver

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.smartdriver.overlay.OverlayService
import com.example.smartdriver.utils.EvaluationResult
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.OfferEvaluator
import com.example.smartdriver.ScreenCaptureService
import com.example.smartdriver.data.TelemetryStore
import com.example.smartdriver.utils.IndividualRating
import java.util.Locale
import kotlin.math.max

/**
 * Processa ofertas:
 * - Dedupe por assinatura com timeout
 * - Avalia com OfferEvaluator
 * - Ajusta dinamicamente o rating de €/h para manter/atingir o alvo "bom"
 * - Mostra overlay (sem qualquer fila)
 * - Pede ao ScreenCaptureService para gravar screenshot da última válida
 */
class OfferManager private constructor(context: Context) {

    companion object {
        private const val TAG = "OfferManager"
        @Volatile private var instance: OfferManager? = null

        fun getInstance(context: Context): OfferManager =
            instance ?: synchronized(this) {
                instance ?: OfferManager(context.applicationContext).also { instance = it }
            }

        private const val ACTIVE_OFFER_TIMEOUT_MS = 25_000L
    }

    private val appContext = context.applicationContext
    private val offerEvaluator = OfferEvaluator(appContext)

    @Volatile private var useOverlay = true
    // Mantemos a API por compatibilidade, mas não usamos para fila
    @Volatile private var trackingActive: Boolean = false

    private var currentDisplayedOfferData: OfferData? = null
    private var currentDisplayedOfferTimestamp: Long = 0L
    private var currentDisplayedOfferSignature: String? = null

    fun setUseOverlay(use: Boolean) {
        useOverlay = use
        if (!use) {
            hideOverlay()
            clearLastOfferState()
        }
        Log.i(TAG, "Uso de Overlay = $use")
    }

    /** Mantido por compatibilidade (sem efeitos de fila) */
    fun setTrackingActive(active: Boolean) {
        trackingActive = active
        Log.i(TAG, "TrackingActive = $trackingActive")
    }

    fun processOffer(offerData: OfferData) {
        val now = System.currentTimeMillis()
        val signature = createOfferSignature(offerData)
        Log.d(TAG, "Processando oferta: sig=[$signature], valor=${offerData.value}€")

        val isActive = currentDisplayedOfferData != null && currentDisplayedOfferSignature != null
        val delta = now - currentDisplayedOfferTimestamp
        if (isActive && delta < ACTIVE_OFFER_TIMEOUT_MS) {
            if (currentDisplayedOfferSignature == signature) {
                Log.d(TAG, "Ignorada: mesma oferta dentro do timeout (${delta}ms < ${ACTIVE_OFFER_TIMEOUT_MS}ms).")
                currentDisplayedOfferTimestamp = now
                return
            } else {
                Log.d(TAG, "Nova oferta diferente (sig=$signature) vs ativa ($currentDisplayedOfferSignature).")
            }
        }

        currentDisplayedOfferData = offerData.copy()
        currentDisplayedOfferTimestamp = now
        currentDisplayedOfferSignature = signature

        // Avaliação base (km, hora, combinado, etc.)
        var evaluationResult: EvaluationResult = offerEvaluator.evaluateOffer(offerData)
        Log.i(TAG, "Avaliação base: Borda=${evaluationResult.combinedBorderRating} (Km=${evaluationResult.kmRating}, Hora=${evaluationResult.hourRating})")

        // ================== AJUSTE DINÂMICO DO €/H ==================
        try {
            // Tempo estimado da oferta (h)
            val minutes = offerData.calculateTotalTimeMinutes() ?: 0
            val tHours = minutes.coerceAtLeast(0) / 60.0
            val offerValueEuro = parseValueEuro(offerData.value)
            val offerRate = if (tHours > 0.0) offerValueEuro / tHours else Double.POSITIVE_INFINITY

            // thresholds de referência (config)
            val g = try { SettingsActivity.getGoodHourThreshold(appContext) } catch (_: Exception) { 16.0 }
            // poor mantém-se no OfferEvaluator; aqui só ajustamos o alvo "bom"

            // telemetria atual do turno: ganhos E, média A → T = E / A
            val (E, A) = TelemetryStore.read(appContext)
            val T = if (A > 0.0) (E / A) else 0.0

            // condição p/ manter média final >= g:
            // (E + V) / (T + t) >= g  ⇒  V/t >= g + ((g - A) * T / t)
            val requiredRateForGood =
                if (tHours > 0.0) (g + ((g - A) * T / tHours)) else g

            // saneamento: não negativo e sem absurdos
            val rReq = max(0.0, requiredRateForGood.coerceAtMost(g * 4.0))

            val dynHourRating = when {
                offerRate >= rReq          -> IndividualRating.GOOD
                offerRate >= (0.90 * rReq) -> IndividualRating.MEDIUM
                else                       -> IndividualRating.POOR
            }

            // Se mudou, criamos uma cópia com o novo hourRating; o combinado mantém-se (km/halo intactos)
            if (dynHourRating != evaluationResult.hourRating) {
                try {
                    // se EvaluationResult for data class, copy(...) existe:
                    evaluationResult = evaluationResult.copy(hourRating = dynHourRating)
                    Log.i(TAG, "€/h dinâmico ajustado: ${evaluationResult.hourRating} (req=${"%.2f".format(Locale.US, rReq)} €/h, oferta=${"%.2f".format(Locale.US, offerRate)} €/h)")
                } catch (_: Throwable) {
                    // se não houver copy(), ficamos com o resultado base
                    Log.w(TAG, "EvaluationResult não suporta copy(hourRating). Mantido rating base.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ajuste dinâmico €/h falhou: ${e.message}")
        }
        // ============================================================

        if (useOverlay && hasOverlayPermission()) {
            try {
                appContext.startService(Intent(appContext, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_SAVE_LAST_VALID_OFFER_SCREENSHOT
                })
            } catch (e: Exception) {
                Log.e(TAG, "Erro ACTION_SAVE_LAST_VALID_OFFER_SCREENSHOT: ${e.message}", e)
            }
            showOverlay(evaluationResult, offerData)
        } else {
            Log.w(TAG, "Overlay desativado ou sem permissão. Não mostro.")
        }
    }

    private fun createOfferSignature(offerData: OfferData): String {
        val v  = offerData.value.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0.00"
        val pd = offerData.pickupDistance.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0.00"
        val td = offerData.tripDistance.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0.00"
        val pt = offerData.pickupDuration.toIntOrNull()?.toString() ?: "0"
        val tt = offerData.tripDuration.toIntOrNull()?.toString() ?: "0"
        return "v:$v|pd:$pd|td:$td|pt:$pt|tt:$tt"
    }

    private fun hasOverlayPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(appContext) else true

    private fun showOverlay(evaluationResult: EvaluationResult, offerData: OfferData) {
        val intent = Intent(appContext, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_OVERLAY
            putExtra(OverlayService.EXTRA_EVALUATION_RESULT, evaluationResult)
            putExtra(OverlayService.EXTRA_OFFER_DATA, offerData)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
            else appContext.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar SHOW_OVERLAY: ${e.message}", e)
        }
    }

    private fun hideOverlay() {
        val intent = Intent(appContext, OverlayService::class.java).apply { action = OverlayService.ACTION_HIDE_OVERLAY }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
            else appContext.startService(intent)
        } catch (e: Exception) { Log.e(TAG, "Erro ao enviar HIDE_OVERLAY: ${e.message}", e) }
    }

    fun clearLastOfferState() {
        currentDisplayedOfferData = null
        currentDisplayedOfferTimestamp = 0L
        currentDisplayedOfferSignature = null
        Log.d(TAG, "Estado da última oferta limpo.")
    }

    // --- helpers ---
    private fun parseValueEuro(raw: String?): Double {
        if (raw.isNullOrBlank()) return 0.0
        val cleaned = raw.replace("€","", ignoreCase = true)
            .replace(" ", "")
            .replace(",", ".")
            .trim()
        return cleaned.toDoubleOrNull() ?: 0.0
    }
}
