package com.example.smartdriver.utils

import android.content.Context
import android.util.Log
import com.example.smartdriver.SettingsActivity

/**
 * Avaliador que analisa uma oferta e determina sua qualidade com base
 * nos critérios (€/km e €/hora) definidos nas configurações.
 * Retorna um EvaluationResult contendo as classificações individuais e combinada.
 */
class OfferEvaluator(private val context: Context) {

    companion object {
        private const val TAG = "OfferEvaluator"
    }

    /**
     * Avalia a qualidade de uma oferta com base nos valores por km e por hora.
     * Lê os limiares (Bom/Mau) das configurações salvas via SettingsActivity.
     * Trata valores inválidos retornados pelo OfferData como UNKNOWN.
     *
     * @param offer O objeto OfferData a ser avaliado.
     * @return Um objeto EvaluationResult contendo as classificações.
     */
    fun evaluateOffer(offer: OfferData): EvaluationResult {
        Log.d(
            TAG,
            "Avaliando oferta: ${offer.value}€, Dist=${offer.calculateTotalDistance()}km, Tempo=${offer.calculateTotalTimeMinutes()}min"
        )

        // 1) Limiar configurável
        val goodKmThreshold = SettingsActivity.getGoodKmThreshold(context)
        val poorKmThreshold = SettingsActivity.getPoorKmThreshold(context)
        val goodHourThreshold = SettingsActivity.getGoodHourThreshold(context)
        val poorHourThreshold = SettingsActivity.getPoorHourThreshold(context)

        Log.d(
            TAG,
            "Limiares: Km(Bom≥$goodKmThreshold, Mau≤$poorKmThreshold), Hora(Bom≥$goodHourThreshold, Mau≤$poorHourThreshold)"
        )

        // 2) Métricas da oferta
        val valuePerKm = offer.calculateProfitability()
        val valuePerHour = offer.calculateValuePerHour()
        Log.d(TAG, "Valores: €/km=$valuePerKm, €/hora=$valuePerHour")

        // 3) Rating €/km
        val kmRating = when {
            valuePerKm == null -> IndividualRating.UNKNOWN
            valuePerKm >= goodKmThreshold -> IndividualRating.GOOD
            valuePerKm <= poorKmThreshold -> IndividualRating.POOR
            else -> IndividualRating.MEDIUM
        }
        Log.d(TAG, "Classificação €/km: $kmRating")

        // 4) Rating €/hora (dinâmica já refletida via thresholds)
        val hourRating = when {
            valuePerHour == null -> IndividualRating.UNKNOWN
            valuePerHour >= goodHourThreshold -> IndividualRating.GOOD
            valuePerHour <= poorHourThreshold -> IndividualRating.POOR
            else -> IndividualRating.MEDIUM
        }
        Log.d(TAG, "Classificação €/hora: $hourRating")

        // 5) Combinação para o halo
        //    IMPORTANTE: UNKNOWN -> MEDIUM, para manter dinâmica (verde/vermelho) e evitar GRAY.
        val kmForHalo = kmRating.toCombining()
        val hrForHalo = hourRating.toCombining()
        val borderRating = determineBorderRating(kmForHalo, hrForHalo)

        Log.i(TAG, "Final: Halo=$borderRating (Km=$kmRating⇒$kmForHalo, Hora=$hourRating⇒$hrForHalo)")

        // 6) Resultado
        return EvaluationResult(
            kmRating = kmRating,
            hourRating = hourRating,
            combinedBorderRating = borderRating
        )
    }

    /**
     * UNKNOWN é tratado como MEDIUM para efeitos de halo.
     */
    private fun IndividualRating.toCombining(): IndividualRating =
        if (this == IndividualRating.UNKNOWN) IndividualRating.MEDIUM else this

    /**
     * Regra do halo:
     * - Verde  se Km=GOOD e Hora=GOOD
     * - Vermelho se Km=POOR e Hora=POOR
     * - Amarelo nos restantes casos (inclui mixes e UNKNOWN tratado como MEDIUM)
     */
    private fun determineBorderRating(km: IndividualRating, hour: IndividualRating): BorderRating {
        return when {
            km == IndividualRating.GOOD && hour == IndividualRating.GOOD -> BorderRating.GREEN
            km == IndividualRating.POOR && hour == IndividualRating.POOR -> BorderRating.RED
            else -> BorderRating.YELLOW
        }
    }
}
