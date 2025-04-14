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
 * Usa regex específicas para tempo/distância de recolha e viagem.
 */
class ImageAnalysisUtils {

    companion object {
        private const val TAG = "ImageAnalysisUtils"

        // Regex para Valor (€) - Mantida como antes
        // Procura por: [€ opcional] DDD,DD [€ opcional] OU DDD [€ obrigatório]
        private val MONEY_PATTERN = Pattern.compile("(?:€\\s*?)?(\\d+[,.]\\d{1,2})(?:\\s*€)?|(\\d+)\\s*€")

        // --- NOVAS Regex Específicas para Tempo e Distância ---
        // Regex para Recolha: Procura por "X min (Y km) de distância" (ignorando case)
        // Captura Grupo 1: minutos (\d+)
        // Captura Grupo 2: km (\d+([.,]\d+)?) - permite decimal opcional com . ou ,
        private val PICKUP_TIME_DIST_PATTERN = Pattern.compile(
            "(\\d+)\\s+min\\s*\\(\\s*(\\d+(?:[.,]\\d+)?)\\s*km\\s*\\)\\s+de\\s+distância",
            Pattern.CASE_INSENSITIVE
        )

        // Regex para Viagem: Procura por "Viagem de X min (Y km)" (ignorando case)
        // Captura Grupo 1: minutos (\d+)
        // Captura Grupo 2: km (\d+([.,]\d+)?)
        private val TRIP_TIME_DIST_PATTERN = Pattern.compile(
            "viagem\\s+de\\s+(\\d+)\\s+min\\s*\\(\\s*(\\d+(?:[.,]\\d+)?)\\s*km\\s*\\)",
            Pattern.CASE_INSENSITIVE
        )
        // ----------------------------------------------------

        // Keywords - Mantidas como antes
        private val ESSENTIAL_OFFER_KEYWORDS = listOf(
            "aceitar", "confirmar", "nova viagem", "novo pedido", "corresponder",
            "€",
            "ganhe até", "valor estimado", "tarifa", "total", "após dedução"
        )
        private val SUPPORTING_OFFER_KEYWORDS = listOf(
            "km", "min", "distância", "tempo", "duração",
            "viagem", "trajeto", "corrida", "pedido", "entrega",
            "recolha", "apanhar", "destino", "localização",
            "uberx", "comfort", "black", "green", "xl", "business", // Adicionar tipos aqui
            "passageiro", "cliente", "green · teens" // Adicionado Green · Teens
        )
        private val SERVICE_TYPES = listOf(
            "UberX", "Comfort", "Black", "Green", "XL", "Pet", "WAV", "Assist", "Pool",
            "Flash", "Taxi", "Business Comfort", "UberGreen", "Green · Teens", // Adicionado
            "Exclusivo" // Pode aparecer junto com Comfort
        )
    }

