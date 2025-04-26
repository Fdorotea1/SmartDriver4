package com.example.smartdriver.utils // Ou o teu pacote utils

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize

@Keep
@Parcelize
data class TripHistoryEntry(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val durationSeconds: Long,
    val offerValue: Double?,
    val initialVph: Double?,
    val finalVph: Double?,
    val initialVpk: Double?,
    val initialDistanceKm: Double?,
    val initialDurationMinutes: Int?,
    val serviceType: String?,
    // --- NOVO CAMPO ---
    val originalBorderRating: BorderRating = BorderRating.GRAY // Default para GRAY se não for salvo (compatibilidade)
) : Parcelable