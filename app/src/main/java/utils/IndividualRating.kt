package com.example.smartdriver.utils

/**
 * Enumeração para a classificação INDIVIDUAL de uma métrica (€/km ou €/hora).
 */
enum class IndividualRating {
    GOOD,    // Bom
    MEDIUM,  // Médio
    POOR,    // Mau
    UNKNOWN  // Desconhecido / Impossível calcular
}