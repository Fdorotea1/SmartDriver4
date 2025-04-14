package com.example.smartdriver.utils

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import java.util.Locale // Import necessário
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * Utilitário para análise de texto extraído de imagens (OCR),
 * focado na detecção e extração de dados de ofertas da Uber.
 * Usa uma regex genérica (versão mais tolerante) para encontrar segmentos de tempo/distância
 * e lógica de pontuação para extrair o valor monetário.
 */
class ImageAnalysisUtils {

    companion object {
        private const val TAG = "ImageAnalysisUtils"

        // Regex
        private val MONEY_PATTERN = Pattern.compile("(?:€\\s*?)?(\\d+[,.]\\d{1,2})(?:\\s*€)?|(\\d+)\\s*€")
        // PATTERN_GENERIC_TIME_DIST: Versão mais tolerante a espaços e km inteiro
        private val PATTERN_GENERIC_TIME_DIST = Pattern.compile("(\\d+)\\s+min\\s*\\(\\s*(\\d+(?:[,.]\\d+)?)\\s*km\\s*\\)", Pattern.CASE_INSENSITIVE)

        // Keywords
        private val ESSENTIAL_OFFER_KEYWORDS = listOf(
            "aceitar", "confirmar", "nova viagem", "novo pedido", "corresponder",
            "€",
            "ganhe até", "valor estimado", "tarifa", "total", "após dedução"
        )
        private val SUPPORTING_OFFER_KEYWORDS = listOf(
            "km", "min", "distância", "tempo", "duração",
            "viagem", "trajeto", "corrida", "pedido", "entrega",
            "recolha", "apanhar", "destino", "localização",
            "uberx", "comfort", "black", "green", "xl", "business",
            "passageiro", "cliente"
        )
        private val SERVICE_TYPES = listOf(
            "UberX", "Comfort", "Black", "Green", "XL", "Pet", "WAV", "Assist", "Pool", "Flash", "Taxi", "Business Comfort", "UberGreen"
        )
    }

    /** Analisa o texto extraído para identificar e extrair dados de uma oferta. */
    fun analyzeTextForOffer(extractedText: String): OfferData? {
        val normalizedText = extractedText.lowercase()
            .replace("\\s+".toRegex(), " ")
            .trim()
        Log.d(TAG, "Analisando texto normalizado (início): ${normalizedText.take(500)}...")

        val hasEuro = normalizedText.contains("€")
        val hasKm = normalizedText.contains("km")
        val hasMin = normalizedText.contains("min")
        val hasNumber = Pattern.compile("\\d").matcher(normalizedText).find()

        if (!hasNumber || !(hasEuro || (hasKm && hasMin))) {
            Log.d(TAG, "Texto não parece ter os requisitos numéricos/unidades mínimos.")
            return null
        }

        val essentialKeywordsFound = ESSENTIAL_OFFER_KEYWORDS.count { keyword ->
            val pattern = Pattern.compile("\\b${Pattern.quote(keyword)}\\b")
            pattern.matcher(normalizedText).find()
        }
        val hasEuroSymbolOnly = essentialKeywordsFound == 1 && hasEuro && !ESSENTIAL_OFFER_KEYWORDS.any{ it != "€" && normalizedText.contains(it)}

        if (essentialKeywordsFound == 0 || (hasEuroSymbolOnly && !hasKm && !hasMin)) {
            Log.d(TAG, "Keywords essenciais insuficientes (encontradas: $essentialKeywordsFound, só Euro: $hasEuroSymbolOnly, km: $hasKm, min: $hasMin).")
            return null
        }

        val supportingKeywordsFound = SUPPORTING_OFFER_KEYWORDS.count { keyword ->
            val pattern = Pattern.compile("\\b${Pattern.quote(keyword)}\\b")
            pattern.matcher(normalizedText).find()
        }
        Log.d(TAG, "Keywords encontradas: Essenciais=$essentialKeywordsFound, Suporte=$supportingKeywordsFound")

        val timeDistMatcherCheck = PATTERN_GENERIC_TIME_DIST.matcher(normalizedText)
        val hasPotentialTimeDist = timeDistMatcherCheck.find()
        timeDistMatcherCheck.reset()

        val meetsCriteria = (essentialKeywordsFound >= 1 || (hasEuro && (hasKm || hasMin))) &&
                (supportingKeywordsFound >= 1 || hasPotentialTimeDist)

        if (meetsCriteria) {
            Log.i(TAG, ">>> OFERTA POTENCIAL IDENTIFICADA! <<< Iniciando extração de dados.")
            // Log.d(TAG, "Texto Completo Identificado:\n$extractedText") // Descomentar se necessário
            return extractOfferData(normalizedText, extractedText)
        } else {
            Log.d(TAG, "Critérios de confiança não atingidos (essenciais: $essentialKeywordsFound, suporte: $supportingKeywordsFound, tempo/dist: $hasPotentialTimeDist).")
            return null
        }
    }

