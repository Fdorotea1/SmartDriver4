package com.example.smartdriver.utils

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize

/**
 * Data class para encapsular o resultado completo da avaliação de uma oferta,
 * incluindo as classificações individuais e a classificação combinada da borda.
 *
 * Implementa Parcelable para poder ser passado entre componentes (Activity/Service).
 */
@Keep // Garante que o Proguard/R8 não ofusque nomes necessários para Parcelize
@Parcelize
data class EvaluationResult(
    val kmRating: IndividualRating,     // Classificação do €/km
    val hourRating: IndividualRating,   // Classificação do €/hora
    val combinedBorderRating: BorderRating // Classificação combinada para a cor da borda
) : Parcelable