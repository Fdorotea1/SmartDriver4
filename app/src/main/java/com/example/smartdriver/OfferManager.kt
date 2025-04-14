package com.example.smartdriver

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.smartdriver.overlay.OverlayService // Import para interagir com o overlay
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.OfferEvaluator
// Removido import com.example.smartdriver.utils.OfferRating
import com.example.smartdriver.utils.EvaluationResult // <<< NOVO IMPORT
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
        // Tolerância aumentada ligeiramente para similaridade, pode ajustar se necessário
        private const val SIMILARITY_TOLERANCE_PERCENT = 0.08 // Ex: 8%
    }

    private val offerEvaluator = OfferEvaluator(context)
    @Volatile private var useOverlay = true // Assume que o overlay deve ser usado por padrão
    private var lastProcessedOfferData: OfferData? = null
    private var lastProcessedOfferTimestamp = 0L

    // Função para definir se o overlay deve ser mostrado (pode ser chamada por Settings ou outra lógica futura)
    fun setUseOverlay(use: Boolean) {
        useOverlay = use
        Log.i(TAG, "Uso do overlay definido para: $use")
        if (!use) {
            hideOverlay() // Esconde o overlay se for desativado
        }
    }

    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // Permissão não necessária antes do Android M
        }
    }

    /**
     * Processa uma oferta recebida, avalia e mostra (ou não) o overlay.
     * @param offerData Os dados da oferta extraídos.
     */
    fun processOffer(offerData: OfferData) {
        val timeProcessStart = System.currentTimeMillis()
        Log.i(TAG, "[TIME] >>> OfferManager.processOffer iniciado at $timeProcessStart")
        Log.d(TAG, "   Oferta recebida: Valor=${offerData.value}, Dist=${offerData.distance}, Tempo=${offerData.duration}")
        // Log dos dados parciais também
        Log.d(TAG, "   Detalhes: PDist=${offerData.pickupDistance}, TDist=${offerData.tripDistance}, PDur=${offerData.pickupDuration}, TDur=${offerData.tripDuration}")


        if (isDuplicateOrSimilarOffer(offerData)) {
            Log.d(TAG, "Oferta ignorada por ser duplicata/similar à anterior recente.")
            return // Sai cedo se for duplicada/similar
        }

        // Atualiza estado *após* checar duplicata/similaridade
        lastProcessedOfferData = offerData.copy() // Guarda uma cópia
        lastProcessedOfferTimestamp = System.currentTimeMillis()

        // Avalia a oferta para obter o resultado completo
        val timeEvalStart = System.currentTimeMillis()
        // <<< MUDANÇA: Chama evaluateOffer que agora retorna EvaluationResult >>>
        val evaluationResult = offerEvaluator.evaluateOffer(offerData)
        val timeEvalEnd = System.currentTimeMillis()
        // Log atualizado para mostrar o resultado da avaliação
        Log.i(TAG, "[TIME] Avaliação demorou ${timeEvalEnd - timeEvalStart}ms. Resultado: Borda=${evaluationResult.combinedBorderRating}, Km=${evaluationResult.kmRating}, Hora=${evaluationResult.hourRating}")

        // Decide se mostra o overlay
        if (useOverlay) {
            if (hasOverlayPermission()) {
                val timeShowOverlayStart = System.currentTimeMillis()
                // <<< MUDANÇA: Passa evaluationResult para showOverlay >>>
                showOverlay(evaluationResult, offerData)
                Log.d(TAG, "[TIME] Comando SHOW_OVERLAY enviado at $timeShowOverlayStart")
            } else {
                Log.w(TAG, "Overlay ativado, mas sem permissão para desenhar sobre outros apps.")
                // Poderia tentar notificar o usuário ou desativar `useOverlay` automaticamente aqui
            }
        } else {
            Log.d(TAG, "Overlay desativado, não será mostrado.")
        }

        val timeProcessEnd = System.currentTimeMillis()
        Log.i(TAG,"[TIME] OfferManager.processOffer concluído em ${timeProcessEnd - timeProcessStart}ms")
    }

    /** Verifica se a nova oferta é muito similar a última processada recentemente. */
    private fun isDuplicateOrSimilarOffer(newOfferData: OfferData): Boolean {
        val lastOffer = lastProcessedOfferData ?: return false // Se não há oferta anterior, não é duplicata
        val currentTime = System.currentTimeMillis()

        // Se passou muito tempo desde a última, não é duplicata
        if (currentTime - lastProcessedOfferTimestamp > DUPLICATE_OFFER_THRESHOLD_MS) {
            return false
        }

        // Compara os valores principais (€, Dist Total, Tempo Total)
        val isValueSimilar = areNumericsSimilar(lastOffer.value, newOfferData.value, SIMILARITY_TOLERANCE_PERCENT)
        // Compara distância total calculada
        val isDistanceSimilar = areNumericsSimilar(lastOffer.calculateTotalDistance().toString(), newOfferData.calculateTotalDistance().toString(), SIMILARITY_TOLERANCE_PERCENT)
        // Compara tempo total calculado
        val isDurationSimilar = areNumericsSimilar(lastOffer.calculateTotalTimeMinutes().toString(), newOfferData.calculateTotalTimeMinutes().toString(), SIMILARITY_TOLERANCE_PERCENT)

        // Considera similar se VALOR for similar E (Distância OU Tempo for similar)
        val isSimilar = isValueSimilar && (isDistanceSimilar || isDurationSimilar)

        if (isSimilar) {
            Log.d(TAG, "Detecção de similaridade: Valor~${isValueSimilar}, Dist~${isDistanceSimilar}, Tempo~${isDurationSimilar}. Tolerancia: ${SIMILARITY_TOLERANCE_PERCENT*100}%")
        }
        return isSimilar
    }

    /** Compara duas strings que representam números, com uma tolerância percentual. */
    private fun areNumericsSimilar(valStr1: String?, valStr2: String?, tolerancePercent: Double): Boolean {
        if (valStr1.isNullOrBlank() || valStr2.isNullOrBlank()) return false // Se algum for nulo/vazio, não são similares
        return try {
            // Converte para Double, tratando vírgula e ponto
            val num1 = valStr1.replace(",", ".").toDouble()
            val num2 = valStr2.replace(",", ".").toDouble()

            // Se forem exatamente iguais
            if (num1 == num2) return true

            // Calcula a diferença absoluta
            val difference = abs(num1 - num2)
            // Calcula a média dos valores absolutos (para lidar com números negativos e evitar divisão por zero se um for zero)
            val average = (abs(num1) + abs(num2)) / 2.0

            // Se a média for zero (ambos são zero ou muito próximos), a diferença deve ser zero
            if (average == 0.0) return difference == 0.0

            // Calcula o limiar absoluto baseado na tolerância percentual da média
            val threshold = average * tolerancePercent

            // Verifica se a diferença está dentro do limiar
            difference <= threshold
        } catch (e: NumberFormatException) {
            Log.w(TAG,"Erro NFE ao comparar strings numéricas: '$valStr1', '$valStr2' - ${e.message}")
            false // Erro na conversão, considera não similar
        } catch (e: Exception) {
            Log.e(TAG,"Erro inesperado ao comparar strings numéricas: '$valStr1', '$valStr2' - ${e.message}")
            false // Erro inesperado, considera não similar
        }
    }

    /**
     * Envia um Intent para o OverlayService para mostrar o overlay com a avaliação.
     * @param evaluationResult O resultado da avaliação da oferta.
     * @param offerData Os dados da oferta originais.
     */
    // <<< MUDANÇA: Aceita EvaluationResult >>>
    private fun showOverlay(evaluationResult: EvaluationResult, offerData: OfferData) {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_OVERLAY
            // <<< MUDANÇA: Passa EvaluationResult como Parcelable >>>
            putExtra(OverlayService.EXTRA_EVALUATION_RESULT, evaluationResult) // Usa nova chave
            putExtra(OverlayService.EXTRA_OFFER_DATA, offerData)
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar SHOW_OVERLAY para OverlayService: ${e.message}", e)
            // Considerar notificar o usuário ou tentar novamente
        }
    }


    /** Envia um Intent para o OverlayService para esconder o overlay. */
    private fun hideOverlay() {
        Log.d(TAG, "Enviando comando HIDE_OVERLAY para OverlayService.")
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_HIDE_OVERLAY
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar HIDE_OVERLAY para OverlayService: ${e.message}", e)
        }
    }

    /** Limpa o estado da última oferta processada (útil ao sair/entrar no app alvo). */
    fun clearLastOfferState() {
        Log.d(TAG,"Limpando estado da última oferta processada.")
        lastProcessedOfferData = null
        lastProcessedOfferTimestamp = 0L
        // Poderia também esconder o overlay aqui, se desejado:
        // hideOverlay()
    }
}