    /** Extrai os dados relevantes usando regex e lógica de pontuação para o valor. */
    private fun extractOfferData(normalizedText: String, originalText: String): OfferData {
        var value = ""
        var totalDistance = "" // Será preenchido pela soma
        var pickupDistance = ""
        var tripDistance = ""
        var duration = "" // Será preenchido pela soma
        var pickupDuration = ""
        var tripDuration = ""
        var serviceType = ""

        Log.d(TAG, "--- Iniciando Extração Detalhada (Regex Tolerante) ---") // Nome atualizado

        // --- Extração de Valor (€) ---
        val moneyMatcher = MONEY_PATTERN.matcher(normalizedText)
        var bestMoneyMatch: String? = null
        var highestScore = -1

        while(moneyMatcher.find()) {
            val decimalPart = moneyMatcher.group(1)?.replace(",", ".")
            val integerPart = moneyMatcher.group(2)
            val currentMatchValue = decimalPart ?: integerPart
            if (currentMatchValue == null) continue

            var currentScore = 0
            if (decimalPart != null) {
                currentScore += 10
                if (decimalPart.substringAfter(".", "").length == 2) { currentScore += 5 }
            } else if (integerPart != null){ currentScore += 1 }

            val matchStart = moneyMatcher.start()
            val matchEnd = moneyMatcher.end()
            val searchRadius = 10
            val startSearchIndex = (matchStart - searchRadius).coerceAtLeast(0)
            val endSearchIndex = (matchEnd + searchRadius).coerceAtMost(normalizedText.length)
            val searchSubstring = normalizedText.substring(startSearchIndex, endSearchIndex)
            val euroFoundNearby = searchSubstring.contains('€')
            if (euroFoundNearby) { currentScore += 20 }

            Log.d(TAG, "[EXTRACT_VAL] Match encontrado: '$currentMatchValue' (Score: $currentScore, € Perto: $euroFoundNearby na substring: '$searchSubstring')")

            if (currentScore > highestScore) {
                highestScore = currentScore
                bestMoneyMatch = currentMatchValue
                Log.d(TAG, "[EXTRACT_VAL] Novo melhor match: '$bestMoneyMatch' (Score: $highestScore)")
            }
        }
        value = bestMoneyMatch?.replace(Regex("[^0-9.]"), "") ?: ""
        Log.d(TAG, "[EXTRACT] Valor Final Escolhido: $value (Baseado em: '$bestMoneyMatch', Score: $highestScore)")


        // --- Extração de Tempo e Distância (Método Genérico Duplo com Regex Tolerante) ---
        val timeDistMatcher = PATTERN_GENERIC_TIME_DIST.matcher(normalizedText) // Usa a nova regex
        val foundSegments = mutableListOf<Pair<String, String>>()

        while (timeDistMatcher.find()) {
            val dur = timeDistMatcher.group(1) // Grupo 1 é Duração
            val dist = timeDistMatcher.group(2)?.replace(",", ".") // Grupo 2 é Distância
            if (dur != null && dist != null) {
                // Log para ver o que a regex tolerante capturou
                Log.d(TAG, "[EXTRACT TOLERANT] Padrão Tempo/Dist Genérico MATCH: Dur='$dur', Dist='$dist'")
                foundSegments.add(Pair(dur, dist))
            }
        }

        Log.d(TAG, "[EXTRACT] Encontrados ${foundSegments.size} segmentos Tempo/Dist (com regex tolerante).")
        if (foundSegments.size < 2) {
            Log.w(TAG, "[EXTRACT_FAIL] Não encontrou 2 segmentos! Texto analisado:\n$normalizedText")
        }

        if (foundSegments.size >= 2) {
            pickupDuration = foundSegments[0].first
            pickupDistance = foundSegments[0].second
            tripDuration = foundSegments[1].first
            tripDistance = foundSegments[1].second
            Log.d(TAG, "[EXTRACT] Atribuído: PDur=$pickupDuration, PDist=$pickupDistance, TDur=$tripDuration, TDist=$tripDistance")
        } else if (foundSegments.size == 1) {
            tripDuration = foundSegments[0].first
            tripDistance = foundSegments[0].second
            Log.w(TAG, "[EXTRACT] Apenas UM segmento encontrado. Atribuído à Viagem (Fallback): TDur=$tripDuration, TDist=$tripDistance")
        } else {
            Log.w(TAG, "[EXTRACT] NENHUM segmento Tempo/Dist encontrado com o padrão genérico tolerante.")
        }

        // --- Cálculo dos Totais ---
        var calculatedTotalDuration = 0
        try {
            val pDur = pickupDuration.toIntOrNull() ?: 0
            val tDur = tripDuration.toIntOrNull() ?: 0
            if (pDur >= 0 && tDur >= 0 && (pDur > 0 || tDur > 0)) {
                calculatedTotalDuration = pDur + tDur
                duration = calculatedTotalDuration.toString()
                Log.d(TAG, "[EXTRACT] Duração Total Calculada: $duration min (pDur=$pDur, tDur=$tDur)")
            } else {
                Log.d(TAG, "[EXTRACT] Duração Total não calculada.")
                duration = ""
            }
        } catch (e: Exception) { Log.w(TAG, "[EXTRACT] Erro ao calcular duração total: ${e.message}"); duration = "" }

        var calculatedTotalDistance = 0.0
        try {
            val pDist = pickupDistance.toDoubleOrNull() ?: 0.0
            val tDist = tripDistance.toDoubleOrNull() ?: 0.0
            if (pDist >= 0.0 && tDist >= 0.0 && (pDist > 0.0 || tDist > 0.0)) {
                calculatedTotalDistance = pDist + tDist
                totalDistance = String.format(Locale.US, "%.1f", calculatedTotalDistance)
                Log.d(TAG, "[EXTRACT] Distância Total Calculada: $totalDistance km (pDist=$pDist, tDist=$tDist)")
            } else {
                Log.d(TAG, "[EXTRACT] Distância Total não calculada.")
                totalDistance = ""
            }
        } catch (e: Exception) { Log.w(TAG, "[EXTRACT] Erro ao calcular distância total: ${e.message}"); totalDistance = "" }

        // --- Extração do Tipo de Serviço ---
        for (type in SERVICE_TYPES) {
            val pattern = Pattern.compile("\\b${Pattern.quote(type.lowercase())}\\b")
            if (pattern.matcher(normalizedText).find()) {
                serviceType = type
                Log.d(TAG, "[EXTRACT] Tipo de Serviço encontrado: $serviceType")
                break
            }
        }
        if (serviceType.isBlank()) {
            Log.d(TAG, "[EXTRACT] Nenhum tipo de serviço conhecido encontrado.")
        }

        Log.d(TAG, "--- Fim da Extração Detalhada ---")
        return OfferData(
            value = value,
            distance = totalDistance,
            pickupDistance = pickupDistance,
            tripDistance = tripDistance,
            duration = duration,
            pickupDuration = pickupDuration,
            tripDuration = tripDuration,
            serviceType = serviceType,
            rawText = originalText.take(500)
        )
    }