    /** Analisa o texto extraído para identificar e extrair dados de uma oferta. */
    fun analyzeTextForOffer(extractedText: String): OfferData? {
        val normalizedText = extractedText.lowercase(Locale.ROOT) // Usar Locale.ROOT para consistência
            .replace("\\s+".toRegex(), " ") // Normaliza espaços múltiplos
            .trim()

        // Log inicial (reduzido para não poluir muito)
        Log.d(TAG, "Analisando texto normalizado (len=${normalizedText.length})...")

        // Verificações rápidas de pré-requisitos (mantidas)
        val hasEuro = normalizedText.contains("€")
        val hasKm = normalizedText.contains("km")
        val hasMin = normalizedText.contains("min")
        val hasNumber = Pattern.compile("\\d").matcher(normalizedText).find()
        if (!hasNumber || !(hasEuro || (hasKm && hasMin))) {
            Log.d(TAG, "Texto não parece ter os requisitos numéricos/unidades mínimos.")
            return null
        }

        // Verificação de keywords (mantida, pode ser afinada se necessário)
        val essentialKeywordsFound = ESSENTIAL_OFFER_KEYWORDS.count { keyword ->
            // Usar word boundaries (\b) para evitar matches parciais
            val pattern = Pattern.compile("\\b${Pattern.quote(keyword)}\\b", Pattern.CASE_INSENSITIVE)
            pattern.matcher(normalizedText).find()
        }
        val hasEuroSymbolOnly = essentialKeywordsFound == 1 && hasEuro && !ESSENTIAL_OFFER_KEYWORDS.any{ it != "€" && normalizedText.contains(it)}
        if (essentialKeywordsFound == 0 || (hasEuroSymbolOnly && !hasKm && !hasMin)) {
            Log.d(TAG, "Keywords essenciais insuficientes (encontradas: $essentialKeywordsFound, só Euro: $hasEuroSymbolOnly, km: $hasKm, min: $hasMin).")
            return null
        }
        val supportingKeywordsFound = SUPPORTING_OFFER_KEYWORDS.count { keyword ->
            val pattern = Pattern.compile("\\b${Pattern.quote(keyword)}\\b", Pattern.CASE_INSENSITIVE)
            pattern.matcher(normalizedText).find()
        }
        Log.d(TAG, "Keywords encontradas: Essenciais=$essentialKeywordsFound, Suporte=$supportingKeywordsFound")

        // Verifica se *alguma* das regex de tempo/distância encontra algo (indicativo de oferta)
        val pickupMatcherCheck = PICKUP_TIME_DIST_PATTERN.matcher(normalizedText)
        val tripMatcherCheck = TRIP_TIME_DIST_PATTERN.matcher(normalizedText)
        val hasPotentialTimeDist = pickupMatcherCheck.find() || tripMatcherCheck.find()
        pickupMatcherCheck.reset(); tripMatcherCheck.reset() // Reseta para uso posterior

        // Critério de confiança: Precisa de keywords OU ter o padrão tempo/dist reconhecido
        val meetsCriteria = (essentialKeywordsFound >= 1 || (hasEuro && hasPotentialTimeDist)) &&
                (supportingKeywordsFound >= 1 || hasPotentialTimeDist)

        if (meetsCriteria) {
            Log.i(TAG, ">>> OFERTA POTENCIAL IDENTIFICADA! <<< Iniciando extração detalhada.")
            // Log do texto completo pode ser útil para debug de falhas de regex
            // Log.d(TAG, "Texto Completo Identificado:\n$extractedText")
            return extractOfferData(normalizedText, extractedText) // Chama a nova extração
        } else {
            Log.d(TAG, "Critérios de confiança não atingidos (essenciais: $essentialKeywordsFound, suporte: $supportingKeywordsFound, tempo/dist: $hasPotentialTimeDist).")
            return null
        }
    }

