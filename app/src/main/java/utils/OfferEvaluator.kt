package com.example.smartdriver.utils

import android.content.Context
import android.util.Log
import com.example.smartdriver.SettingsActivity // Import DESCOMENTADO

/**
 * Avaliador que analisa uma oferta e determina sua qualidade com base nos critérios definidos.
 */
class OfferEvaluator(private val context: Context) {

    companion object {
        private const val TAG = "OfferEvaluator"
        // Constantes padrão removidas daqui, pois são lidas de SettingsActivity
    }

    /**
     * Avalia a qualidade de uma oferta com base principalmente no valor por km.
     * Lê os limiares (thresholds) das configurações salvas via SettingsActivity.
     *
     * @param offer O objeto OfferData a ser avaliado.
     * @return A classificação OfferRating correspondente (EXCELLENT, GOOD, MEDIUM, POOR, UNKNOWN).
     */
    fun evaluateOffer(offer: OfferData): OfferRating {
        Log.d(TAG, "Avaliando oferta: ${offer.value}€, DistTotal=${offer.calculateTotalDistance()}km, TempoTotal=${offer.calculateTotalTimeMinutes()}min")

        // 1. Obter critérios salvos das configurações usando os métodos estáticos de SettingsActivity
        // Linhas DESCOMENTADAS:
        val excellentThreshold = SettingsActivity.getExcellentThreshold(context)
        val goodThreshold = SettingsActivity.getGoodThreshold(context)
        val mediumThreshold = SettingsActivity.getMediumThreshold(context)

        // Logar os limiares lidos das configurações
        Log.d(TAG, "Limiares (€/km) lidos das Configs: Excelente >= $excellentThreshold, Bom >= $goodThreshold, Médio >= $mediumThreshold")

        // 2. Calcular valor por km da oferta atual
        val valuePerKm = offer.calculateProfitability() // Este método já loga o resultado

        // 3. Verificar se o cálculo foi possível e resultou num valor válido
        if (valuePerKm == null || !valuePerKm.isFinite() || valuePerKm < 0) {
            Log.w(TAG, "Não foi possível calcular um valor por km válido ($valuePerKm). Classificação: UNKNOWN")
            return OfferRating.UNKNOWN
        }

        // 4. Avaliar com base nos limiares (Comparação Double >= Double)
        return when {
            valuePerKm >= excellentThreshold -> {
                Log.i(TAG, "Classificação: EXCELLENT (Valor/km: $valuePerKm)")
                OfferRating.EXCELLENT
            }
            valuePerKm >= goodThreshold -> {
                Log.i(TAG, "Classificação: GOOD (Valor/km: $valuePerKm)")
                OfferRating.GOOD
            }
            valuePerKm >= mediumThreshold -> {
                Log.i(TAG, "Classificação: MEDIUM (Valor/km: $valuePerKm)")
                OfferRating.MEDIUM
            }
            else -> {
                Log.i(TAG, "Classificação: POOR (Valor/km: $valuePerKm)")
                OfferRating.POOR
            }
        }
    }
}