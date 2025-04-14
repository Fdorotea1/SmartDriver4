package com.example.smartdriver.utils

import android.content.Context
import android.util.Log
import com.example.smartdriver.SettingsActivity // Precisa importar SettingsActivity para ler os limiares

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
     *
     * @param offer O objeto OfferData a ser avaliado.
     * @return Um objeto EvaluationResult contendo as classificações.
     */
    fun evaluateOffer(offer: OfferData): EvaluationResult {
        Log.d(TAG, "Avaliando oferta: ${offer.value}€, Dist=${offer.calculateTotalDistance()}km, Tempo=${offer.calculateTotalTimeMinutes()}min")

        // 1. Obter os limiares salvos das configurações
        val goodKmThreshold = SettingsActivity.getGoodKmThreshold(context)
        val poorKmThreshold = SettingsActivity.getPoorKmThreshold(context)
        val goodHourThreshold = SettingsActivity.getGoodHourThreshold(context)
        val poorHourThreshold = SettingsActivity.getPoorHourThreshold(context)

        Log.d(TAG, "Limiares lidos: Km(Bom≥$goodKmThreshold, Mau≤$poorKmThreshold), Hora(Bom≥$goodHourThreshold, Mau≤$poorHourThreshold)")

        // 2. Calcular valor por km e por hora da oferta atual
        val valuePerKm = offer.calculateProfitability()
        val valuePerHour = offer.calculateValuePerHour()

        Log.d(TAG, "Valores calculados: €/km = $valuePerKm, €/hora = $valuePerHour")

        // 3. Avaliar €/km individualmente
        val kmRating = when {
            valuePerKm == null || !valuePerKm.isFinite() -> IndividualRating.UNKNOWN
            valuePerKm >= goodKmThreshold -> IndividualRating.GOOD
            valuePerKm <= poorKmThreshold -> IndividualRating.POOR
            else -> IndividualRating.MEDIUM // Entre Mau e Bom
        }
        Log.d(TAG, "Classificação €/km: $kmRating")

        // 4. Avaliar €/hora individualmente
        val hourRating = when {
            valuePerHour == null || !valuePerHour.isFinite() -> IndividualRating.UNKNOWN
            valuePerHour >= goodHourThreshold -> IndividualRating.GOOD
            valuePerHour <= poorHourThreshold -> IndividualRating.POOR
            else -> IndividualRating.MEDIUM // Entre Mau e Bom
        }
        Log.d(TAG, "Classificação €/hora: $hourRating")

        // 5. Determinar a cor da borda combinada
        val borderRating = determineBorderRating(kmRating, hourRating)
        Log.i(TAG, "Classificação Final: Borda=$borderRating (Km=$kmRating, Hora=$hourRating)")

        // 6. Retornar o resultado completo
        return EvaluationResult(
            kmRating = kmRating,
            hourRating = hourRating,
            combinedBorderRating = borderRating
        )
    }

    /**
     * Determina a cor da borda com base nas classificações individuais.
     */
    private fun determineBorderRating(km: IndividualRating, hour: IndividualRating): BorderRating {
        return when {
            // Se algum for desconhecido, a borda é Cinza
            km == IndividualRating.UNKNOWN || hour == IndividualRating.UNKNOWN -> BorderRating.GRAY
            // Se ambos forem Bons, a borda é Verde
            km == IndividualRating.GOOD && hour == IndividualRating.GOOD -> BorderRating.GREEN
            // Se ambos forem Maus, a borda é Vermelha
            km == IndividualRating.POOR && hour == IndividualRating.POOR -> BorderRating.RED
            // Em todos os outros casos (mistos, médios), a borda é Amarela
            else -> BorderRating.YELLOW
        }
    }
}