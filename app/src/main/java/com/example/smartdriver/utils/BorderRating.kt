package com.example.smartdriver.utils

/**
 * Enumeração para a classificação COMBINADA que define a cor da borda do overlay.
 */
enum class BorderRating {
    GREEN,   // Borda Verde (Ambos Bons)
    YELLOW,  // Borda Amarela (Combinações Mistas ou Médias)
    RED,     // Borda Vermelha (Ambos Maus)
    GRAY     // Borda Cinza (Algum valor Desconhecido)
}