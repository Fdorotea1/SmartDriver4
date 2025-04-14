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
            Log.d(TAG, "[PROFIT] Valor por km calculado: ${String.format(Locale.US,"%.2f",valuePerKm)} (€$monetaryValue / $totalDistance km)")
            return if (valuePerKm.isFinite()) valuePerKm else null
        } catch (e: NumberFormatException) { Log.e(TAG, "[PROFIT] Erro NFE ao calcular €/km: ${e.message}"); return null }
        catch (e: Exception) { Log.e(TAG, "[PROFIT] Erro GEN ao calcular €/km: ${e.message}"); return null }
    }

    /**
     * Calcula o valor por hora. Prioriza tempos extraídos, estima tempos parciais
     * faltantes usando distâncias parciais, e como último recurso, estima o tempo
     * total a partir da distância total.
     */
    fun calculateValuePerHour(): Double? {
        try {
            val monetaryValue = value.replace(",", ".").toDoubleOrNull() ?: run {
                Log.w(TAG, "[HOURLY] Valor monetário inválido para cálculo (€/h).")
                return null
            }
            if (monetaryValue <= 0) {
                Log.w(TAG, "[HOURLY] Valor monetário não positivo ($monetaryValue €) para cálculo (€/h).")
                return null
            }

            var totalTimeMinutes = 0
            var timeSource = "Não Definido"

            // Tenta converter tempos e distâncias parciais extraídos
            val pDurInt = pickupDuration.toIntOrNull()
            val tDurInt = tripDuration.toIntOrNull()
            val pDistDouble = pickupDistance.replace(",", ".").toDoubleOrNull()
            val tDistDouble = tripDistance.replace(",", ".").toDoubleOrNull()

            // Caso 1: Ambos os tempos (pickup e trip) foram extraídos corretamente e são válidos
            if (pDurInt != null && pDurInt >= 0 && tDurInt != null && tDurInt >= 0 && (pDurInt > 0 || tDurInt > 0)) {
                totalTimeMinutes = pDurInt + tDurInt
                timeSource = "Soma Direta (P:$pDurInt + T:$tDurInt)"
            }
            // Caso 2: Apenas tempo da VIAGEM foi extraído, tentar estimar RECOLHA pela DISTÂNCIA de recolha
            else if (tDurInt != null && tDurInt > 0 && (pDurInt == null || pDurInt < 0) && pDistDouble != null && pDistDouble > 0.0) {
                val estimatedPickupTime = ((pDistDouble / AVG_PICKUP_SPEED) * 60.0).toInt().coerceAtLeast(1) // Estima e garante min 1 min
                totalTimeMinutes = estimatedPickupTime + tDurInt
                timeSource = "Parcial (Est. P:$estimatedPickupTime [Dist:$pDistDouble km] + Lida T:$tDurInt)"
                Log.d(TAG, "[HOURLY] Tempo recolha não lido/inválido, estimado $estimatedPickupTime min a partir de $pDistDouble km.")
            }
            // Caso 3: Apenas tempo da RECOLHA foi extraído, tentar estimar VIAGEM pela DISTÂNCIA de viagem
            else if (pDurInt != null && pDurInt > 0 && (tDurInt == null || tDurInt < 0) && tDistDouble != null && tDistDouble > 0.0) {
                val estimatedTripTime = ((tDistDouble / AVG_TRIP_SPEED) * 60.0).toInt().coerceAtLeast(1) // Estima e garante min 1 min
                totalTimeMinutes = pDurInt + estimatedTripTime
                timeSource = "Parcial (Lida P:$pDurInt + Est. T:$estimatedTripTime [Dist:$tDistDouble km])"
                Log.d(TAG, "[HOURLY] Tempo viagem não lido/inválido, estimado $estimatedTripTime min a partir de $tDistDouble km.")
            }
            // Caso 4: Nenhum tempo parcial extraído ou combinações inválidas. Tentar estimativa TOTAL pela distância total.
            else {
                Log.w(TAG, "[HOURLY] Tempos parciais não lidos ou inválidos (P:$pDurInt, T:$tDurInt). Tentando estimativa total.")
                totalTimeMinutes = estimateTimeMinutesFromDistance() // Usa a função que já considera distância total
                if (totalTimeMinutes > 0) {
                    timeSource = "Estimado Total (baseado na Dist. Total)"
                } else {
                    timeSource = "Falha (Tempos e Estimativa Total Inválidos)"
                }
            }

            // --- Verificação Final e Cálculo €/h ---
            if (totalTimeMinutes <= 0) {
                Log.e(TAG, "[HOURLY] Tempo total final inválido ($totalTimeMinutes min, Fonte: $timeSource). Impossível calcular €/h.")
                return null // Retorna null se o tempo for zero ou negativo
            }

            Log.d(TAG, "[HOURLY] Usando Tempo Total = $totalTimeMinutes min (Fonte: $timeSource)")

            val totalTimeHours = totalTimeMinutes / 60.0
            // Evitar divisão por zero explícita (embora totalTimeMinutes > 0 já deva prevenir)
            if (totalTimeHours == 0.0) {
                Log.e(TAG, "[HOURLY] Tempo total em horas resultou em zero após divisão ($totalTimeMinutes min). Impossível calcular €/h.")
                return null
            }

            val valuePerHour = monetaryValue / totalTimeHours
            Log.d(TAG, "[HOURLY] Valor por hora calculado: ${String.format(Locale.US,"%.2f",valuePerHour)} (€${String.format(Locale.US,"%.2f",monetaryValue)} / ${String.format(Locale.US,"%.2f",totalTimeHours)} h)")

            // Retorna o valor calculado se for um número finito
            return if (valuePerHour.isFinite()) valuePerHour else {
                Log.e(TAG, "[HOURLY] Resultado do cálculo €/h não é um número finito ($valuePerHour).")
                null
            }

        } catch (e: NumberFormatException) {
            Log.e(TAG, "[HOURLY] Erro NFE ao calcular €/h: ${e.message}", e); return null
        } catch (e: Exception) {
            Log.e(TAG, "[HOURLY] Erro GEN ao calcular €/h: ${e.message}", e); return null
        }
    }


    /** Retorna a distância total calculada (campo 'distance') como Double. */
    fun calculateTotalDistance(): Double {
        // Este método agora simplesmente retorna o valor do campo 'distance'
        // que foi preenchido em extractOfferData.
        // Log.d(TAG, "--- Chamando calculateTotalDistance ---") // Log menos verboso
        val totalDist = this.distance.replace(",", ".").toDoubleOrNull() ?: 0.0
        // Log.d(TAG, "[CALC_DIST] Retornando valor do campo 'distance': $totalDist")
        // Log.d(TAG, "--- Final calculateTotalDistance: Retornando $totalDist ---")
        return totalDist.coerceAtLeast(0.0) // Garante não negativo
    }

    /** Retorna a duração total calculada (campo 'duration') como Int. */
    fun calculateTotalTimeMinutes(): Int {
        // Este método agora simplesmente retorna o valor do campo 'duration'
        // que foi preenchido em extractOfferData.
        // Log.d(TAG, "--- Chamando calculateTotalTimeMinutes ---") // Log menos verboso
        val totalTime = this.duration.toIntOrNull() ?: 0
        // Log.d(TAG, "[CALC_TIME] Retornando valor do campo 'duration': $totalTime")
        // Log.d(TAG, "--- Final calculateTotalTimeMinutes: Retornando $totalTime ---")
        return totalTime.coerceAtLeast(0) // Garante não negativo
    }

    /** Estima o tempo total em minutos baseado nas distâncias e velocidades médias. */
    private fun estimateTimeMinutesFromDistance(): Int {
        Log.d(TAG, "[ESTIMATE_TIME] Tentando estimar tempo total a partir das distâncias...")
        // Usa o método que lê o campo 'distance' calculado
        val totalDistance = calculateTotalDistance()
        if (totalDistance <= 0.0) {
            Log.w(TAG, "[ESTIMATE_TIME] Distância total inválida ($totalDistance), não é possível estimar.")
            return 0 // Retorna 0 se não há distância
        }

        // Tenta usar as distâncias parciais EXTRAÍDAS
        var pickupDist = pickupDistance.replace(",", ".").toDoubleOrNull() ?: 0.0
        var tripDist = tripDistance.replace(",", ".").toDoubleOrNull() ?: 0.0
        if (pickupDist < 0) pickupDist = 0.0
        if (tripDist < 0) tripDist = 0.0

        // Se não temos distâncias parciais válidas, usa a total dividida heuristicamente
        if (pickupDist <= 0.0 && tripDist <= 0.0) {
            Log.d(TAG, "[ESTIMATE_TIME] Distâncias parciais indisponíveis ou inválidas. Dividindo Dist. Total ($totalDistance km) em 30% Recolha / 70% Viagem para estimativa.")
            pickupDist = totalDistance * 0.30 // Ex: 30% do tempo para buscar
            tripDist = totalDistance * 0.70   // Ex: 70% do tempo na viagem
        } else if (pickupDist > 0.0 && tripDist <= 0.0) {
            // Se só temos pickup, assume que a viagem é zero para este cálculo de estimativa
            Log.d(TAG, "[ESTIMATE_TIME] Apenas PDist ($pickupDist km) disponível para estimativa. TDist considerada 0.")
            tripDist = 0.0
        } else if (tripDist > 0.0 && pickupDist <= 0.0) {
            // Se só temos trip, assume que a recolha é zero para este cálculo de estimativa
            Log.d(TAG, "[ESTIMATE_TIME] Apenas TDist ($tripDist km) disponível para estimativa. PDist considerada 0.")
            pickupDist = 0.0
        }
        // Se ambos pDist e tDist > 0, usa os valores extraídos diretamente

        Log.d(TAG, "[ESTIMATE_TIME] Usando para estimativa: PDist=$pickupDist km, TDist=$tripDist km")

        // Calcula tempos estimados com base nas velocidades médias
        val pickupTimeMinutes = if (pickupDist > 0) (pickupDist / AVG_PICKUP_SPEED) * 60.0 else 0.0
        val tripTimeMinutes = if (tripDist > 0) (tripDist / AVG_TRIP_SPEED) * 60.0 else 0.0

        // Soma os tempos estimados e arredonda para inteiro. Garante pelo menos 1 min se houver alguma distância.
        val estimatedTotalTime = if(pickupTimeMinutes + tripTimeMinutes > 0) {
            (pickupTimeMinutes + tripTimeMinutes).toInt().coerceAtLeast(1)
        } else {
            0 // Retorna 0 se ambos os tempos estimados forem 0
        }

        Log.d(TAG, "[ESTIMATE_TIME] Tempo total estimado final: $estimatedTotalTime min " +
                "(Recolha: ${pickupTimeMinutes.toInt()} min [${String.format(Locale.US,"%.1f",pickupDist)}km @ ${AVG_PICKUP_SPEED}km/h] + " +
                "Viagem: ${tripTimeMinutes.toInt()} min [${String.format(Locale.US,"%.1f",tripDist)}km @ ${AVG_TRIP_SPEED}km/h])")

        return estimatedTotalTime
    }


    /** Verifica se a oferta tem dados mínimos para ser considerada válida. */
    fun isValid(): Boolean {
        val hasValue = try { (value.replace(",", ".").toDoubleOrNull() ?: 0.0) > 0.0 } catch (e: Exception) { false }

        // Verifica se temos DURAÇÃO extraída OU podemos ESTIMAR tempo via distâncias parciais/totais.
        // Verifica se temos DISTÂNCIA extraída/calculada.
        // Uma oferta sem valor, OU sem distância, OU sem NENHUMA forma de obter tempo (nem extraído nem estimado) não é válida.

        // Recalcula o tempo que seria usado para €/h para verificar se é > 0
        var timeForHourlyCalc = 0
        val pDurInt = pickupDuration.toIntOrNull()
        val tDurInt = tripDuration.toIntOrNull()
        if (pDurInt != null && pDurInt >= 0 && tDurInt != null && tDurInt >= 0 && (pDurInt > 0 || tDurInt > 0)) { timeForHourlyCalc = pDurInt + tDurInt }
        else {
            val pDistDouble = pickupDistance.replace(",", ".").toDoubleOrNull()
            val tDistDouble = tripDistance.replace(",", ".").toDoubleOrNull()
            if (tDurInt != null && tDurInt > 0 && pDistDouble != null && pDistDouble > 0.0) { timeForHourlyCalc = ((pDistDouble / AVG_PICKUP_SPEED) * 60.0).toInt().coerceAtLeast(1) + tDurInt }
            else if (pDurInt != null && pDurInt > 0 && tDistDouble != null && tDistDouble > 0.0) { timeForHourlyCalc = pDurInt + ((tDistDouble / AVG_TRIP_SPEED) * 60.0).toInt().coerceAtLeast(1) }
            else { timeForHourlyCalc = estimateTimeMinutesFromDistance() }
        }
        val hasValidTimeSource = timeForHourlyCalc > 0

        // Verifica se a distância total calculada é válida
        val hasValidDistance = calculateTotalDistance() > 0.0

        val isValid = hasValue && hasValidTimeSource && hasValidDistance

        if (!isValid) {
            Log.w(TAG, "[VALID?] OfferData considerada INVÁLIDA: Valor=$value (Ok: $hasValue), DistTotal=${calculateTotalDistance()} (Ok: $hasValidDistance), TempoP/Calc=${timeForHourlyCalc} (Ok: $hasValidTimeSource)")
        } else {
            Log.d(TAG, "[VALID?] OfferData OK: Valor=$value, DistTotal=${calculateTotalDistance()}, TempoP/Calc=${timeForHourlyCalc}")
        }
        return isValid
    }
}