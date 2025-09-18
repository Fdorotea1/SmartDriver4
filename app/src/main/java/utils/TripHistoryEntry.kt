package com.example.smartdriver.utils

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize
import java.util.UUID

/**
 * Extensão do modelo para permitir associar screenshots ao histórico.
 * - tripId: identificador estável da viagem (gera UUID por omissão para compatibilidade com JSON antigo).
 * - screenshotPaths: lista de caminhos absolutos (armazenados em storage privado da app).
 */
@Keep
@Parcelize
data class TripHistoryEntry(
    // NOVO: identificador único da viagem (usado para indexar screenshots)
    val tripId: String = UUID.randomUUID().toString(),

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
    val effectiveValue: Double? = null,

    // NOVO: caminhos das screenshots associadas a esta viagem (pode ficar vazio nas entradas antigas)
    val screenshotPaths: List<String> = emptyList()
) : Parcelable
