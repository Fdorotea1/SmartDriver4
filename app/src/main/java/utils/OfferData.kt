package com.example.smartdriver.utils // <<< VERIFIQUE O PACKAGE

import android.os.Parcelable
import android.util.Log
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize
import java.util.Locale

// --- FUNÇÕES AUXILIARES DE CORREÇÃO ---
/** Tenta converter String para Double, corrigindo ',' para '.' e 'l'/'I' para '1'. */
fun String?.toDoubleOrNullWithCorrection(): Double? {
    if (this.isNullOrBlank()) return null
    try {
        val corrected = this.replace(',', '.')
            .replace('l', '1', ignoreCase = true) // l ou L -> 1
            .replace('I', '1')                   // I -> 1
            // Adicionar mais correções se necessário (ex: O -> 0)
            // .replace('O', '0', ignoreCase = true)
            .replace(Regex("[^0-9.]"), "") // Remove caracteres não numéricos exceto ponto
        // Log.v("CorrectionUtil", "toDouble: Original='$this', Corrigido='$corrected'") // Log para depuração
        return corrected.toDoubleOrNull()
    } catch (e: Exception) {
        Log.e("CorrectionUtil", "Erro em toDoubleOrNullWithCorrection para '$this': ${e.message}")
        return null
    }
}