    /**
     * Extrai os dados relevantes usando regex específicas e lógica de pontuação para o valor.
     * @param normalizedText Texto normalizado (lowercase, espaços únicos).
     * @param originalText Texto original para incluir no OfferData (debug).
     * @return OfferData preenchido com os dados encontrados.
     */
    private fun extractOfferData(normalizedText: String, originalText: String): OfferData {
        var value = ""
        var pickupDistance = ""
        var tripDistance = ""
        var pickupDuration = ""
        var tripDuration = ""
        var serviceType = ""

        Log.d(TAG, "--- Iniciando Extração Detalhada (Regex Específicas) ---")

        // --- Extração de Valor (€) --- (Lógica mantida)
        val moneyMatcher = MONEY_PATTERN.matcher(normalizedText)
        var bestMoneyMatch: String? = null; var highestScore = -1
        while(moneyMatcher.find()) {
            val currentMatchValue = moneyMatcher.group(1)?.replace(",", ".") ?: moneyMatcher.group(2)
            if (currentMatchValue == null) continue
            var currentScore = 0
            if (moneyMatcher.group(1) != null) { currentScore += 10; if (currentMatchValue.substringAfter(".", "").length == 2) currentScore += 5 }
            else if (moneyMatcher.group(2) != null) { currentScore += 1 }
            val searchRadius = 15 // Aumentar raio de busca por €
            val startSearchIndex = (moneyMatcher.start() - searchRadius).coerceAtLeast(0)
            val endSearchIndex = (moneyMatcher.end() + searchRadius).coerceAtMost(normalizedText.length)
            val searchSubstring = normalizedText.substring(startSearchIndex, endSearchIndex)
            if (searchSubstring.contains('€')) { currentScore += 20 }
            // Log.d(TAG, "[EXTRACT_VAL] Match: '$currentMatchValue' Score: $currentScore (€ Perto: ${searchSubstring.contains('€')}) Sub: '$searchSubstring'") // Log mais detalhado se necessário
            if (currentScore > highestScore) { highestScore = currentScore; bestMoneyMatch = currentMatchValue; /* Log.d(TAG, "[EXTRACT_VAL] Novo melhor: '$bestMoneyMatch' ($highestScore)") */ }
        }
        value = bestMoneyMatch?.replace(Regex("[^0-9.]"), "") ?: "" // Limpa não-dígitos/ponto
        Log.d(TAG, "[EXTRACT] Valor Final Escolhido: $value (Baseado em: '$bestMoneyMatch', Score: $highestScore)")

        // --- Extração de Tempo e Distância (NOVAS Regex Específicas) ---
        // Tenta encontrar Recolha
        val pickupMatcher = PICKUP_TIME_DIST_PATTERN.matcher(normalizedText)
        if (pickupMatcher.find()) {
            pickupDuration = pickupMatcher.group(1) ?: ""
            pickupDistance = pickupMatcher.group(2)?.replace(",", ".") ?: "" // Grupo 2 é a distância
            Log.d(TAG, "[EXTRACT] Recolha encontrada: Dur='$pickupDuration', Dist='$pickupDistance'")
        } else {
            Log.w(TAG, "[EXTRACT] Padrão de Recolha NÃO encontrado no texto.")
            // Poderia logar o texto aqui para análise se falhar frequentemente:
            // Log.w(TAG, "Texto onde falhou recolha:\n$normalizedText")
        }

        // Tenta encontrar Viagem
        val tripMatcher = TRIP_TIME_DIST_PATTERN.matcher(normalizedText)
        if (tripMatcher.find()) {
            tripDuration = tripMatcher.group(1) ?: ""
            tripDistance = tripMatcher.group(2)?.replace(",", ".") ?: "" // Grupo 2 é a distância
            Log.d(TAG, "[EXTRACT] Viagem encontrada: Dur='$tripDuration', Dist='$tripDistance'")
        } else {
            Log.w(TAG, "[EXTRACT] Padrão de Viagem NÃO encontrado no texto.")
            // Log.w(TAG, "Texto onde falhou viagem:\n$normalizedText")
        }

        // --- Cálculo dos Totais (Lógica mantida) ---
        var calculatedTotalDuration = 0
        var calculatedTotalDistance = 0.0
        try {
            val pDur = pickupDuration.toIntOrNull() ?: 0
            val tDur = tripDuration.toIntOrNull() ?: 0
            if (pDur >= 0 && tDur >= 0 && (pDur > 0 || tDur > 0)) {
                calculatedTotalDuration = pDur + tDur
            }
            val pDist = pickupDistance.toDoubleOrNull() ?: 0.0
            val tDist = tripDistance.toDoubleOrNull() ?: 0.0
            if (pDist >= 0.0 && tDist >= 0.0 && (pDist > 0.0 || tDist > 0.0)) {
                calculatedTotalDistance = pDist + tDist
            }
        } catch (e: Exception) { Log.w(TAG, "[EXTRACT] Erro ao calcular totais: ${e.message}") }

        // Formata os totais para guardar em OfferData (se calculados)
        val totalDistanceStr = if (calculatedTotalDistance > 0) String.format(Locale.US, "%.1f", calculatedTotalDistance) else ""
        val totalDurationStr = if (calculatedTotalDuration > 0) calculatedTotalDuration.toString() else ""

        Log.d(TAG, "[EXTRACT] Totais Calculados: Dist='$totalDistanceStr', Dur='$totalDurationStr'")


        // --- Extração do Tipo de Serviço (Lógica mantida, lista atualizada) ---
        val lowerCaseText = normalizedText // Já está em lowercase
        for (type in SERVICE_TYPES) {
            // Procura pelo tipo de serviço como palavra completa ou no início/fim de palavras compostas
            val pattern = Pattern.compile("\\b${Pattern.quote(type.lowercase(Locale.ROOT))}\\b|\\b${Pattern.quote(type.lowercase(Locale.ROOT))}[\\s·-]|[\\s·-]${Pattern.quote(type.lowercase(Locale.ROOT))}\\b")
            if (pattern.matcher(lowerCaseText).find()) {
                // Se encontrar, tenta pegar o nome mais completo da lista original
                serviceType = SERVICE_TYPES.firstOrNull { it.contains(type, ignoreCase = true) } ?: type
                Log.d(TAG, "[EXTRACT] Tipo de Serviço encontrado: $serviceType (match por '$type')")
                break // Para no primeiro tipo encontrado
            }
        }
        if (serviceType.isBlank()) {
            Log.d(TAG, "[EXTRACT] Nenhum tipo de serviço conhecido encontrado.")
        }

        Log.d(TAG, "--- Fim da Extração Detalhada ---")
        return OfferData(
            value = value,
            distance = totalDistanceStr, // Total calculado
            pickupDistance = pickupDistance, // Extraído
            tripDistance = tripDistance,     // Extraído
            duration = totalDurationStr,   // Total calculado
            pickupDuration = pickupDuration, // Extraído
            tripDuration = tripDuration,     // Extraído
            serviceType = serviceType,
            rawText = originalText.take(500) // Guarda apenas o início do texto original
        )
    }

