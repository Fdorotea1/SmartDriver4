package com.example.smartdriver.utils

import android.os.Parcelable
import android.util.Log
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize // Import para @Parcelize
import java.util.Locale // Import para String.format

@Keep
@Parcelize // Usa anotação para implementação automática de Parcelable
data class OfferData(
    var value: String = "",
    var distance: String = "",        // Distância total CALCULADA (ou vazia)
    var pickupDistance: String = "",  // Distância da recolha EXTRAÍDA
    var tripDistance: String = "",    // Distância da viagem EXTRAÍDA
    var duration: String = "",        // Duração total CALCULADA (ou vazia)
    var pickupDuration: String = "",  // Duração da recolha EXTRAÍDA
    var tripDuration: String = "",    // Duração da viagem EXTRAÍDA
    var serviceType: String = "",     // Tipo de serviço (UberX, etc.)
    var rawText: String = ""          // Trecho do texto OCR original para debug
) : Parcelable {

    companion object {
        private const val TAG = "OfferData"
        // Velocidades médias estimadas (km/h) para cálculos de estimativa
        private const val AVG_PICKUP_SPEED = 20.0  // km/h
        private const val AVG_TRIP_SPEED = 35.0    // km/h
    }

    // --- Métodos de Cálculo ---

    /** Calcula o valor por km usando o campo 'distance' (que deve conter a soma). */
    fun calculateProfitability(): Double? {
        try {
            // USA DIRETAMENTE O CAMPO 'distance' (que foi calculado na extração)
            val totalDistance = this.distance.replace(",", ".").toDoubleOrNull() ?: 0.0
            if (totalDistance <= 0.05) {
                Log.w(TAG, "[PROFIT] Distância total calculada ($totalDistance km) inválida para calcular €/km.")
                return null
            }
            val monetaryValue = value.replace(",", ".").toDoubleOrNull() ?: return null
            if (monetaryValue <= 0) {
                Log.w(TAG, "[PROFIT] Valor monetário ($monetaryValue €) inválido para calcular €/km.")
                return null
            }
            val valuePerKm = monetaryValue / totalDistance
            Log.d(TAG, "[PROFIT] Valor por km calculado: $valuePerKm (€$monetaryValue / $totalDistance km)")
            return if (valuePerKm.isFinite()) valuePerKm else null
        } catch (e: NumberFormatException) { Log.e(TAG, "[PROFIT] Erro NFE ao calcular €/km: ${e.message}"); return null }
        catch (e: Exception) { Log.e(TAG, "[PROFIT] Erro GEN ao calcular €/km: ${e.message}"); return null }
    }

    /** Calcula o valor por hora usando o campo 'duration' (que deve conter a soma) ou estimativa. */
    fun calculateValuePerHour(): Double? {
        try {
            // 1. Tenta obter o tempo total do campo 'duration' (calculado na extração)
            var totalTimeMinutes = this.duration.toIntOrNull() ?: 0
            var timeSource = "Soma Direta (Campo Duration)"

            // 2. Se o tempo calculado/extraído for inválido (<=0), tenta estimar pela distância
            if (totalTimeMinutes <= 0) {
                totalTimeMinutes = estimateTimeMinutesFromDistance() // Usa a estimativa
                timeSource = "Estimado Dist"
                if (totalTimeMinutes <= 0) {
                    Log.w(TAG, "[HOURLY] Tempo total inválido (Fonte: $timeSource) para calcular €/h.")
                    return null
                }
            }

            Log.d(TAG, "[HOURLY] Usando Tempo Total = $totalTimeMinutes min (Fonte: $timeSource)")

            val monetaryValue = value.replace(",", ".").toDoubleOrNull() ?: return null
            if (monetaryValue <= 0) {
                Log.w(TAG, "[HOURLY] Valor monetário ($monetaryValue €) inválido para calcular €/h.")
                return null
            }
            val totalTimeHours = totalTimeMinutes / 60.0
            if (totalTimeHours == 0.0) {
                Log.w(TAG, "[HOURLY] Tempo total em horas é zero, impossível calcular €/h.")
                return null
            }
            val valuePerHour = monetaryValue / totalTimeHours
            Log.d(TAG, "[HOURLY] Valor por hora calculado: $valuePerHour (€$monetaryValue / $totalTimeHours h)")
            return if (valuePerHour.isFinite()) valuePerHour else null
        } catch (e: NumberFormatException) { Log.e(TAG, "[HOURLY] Erro NFE ao calcular €/h: ${e.message}"); return null }
        catch (e: Exception) { Log.e(TAG, "[HOURLY] Erro GEN ao calcular €/h: ${e.message}"); return null }
    }

    /** Retorna a distância total calculada (campo 'distance') como Double. */
    fun calculateTotalDistance(): Double {
        // Este método agora simplesmente retorna o valor do campo 'distance'
        // que foi preenchido em extractOfferData.
        Log.d(TAG, "--- Chamando calculateTotalDistance ---")
        val totalDist = this.distance.replace(",", ".").toDoubleOrNull() ?: 0.0
        Log.d(TAG, "[CALC_DIST] Retornando valor do campo 'distance': $totalDist")
        Log.d(TAG, "--- Final calculateTotalDistance: Retornando $totalDist ---")
        return totalDist.coerceAtLeast(0.0) // Garante não negativo
    }

    /** Retorna a duração total calculada (campo 'duration') como Int. */
    fun calculateTotalTimeMinutes(): Int {
        // Este método agora simplesmente retorna o valor do campo 'duration'
        // que foi preenchido em extractOfferData.
        Log.d(TAG, "--- Chamando calculateTotalTimeMinutes ---")
        val totalTime = this.duration.toIntOrNull() ?: 0
        Log.d(TAG, "[CALC_TIME] Retornando valor do campo 'duration': $totalTime")
        Log.d(TAG, "--- Final calculateTotalTimeMinutes: Retornando $totalTime ---")
        return totalTime.coerceAtLeast(0) // Garante não negativo
    }

    /** Estima o tempo total em minutos baseado nas distâncias e velocidades médias. */
    private fun estimateTimeMinutesFromDistance(): Int {
        Log.d(TAG, "[ESTIMATE_TIME] Tentando estimar tempo a partir das distâncias...")
        // Usa o método que lê o campo 'distance' calculado
        val totalDistance = calculateTotalDistance()
        if (totalDistance <= 0.0) {
            Log.d(TAG, "[ESTIMATE_TIME] Distância total inválida ($totalDistance), não é possível estimar.")
            return 0
        }

        // Tenta usar as distâncias parciais EXTRAÍDAS
        var pickupDist = pickupDistance.replace(",", ".").toDoubleOrNull() ?: 0.0
        var tripDist = tripDistance.replace(",", ".").toDoubleOrNull() ?: 0.0
        if (pickupDist < 0) pickupDist = 0.0
        if (tripDist < 0) tripDist = 0.0

        // Se não temos distâncias parciais válidas, divide a total
        if (pickupDist <= 0.0 && tripDist <= 0.0) {
            Log.d(TAG, "[ESTIMATE_TIME] Distâncias parciais indisponíveis ou inválidas, dividindo a distância total ($totalDistance km) em 30% recolha / 70% viagem.")
            pickupDist = totalDistance * 0.3
            tripDist = totalDistance * 0.7
        } else if (pickupDist > 0.0 && tripDist <= 0.0) {
            // Se só temos pickup, assume que a viagem é zero para este cálculo
            Log.d(TAG, "[ESTIMATE_TIME] Usando apenas PDist ($pickupDist) para estimativa.")
            tripDist = 0.0
        } else if (tripDist > 0.0 && pickupDist <= 0.0) {
            // Se só temos trip, assume que a recolha é zero para este cálculo
            Log.d(TAG, "[ESTIMATE_TIME] Usando apenas TDist ($tripDist) para estimativa.")
            pickupDist = 0.0
        }

        Log.d(TAG, "[ESTIMATE_TIME] Usando para estimativa: PDist=$pickupDist, TDist=$tripDist")

        val pickupTimeMinutes = if (pickupDist > 0) (pickupDist / AVG_PICKUP_SPEED) * 60.0 else 0.0
        val tripTimeMinutes = if (tripDist > 0) (tripDist / AVG_TRIP_SPEED) * 60.0 else 0.0

        val estimatedTotalTime = if(pickupTimeMinutes + tripTimeMinutes > 0) (pickupTimeMinutes + tripTimeMinutes).toInt().coerceAtLeast(1) else 0

        Log.d(TAG, "[ESTIMATE_TIME] Tempo estimado final: $estimatedTotalTime min " +
                "(Recolha: ${pickupTimeMinutes.toInt()} min @ ${AVG_PICKUP_SPEED}km/h + " +
                "Viagem: ${tripTimeMinutes.toInt()} min @ ${AVG_TRIP_SPEED}km/h)")

        return estimatedTotalTime
    }

    /** Verifica se a oferta tem dados mínimos para ser considerada válida. */
    fun isValid(): Boolean {
        val hasValue = try { (value.replace(",", ".").toDoubleOrNull() ?: 0.0) > 0.0 } catch (e: Exception) { false }
        // Verifica se temos DURAÇÃO calculada OU podemos ESTIMAR tempo E se temos DISTÂNCIA calculada.
        // Uma oferta sem distância E sem tempo (nem estimado) não é válida.
        val hasValidTimeSource = calculateTotalTimeMinutes() > 0 || estimateTimeMinutesFromDistance() > 0
        val hasValidDistance = calculateTotalDistance() > 0.0

        val isValid = hasValue && hasValidTimeSource && hasValidDistance
        if (!isValid) {
            Log.w(TAG, "[VALID?] OfferData inválida: Valor=$value, HasDist=$hasValidDistance, HasTimeSource=$hasValidTimeSource (TempoCalc=${calculateTotalTimeMinutes()}, TempoEst=${estimateTimeMinutesFromDistance()})")
        }
        return isValid
    }
}