/** Tenta converter String para Int, corrigindo 'l'/'I' para '1'. */
fun String?.toIntOrNullWithCorrection(): Int? {
    if (this.isNullOrBlank()) return null
    try {
        val corrected = this.replace('l', '1', ignoreCase = true) // l ou L -> 1
            .replace('I', '1')                   // I -> 1
            // Adicionar mais correções se necessário (ex: O -> 0)
            // .replace('O', '0', ignoreCase = true)
            .replace(Regex("[^0-9]"), "") // Remove caracteres não numéricos
        // Log.v("CorrectionUtil", "toInt: Original='$this', Corrigido='$corrected'") // Log para depuração
        return corrected.toIntOrNull()
    } catch (e: Exception) {
        Log.e("CorrectionUtil", "Erro em toIntOrNullWithCorrection para '$this': ${e.message}")
        return null
    }
}
// --- FIM DAS FUNÇÕES AUXILIARES ---


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
        private const val MIN_VALID_EXTRACTED_DISTANCE = 0.01
        private const val MIN_VALID_EXTRACTED_DURATION = 0
        private const val MIN_VALID_VALUE = 0.01
    }

    // --- Métodos Públicos para Cálculos de Rentabilidade (€/km, €/h) ---

    /** Calcula €/km. Retorna null se valor ou distância total forem inválidos/insuficientes. */
    fun calculateProfitability(): Double? {
        // USA A FUNÇÃO COM CORREÇÃO
        val totalDistanceKm = distance.toDoubleOrNullWithCorrection()
        val monetaryValue = value.toDoubleOrNullWithCorrection()

        if (totalDistanceKm == null || totalDistanceKm < MIN_VALID_EXTRACTED_DISTANCE ||
            monetaryValue == null || monetaryValue < MIN_VALID_VALUE) {
            Log.w(TAG, "[€/km Calc] Dados inválidos/insuficientes (Valor: $monetaryValue, Dist: $totalDistanceKm). Retornando null.")
            return null
        }

        return try {
            val result = monetaryValue / totalDistanceKm
            if (result.isFinite()) result else { Log.w(TAG,"[€/km Calc] Resultado não finito: $result"); null }
        } catch (e: Exception) {
            Log.e(TAG, "[€/km Calc] Erro na divisão: ${e.message}")
            null
        }
    }

    /** Calcula €/h. Retorna null se valor ou tempo total forem inválidos/insuficientes. */
    fun calculateValuePerHour(): Double? {
        // USA A FUNÇÃO COM CORREÇÃO
        val totalTimeMinutes = duration.toIntOrNullWithCorrection()
        val monetaryValue = value.toDoubleOrNullWithCorrection()

        if (totalTimeMinutes == null || totalTimeMinutes < 0 ||
            monetaryValue == null || monetaryValue < MIN_VALID_VALUE) {
            Log.w(TAG, "[€/h Calc] Dados inválidos/insuficientes (Valor: $monetaryValue, Tempo: $totalTimeMinutes). Retornando null.")
            return null
        }
        if (totalTimeMinutes == 0) {
            Log.w(TAG, "[€/h Calc] Tempo total é zero, não é possível calcular €/h. Retornando null.")
            return null
        }

        return try {
            val totalTimeHours = totalTimeMinutes / 60.0
            val result = monetaryValue / totalTimeHours
            if (result.isFinite()) result else { Log.w(TAG,"[€/h Calc] Resultado não finito: $result"); null }
        } catch (e: Exception) {
            Log.e(TAG, "[€/h Calc] Erro na divisão: ${e.message}")
            null
        }
    }

    // --- Métodos para Calcular e Atualizar Totais Internamente ---

    /** Calcula e atualiza os campos 'distance' e 'duration' totais. */
    fun updateCalculatedTotals() {
        // Os métodos internos agora usam a correção
        this.distance = calculateTotalDistanceInternal()?.let { String.format(Locale.US, "%.1f", it) } ?: ""
        this.duration = calculateTotalTimeMinutesInternal()?.toString() ?: ""
        // Log.d(TAG, "updateCalculatedTotals: Dist='${this.distance}', Dur='${this.duration}'")
    }

    /** Retorna a distância total calculada ou null se impossível. */
    fun calculateTotalDistance(): Double? = calculateTotalDistanceInternal()

    /** Retorna o tempo total calculado em minutos ou null se impossível. */
    fun calculateTotalTimeMinutes(): Int? = calculateTotalTimeMinutesInternal()


    // --- Métodos INTERNOS de Cálculo (Usam funções com correção) ---

    /** Calcula Distância Total. Retorna null se ambas as partes forem inválidas. */
    private fun calculateTotalDistanceInternal(): Double? {
        // USA A FUNÇÃO COM CORREÇÃO
        val pDist = pickupDistance.toDoubleOrNullWithCorrection()
        val tDist = tripDistance.toDoubleOrNullWithCorrection()

        val pDistValid = pDist != null && pDist >= 0.0 // Permite 0.0 aqui? Talvez mínimo > 0
        val tDistValid = tDist != null && tDist >= 0.0

        return when {
            pDistValid && tDistValid -> pDist!! + tDist!! // Ambas válidas
            pDistValid -> pDist!! // Apenas recolha válida
            tDistValid -> tDist!! // Apenas viagem válida
            else -> null // Nenhuma parte válida
        }
    }

    /** Calcula Tempo Total. Retorna null se ambas as partes forem inválidas. */
    private fun calculateTotalTimeMinutesInternal(): Int? {
        // USA A FUNÇÃO COM CORREÇÃO
        val pDur = pickupDuration.toIntOrNullWithCorrection()
        val tDur = tripDuration.toIntOrNullWithCorrection()

        val pDurValid = pDur != null && pDur >= MIN_VALID_EXTRACTED_DURATION
        val tDurValid = tDur != null && tDur >= MIN_VALID_EXTRACTED_DURATION

        return when {
            pDurValid && tDurValid -> pDur!! + tDur!! // Ambas válidas
            pDurValid -> pDur!! // Apenas recolha válida
            tDurValid -> tDur!! // Apenas viagem válida
            else -> null // Nenhuma parte válida
        }
    }

    /** Verifica se a oferta tem dados mínimos para ser considerada válida PARA CÁLCULOS. */
    fun isValidForCalculations(): Boolean {
        if (distance.isBlank() || duration.isBlank()) {
            updateCalculatedTotals()
        }
        val hasValidValue = (value.toDoubleOrNullWithCorrection() ?: 0.0) >= MIN_VALID_VALUE
        val hasValidDistance = (distance.toDoubleOrNullWithCorrection() ?: -1.0) >= 0.0 // Dist pode ser 0
        val hasValidTime = (duration.toIntOrNullWithCorrection() ?: -1) > 0 // Tempo tem que ser > 0 para €/h

        val isValid = hasValidValue && hasValidDistance && hasValidTime
        // Log removido para não poluir
        return isValid
    }

    /** Verificação básica se a oferta parece ter sido extraída minimamente (tem valor). */
    fun isValid(): Boolean {
        val hasValue = (value.toDoubleOrNullWithCorrection() ?: 0.0) >= MIN_VALID_VALUE
        return hasValue
    }
}