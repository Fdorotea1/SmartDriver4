package com.example.smartdriver.utils // <<< VERIFIQUE O PACKAGE

import android.os.Parcelable
import android.util.Log
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize
import java.util.Locale

@Keep
@Parcelize
data class OfferData(
    var value: String = "",
    var distance: String = "",        // Distância total CALCULADA
    var pickupDistance: String = "",  // Distância recolha EXTRAÍDA
    var tripDistance: String = "",    // Distância viagem EXTRAÍDA
    var duration: String = "",        // Duração total CALCULADA (minutos)
    var pickupDuration: String = "",  // Duração recolha EXTRAÍDA (minutos)
    var tripDuration: String = "",    // Duração viagem EXTRAÍDA (minutos)
    var serviceType: String = "",
    var rawText: String = ""
) : Parcelable {

    companion object {
        private const val TAG = "OfferData"
        // Constantes para validação MÍNIMA dos dados EXTRAÍDOS
        private const val MIN_VALID_EXTRACTED_DISTANCE = 0.01 // km (quase zero)
        private const val MIN_VALID_EXTRACTED_DURATION = 0   // min (permite zero se extraído)
        private const val MIN_VALID_VALUE = 0.01             // €
    }

    // --- Métodos Públicos para Cálculos de Rentabilidade (€/km, €/h) ---

    /** Calcula €/km. Retorna null se valor ou distância total forem inválidos/insuficientes. */
    fun calculateProfitability(): Double? {
        val totalDistanceKm = distance.replace(",", ".").toDoubleOrNull()
        val monetaryValue = value.replace(",", ".").toDoubleOrNull()

        // Valida se temos dados suficientes e válidos
        if (totalDistanceKm == null || totalDistanceKm < MIN_VALID_EXTRACTED_DISTANCE ||
            monetaryValue == null || monetaryValue < MIN_VALID_VALUE) {
            Log.w(TAG, "[€/km Calc] Dados inválidos/insuficientes (Valor: $monetaryValue, Dist: $totalDistanceKm). Retornando null.")
            return null
        }

        return try {
            val result = monetaryValue / totalDistanceKm
            if (result.isFinite()) result else null // Evita Infinito/NaN
        } catch (e: Exception) {
            Log.e(TAG, "[€/km Calc] Erro na divisão: ${e.message}")
            null
        }
    }

    /** Calcula €/h. Retorna null se valor ou tempo total forem inválidos/insuficientes. */
    fun calculateValuePerHour(): Double? {
        val totalTimeMinutes = duration.toIntOrNull() // Usa o total já calculado
        val monetaryValue = value.replace(",", ".").toDoubleOrNull()

        // Valida se temos dados suficientes e válidos (permite tempo 0 se foi explicitamente calculado)
        if (totalTimeMinutes == null || totalTimeMinutes < 0 || // Não pode ser negativo
            monetaryValue == null || monetaryValue < MIN_VALID_VALUE) {
            Log.w(TAG, "[€/h Calc] Dados inválidos/insuficientes (Valor: $monetaryValue, Tempo: $totalTimeMinutes). Retornando null.")
            return null
        }

        // Evita divisão por zero se o tempo for exatamente 0
        if (totalTimeMinutes == 0) {
            Log.w(TAG, "[€/h Calc] Tempo total é zero, não é possível calcular €/h. Retornando null.")
            return null
        }

        return try {
            val totalTimeHours = totalTimeMinutes / 60.0
            val result = monetaryValue / totalTimeHours
            if (result.isFinite()) result else null // Evita Infinito/NaN
        } catch (e: Exception) {
            Log.e(TAG, "[€/h Calc] Erro na divisão: ${e.message}")
            null
        }
    }

    // --- Métodos para Calcular e Atualizar Totais Internamente ---

    /** Calcula e atualiza os campos 'distance' e 'duration' totais. */
    fun updateCalculatedTotals() {
        this.distance = calculateTotalDistanceInternal()?.let { String.format(Locale.US, "%.1f", it) } ?: ""
        this.duration = calculateTotalTimeMinutesInternal()?.toString() ?: ""
        Log.d(TAG, "updateCalculatedTotals: Dist='${this.distance}', Dur='${this.duration}'")
    }

    /** Retorna a distância total calculada ou null se impossível. */
    fun calculateTotalDistance(): Double? = calculateTotalDistanceInternal()

    /** Retorna o tempo total calculado em minutos ou null se impossível. */
    fun calculateTotalTimeMinutes(): Int? = calculateTotalTimeMinutesInternal()


    // --- Métodos INTERNOS de Cálculo (Retornam null se dados insuficientes) ---

    /** Calcula Distância Total. Retorna null se ambas as partes forem inválidas. */
    private fun calculateTotalDistanceInternal(): Double? {
        val pDist = pickupDistance.replace(",", ".").toDoubleOrNull()
        val tDist = tripDistance.replace(",", ".").toDoubleOrNull()

        val pDistValid = pDist != null && pDist >= MIN_VALID_EXTRACTED_DISTANCE
        val tDistValid = tDist != null && tDist >= MIN_VALID_EXTRACTED_DISTANCE

        return when {
            pDistValid && tDistValid -> pDist!! + tDist!! // Ambas válidas
            pDistValid -> pDist!! // Apenas recolha válida
            tDistValid -> tDist!! // Apenas viagem válida
            else -> null // Nenhuma parte válida
        }
    }

    /** Calcula Tempo Total. Retorna null se ambas as partes forem inválidas. */
    private fun calculateTotalTimeMinutesInternal(): Int? {
        val pDur = pickupDuration.toIntOrNull()
        val tDur = tripDuration.toIntOrNull()

        // Considera 0 minutos como válido se foi explicitamente extraído
        val pDurValid = pDur != null && pDur >= MIN_VALID_EXTRACTED_DURATION
        val tDurValid = tDur != null && tDur >= MIN_VALID_EXTRACTED_DURATION

        return when {
            pDurValid && tDurValid -> pDur!! + tDur!! // Ambas válidas
            pDurValid -> pDur!! // Apenas recolha válida
            tDurValid -> tDur!! // Apenas viagem válida
            else -> null // Nenhuma parte válida
        }
        // REMOVIDA a lógica de estimativa. Se uma parte faltar, o total será baseado apenas na parte existente,
        // ou será null se ambas faltarem. Isso evita cálculos baseados em estimativas imprecisas.
    }


    /** Verifica se a oferta tem dados mínimos para ser considerada válida PARA CÁLCULOS. */
    fun isValidForCalculations(): Boolean {
        // Recalcula os totais caso não tenham sido atualizados
        if (distance.isBlank() || duration.isBlank()) {
            updateCalculatedTotals()
        }
        val hasValidValue = (value.replace(",", ".").toDoubleOrNull() ?: 0.0) >= MIN_VALID_VALUE
        // Verifica se os totais calculados são válidos (não vazios/nulos)
        val hasValidDistance = distance.isNotBlank()
        val hasValidTime = duration.isNotBlank() && duration != "0" // Precisa de tempo > 0 para €/h

        val isValid = hasValidValue && hasValidDistance && hasValidTime

        if (!isValid) {
            Log.w(TAG, "[VALID_CALC?] OfferData INVÁLIDA para cálculos: ValorOK=$hasValidValue, DistOK=$hasValidDistance ('${distance}'), TempoOK=$hasValidTime ('${duration}')")
        } else {
            Log.d(TAG, "[VALID_CALC?] OfferData VÁLIDA para cálculos.")
        }
        return isValid
    }

    /** Verificação básica se a oferta parece ter sido extraída minimamente (tem valor). */
    fun isValid(): Boolean {
        val hasValue = try { (value.replace(",", ".").toDoubleOrNull() ?: 0.0) >= MIN_VALID_VALUE } catch (e: Exception) { false }
        // Poderia adicionar outras verificações básicas se necessário
        return hasValue
    }
}