package com.example.smartdriver.utils

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize

@Keep
@Parcelize
data class TripHistoryEntry(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val durationSeconds: Long,

    // Valor inicial registado para a oferta (detectado ou introduzido)
    val offerValue: Double?,

    // Métricas estimadas/iniciais
    val initialVph: Double?,
    val finalVph: Double?,          // € / h real calculado com base no offerValue e duração decorrida
    val initialVpk: Double?,
    val initialDistanceKm: Double?,
    val initialDurationMinutes: Int?,

    // Metadados
    val serviceType: String?,

    // Compatibilidade com histórico antigo: se não existir no JSON antigo, fica null e tratamos como GRAY
    val originalBorderRating: BorderRating = BorderRating.GRAY,

    // NOVO: valor efetivo após ajustes (gorjetas, espera, correção manual, etc.)
    // Se não estiver presente (entradas antigas), fica null — o app usa offerValue como fallback.
    val effectiveValue: Double? = null
) : Parcelable
