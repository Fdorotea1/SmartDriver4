package com.example.smartdriver.utils

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize
import java.util.UUID

/**
 * Modelo de histórico de viagem.
 * - tripId: identificador estável da viagem (gera UUID por omissão para compat com JSON antigo).
 * - screenshotPaths: caminhos absolutos das screenshots associadas (armazenadas no storage privado da app).
 * - pickupAddress / dropoffAddress: moradas (se captadas), opcionais para manter compatibilidade.
 */
@Keep
@Parcelize
data class TripHistoryEntry(
    // Identificador único da viagem (usado para indexar screenshots)
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

    // NOVO: moradas (opcional para compatibilidade com histórico antigo)
    val pickupAddress: String? = null,
    val dropoffAddress: String? = null,

    // Compatibilidade com histórico antigo: se não existir no JSON antigo, fica GRAY
    val originalBorderRating: BorderRating = BorderRating.GRAY,

    // Valor efetivo após ajustes (gorjetas, espera, correção manual, etc.)
    // Se não estiver presente (entradas antigas), fica null — o app usa offerValue como fallback.
    val effectiveValue: Double? = null,

    // Caminhos das screenshots associadas a esta viagem (pode ficar vazio nas entradas antigas)
    val screenshotPaths: List<String> = emptyList()
) : Parcelable
