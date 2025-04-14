package com.example.smartdriver

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.smartdriver.overlay.OverlayService // Import para interagir com o overlay
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.OfferEvaluator
import com.example.smartdriver.utils.OfferRating
import kotlin.math.abs // Para comparação de valores

class OfferManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "OfferManager"

        @Volatile private var instance: OfferManager? = null

        fun getInstance(context: Context): OfferManager {
            return instance ?: synchronized(this) {
                instance ?: OfferManager(context.applicationContext).also { instance = it }
            }
        }

        private const val DUPLICATE_OFFER_THRESHOLD_MS = 3000L
        private const val SIMILARITY_TOLERANCE_PERCENT = 0.05
    }

    private val offerEvaluator = OfferEvaluator(context)
    @Volatile private var useOverlay = true
    private var lastProcessedOfferData: OfferData? = null
    private var lastProcessedOfferTimestamp = 0L

    fun setUseOverlay(use: Boolean) {
        useOverlay = use
        Log.i(TAG, "Uso do overlay definido para: $use")
        if (!use) {
            hideOverlay()
        }
    }

    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun processOffer(offerData: OfferData) {
        val timeProcessStart = System.currentTimeMillis()
        Log.i(TAG, "[TIME] >>> OfferManager.processOffer iniciado at $timeProcessStart")
        Log.d(TAG, "   Oferta recebida: Valor=${offerData.value}, Dist=${offerData.distance}, Tempo=${offerData.duration}")

        if (isDuplicateOrSimilarOffer(offerData)) {
            Log.d(TAG, "Oferta ignorada por ser duplicata/similar à anterior recente.")
            return
        }

        lastProcessedOfferData = offerData
        lastProcessedOfferTimestamp = System.currentTimeMillis() // Atualiza timestamp *após* checar duplicata

        val timeEvalStart = System.currentTimeMillis()
        val rating = offerEvaluator.evaluateOffer(offerData)
        val timeEvalEnd = System.currentTimeMillis()
        Log.i(TAG, "[TIME] Avaliação demorou ${timeEvalEnd - timeEvalStart}ms. Resultado: $rating")

        if (useOverlay) {
            if (hasOverlayPermission()) {
                val timeShowOverlayStart = System.currentTimeMillis()
                showOverlay(rating, offerData)
                Log.d(TAG, "[TIME] Comando SHOW_OVERLAY enviado at $timeShowOverlayStart")
            } else {
                Log.w(TAG, "Overlay ativado, mas sem permissão para desenhar sobre outros apps.")
            }
        } else {
            Log.d(TAG, "Overlay desativado, não será mostrado.")
        }
        val timeProcessEnd = System.currentTimeMillis()
        Log.i(TAG,"[TIME] OfferManager.processOffer concluído em ${timeProcessEnd - timeProcessStart}ms")
    }

    private fun isDuplicateOrSimilarOffer(newOfferData: OfferData): Boolean {
        val lastOffer = lastProcessedOfferData ?: return false
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedOfferTimestamp > DUPLICATE_OFFER_THRESHOLD_MS) {
            return false
        }

        val isValueSimilar = areNumericsSimilar(lastOffer.value, newOfferData.value, SIMILARITY_TOLERANCE_PERCENT)
        val isDistanceSimilar = areNumericsSimilar(lastOffer.calculateTotalDistance().toString(), newOfferData.calculateTotalDistance().toString(), SIMILARITY_TOLERANCE_PERCENT) ||
                areNumericsSimilar(lastOffer.tripDistance, newOfferData.tripDistance, SIMILARITY_TOLERANCE_PERCENT)
        val isDurationSimilar = areNumericsSimilar(lastOffer.calculateTotalTimeMinutes().toString(), newOfferData.calculateTotalTimeMinutes().toString(), SIMILARITY_TOLERANCE_PERCENT) ||
                areNumericsSimilar(lastOffer.tripDuration, newOfferData.tripDuration, SIMILARITY_TOLERANCE_PERCENT)

        val isSimilar = isValueSimilar && (isDistanceSimilar || isDurationSimilar)
        if (isSimilar) {
            Log.d(TAG, "Detecção de similaridade: ValorSimilar=$isValueSimilar, DistSimilar=$isDistanceSimilar, TempoSimilar=$isDurationSimilar")
        }
        return isSimilar
    }

    private fun areNumericsSimilar(valStr1: String?, valStr2: String?, tolerancePercent: Double): Boolean {
        if (valStr1.isNullOrBlank() || valStr2.isNullOrBlank()) return false
        return try {
            val num1 = valStr1.replace(",", ".").toDouble()
            val num2 = valStr2.replace(",", ".").toDouble()
            if (num1 == num2) return true
            val difference = abs(num1 - num2)
            val average = (abs(num1) + abs(num2)) / 2.0
            if (average == 0.0) return difference == 0.0
            val threshold = average * tolerancePercent
            difference <= threshold
        } catch (e: NumberFormatException) {
            Log.w(TAG,"Erro ao comparar strings numéricas: '$valStr1', '$valStr2' - ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG,"Erro inesperado ao comparar strings numéricas: ${e.message}")
            false
        }
    }

    private fun showOverlay(rating: OfferRating, offerData: OfferData) {
        // Log já está na função processOffer antes da chamada
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_OVERLAY
            putExtra(OverlayService.EXTRA_RATING, rating)
            putExtra(OverlayService.EXTRA_OFFER_DATA, offerData)
        }
        try { // Adicionar try-catch para startService
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar SHOW_OVERLAY para OverlayService: ${e.message}")
        }
    }

    private fun hideOverlay() {
        Log.d(TAG, "Enviando comando HIDE_OVERLAY para OverlayService.")
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_HIDE_OVERLAY
        }
        try { // Adicionar try-catch para startService
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar HIDE_OVERLAY para OverlayService: ${e.message}")
        }
    }

    fun clearLastOfferState() {
        Log.d(TAG,"Limpando estado da última oferta processada.")
        lastProcessedOfferData = null
        lastProcessedOfferTimestamp = 0L
    }
}