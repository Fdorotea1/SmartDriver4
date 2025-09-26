package com.example.smartdriver.utils

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.text.Text
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.min
import kotlin.math.abs

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

        /** Correções agressivas — usar apenas para números. NÃO usar para moradas. */
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

        // ---------- Regex valores/tempos/distâncias ----------
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

        // COMBINADOS (min+km na mesma linha)
        private val TRIP_COMBINED_PATTERNS = listOf(
            Regex("\\bviagem\\s+de\\s+(\\d{1,3})\\s*min\\b.*?\\(\\s*(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*km\\s*\\)"),
            Regex("\\bviagem\\b.*?(\\d{1,3})\\s*min\\b.*?(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*km"),
            Regex("\\bpercurso\\s+de\\s+(\\d{1,3})\\s*min\\b.*?\\(\\s*(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*km\\s*\\)")
        )
        private val PICKUP_COMBINED_PATTERNS = listOf(
            Regex("\\b(\\d{1,3})\\s*min\\b\\s*\\(\\s*(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*km\\s*\\)\\s*(?:de\\s*)?distancia\\b"),
            Regex("\\bdistancia\\b.*?(\\d{1,3})\\s*min\\b.*?\\(\\s*(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*km\\s*\\)")
        )

        // ---------- Moradas / marcadores ----------
        private val DESTINO_MARKER = Regex("\\bdestino\\b")
        private val VIAGEM_MARKER  = Regex("\\bviagem(?:\\s+de)?\\b|\\bpercurso(?:\\s+de)?\\b")

        // Tokens de via (abreviações e formas frequentes PT-PT)
        private val STREET_TOKENS = listOf(
            "rua","r.","avenida","av.","travessa","tv.","praça","praca","estrada","estr.","alameda",
            "largo","rotunda","bairro","urbanizacao","urbanização","calçada","calcada","cais","praceta",
            "estr. da","estr. do","estr. de","estr. das","estr. dos"
        )

        // Cidades/locais comuns: pequeno conjunto (podes acrescentar)
        private val CITY_HINTS = listOf(
            "lisboa","oeiras","odivelas","loures","amadora","cascais","sintra","almada",
            "barreiro","seixal","matosinhos","porto","gondomar","vila nova de gaia",
            "braga","coimbra","faro","aveiro","setúbal","setubal","leiria"
        )

        // Linhas de UI/ruído que não são morada
        private val UI_NOISE = listOf(
            "partilhar","editar","corresponder","adicionar","eliminar","uber","smartdriver",
            "ver no mapa","mapa","carregamento rapido","carregamento rápido","a caminho","ok"
        )

        // Detector dedicado: "A recolher"
        fun detectPickupState(visionText: Text): Boolean {
            val raw = visionText.text ?: return false
            if (raw.isBlank()) return false
            val pretty = normalizeSpaces(raw)
            val normalized = stripAccentsLower(pretty)
            val pickupRegex = Regex("\\ba\\s+recolher\\b")
            if (pickupRegex.containsMatchIn(normalized)) return true
            val tolerant = normalized.replace('1', 'l').replace('0', 'o')
            return pickupRegex.containsMatchIn(tolerant)
        }
    }

    private data class MatchedTimeDist(
        val lineIndex: Int,
        val timeMinutes: Int? = null,
        val distanceKm: Double? = null,
        var isTripLine: Boolean = false,
        var isPickupHint: Boolean = false
    )

    private fun cleanLineForMatching(s: String): String =
        s.replaceFirst(Regex("^[^\\p{L}\\d]+"), "").trim()

    fun analyzeTextForOffer(visionText: Text, originalWidth: Int, originalHeight: Int): OfferData? {
        val raw = visionText.text ?: ""
        if (raw.isBlank()) {
            Log.w(TAG, "Texto OCR vazio.")
            return null
        }

        // Números
        val corrected = applyOcrCorrections(raw)
        val pretty = normalizeSpaces(corrected)
        val normalized = stripAccentsLower(pretty)
        val normalizedOneLine = normalized.replace(Regex("\\s+"), " ")

        // MORADAS — sem correções agressivas
        val prettyForAddr = normalizeSpaces(raw)
        val originalAddrLines = prettyForAddr.split('\n').map { it.trim() }
        val addrPrettyLines: List<String> =
            if (originalAddrLines.size <= 1) segmentIntoPseudoLines(prettyForAddr) else originalAddrLines
        val addrNormLines: List<String> = addrPrettyLines.map { stripAccentsLower(normalizeSpaces(it)) }

        val hasEuro = normalized.contains('€') || normalized.contains("eur")
        val hasKm   = Regex("\\bkm\\b").containsMatchIn(normalized)
        val hasMin  = Regex("\\b(min\\.?|minutos?|\\dm\\b)").containsMatchIn(normalized)
        val hasKeyword = OFFER_KEYWORDS.any { normalizedOneLine.contains(it) }

        if (!(hasEuro && hasKm && hasMin && hasKeyword)) return null

        val valueStr = extractMoney(pretty) ?: return null

        // ---------- Viagem (padrões combinados) ----------
        var tripDuration = ""
        var tripDistance = ""
        for (pat in TRIP_COMBINED_PATTERNS) {
            val m = pat.find(normalized)
            if (m != null && m.groupValues.size >= 3) {
                m.groupValues[1].toIntOrNull()?.let { tripDuration = it.toString() }
                m.groupValues[2].replace(',', '.').toDoubleOrNull()
                    ?.let { tripDistance = String.format(Locale.US, "%.1f", it) }
                break
            }
        }

        // ---------- Recolha (padrões combinados) ----------
        var pickupDuration = ""
        var pickupDistance = ""
        for (pat in PICKUP_COMBINED_PATTERNS) {
            val m = pat.find(normalized)
            if (m != null && m.groupValues.size >= 3) {
                m.groupValues[1].toIntOrNull()?.let { pickupDuration = it.toString() }
                m.groupValues[2].replace(',', '.').toDoubleOrNull()
                    ?.let { pickupDistance = String.format(Locale.US, "%.1f", it) }
                break
            }
        }

        // ---------- Fallback por linhas (preenche tempos/distâncias) ----------
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
                    var score = it.lineIndex
                    if (it.isPickupHint) score += 1000
                    score
                }
                if (pickup != null) {
                    if (pickupDuration.isBlank()) pickupDuration = pickup.timeMinutes?.toString() ?: ""
                    if (pickupDistance.isBlank()) pickupDistance = pickup.distanceKm?.let { String.format(Locale.US, "%.1f", it) } ?: ""
                }
            } else {
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

        val serviceType = detectServiceType(normalizedOneLine)

        // ---------- Moradas (com pseudo-linhas resegmentadas) ----------
        val (moradaRecolha, moradaDestino) = extractAddresses(addrPrettyLines, addrNormLines)

        // ---------- Construção ----------
        if (pickupDuration.isBlank() && pickupDistance.isBlank() && tripDuration.isBlank() && tripDistance.isBlank()) {
            return null
        }

        val offerData = OfferData(
            value = valueStr,
            pickupDistance = pickupDistance,
            tripDistance = tripDistance,
            pickupDuration = pickupDuration,
            tripDuration = tripDuration,
            serviceType = serviceType,
            moradaRecolha = moradaRecolha,
            moradaDestino = moradaDestino,
            rawText = prettyForAddr.take(800)
        )
        offerData.updateCalculatedTotals()

        return if (offerData.isValid() && (!offerData.distance.isNullOrEmpty() || !offerData.duration.isNullOrEmpty())) {
            offerData
        } else null
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
        val serviceTypes = setOf(
            "uberx", "comfort", "black", "green", "xl", "pet", "wav", "assist", "pool",
            "flash", "taxi", "business comfort", "ubergreen", "green teens", "exclusivo"
        )
        for (type in serviceTypes) {
            val pat = Pattern.compile("\\b${Pattern.quote(type)}\\b")
            if (pat.matcher(normalizedOneLine).find()) {
                return type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
        }
        for (type in serviceTypes) {
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
        if (safe.width() <= 0 || safe.height() <= 0) return null
        return try {
            Bitmap.createBitmap(original, safe.left, safe.top, safe.width(), safe.height())
        } catch (_: Exception) { null }
    }

    // ---------- Pseudo-linhas ----------
    private fun segmentIntoPseudoLines(pretty: String): List<String> {
        var s = normalizeSpaces(pretty)

        // quebra depois de “) de distância”
        s = s.replace(Regex("\\)\\s*(?:de\\s*)?distânci[ao]", RegexOption.IGNORE_CASE), ") de distância\n")
        s = s.replace(Regex("\\)\\s*(?:de\\s*)?distancia", RegexOption.IGNORE_CASE), ") de distancia\n")

        // quebra antes de “Viagem …”
        s = s.replace(Regex("\\b(Viagem\\s+de)\\b", RegexOption.IGNORE_CASE), "\n$1")
        s = s.replace(Regex("\\b(Viagem)\\b", RegexOption.IGNORE_CASE), "\n$1")

        // quebra imediatamente DEPOIS de “Viagem de X min (...)”
        s = s.replace(Regex("(Viagem\\s+de\\s+\\d{1,3}\\s*min[^\\n]*?\\))", RegexOption.IGNORE_CASE), "$1\n")

        // quebra antes de “Destino”
        s = s.replace(Regex("\\b(Destino)\\b", RegexOption.IGNORE_CASE), "\n$1")

        // quebra antes de botões/rodapés
        s = s.replace(Regex("\\b(Aceitar|Corresponder|Novos pedidos|Partilhar|Editar|Adicionar|Eliminar)\\b", RegexOption.IGNORE_CASE), "\n$1")

        // quebra antes de pequenos badges
        s = s.replace(Regex("\\b\\<?\\s*\\d+\\s*km\\s*do\\s*carregamento\\b.*", RegexOption.IGNORE_CASE), "\n$0")

        return s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ---------- EXTRAÇÃO DE MORADAS ----------
    private fun extractAddresses(linesPretty: List<String>, linesNormalized: List<String>): Pair<String, String> {

        fun stripNoiseStart(s: String): String =
            s.replaceFirst(Regex("^[\\s•·▶▷⦿●○▸▹▻▏▎▍\\-|—–]*"), "").trim()

        fun stripNoiseTail(s: String): String {
            var t = s
            // bullets / info lateral
            t = t.replace(Regex("\\s*[•·•]\\s*.*$"), "")
            // parenteses com “distância”
            t = t.replace(Regex("\\(.*?distânci[ao].*?\\)", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\(.*?distancia.*?\\)", RegexOption.IGNORE_CASE), "")
            // sufixos óbvios não-endereço
            t = t.replace(Regex("\\b(ver\\s+no\\s+mapa|ver\\s+no\\s+google\\s+maps|detalhes|a\\s+caminho)\\b.*",
                RegexOption.IGNORE_CASE), "")
            // unidades/tempos residuais
            t = t.replace(Regex("\\b\\d+\\s*(?:min\\.?|m)\\b.*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\b\\d+[.,]?\\d*\\s*km\\b.*", RegexOption.IGNORE_CASE), "")
            return t.trim()
        }

        fun isUiNoise(norm: String): Boolean =
            UI_NOISE.any { norm.contains(it) }

        fun looksLikeAddress(norm: String): Boolean {
            val n = norm.trim()
            if (n.isEmpty()) return false
            if (!n.matches(Regex(".*[a-z].*", RegexOption.IGNORE_CASE))) return false
            if (n.contains('€') || Regex("\\bkm\\b").containsMatchIn(n) ||
                Regex("\\b(min\\.?|minutos?|\\dm\\b)").containsMatchIn(n)) return false
            if (isUiNoise(n)) return false
            if (!n.matches(Regex("^[\\p{L}\\p{N}\\s,.'ºª°#/-]{8,}$"))) return false

            val hasStreetToken = STREET_TOKENS.any { n.contains(it) }
            val hasComma = n.contains(',')
            val hasNumber = Regex("\\b\\d{1,4}[A-Z]?\\b").containsMatchIn(n)
            val hasPostcode = Regex("\\b\\d{4}-\\d{3}\\b").containsMatchIn(n)
            val hasCityHint = CITY_HINTS.any { n.contains(it) }

            return hasStreetToken || hasPostcode || (hasComma && hasNumber) || (hasNumber && hasCityHint)
        }

        fun cleanAddressCandidate(s: String?): String {
            if (s.isNullOrBlank()) return ""
            var t = stripNoiseStart(s)
            t = stripNoiseTail(t)
            t = t.replace(Regex("\\s{2,}"), " ").trim().trim(',')
            return t
        }

        fun scoreAddressCandidate(norm: String, proximityBoost: Int = 0): Int {
            var score = 0
            if (looksLikeAddress(norm)) score += 60
            if (norm.contains(',')) score += 5
            if (Regex("\\b\\d{4}-\\d{3}\\b").containsMatchIn(norm)) score += 20
            if (Regex("\\b\\d{1,4}[A-Z]?\\b").containsMatchIn(norm)) score += 10
            if (STREET_TOKENS.any { norm.contains(it) }) score += 10
            if (CITY_HINTS.any { norm.contains(it) }) score += 8
            score += norm.length.coerceAtMost(40) / 2
            score += proximityBoost
            return score
        }

        // 1) localizar índices com “Viagem…” e/ou “Destino”
        var viagemIdx = -1
        var destinoIdx = -1
        for (i in linesNormalized.indices) {
            val ln = linesNormalized[i]
            if (viagemIdx == -1 && VIAGEM_MARKER.containsMatchIn(ln)) viagemIdx = i
            if (destinoIdx == -1 && DESTINO_MARKER.containsMatchIn(ln)) destinoIdx = i
        }

        // 2) construir candidatos “plausíveis” (já limpos) e filtrar UI
        data class Cand(val idx: Int, val pretty: String, val norm: String)
        val cands = mutableListOf<Cand>()
        for (i in linesNormalized.indices) {
            val pretty = linesPretty[i]
            val cleaned = cleanAddressCandidate(pretty)
            if (cleaned.isBlank()) continue
            val norm = stripAccentsLower(cleaned)
            if (isUiNoise(norm)) continue
            if (norm.contains('€') ||
                Regex("\\bkm\\b").containsMatchIn(norm) ||
                Regex("\\b(min\\.?|minutos?|\\dm\\b)").containsMatchIn(norm)) continue
            cands += Cand(i, cleaned, norm)
        }

        fun bestNear(index: Int, window: IntRange, forbid: String? = null): String? {
            if (index < 0) return null
            var best: Pair<Int, String>? = null
            for (c in cands) {
                if (c.idx in window) {
                    if (!forbid.isNullOrEmpty() && c.norm == forbid) continue
                    val prox = (50 - abs(c.idx - index)).coerceAtLeast(0)
                    val s = scoreAddressCandidate(c.norm, prox)
                    if (best == null || s > best!!.first) best = s to c.pretty
                }
            }
            return best?.second
        }

        var pickup: String? = null
        var dest: String? = null

        if (viagemIdx >= 0) {
            // antes da secção “Viagem” tende a estar a Recolha
            pickup = bestNear(viagemIdx, (viagemIdx - 4).coerceAtLeast(0)..(viagemIdx - 1))
            // depois da “Viagem” tende a estar o Destino
            dest   = bestNear(viagemIdx, (viagemIdx + 1)..(viagemIdx + 5))
        }
        if (destinoIdx >= 0) {
            // Se existir “Destino”, prioriza proximidade a ele
            dest = bestNear(destinoIdx, (destinoIdx + 1)..(destinoIdx + 6)) ?: dest
        }

        // Fallback: 1ª morada ≙ Recolha / última ≙ Destino
        if (pickup.isNullOrBlank() || dest.isNullOrBlank()) {
            val addrOnly = cands.filter { looksLikeAddress(it.norm) }
            if (addrOnly.isNotEmpty()) {
                if (pickup.isNullOrBlank()) pickup = addrOnly.first().pretty
                if (dest.isNullOrBlank())   dest   = if (addrOnly.size >= 2) addrOnly.last().pretty else ""
            }
        }

        if (!pickup.isNullOrBlank() && pickup == dest) dest = ""

        return (pickup ?: "").trim() to (dest ?: "").trim()
    }
}
