package com.example.smartdriver // <<< VERIFIQUE O PACKAGE

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.smartdriver.overlay.OverlayService
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.OfferEvaluator
import com.example.smartdriver.utils.EvaluationResult
import kotlin.math.abs // <<< Import Essencial

class OfferManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "OfferManager"
        @Volatile private var instance: OfferManager? = null

        fun getInstance(context: Context): OfferManager {
            return instance ?: synchronized(this) {
                instance ?: OfferManager(context.applicationContext).also { instance = it }
            }
        }

        private const val ACTIVE_OFFER_TIMEOUT_MS = 15000L // 15 segundos
        private const val ACTIVE_SIMILARITY_TOLERANCE = 0.05 // 5%
    }

    private val offerEvaluator = OfferEvaluator(context)
    @Volatile private var useOverlay = true
    private var currentDisplayedOfferData: OfferData? = null // Última oferta mostrada
    private var currentDisplayedOfferTimestamp = 0L // Timestamp da última oferta mostrada

    fun setUseOverlay(use: Boolean) {
        useOverlay = use
        if (!use) { hideOverlay() }
    }

    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
    }

    fun processOffer(offerData: OfferData) {
        val timeProcessStart = System.currentTimeMillis()
        Log.d(TAG, "Processando oferta: ${offerData.value}€") // Log simples

        val currentlyDisplayed = currentDisplayedOfferData
        val timeSinceLastDisplay = timeProcessStart - currentDisplayedOfferTimestamp

        // Verifica se já há uma oferta ativa e se a nova é similar
        if (currentlyDisplayed != null && timeSinceLastDisplay < ACTIVE_OFFER_TIMEOUT_MS) {
            if (isEssentiallyTheSameOffer(currentlyDisplayed, offerData)) {
                Log.d(TAG, "Ignorando oferta similar à ativa.")
                currentDisplayedOfferTimestamp = timeProcessStart // Atualiza timestamp para manter ativa
                return // Ignora
            } else {
                Log.d(TAG, "Nova oferta diferente da ativa. Substituindo.")
                // Continua para processar e mostrar a nova
            }
        } else if (currentlyDisplayed != null) {
            Log.d(TAG, "Timeout da oferta anterior. Processando nova.")
        }

        // Atualiza a oferta "ativa"
        currentDisplayedOfferData = offerData.copy()
        currentDisplayedOfferTimestamp = timeProcessStart

        // Avalia
        val evaluationResult = offerEvaluator.evaluateOffer(offerData)
        Log.d(TAG, "Avaliação: ${evaluationResult.combinedBorderRating}") // Log simples

        // Mostra
        if (useOverlay && hasOverlayPermission()) {
            showOverlay(evaluationResult, offerData)
        } else if (useOverlay) {
            Log.w(TAG, "Sem permissão de overlay.")
        }
    }

    private fun isEssentiallyTheSameOffer(currentOffer: OfferData, newOffer: OfferData): Boolean {
        val isValueSame = areNumericsSimilar(currentOffer.value, newOffer.value, ACTIVE_SIMILARITY_TOLERANCE)
        val isDistanceSame = areNumericsSimilar(currentOffer.distance, newOffer.distance, ACTIVE_SIMILARITY_TOLERANCE)
        val isDurationSame = areNumericsSimilar(currentOffer.duration, newOffer.duration, ACTIVE_SIMILARITY_TOLERANCE)
        // Precisa que todos sejam similares
        return isValueSame && isDistanceSame && isDurationSame
    }

    private fun areNumericsSimilar(valStr1: String?, valStr2: String?, tolerancePercent: Double): Boolean {
        if (valStr1.isNullOrBlank() || valStr2.isNullOrBlank()) return false
        return try {
            val num1 = valStr1.replace(",", ".").toDouble(); val num2 = valStr2.replace(",", ".").toDouble()
            if (num1 == num2) return true; val difference = abs(num1 - num2); val average = (abs(num1) + abs(num2)) / 2.0
            if (average == 0.0) return difference == 0.0; val threshold = average * tolerancePercent; difference <= threshold
        } catch (e: Exception) { false } // Simplificado
    }

    private fun showOverlay(evaluationResult: EvaluationResult, offerData: OfferData) {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_OVERLAY; putExtra(OverlayService.EXTRA_EVALUATION_RESULT, evaluationResult); putExtra(OverlayService.EXTRA_OFFER_DATA, offerData)
        }
        try { context.startService(intent) } catch (e: Exception) { Log.e(TAG, "Erro showOverlay: ${e.message}", e) }
    }

    private fun hideOverlay() {
        val intent = Intent(context, OverlayService::class.java).apply { action = OverlayService.ACTION_HIDE_OVERLAY }
        try { context.startService(intent) } catch (e: Exception) { Log.e(TAG, "Erro hideOverlay: ${e.message}", e) }
    }

    fun clearLastOfferState() {
        Log.d(TAG,"Limpando estado da oferta exibida.")
        currentDisplayedOfferData = null
        currentDisplayedOfferTimestamp = 0L
    }
}