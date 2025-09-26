package com.example.smartdriver.utils

import android.os.Parcelable
import android.util.Log
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize
import java.util.Locale

// --- Funções auxiliares de correção ---
fun String?.toDoubleOrNullWithCorrection(): Double? {
    if (this.isNullOrBlank()) return null
    return try {
        val corrected = this.replace(',', '.')
            .replace('l', '1', ignoreCase = true)
            .replace('I', '1')
            .replace(Regex("[^0-9.]"), "")
        corrected.toDoubleOrNull()
    } catch (_: Exception) { null }
}
fun String?.toIntOrNullWithCorrection(): Int? {
    if (this.isNullOrBlank()) return null
    return try {
        val corrected = this.replace('l', '1', ignoreCase = true)
            .replace('I', '1')
            .replace(Regex("[^0-9]"), "")
        corrected.toIntOrNull()
    } catch (_: Exception) { null }
}
// --------------------------------------

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
    var moradaRecolha: String? = null,
    var moradaDestino: String? = null,
    var rawText: String = ""
) : Parcelable {

    companion object {
        private const val TAG = "OfferData"
        private const val MIN_VALID_EXTRACTED_DISTANCE = 0.01
        private const val MIN_VALID_EXTRACTED_DURATION = 0
        private const val MIN_VALID_VALUE = 0.01
    }

    /** €/km */
    fun calculateProfitability(): Double? {
        val totalDistanceKm = distance.toDoubleOrNullWithCorrection()
        val monetaryValue = value.toDoubleOrNullWithCorrection()
        if (totalDistanceKm == null || totalDistanceKm < MIN_VALID_EXTRACTED_DISTANCE ||
            monetaryValue == null || monetaryValue < MIN_VALID_VALUE) return null
        return try {
            val r = monetaryValue / totalDistanceKm
            if (r.isFinite()) r else null
        } catch (_: Exception) { null }
    }

    /** €/h */
    fun calculateValuePerHour(): Double? {
        val totalTimeMinutes = duration.toIntOrNullWithCorrection()
        val monetaryValue = value.toDoubleOrNullWithCorrection()
        if (totalTimeMinutes == null || totalTimeMinutes <= 0 ||
            monetaryValue == null || monetaryValue < MIN_VALID_VALUE) return null
        return try {
            val r = monetaryValue / (totalTimeMinutes / 60.0)
            if (r.isFinite()) r else null
        } catch (_: Exception) { null }
    }

    /** Atualiza distance/duration calculados a partir de pickup/trip. */
    fun updateCalculatedTotals() {
        this.distance = calculateTotalDistanceInternal()
            ?.let { String.format(Locale.US, "%.1f", it) } ?: ""
        this.duration = calculateTotalTimeMinutesInternal()?.toString() ?: ""
    }

    fun calculateTotalDistance(): Double? = calculateTotalDistanceInternal()
    fun calculateTotalTimeMinutes(): Int? = calculateTotalTimeMinutesInternal()

    private fun calculateTotalDistanceInternal(): Double? {
        val p = pickupDistance.toDoubleOrNullWithCorrection()
        val t = tripDistance.toDoubleOrNullWithCorrection()
        val pv = p != null && p >= 0.0
        val tv = t != null && t >= 0.0
        return when {
            pv && tv -> p!! + t!!
            pv -> p!!
            tv -> t!!
            else -> null
        }
    }
    private fun calculateTotalTimeMinutesInternal(): Int? {
        val p = pickupDuration.toIntOrNullWithCorrection()
        val t = tripDuration.toIntOrNullWithCorrection()
        val pv = p != null && p >= MIN_VALID_EXTRACTED_DURATION
        val tv = t != null && t >= MIN_VALID_EXTRACTED_DURATION
        return when {
            pv && tv -> p!! + t!!
            pv -> p!!
            tv -> t!!
            else -> null
        }
    }

    fun isValidForCalculations(): Boolean {
        if (distance.isBlank() || duration.isBlank()) updateCalculatedTotals()
        val hasValue = (value.toDoubleOrNullWithCorrection() ?: 0.0) >= MIN_VALID_VALUE
        val hasDist  = (distance.toDoubleOrNullWithCorrection() ?: -1.0) >= 0.0
        val hasTime  = (duration.toIntOrNullWithCorrection() ?: -1) > 0
        return hasValue && hasDist && hasTime
    }
    fun isValid(): Boolean {
        val hasValue = (value.toDoubleOrNullWithCorrection() ?: 0.0) >= MIN_VALID_VALUE
        return hasValue
    }
}
