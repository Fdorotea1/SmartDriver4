package com.example.smartdriver.utils

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

class ImageAnalysisUtils {

    companion object {
        private const val TAG = "ImageAnalysisUtils"
        private val MONEY_PATTERN = Pattern.compile("(?:€\\s*?)?(\\d+[,.]\\d{1,2})(?:\\s*€)?|(\\d+)\\s*€")

        // Mantemos as Regex separadas da última tentativa que funcionou para extração
        private val PICKUP_TIME_DIST_PATTERN = Pattern.compile(
            "(\\d+)\\s*min\\s*\\(\\s*(\\d+(?:[.,]\\d+)?)\\s*km\\s*\\)",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        private val TRIP_TIME_DIST_PATTERN = Pattern.compile(
            "viagem\\s+de\\s+" +
                    "(\\d+)\\s*min\\s*\\(\\s*(\\d+(?:[.,]\\d+)?)\\s*km\\s*\\)",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )

        private val ESSENTIAL_OFFER_KEYWORDS = listOf("aceitar", "confirmar", "nova viagem", "novo pedido", "corresponder", "€", "ganhe até", "valor estimado", "tarifa", "total", "após dedução")
        private val SUPPORTING_OFFER_KEYWORDS = listOf("km", "min", "distância", "tempo", "duração", "viagem", "trajeto", "corrida", "pedido", "entrega", "recolha", "apanhar", "destino", "localização", "uberx", "comfort", "black", "green", "xl", "business", "pool", "pet", "wav", "assist", "passageiro", "cliente", "green · teens", "exclusivo", "flash", "taxi")
        private val SERVICE_TYPES = listOf("UberX", "Comfort", "Black", "Green", "XL", "Pet", "WAV", "Assist", "Pool", "Flash", "Taxi", "Business Comfort", "UberGreen", "Green · Teens", "Exclusivo")
    }

    /** Analisa texto OCR para encontrar dados de oferta - VALIDAÇÃO INICIAL SIMPLIFICADA */
    fun analyzeTextForOffer(extractedText: String): OfferData? {
        val cleanedText = extractedText.replace("\u0000", "")
        val normalizedText = cleanedText.lowercase(Locale.ROOT).replace("\\s+".toRegex(), " ").trim()

        // Log Opcional
        // Log.v(TAG, "Analisando texto normalizado (len=${normalizedText.length})")

        // --- Validação Inicial SIMPLIFICADA ---
        // 1. Precisa ter números? (Muito básico)
        val hasNumber = Pattern.compile("\\d").matcher(normalizedText).find()
        if (!hasNumber) return null

        // 2. Precisa ter € OU (km E min)?
        val hasEuro = normalizedText.contains("€") || normalizedText.contains("eur")
        val hasKm = normalizedText.contains("km")
        val hasMin = normalizedText.contains("min")
        if (!(hasEuro || (hasKm && hasMin))) {
            // Log.d(TAG, "Faltam elementos básicos (€ ou (km e min)).") // Opcional
            return null
        }

        // 3. Precisa ter PELO MENOS uma keyword essencial OU de suporte?
        //    (Evita telas aleatórias que tenham números, €/km/min por acaso)
        val essentialKeywordsFound = ESSENTIAL_OFFER_KEYWORDS.any { normalizedText.contains(it) }
        val supportingKeywordsFound = SUPPORTING_OFFER_KEYWORDS.any { normalizedText.contains(it) }
        if (!essentialKeywordsFound && !supportingKeywordsFound) {
            // Log.d(TAG, "Nenhuma keyword essencial ou de suporte encontrada.") // Opcional
            return null
        }
        // --- Fim Validação Simplificada ---


        // Se passou nas validações básicas, tenta extrair
        Log.i(TAG, ">>> OFERTA POTENCIAL (Validação OK)! Iniciando extração...")
        return extractOfferData(normalizedText, cleanedText)
    }


    /** Extrai dados da oferta usando Regex separadas e verificando posições. */
    private fun extractOfferData(normalizedText: String, originalText: String): OfferData {
        var value = ""; var pickupDistance = ""; var tripDistance = ""
        var pickupDuration = ""; var tripDuration = ""; var serviceType = ""
        var tripMatchStart = -1 // Guarda a posição inicial do match da viagem

        Log.d(TAG, "--- Iniciando Extração Detalhada (Lógica 2 Passos) ---")

        // 1. Extração de Valor (€)
        val moneyMatcher = MONEY_PATTERN.matcher(normalizedText); var bestMoneyMatch: String? = null; var highestScore = -1
        while(moneyMatcher.find()) {
            val currentMatchValue = moneyMatcher.group(1)?.replace(",", ".") ?: moneyMatcher.group(2) ?: continue
            var currentScore = 0
            if (moneyMatcher.group(1) != null) { currentScore += 10; if (currentMatchValue.substringAfter(".", "").length == 2) currentScore += 5 } else { currentScore += 1 }
            val searchRadius = 20; val startIdx = (moneyMatcher.start() - searchRadius).coerceAtLeast(0); val endIdx = (moneyMatcher.end() + searchRadius).coerceAtMost(normalizedText.length)
            if (normalizedText.substring(startIdx, endIdx).contains('€')) { currentScore += 20 }
            if (currentScore > highestScore) { highestScore = currentScore; bestMoneyMatch = currentMatchValue; }
        }
        value = bestMoneyMatch?.replace(Regex("[^0-9.]"), "") ?: ""
        Log.d(TAG, "[EXTRACT] Valor: $value")

        // 2. Tentar encontrar a VIAGEM PRIMEIRO
        val tripMatcher = TRIP_TIME_DIST_PATTERN.matcher(normalizedText)
        if (tripMatcher.find()) {
            tripDuration = tripMatcher.group(1) ?: ""
            tripDistance = tripMatcher.group(2)?.replace(",", ".") ?: ""
            tripMatchStart = tripMatcher.start()
            Log.d(TAG, "[EXTRACT] VIAGEM encontrada: Dur='$tripDuration', Dist='$tripDistance' at $tripMatchStart")
        } else {
            Log.w(TAG, "[EXTRACT] Padrão de Viagem NÃO encontrado.")
        }

        // 3. Tentar encontrar a RECOLHA
        val pickupMatcher = PICKUP_TIME_DIST_PATTERN.matcher(normalizedText)
        while (pickupMatcher.find()) {
            // Verifica se este match NÃO é o mesmo que já foi identificado como VIAGEM
            if (pickupMatcher.start() != tripMatchStart) {
                pickupDuration = pickupMatcher.group(1) ?: ""
                pickupDistance = pickupMatcher.group(2)?.replace(",", ".") ?: ""
                Log.d(TAG, "[EXTRACT] RECOLHA encontrada: Dur='$pickupDuration', Dist='$pickupDistance' at ${pickupMatcher.start()}")
                break // Encontrou a recolha, para
            } else {
                Log.v(TAG, "Match genérico em $tripMatchStart ignorado (Viagem).")
            }
        }
        if (pickupDuration.isBlank()) {
            Log.w(TAG, "[EXTRACT] Padrão de Recolha NÃO encontrado/identificado.")
        }

        // 4. Extração Tipo de Serviço
        val lowerCaseText = normalizedText; for (type in SERVICE_TYPES) { val pattern = Pattern.compile("\\b${Pattern.quote(type.lowercase(Locale.ROOT))}\\b"); if (pattern.matcher(lowerCaseText).find()) { serviceType = SERVICE_TYPES.firstOrNull { it.equals(type, ignoreCase = true) } ?: type; break } }
        if (serviceType.isBlank()) { for (type in SERVICE_TYPES) { if(lowerCaseText.contains(type.lowercase(Locale.ROOT))) { serviceType = SERVICE_TYPES.firstOrNull { it.equals(type, ignoreCase = true) } ?: type; break } } }

        // 5. Cria OfferData e Calcula Totais
        val offerData = OfferData(value = value, distance = "", pickupDistance = pickupDistance, tripDistance = tripDistance, duration = "", pickupDuration = pickupDuration, tripDuration = tripDuration, serviceType = serviceType, rawText = originalText.take(600))
        offerData.updateCalculatedTotals()
        Log.d(TAG, "[EXTRACT] Totais Finais: Dist='${offerData.distance}', Dur='${offerData.duration}'")
        Log.d(TAG, "--- Fim da Extração Detalhada ---")
        return offerData
    }

    // Funções getRegionsOfInterest e cropToRegion (inalteradas)
    fun getRegionsOfInterest(screenWidth: Int, screenHeight: Int): List<Rect> {
        val topMarginPercent = 0.05; val startYPercent = 0.30
        val topMarginPx = (screenHeight * topMarginPercent).toInt(); val startYPx = (screenHeight * startYPercent).toInt() + topMarginPx
        val validStartY = startYPx.coerceIn(0, screenHeight - 1)
        val bottomRegion = Rect(0, validStartY, screenWidth, screenHeight)
        if (bottomRegion.height() <= 0) { Log.e(TAG, "ROI inválida."); return listOf(Rect(0, 0, screenWidth, screenHeight)) }
        return listOf(bottomRegion)
    }
    fun cropToRegion(original: Bitmap, region: Rect): Bitmap? {
        val safeRegion = Rect( region.left.coerceIn(0, original.width), region.top.coerceIn(0, original.height), region.right.coerceIn(0, original.width), region.bottom.coerceIn(0, original.height) )
        if (safeRegion.width() <= 0 || safeRegion.height() <= 0) { Log.w(TAG, "Região recorte inválida: $safeRegion"); return null }
        return try { Bitmap.createBitmap( original, safeRegion.left, safeRegion.top, safeRegion.width(), safeRegion.height() ) }
        catch (e: Exception) { Log.e(TAG, "Erro recortar bitmap: ${e.message}"); null }
    }
} // Fim da classe