    /** Determina a região de interesse (ROI). Tenta ser um pouco mais alto? */
    fun getRegionsOfInterest(screenWidth: Int, screenHeight: Int): List<Rect> {
        // Começa um pouco mais acima (ex: 25% ou 30% da altura) e adiciona margem
        val topMarginPercent = 0.05 // 5% de margem do topo da ROI
        val startYPercent = 0.30  // Começa a ROI a 30% da altura da tela

        val topMarginPx = (screenHeight * topMarginPercent).toInt()
        val startYPx = (screenHeight * startYPercent).toInt() + topMarginPx
        val validStartY = startYPx.coerceIn(0, screenHeight - 1) // Garante que está dentro dos limites

        // ROI vai de startY até o fundo da tela
        val bottomRegion = Rect(0, validStartY, screenWidth, screenHeight)

        if (bottomRegion.height() <= 0) {
            Log.e(TAG, "Região de interesse calculada tem altura inválida: $bottomRegion. Usando tela inteira como fallback.")
            return listOf(Rect(0, 0, screenWidth, screenHeight))
        }
        Log.d(TAG, "Região de Interesse definida: ${bottomRegion.flattenToString()} (Início em ${validStartY}px - ${startYPercent*100}%)")
        return listOf(bottomRegion)
    }

    /** Recorta a imagem original para a região especificada. (Inalterado) */
    fun cropToRegion(original: Bitmap, region: Rect): Bitmap? {
        // Garante que a região de corte esteja dentro dos limites do bitmap
        val safeRegion = Rect(
            region.left.coerceIn(0, original.width),
            region.top.coerceIn(0, original.height),
            region.right.coerceIn(0, original.width),
            region.bottom.coerceIn(0, original.height)
        )
        // Verifica se a região segura tem dimensões válidas
        if (safeRegion.width() <= 0 || safeRegion.height() <= 0) {
            Log.w(TAG, "Região de recorte inválida após ajuste: $safeRegion (Original: $region, Bitmap: ${original.width}x${original.height})")
            return null // Retorna nulo se a região for inválida
        }
        return try {
            // Cria o bitmap recortado
            Bitmap.createBitmap(
                original, safeRegion.left, safeRegion.top, safeRegion.width(), safeRegion.height()
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Erro ao recortar bitmap (Argumento Ilegal): ${e.message}. Região: $safeRegion, Bitmap: ${original.width}x${original.height}")
            null // Retorna nulo em caso de erro
        } catch (e: Exception) {
            Log.e(TAG, "Erro genérico ao recortar bitmap: ${e.message}")
            null // Retorna nulo em caso de erro
        }
    }
}