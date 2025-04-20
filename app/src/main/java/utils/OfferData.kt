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
        private const val MIN_VALID_DISTANCE = 0.05 // Distância mínima em km para ser considerada válida
        private const val MIN_VALID_DURATION = 1    // Duração mínima em minutos para ser considerada válida
        private const val MIN_VALID_VALUE = 0.01    // Valor mínimo em € para ser considerado válido
    }

    // --- Métodos de Cálculo COM VALIDAÇÃO ---

    /**
     * Calcula o valor por km. Retorna null se valor ou distância forem inválidos.
     */
    fun calculateProfitability(): Double? {
        try {
            val totalDistance = this.distance.replace(",", ".").toDoubleOrNull() ?: 0.0
            val monetaryValue = value.replace(",", ".").toDoubleOrNull() ?: 0.0

            // *** VALIDAÇÃO ADICIONADA ***
            if (totalDistance < MIN_VALID_DISTANCE) {
                Log.w(TAG, "[PROFIT_VALIDATION] Distância total ($totalDistance km) abaixo do mínimo ($MIN_VALID_DISTANCE km). €/km inválido.")
                return null
            }
            if (monetaryValue < MIN_VALID_VALUE) {
                Log.w(TAG, "[PROFIT_VALIDATION] Valor monetário ($monetaryValue €) abaixo do mínimo ($MIN_VALID_VALUE €). €/km inválido.")
                return null
            }
            // *** FIM VALIDAÇÃO ***

            val valuePerKm = monetaryValue / totalDistance
            Log.d(TAG, "[PROFIT] Valor por km calculado: ${String.format(Locale.US,"%.2f",valuePerKm)} (€$monetaryValue / $totalDistance km)")
            return if (valuePerKm.isFinite()) valuePerKm else null // Retorna null se divisão resultar em Infinito/NaN
        } catch (e: NumberFormatException) { Log.e(TAG, "[PROFIT] Erro NFE ao calcular €/km: ${e.message}"); return null }
        catch (e: Exception) { Log.e(TAG, "[PROFIT] Erro GEN ao calcular €/km: ${e.message}"); return null }
    }

    /**
     * Calcula o valor por hora. Retorna null se valor ou tempo total forem inválidos.
     * Prioriza tempos extraídos, estima tempos parciais faltantes usando distâncias parciais,
     * e como último recurso, estima o tempo total a partir da distância total.
     */
    fun calculateValuePerHour(): Double? {
        try {
            val monetaryValue = value.replace(",", ".").toDoubleOrNull() ?: 0.0

            // *** VALIDAÇÃO ADICIONADA (Valor) ***
            if (monetaryValue < MIN_VALID_VALUE) {
                Log.w(TAG, "[HOURLY_VALIDATION] Valor monetário ($monetaryValue €) abaixo do mínimo ($MIN_VALID_VALUE €). €/h inválido.")
                return null
            }
            // *** FIM VALIDAÇÃO (Valor) ***

            val totalTimeMinutes = calculateTotalTimeMinutesInternal() // Usa método interno para obter tempo
            var timeSource = lastTimeSource // Obtem a fonte do cálculo interno

            // *** VALIDAÇÃO ADICIONADA (Tempo) ***
            if (totalTimeMinutes < MIN_VALID_DURATION) {
                Log.w(TAG, "[HOURLY_VALIDATION] Tempo total final ($totalTimeMinutes min, Fonte: $timeSource) abaixo do mínimo ($MIN_VALID_DURATION min). €/h inválido.")
                return null
            }
            // *** FIM VALIDAÇÃO (Tempo) ***

            Log.d(TAG, "[HOURLY] Usando Tempo Total = $totalTimeMinutes min (Fonte: $timeSource)")

            val totalTimeHours = totalTimeMinutes / 60.0
            if (totalTimeHours == 0.0) { // Segurança adicional
                Log.e(TAG, "[HOURLY] Tempo total em horas resultou em zero após divisão ($totalTimeMinutes min).")
                return null
            }

            val valuePerHour = monetaryValue / totalTimeHours
            Log.d(TAG, "[HOURLY] Valor por hora calculado: ${String.format(Locale.US,"%.2f",valuePerHour)} (€${String.format(Locale.US,"%.2f",monetaryValue)} / ${String.format(Locale.US,"%.2f",totalTimeHours)} h)")

            return if (valuePerHour.isFinite()) valuePerHour else null // Retorna null se Infinito/NaN

        } catch (e: NumberFormatException) {
            Log.e(TAG, "[HOURLY] Erro NFE ao calcular €/h: ${e.message}", e); return null
        } catch (e: Exception) {
            Log.e(TAG, "[HOURLY] Erro GEN ao calcular €/h: ${e.message}", e); return null
        }
    }

    // Variável para guardar a fonte do último cálculo de tempo
    @kotlin.jvm.Transient // Evita que seja parcelado
    private var lastTimeSource : String = "Não Definido"

    /** Método INTERNO para calcular o tempo total, guardando a fonte. */
    private fun calculateTotalTimeMinutesInternal(): Int {
        var totalTimeMinutes = 0
        lastTimeSource = "Não Definido" // Reseta a fonte

        // Tenta converter tempos e distâncias parciais extraídos
        val pDurInt = pickupDuration.toIntOrNull()
        val tDurInt = tripDuration.toIntOrNull()
        val pDistDouble = pickupDistance.replace(",", ".").toDoubleOrNull()
        val tDistDouble = tripDistance.replace(",", ".").toDoubleOrNull()

        // Caso 1: Ambos os tempos extraídos válidos
        if (pDurInt != null && pDurInt >= 0 && tDurInt != null && tDurInt >= 0 && (pDurInt > 0 || tDurInt > 0)) {
            totalTimeMinutes = pDurInt + tDurInt
            lastTimeSource = "Soma Direta (P:$pDurInt + T:$tDurInt)"
        }
        // Caso 2: Apenas tempo VIAGEM lido, estimar RECOLHA pela DISTÂNCIA
        else if (tDurInt != null && tDurInt >= MIN_VALID_DURATION && (pDurInt == null || pDurInt < 0) && pDistDouble != null && pDistDouble >= MIN_VALID_DISTANCE) {
            val estimatedPickupTime = ((pDistDouble / AVG_PICKUP_SPEED) * 60.0).toInt().coerceAtLeast(MIN_VALID_DURATION)
            totalTimeMinutes = estimatedPickupTime + tDurInt
            lastTimeSource = "Parcial (Est. P:$estimatedPickupTime [Dist:$pDistDouble km] + Lida T:$tDurInt)"
            Log.d(TAG, "[INTERNAL_TIME] Estimando recolha: $estimatedPickupTime min a partir de $pDistDouble km.")
        }
        // Caso 3: Apenas tempo RECOLHA lido, estimar VIAGEM pela DISTÂNCIA
        else if (pDurInt != null && pDurInt >= MIN_VALID_DURATION && (tDurInt == null || tDurInt < 0) && tDistDouble != null && tDistDouble >= MIN_VALID_DISTANCE) {
            val estimatedTripTime = ((tDistDouble / AVG_TRIP_SPEED) * 60.0).toInt().coerceAtLeast(MIN_VALID_DURATION)
            totalTimeMinutes = pDurInt + estimatedTripTime
            lastTimeSource = "Parcial (Lida P:$pDurInt + Est. T:$estimatedTripTime [Dist:$tDistDouble km])"
            Log.d(TAG, "[INTERNAL_TIME] Estimando viagem: $estimatedTripTime min a partir de $tDistDouble km.")
        }
        // Caso 4: Estimar TOTAL pela distância total
        else {
            Log.w(TAG, "[INTERNAL_TIME] Tempos parciais inválidos (P:$pDurInt, T:$tDurInt). Tentando estimativa total.")
            totalTimeMinutes = estimateTimeMinutesFromDistance() // Usa a função que já considera distância total
            if (totalTimeMinutes >= MIN_VALID_DURATION) {
                lastTimeSource = "Estimado Total (baseado na Dist. Total)"
            } else {
                lastTimeSource = "Falha (Tempos e Estimativa Total Inválidos)"
            }
        }
        return totalTimeMinutes.coerceAtLeast(0) // Garante não negativo
    }


    /** Retorna a distância total calculada (campo 'distance') como Double. */
    fun calculateTotalDistance(): Double {
        val totalDist = this.distance.replace(",", ".").toDoubleOrNull() ?: 0.0
        return totalDist.coerceAtLeast(0.0) // Garante não negativo
    }

    /** Retorna a duração total calculada (campo 'duration') como Int. */
    fun calculateTotalTimeMinutes(): Int {
        // Chama o método interno que também define a 'lastTimeSource'
        return calculateTotalTimeMinutesInternal()
    }

    /** Estima o tempo total em minutos baseado nas distâncias e velocidades médias. */
    private fun estimateTimeMinutesFromDistance(): Int {
        Log.d(TAG, "[ESTIMATE_TIME] Tentando estimar tempo total a partir das distâncias...")
        val totalDistance = calculateTotalDistance()
        if (totalDistance < MIN_VALID_DISTANCE) { // Usa constante
            Log.w(TAG, "[ESTIMATE_TIME] Distância total inválida ($totalDistance), não é possível estimar.")
            return 0
        }

        var pickupDist = pickupDistance.replace(",", ".").toDoubleOrNull() ?: 0.0
        var tripDist = tripDistance.replace(",", ".").toDoubleOrNull() ?: 0.0
        if (pickupDist < 0) pickupDist = 0.0
        if (tripDist < 0) tripDist = 0.0

        // Lógica de estimativa mantida...
        if (pickupDist < MIN_VALID_DISTANCE && tripDist < MIN_VALID_DISTANCE) {
            Log.d(TAG, "[ESTIMATE_TIME] Distâncias parciais indisponíveis/inválidas. Dividindo Dist. Total ($totalDistance km) em 30% Recolha / 70% Viagem.")
            pickupDist = totalDistance * 0.30
            tripDist = totalDistance * 0.70
        } else if (pickupDist >= MIN_VALID_DISTANCE && tripDist < MIN_VALID_DISTANCE) {
            Log.d(TAG, "[ESTIMATE_TIME] Apenas PDist ($pickupDist km) disponível. TDist=0.")
            tripDist = 0.0
        } else if (tripDist >= MIN_VALID_DISTANCE && pickupDist < MIN_VALID_DISTANCE) {
            Log.d(TAG, "[ESTIMATE_TIME] Apenas TDist ($tripDist km) disponível. PDist=0.")
            pickupDist = 0.0
        }

        Log.d(TAG, "[ESTIMATE_TIME] Usando para estimativa: PDist=$pickupDist km, TDist=$tripDist km")

        val pickupTimeMinutes = if (pickupDist >= MIN_VALID_DISTANCE) (pickupDist / AVG_PICKUP_SPEED) * 60.0 else 0.0
        val tripTimeMinutes = if (tripDist >= MIN_VALID_DISTANCE) (tripDist / AVG_TRIP_SPEED) * 60.0 else 0.0

        // Garante pelo menos MIN_VALID_DURATION se houver alguma distância
        val estimatedTotalTime = if(pickupTimeMinutes + tripTimeMinutes > 0) {
            (pickupTimeMinutes + tripTimeMinutes).toInt().coerceAtLeast(MIN_VALID_DURATION)
        } else {
            0
        }

        Log.d(TAG, "[ESTIMATE_TIME] Tempo total estimado final: $estimatedTotalTime min " +
                "(P: ${pickupTimeMinutes.toInt()} min + T: ${tripTimeMinutes.toInt()} min)")

        return estimatedTotalTime
    }


    /** Verifica se a oferta tem dados mínimos para ser considerada válida PARA CÁLCULOS. */
    fun isValidForCalculations(): Boolean {
        val hasValidValue = (value.replace(",", ".").toDoubleOrNull() ?: 0.0) >= MIN_VALID_VALUE
        val hasValidDistance = calculateTotalDistance() >= MIN_VALID_DISTANCE
        val hasValidTime = calculateTotalTimeMinutesInternal() >= MIN_VALID_DURATION // Usa interno

        val isValid = hasValidValue && hasValidDistance && hasValidTime

        if (!isValid) {
            Log.w(TAG, "[VALID_CALC?] OfferData INVÁLIDA para cálculos: Valor OK=$hasValidValue, Dist OK=$hasValidDistance, Tempo OK=$hasValidTime")
        }
        return isValid
    }

    // Mantém a função isValid original para compatibilidade, se necessário,
    // mas a validação real agora está nos métodos de cálculo.
    // Se preferir, pode remover esta ou fazer ela chamar isValidForCalculations().
    fun isValid(): Boolean {
        // Poderia simplesmente chamar a outra: return isValidForCalculations()
        // Ou manter uma lógica mais simples baseada só na presença de valor
        val hasValue = try { (value.replace(",", ".").toDoubleOrNull() ?: 0.0) > 0.0 } catch (e: Exception) { false }
        return hasValue // Exemplo: considerar válido se tiver algum valor
    }
}