    /** Determina a região de interesse (2/3 inferiores com margem). */
    fun getRegionsOfInterest(screenWidth: Int, screenHeight: Int): List<Rect> {
        val topMargin = (screenHeight * 0.05).toInt()
        val startY = (screenHeight / 3) + topMargin
        val validStartY = startY.coerceAtMost(screenHeight - 1)
        val bottomRegion = Rect(0, validStartY, screenWidth, screenHeight)
        if (bottomRegion.height() <= 0) {
            Log.e(TAG, "Região de interesse calculada tem altura inválida: $bottomRegion. Usando tela inteira como fallback.")
            return listOf(Rect(0, 0, screenWidth, screenHeight))
        }
        Log.d(TAG, "Região de Interesse definida: ${bottomRegion.flattenToString()}")
        return listOf(bottomRegion)
    }

    /** Recorta a imagem original para a região especificada. */
    fun cropToRegion(original: Bitmap, region: Rect): Bitmap? {
        val safeRegion = Rect(
            region.left.coerceIn(0, original.width),
            region.top.coerceIn(0, original.height),
            region.right.coerceIn(0, original.width),
            region.bottom.coerceIn(0, original.height)
        )
        if (safeRegion.width() <= 0 || safeRegion.height() <= 0) {
            Log.w(TAG, "Região de recorte inválida após ajuste: $safeRegion (Original: $region)")
            return null
        }
        return try {
            Bitmap.createBitmap(
                original, safeRegion.left, safeRegion.top, safeRegion.width(), safeRegion.height()
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Erro ao recortar bitmap (Argumento Ilegal): ${e.message}. Região: $safeRegion, Bitmap: ${original.width}x${original.height}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Erro genérico ao recortar bitmap: ${e.message}")
            null
        }
    }
}