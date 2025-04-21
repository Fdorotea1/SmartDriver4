package com.example.smartdriver.utils // Ou o teu pacote utils

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize

@Keep // Ajuda a evitar problemas com Proguard/R8
@Parcelize // Permite passar entre Activities, se necessário no futuro
data class TripHistoryEntry(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val durationSeconds: Long, // Duração real calculada
    val offerValue: Double?, // Valor original em €
    val initialVph: Double?, // €/Hora inicial estimado
    val finalVph: Double?,   // €/Hora final real calculado
    val initialVpk: Double?, // €/Km inicial estimado
    val initialDistanceKm: Double?, // Distância total inicial estimada
    val initialDurationMinutes: Int?, // Duração total inicial estimada
    val serviceType: String? // Tipo de serviço (ex: UberX)
) : Parcelable