package com.example.smartdriver.utils // <<< VERIFIQUE O PACKAGE

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.text.Text // Manter para acesso ao texto bruto
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ImageAnalysisUtils {

    companion object {
        private const val TAG = "ImageAnalysisUtils"

        // Regex: Priorizar valor COM símbolo € adjacente
        // Procura por (número com decimais) seguido opcionalmente por espaço(s) e €
        // OU por € seguido opcionalmente por espaço(s) e (número com decimais)
        private val STRICT_VALUE_PATTERN = Pattern.compile("(\\d+[,.]\\d{1,2})\\s*€|€\\s*(\\d+[,.]\\d{1,2})")
        // Regex para número genérico (para fallback e outras extrações)
        private val GENERIC_NUMBER_PATTERN = Pattern.compile("(\\d+([.,]\\d+)?)")
        // Regex para Tempo: número seguido por espaço(s) opcional(is) e "min"
        private val TIME_PATTERN = Pattern.compile("(\\d+)\\s*min")
        // Regex para KM: opcionalmente '(', espaço(s), número, espaço(s), "km", espaço(s), opcionalmente ')'
        private val KM_ONLY_PATTERN = Pattern.compile("\\(?\\s*(\\d+([.,]\\d+)?)\\s*km\\s*\\)?")

        // Keywords para identificar a linha da viagem
        private val TRIP_KEYWORDS = listOf("viagem de", "percurso de")

        // Lista original de Keywords de confirmação de oferta
        private val ORIGINAL_OFFER_KEYWORDS = setOf(
            "aceitar", "confirmar", "nova viagem", "novo pedido", //"ganhe até", // Pode ser pouco confiável
            "valor estimado", "tarifa", "total", "receber", "recebe",
            "corresponder", "exclusivo", "após dedução"
        )
        // Cria automaticamente a lista CORRIGIDA aplicando as regras de OCR
        private val CORRECTED_OFFER_KEYWORDS = ORIGINAL_OFFER_KEYWORDS.map { applyOcrCorrections(it.lowercase(Locale.ROOT)) }.toSet()

        // Lista de tipos de serviço (já em minúsculas para facilitar)
        private val SERVICE_TYPES = setOf(
            "uberx", "comfort", "black", "green", "xl", "pet", "wav", "assist", "pool",
            "flash", "taxi", "business comfort", "ubergreen", "green · teens", "exclusivo"
        )

        // Constantes para lógica posicional (se voltarmos a usar)
        private const val MAX_Y_DISTANCE_FOR_SAME_ENTITY = 40
        private const val MIN_Y_DISTANCE_BETWEEN_PICKUP_TRIP = 30
        private const val TRIP_LINE_Y_TOLERANCE = 15

        /** Função auxiliar para aplicar correções comuns de OCR a uma string. */
        private fun applyOcrCorrections(text: String): String {
            // Aplica correções diretamente na string fornecida
            return text.replace('I', '1', ignoreCase = false) // I maiúsculo -> 1
                .replace('l', '1', ignoreCase = false) // l minúsculo -> 1
                .replace('O', '0', ignoreCase = true)  // O ou o -> 0
                .replace('?', '7')                    // ? -> 7 (comum?)
                // Adicionar mais se necessário:
                // .replace('S', '5', ignoreCase = true)
                // .replace('B', '8', ignoreCase = true)
                .replace(" uber ", " uberx ", ignoreCase = true) // Garante espaço para match exato
        }
    }

    // Estruturas de dados internas para guardar resultados intermédios
    private data class MatchedNumber(val value: Double, val start: Int, val end: Int)
    private data class MatchedTimeDist(
        val timeMinutes: Int?,
        val distanceKm: Double?,
        val lineIndex: Int, // Índice da linha onde foi encontrado
        var isTripCandidate: Boolean = false // Para marcar se esta linha foi identificada como "Viagem de"
    ) {
        // toString para facilitar logs
        override fun toString(): String {
            val tStr = timeMinutes?.let { "$it min" } ?: "N/A"
            val dStr = distanceKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "N/A"
            val type = if(isTripCandidate) "[TRIP]" else "[PICKUP?]"
            return "$type T=$tStr, D=$dStr @ Line=${lineIndex+1}"
        }
    }

    /**
     * Função principal de análise. Recebe texto OCR, aplica correções e extrai dados.
     */
    fun analyzeTextForOffer(visionText: Text, originalWidth: Int, originalHeight: Int): OfferData? {
        val rawTextOriginal = visionText.text
        if (rawTextOriginal.isBlank()) {
            Log.w(TAG, "Texto OCR original está vazio.")
            return null
        }

        Log.d(TAG, "-----------------------------------------------------")
        Log.d(TAG, "Texto OCR Original (len=${rawTextOriginal.length}):\n${rawTextOriginal.replace("\n", " \\n ")}")

        // --- PASSO 1: CORREÇÃO OCR ---
        val correctedText = applyOcrCorrections(rawTextOriginal)
        if (correctedText != rawTextOriginal) {
            Log.i(TAG, "Texto OCR Corrigido (len=${correctedText.length}):\n${correctedText.replace("\n", " \\n ")}")
        } else {
            Log.d(TAG, "Texto OCR não precisou de correção.")
        }
        // --- FIM DA CORREÇÃO ---

        // --- PASSO 2: Validação Rápida ---
        val lowerCaseCorrectedText = correctedText.lowercase(Locale.ROOT)
        // Normaliza múltiplos espaços/newlines para um único espaço APENAS para validação de keywords
        val normalizedForValidation = lowerCaseCorrectedText.replace(Regex("\\s+"), " ")
        Log.d(TAG, "Texto Normalizado para Validação: '$normalizedForValidation'")

        Log.d(TAG, "Iniciando Validação Rápida (Texto Corrigido e Normalizado)...")
        val hasCurrency = lowerCaseCorrectedText.contains('€')
        val hasKm = lowerCaseCorrectedText.contains("km")
        val hasMin = lowerCaseCorrectedText.contains("min")
        // *** Valida usando a lista de keywords CORRIGIDAS ***
        val hasConfirmationKeyword = CORRECTED_OFFER_KEYWORDS.any { correctedKeyword -> normalizedForValidation.contains(correctedKeyword) }

        Log.d(TAG, "Validação Rápida: €=$hasCurrency, km=$hasKm, min=$hasMin, Keyword=$hasConfirmationKeyword")
        if (!hasConfirmationKeyword) {
            // Log detalhado se falhar na keyword
            val missingKeywordsInfo = ORIGINAL_OFFER_KEYWORDS.filter { originalKeyword ->
                !normalizedForValidation.contains(applyOcrCorrections(originalKeyword.lowercase(Locale.ROOT)))
            }.joinToString()
            Log.w(TAG, "Keywords de confirmação NÃO encontradas no texto normalizado/corrigido. Possíveis faltantes (originais): [$missingKeywordsInfo]")
        }

        // Verifica se todos os critérios de validação passaram
        if (!(hasCurrency && hasKm && hasMin && hasConfirmationKeyword)) {
            Log.w(TAG, "ANÁLISE FALHOU: Validação rápida inicial falhou (Keyword=$hasConfirmationKeyword).")
            return null
        }
        Log.i(TAG, ">>> OFERTA POTENCIAL (Validação OK)! Iniciando extração com Regex (Texto Corrigido)...")
        // --- Fim Validação Rápida ---

        // --- PASSO 3: Extração usando Regex ---
        // Passa o texto minúsculo corrigido (para Regex) e o original corrigido (para guardar no rawText)
        return extractOfferDataWithRegex(lowerCaseCorrectedText, correctedText)
    }

    /**
     * Extrai dados da oferta usando Regex no texto pré-corrigido (em minúsculas).
     */
    private fun extractOfferDataWithRegex(lowerCaseCorrectedText: String, originalCorrectedText: String): OfferData? {
        Log.d(TAG, "--- Iniciando Extração com Regex (Texto Corrigido) ---")

        var value = ""
        var pickupDistance = ""
        var tripDistance = ""
        var pickupDuration = ""
        var tripDuration = ""
        var serviceType = ""

        // 1. Extrair Valor (€) - Lógica Refinada v3 (Busca € e depois número próximo)
        var finalValue = 0.0
        val euroPositions = mutableListOf<Int>()
        var euroIndex = lowerCaseCorrectedText.indexOf('€')
        while (euroIndex >= 0) {
            euroPositions.add(euroIndex)
            euroIndex = lowerCaseCorrectedText.indexOf('€', euroIndex + 1)
        }

        if (euroPositions.isNotEmpty()) {
            Log.d(TAG, "Símbolo(s) € encontrado(s) nas posições: $euroPositions.")
            var bestValDist = Int.MAX_VALUE
            val numMatcher = GENERIC_NUMBER_PATTERN.matcher(lowerCaseCorrectedText)

            while (numMatcher.find()) {
                val numStr = numMatcher.group(1)?.replace(',', '.')
                val numVal = numStr?.toDoubleOrNull()
                if (numVal != null) {
                    val numStart = numMatcher.start()
                    val numEnd = numMatcher.end()

                    // Verifica se NÃO é seguido por km/min
                    val nextChars = lowerCaseCorrectedText.substring(numEnd, min(numEnd + 5, lowerCaseCorrectedText.length)).trimStart()
                    val isFollowedByUnit = nextChars.startsWith("km") || nextChars.startsWith("min")

                    if (!isFollowedByUnit) {
                        // Encontra a menor distância a QUALQUER dos € encontrados
                        var minDistToAnyEuro = Int.MAX_VALUE
                        for (euroPos in euroPositions) {
                            val dist = if (numEnd <= euroPos) euroPos - numEnd
                            else if (numStart >= euroPos) numStart - euroPos
                            else 0 // Contém o €
                            minDistToAnyEuro = min(minDistToAnyEuro, dist)
                        }

                        Log.v(TAG, "  Encontrado número $numVal (Pos: $numStart-$numEnd). Menor Dist ao €: $minDistToAnyEuro")

                        // Considera como candidato se estiver perto (<15) e for o mais perto até agora
                        if (minDistToAnyEuro < 15 && minDistToAnyEuro < bestValDist) {
                            bestValDist = minDistToAnyEuro
                            finalValue = numVal
                            Log.d(TAG, "    >>> Novo melhor candidato a valor: $finalValue (Dist: $bestValDist)")
                        }
                    } else {
                        Log.v(TAG, "  [SKIP VALUE] Número $numVal ignorado (seguido por km/min)")
                    }
                }
            }
        } else {
            Log.w(TAG, "Símbolo € não encontrado no texto corrigido.")
        }

        if (finalValue <= 0.0) {
            Log.e(TAG, "ANÁLISE FALHOU: Não foi possível extrair um valor monetário válido.")
            return null
        }
        value = String.format(Locale.US, "%.2f", finalValue)
        Log.i(TAG, "[EXTRACT Regex] Valor Selecionado: $value €")


        // 2. Encontrar Linhas de Tempo/Distância
        val lines = lowerCaseCorrectedText.split('\n')
        val timeDistCandidates = mutableListOf<MatchedTimeDist>()
        var tripLineIndex = -1

        for ((index, line) in lines.withIndex()) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue
            Log.d(TAG, "Analisando Linha ${index+1}: '$trimmedLine'")

            if (tripLineIndex == -1 && TRIP_KEYWORDS.any { trimmedLine.startsWith(it) }) {
                tripLineIndex = index
                Log.d(TAG, ">>> Linha de VIAGEM detectada (Índice $index): '$trimmedLine'")
            }

            var time: Int? = null
            var dist: Double? = null

            val timeMatcher = TIME_PATTERN.matcher(trimmedLine)
            if (timeMatcher.find()) {
                time = timeMatcher.group(1)?.toIntOrNull()
                Log.i(TAG,"  [REGEX MATCH] Tempo encontrado: $time min")
            } else {
                Log.d(TAG,"  [REGEX NO MATCH] Nenhum tempo ('<num> min') encontrado na linha.")
            }

            val distMatcher = KM_ONLY_PATTERN.matcher(trimmedLine)
            if (distMatcher.find()) {
                dist = distMatcher.group(1)?.replace(',', '.')?.toDoubleOrNull()
                Log.i(TAG,"  [REGEX MATCH] Distância encontrada: $dist km")
            } else {
                Log.d(TAG,"  [REGEX NO MATCH] Nenhuma distância ('(<num> km)') encontrada na linha.")
            }

            if (time != null || dist != null) {
                val candidate = MatchedTimeDist(time, dist, index)
                if (index == tripLineIndex) { candidate.isTripCandidate = true }
                timeDistCandidates.add(candidate)
                Log.i(TAG, "  >> Candidato T/D Adicionado: $candidate")
            }
        }

        // 3. Separar Recolha e Viagem
        if (timeDistCandidates.isNotEmpty()) {
            if (tripLineIndex != -1) {
                // Viagem: O candidato na linha marcada como viagem
                val tripCand = timeDistCandidates.find { it.isTripCandidate }
                tripDuration = tripCand?.timeMinutes?.toString() ?: ""
                tripDistance = tripCand?.distanceKm?.let { String.format(Locale.US, "%.1f", it) } ?: ""

                // Recolha: O candidato mais próximo (maior índice) ANTES da linha da viagem
                val pickupCand = timeDistCandidates.filter { !it.isTripCandidate && it.lineIndex < tripLineIndex }
                    .maxByOrNull { it.lineIndex }
                pickupDuration = pickupCand?.timeMinutes?.toString() ?: ""
                pickupDistance = pickupCand?.distanceKm?.let { String.format(Locale.US, "%.1f", it) } ?: ""
            } else {
                Log.w(TAG, "Linha 'Viagem de' não encontrada. Inferindo pela ordem.")
                // Mantém a lógica de fallback anterior
                if (timeDistCandidates.size >= 2) {
                    pickupDuration = timeDistCandidates.first().timeMinutes?.toString() ?: ""
                    pickupDistance = timeDistCandidates.first().distanceKm?.let { String.format(Locale.US, "%.1f", it) } ?: ""
                    tripDuration = timeDistCandidates.last().timeMinutes?.toString() ?: ""
                    tripDistance = timeDistCandidates.last().distanceKm?.let { String.format(Locale.US, "%.1f", it) } ?: ""
                } else if (timeDistCandidates.size == 1) {
                    val cand = timeDistCandidates.first()
                    if ((cand.timeMinutes ?: 0) > 5 || (cand.distanceKm ?: 0.0) > 3.0) { // Heurística
                        tripDuration = cand.timeMinutes?.toString() ?: ""
                        tripDistance = cand.distanceKm?.let { String.format(Locale.US, "%.1f", it) } ?: ""
                    } else {
                        pickupDuration = cand.timeMinutes?.toString() ?: ""
                        pickupDistance = cand.distanceKm?.let { String.format(Locale.US, "%.1f", it) } ?: ""
                    }
                }
            }
        }
        Log.i(TAG, "[EXTRACT Regex] Recolha Final: Tempo='$pickupDuration', Dist='$pickupDistance'")
        Log.i(TAG, "[EXTRACT Regex] Viagem Final: Tempo='$tripDuration', Dist='$tripDistance'")

        // 4. Extrair Tipo de Serviço
        val normalizedForService = lowerCaseCorrectedText.replace(Regex("\\s+"), " ")
        for (type in SERVICE_TYPES) {
            val pattern = Pattern.compile("\\b${Pattern.quote(type)}\\b")
            if (pattern.matcher(normalizedForService).find()) {
                serviceType = SERVICE_TYPES.find { it.equals(type, ignoreCase = true) }
                    ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } ?: type
                Log.d(TAG, "[EXTRACT Regex] Tipo de Serviço: $serviceType")
                break
            }
        }
        if (serviceType.isBlank()) { // Fallback
            for (type in SERVICE_TYPES) {
                if (normalizedForService.contains(type)) {
                    serviceType = SERVICE_TYPES.find { it.equals(type, ignoreCase = true) }
                        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } ?: type
                    Log.d(TAG, "[EXTRACT Regex] Tipo de Serviço (Fallback): $serviceType")
                    break
                }
            }
        }

        // 5. Montar e retornar OfferData
        if (pickupDuration.isBlank() && pickupDistance.isBlank() && tripDuration.isBlank() && tripDistance.isBlank()) {
            Log.e(TAG, "ANÁLISE FALHOU: Nenhum dado de tempo ou distância extraído.")
            return null
        }

        val offerData = OfferData(
            value = value,
            pickupDistance = pickupDistance,
            tripDistance = tripDistance,
            pickupDuration = pickupDuration,
            tripDuration = tripDuration,
            serviceType = serviceType,
            rawText = originalCorrectedText.take(800) // Guarda o texto original corrigido
        )
        offerData.updateCalculatedTotals() // Calcula/atualiza distance e duration totais

        Log.i(TAG, "--- Extração com Regex Concluída ---")
        Log.i(TAG, "OfferData Final (Regex): V=${offerData.value}, TotalD=${offerData.distance}, TotalT=${offerData.duration}, PickD=${offerData.pickupDistance}, TripD=${offerData.tripDistance}, PickT=${offerData.pickupDuration}, TripT=${offerData.tripDuration}, Serv=${offerData.serviceType}")

        // Validação Final: Precisa de valor e pelo menos um tempo/distância total válido
        if (offerData.isValid() && (!offerData.distance.isNullOrEmpty() || !offerData.duration.isNullOrEmpty())) {
            Log.i(TAG, ">>> OfferData (Regex) considerado VÁLIDO para retorno. <<<")
            return offerData
        } else {
            Log.w(TAG, "OfferData (Regex) considerado INVÁLIDO ou incompleto.")
            return null
        }
    }


    // --- Funções de Recorte e ROI (INALTERADAS) ---
    fun getRegionsOfInterest(screenWidth: Int, screenHeight: Int): List<Rect> {
        val topMarginPercent = 0.05
        val startYPercent = 0.30
        val topMarginPx = (screenHeight * topMarginPercent).toInt()
        val startYPx = (screenHeight * startYPercent).toInt() + topMarginPx
        val validStartY = startYPx.coerceIn(0, screenHeight - 1)
        val bottomRegion = Rect(0, validStartY, screenWidth, screenHeight)
        if (bottomRegion.height() <= 0) {
            Log.e(TAG, "ROI calculada inválida. Usando tela inteira.")
            return listOf(Rect(0, 0, screenWidth, screenHeight))
        }
        return listOf(bottomRegion)
    }

    fun cropToRegion(original: Bitmap, region: Rect): Bitmap? {
        val safeRegion = Rect(
            region.left.coerceIn(0, original.width -1),
            region.top.coerceIn(0, original.height -1),
            region.right.coerceIn(region.left + 1, original.width),
            region.bottom.coerceIn(region.top + 1, original.height)
        )
        if (safeRegion.width() <= 0 || safeRegion.height() <= 0) {
            Log.w(TAG, "Região de recorte inválida: $safeRegion")
            return null
        }
        return try {
            Bitmap.createBitmap(original, safeRegion.left, safeRegion.top, safeRegion.width(), safeRegion.height())
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao recortar bitmap: ${e.message}", e)
            null
        }
    }

} // Fim da classe ImageAnalysisUtils