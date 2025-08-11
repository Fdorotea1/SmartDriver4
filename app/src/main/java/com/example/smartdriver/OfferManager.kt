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
import java.util.Locale

/**
 * Processa ofertas:
 * - Dedupe por assinatura com timeout
 * - Avalia com OfferEvaluator
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

        val evaluationResult: EvaluationResult = offerEvaluator.evaluateOffer(offerData)
        Log.i(TAG, "Avaliação: Borda=${evaluationResult.combinedBorderRating} (Km=${evaluationResult.kmRating}, Hora=${evaluationResult.hourRating})")

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
}
