package com.example.smartdriver.utils

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.text.Text
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.min

class ImageAnalysisUtils {

    companion object {
        private const val TAG = "ImageAnalysisUtils"

        // ---------- Normalização ----------
        private fun normalizeSpaces(s: String): String =
            s.replace(Regex("[\\u00A0\\u2007\\u202F\\u200B\\uFEFF\\s]+"), " ").trim()

        private fun stripAccentsLower(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                .lowercase(Locale.ROOT)

        private fun applyOcrCorrections(text: String): String {
            return text
                .replace('I', '1')
                .replace('l', '1')
                .replace('O', '0')
                .replace('?', '7')
                .replace("\u00A0", " ")
                .replace("\u200B", " ")
                .replace(Regex("€\\s*([\\d.,]+)"), "€ $1")
                .replace(Regex("([\\d.,]+)\\s*€"), "$1 €")
                .replace(Regex("\\buber\\b", RegexOption.IGNORE_CASE), "uberx")
        }

        // ---------- Regex ----------
        private val MONEY_NEAR_EURO =
            Pattern.compile("(?:€\\s*([0-9]{1,3}(?:[.,][0-9]{3})*(?:[.,][0-9]{2})?|[0-9]+(?:[.,][0-9]{2})))|(?:([0-9]{1,3}(?:[.,][0-9]{3})*(?:[.,][0-9]{2})?|[0-9]+(?:[.,][0-9]{2}))\\s*€)")

        private val TIME_PATTERN = Pattern.compile("\\b(\\d{1,3})\\s*(?:min\\.?|minutos?|m)\\b")
        private val KM_PATTERN = Pattern.compile("\\(?\\s*(\\d{1,3}(?:[.,][0-9]{1,2})?)\\s*km\\s*\\)?")

        private val OFFER_KEYWORDS = setOf(
            "aceitar", "confirmar", "nova viagem", "novo pedido",
            "valor estimado", "tarifa", "total", "receber", "recebe",
            "corresponder", "exclusivo", "apos deducao"
        )

        // Linha “viagem”
        private val TRIP_LINE_PATTERNS = listOf(
            Regex("\\btempo\\s+de\\s+viagem\\b"),
            Regex("\\bviagem(\\s+de)?\\b"),
            Regex("\\bpercurso(\\s+de)?\\b")
        )

        // --- COMBINADOS ---
        // Viagem de 10 min (6.4 km)  |  Viagem … 10 min … 6.4 km
        private val TRIP_COMBINED_PATTERNS = listOf(
            Regex("\\bviagem\\s+de\\s+(\\d{1,3})\\s*min\\b.*?\\(\\s*(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*km\\s*\\)"),
            Regex("\\bviagem\\b.*?(\\d{1,3})\\s*min\\b.*?(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*km"),
            Regex("\\bpercurso\\s+de\\s+(\\d{1,3})\\s*min\\b.*?\\(\\s*(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*km\\s*\\)")
        )

        // Recolha: 12 min (7.8 km) de distância | 12 min (7,8 km) distancia
        // (texto normalizado sem acentos/minúsculas)
        private val PICKUP_COMBINED_PATTERNS = listOf(
            Regex("\\b(\\d{1,3})\\s*min\\b\\s*\\(\\s*(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*km\\s*\\)\\s*(?:de\\s*)?distancia\\b"),
            // variantes onde “distancia” pode vir antes
            Regex("\\bdistancia\\b.*?(\\d{1,3})\\s*min\\b.*?\\(\\s*(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*km\\s*\\)")
        )

        private val SERVICE_TYPES = setOf(
            "uberx", "comfort", "black", "green", "xl", "pet", "wav", "assist", "pool",
            "flash", "taxi", "business comfort", "ubergreen", "green teens", "exclusivo"
        )

        // ---------- Detector dedicado: "A recolher" ----------
        /**
         * Deteta a frase-chave "A recolher" (sem os "...").
         * NOTA: não usa applyOcrCorrections para não converter 'l'→'1'.
         */
        fun detectPickupState(visionText: Text): Boolean {
            val raw = visionText.text ?: return false
            if (raw.isBlank()) return false

            // Limpa espaços e normaliza acentos/minúsculas
            val pretty = normalizeSpaces(raw)
            val normalized = stripAccentsLower(pretty)

            // Casa "a recolher" permitindo espaços/linhas entre as palavras
            val pickupRegex = Regex("\\ba\\s+recolher\\b")

            // 1) Tentativa direta
            if (pickupRegex.containsMatchIn(normalized)) {
                Log.i(TAG, "⚡ Detetado 'A recolher' (normal).")
                return true
            }

            // 2) Tentativa tolerante a erros comuns do OCR (1↔l, 0↔o)
            val tolerant = normalized
                .replace('1', 'l')
                .replace('0', 'o')

            val foundT = pickupRegex.containsMatchIn(tolerant)
            if (foundT) {
                Log.i(TAG, "⚡ Detetado 'A recolher' (tolerante 1→l / 0→o).")
            } else {
                Log.v(TAG, "Pickup não encontrado. Amostra: ${normalized.take(120)}")
            }
            return foundT
        }
    }

    private data class MatchedTimeDist(
        val lineIndex: Int,
        val timeMinutes: Int? = null,
        val distanceKm: Double? = null,
        var isTripLine: Boolean = false,
        var isPickupHint: Boolean = false // se a linha referir “distância”
    )

    private fun cleanLineForMatching(s: String): String =
        s.replaceFirst(Regex("^[^\\p{L}\\d]+"), "").trim()

    fun analyzeTextForOffer(visionText: Text, originalWidth: Int, originalHeight: Int): OfferData? {
        val raw = visionText.text ?: ""
        if (raw.isBlank()) {
            Log.w(TAG, "Texto OCR vazio.")
            return null
        }

        val corrected = applyOcrCorrections(raw)
        val pretty = normalizeSpaces(corrected)  // guardar
        val normalized = stripAccentsLower(pretty)      // matching
        val normalizedOneLine = normalized.replace(Regex("\\s+"), " ")

        Log.d(TAG, "OCR (corrigido): ${pretty.replace("\n", " | ")}")

        val hasEuro = normalized.contains('€') || normalized.contains("eur")
        val hasKm = Regex("\\bkm\\b").containsMatchIn(normalized)
        val hasMin = Regex("\\b(min\\.?|minutos?|\\dm\\b)").containsMatchIn(normalized)
        val hasKeyword = OFFER_KEYWORDS.any { normalizedOneLine.contains(it) }

        Log.d(TAG, "Validação: €=$hasEuro, km=$hasKm, min=$hasMin, kw=$hasKeyword")
        if (!(hasEuro && hasKm && hasMin && hasKeyword)) {
            Log.w(TAG, "Validação falhou.")
            return null
        }

        val valueStr = extractMoney(pretty) ?: run {
            Log.w(TAG, "Sem valor monetário.")
            return null
        }
        Log.i(TAG, "Valor: $valueStr €")

        // ---------- 3A) Viagem (padrões combinados) ----------
        var tripDuration = ""
        var tripDistance = ""
        for (pat in TRIP_COMBINED_PATTERNS) {
            val m = pat.find(normalized)
            if (m != null && m.groupValues.size >= 3) {
                m.groupValues[1].toIntOrNull()?.let { tripDuration = it.toString() }
                m.groupValues[2].replace(',', '.').toDoubleOrNull()
                    ?.let { tripDistance = String.format(Locale.US, "%.1f", it) }
                Log.i(TAG, "Trip (direto): ${tripDuration} min / ${tripDistance} km")
                break
            }
        }

        // ---------- 3B) Recolha (padrões combinados) ----------
        var pickupDuration = ""
        var pickupDistance = ""
        for (pat in PICKUP_COMBINED_PATTERNS) {
            val m = pat.find(normalized)
            if (m != null && m.groupValues.size >= 3) {
                m.groupValues[1].toIntOrNull()?.let { pickupDuration = it.toString() }
                m.groupValues[2].replace(',', '.').toDoubleOrNull()
                    ?.let { pickupDistance = String.format(Locale.US, "%.1f", it) }
                Log.i(TAG, "Pickup (direto): ${pickupDuration} min / ${pickupDistance} km")
                break
            }
        }

        // ---------- 4) Fallback por linhas (preenche o que faltar) ----------
        val lines = normalized.split('\n')
        var tripLineIndex = -1
        val candidates = mutableListOf<MatchedTimeDist>()

        lines.forEachIndexed { idx, lineRaw ->
            val line = cleanLineForMatching(normalizeSpaces(lineRaw))
            if (line.isEmpty()) return@forEachIndexed

            if (tripLineIndex == -1 && TRIP_LINE_PATTERNS.any { it.containsMatchIn(line) }) {
                tripLineIndex = idx
            }

            val t = TIME_PATTERN.matcher(line)
            val time = if (t.find()) t.group(1)?.toIntOrNull() else null

            val d = KM_PATTERN.matcher(line)
            val dist = if (d.find()) d.group(1)?.replace(',', '.')?.toDoubleOrNull() else null

            if (time != null || dist != null) {
                val isPickupHint = Regex("\\bdistancia\\b").containsMatchIn(line)
                candidates += MatchedTimeDist(
                    lineIndex = idx,
                    timeMinutes = time,
                    distanceKm = dist,
                    isTripLine = (idx == tripLineIndex),
                    isPickupHint = isPickupHint
                )
            }
        }

        if (candidates.isNotEmpty()) {
            if (tripLineIndex >= 0) {
                val trip = candidates.firstOrNull { it.isTripLine }
                if (trip != null) {
                    if (tripDuration.isBlank()) tripDuration = trip.timeMinutes?.toString() ?: ""
                    if (tripDistance.isBlank()) tripDistance = trip.distanceKm?.let { String.format(Locale.US, "%.1f", it) } ?: ""
                }
                val beforeTrip = candidates.filter { !it.isTripLine && it.lineIndex < tripLineIndex }
                val pickup = beforeTrip.maxByOrNull {
                    // favorece linhas com “distancia”; senão, índice mais próximo
                    var score = it.lineIndex
                    if (it.isPickupHint) score += 1000
                    score
                }
                if (pickup != null) {
                    if (pickupDuration.isBlank()) pickupDuration = pickup.timeMinutes?.toString() ?: ""
                    if (pickupDistance.isBlank()) pickupDistance = pickup.distanceKm?.let { String.format(Locale.US, "%.1f", it) } ?: ""
                }
            } else {
                // Sem rótulo viagem: tenta inferir
                val withVals = candidates.filter { it.timeMinutes != null || it.distanceKm != null }
                if (withVals.size >= 2) {
                    val pickup = withVals.firstOrNull { it.isPickupHint } ?: withVals.first()
                    val trip = withVals.maxByOrNull { (it.timeMinutes ?: 0) * 60 + ((it.distanceKm ?: 0.0) * 10).toInt() } ?: withVals.last()
                    if (pickupDuration.isBlank()) pickupDuration = pickup.timeMinutes?.toString() ?: ""
                    if (pickupDistance.isBlank()) pickupDistance = pickup.distanceKm?.let { String.format(Locale.US, "%.1f", it) } ?: ""
                    if (tripDuration.isBlank()) tripDuration = trip.timeMinutes?.toString() ?: ""
                    if (tripDistance.isBlank()) tripDistance = trip.distanceKm?.let { String.format(Locale.US, "%.1f", it) } ?: ""
                } else if (withVals.size == 1) {
                    val only = withVals.first()
                    val seemsTrip = (only.timeMinutes ?: 0) >= 6 || (only.distanceKm ?: 0.0) >= 3.5
                    if (seemsTrip) {
                        if (tripDuration.isBlank()) tripDuration = only.timeMinutes?.toString() ?: ""
                        if (tripDistance.isBlank()) tripDistance = only.distanceKm?.let { String.format(Locale.US, "%.1f", it) } ?: ""
                    } else {
                        if (pickupDuration.isBlank()) pickupDuration = only.timeMinutes?.toString() ?: ""
                        if (pickupDistance.isBlank()) pickupDistance = only.distanceKm?.let { String.format(Locale.US, "%.1f", it) } ?: ""
                    }
                }
            }
        }

        Log.i(TAG, "Pickup: $pickupDuration min / $pickupDistance km  |  Trip: $tripDuration min / $tripDistance km")

        // ---------- 5) Tipo de serviço ----------
        val serviceType = detectServiceType(normalizedOneLine)

        // ---------- 6) Construção ----------
        if (pickupDuration.isBlank() && pickupDistance.isBlank() && tripDuration.isBlank() && tripDistance.isBlank()) {
            Log.w(TAG, "Sem tempos/distâncias suficientes.")
            return null
        }

        val offerData = OfferData(
            value = valueStr,
            pickupDistance = pickupDistance,
            tripDistance = tripDistance,
            pickupDuration = pickupDuration,
            tripDuration = tripDuration,
            serviceType = serviceType,
            rawText = pretty.take(800)
        )
        offerData.updateCalculatedTotals()

        return if (offerData.isValid() && (!offerData.distance.isNullOrEmpty() || !offerData.duration.isNullOrEmpty())) {
            Log.i(TAG, "OfferData válido.")
            offerData
        } else {
            Log.w(TAG, "OfferData incompleto/ inválido.")
            null
        }
    }

    // ---------- Dinheiro ----------
    private fun extractMoney(prettyText: String): String? {
        val near = MONEY_NEAR_EURO.matcher(prettyText)
        val hits = mutableListOf<Pair<IntRange, String>>()
        while (near.find()) {
            val g1 = near.group(1)
            val g2 = near.group(2)
            val num = (g1 ?: g2) ?: continue
            val range = near.start()..near.end()
            hits += range to num
        }
        val filtered = hits.filterNot { (_, num) ->
            val idx = prettyText.indexOf(num)
            if (idx < 0) false
            else {
                val tail = prettyText.substring(min(idx + num.length, prettyText.length)).trimStart()
                tail.startsWith("km", ignoreCase = true) || tail.startsWith("min", ignoreCase = true)
            }
        }
        val chosen = (filtered.ifEmpty { hits }).minByOrNull { (r, _) -> r.last - r.first } ?: return null
        return normalizeMoneyNumber(chosen.second)
    }

    private fun normalizeMoneyNumber(s: String): String? {
        val raw = s.trim()
        val hasComma = raw.contains(',')
        val hasDot = raw.contains('.')

        val normalized = when {
            hasComma && hasDot -> {
                val lastComma = raw.lastIndexOf(',')
                val lastDot = raw.lastIndexOf('.')
                val decimalIsComma = lastComma > lastDot
                val cleaned = raw.replace(Regex("[^0-9,\\.]"), "")
                if (decimalIsComma) cleaned.replace(".", "").replace(',', '.')
                else cleaned.replace(",", "")
            }
            hasComma -> raw.replace(".", "").replace(',', '.')
            else -> raw
        }
        return normalized.toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) }
    }

    // ---------- Serviço ----------
    private fun detectServiceType(normalizedOneLine: String): String {
        for (type in SERVICE_TYPES) {
            val pat = Pattern.compile("\\b${Pattern.quote(type)}\\b")
            if (pat.matcher(normalizedOneLine).find()) {
                return type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
        }
        for (type in SERVICE_TYPES) {
            if (normalizedOneLine.contains(type)) {
                return type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
        }
        return ""
    }

    // ---------- ROI ----------
    fun getRegionsOfInterest(screenWidth: Int, screenHeight: Int): List<Rect> {
        val topMarginPercent = 0.05
        val startYPercent = 0.30
        val topMarginPx = (screenHeight * topMarginPercent).toInt()
        val startYPx = (screenHeight * startYPercent).toInt() + topMarginPx
        val validStartY = startYPx.coerceIn(0, screenHeight - 1)
        val bottomRegion = Rect(0, validStartY, screenWidth, screenHeight)
        if (bottomRegion.height() <= 0) {
            Log.e(TAG, "ROI inválida. Ecrã inteiro.")
            return listOf(Rect(0, 0, screenWidth, screenHeight))
        }
        return listOf(bottomRegion)
    }

    fun cropToRegion(original: Bitmap, region: Rect): Bitmap? {
        val safe = Rect(
            region.left.coerceIn(0, original.width - 1),
            region.top.coerceIn(0, original.height - 1),
            region.right.coerceIn(region.left + 1, original.width),
            region.bottom.coerceIn(region.top + 1, original.height)
        )
        if (safe.width() <= 0 || safe.height() <= 0) {
            Log.w(TAG, "Região inválida: $safe")
            return null
        }
        return try {
            Bitmap.createBitmap(original, safe.left, safe.top, safe.width(), safe.height())
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao recortar bitmap: ${e.message}", e)
            null
        }
    }
}
