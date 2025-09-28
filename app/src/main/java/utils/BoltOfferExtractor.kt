package com.example.smartdriver.utils

import android.graphics.Rect

/**
 * Extrator específico para o cartão da Bolt (linha "13 min · 1.9 km").
 * Não mexe em ROIs. Foca-se em normalização do OCR e junção de tokens por linha.
 */
object BoltOfferExtractor {

    data class TimeDist(val minutes: Int?, val km: Double?)
    data class OcrToken(val text: String, val box: Rect) {
        val cx: Float get() = box.exactCenterX()
        val cy: Float get() = box.exactCenterY()
        val h: Float  get() = box.height().toFloat()
    }

    // === API pública ===
    fun extractTimeDistFromTokens(tokens: List<OcrToken>): TimeDist? {
        val lines = joinSameLine(tokens)
        return parseTimeDist(lines)
    }

    fun extractTimeDistFromLines(lines: List<String>): TimeDist? = parseTimeDist(lines)

    // === Normalização ===
    private fun normalizeOcr(s: String): String {
        val replaced = s.map { ch ->
            when (ch) {
                '\u00B7', // ·
                '\u2022', // •
                '\u00A0', // NBSP
                '\u202F'  // NNBSP
                -> ' '
                else -> ch
            }
        }.joinToString("")
        // Correções suaves típicas do OCR nesta faixa
        return replaced
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace('I', '1')
            .replace('l', '1')
    }

    // === Agrupar tokens por linha (mesma baseline) ===
    private fun joinSameLine(tokens: List<OcrToken>, yToleranceFrac: Float = 0.25f): List<String> {
        if (tokens.isEmpty()) return emptyList()
        val sorted = tokens.sortedBy { it.cy }
        val rows = mutableListOf<MutableList<OcrToken>>()
        for (t in sorted) {
            val last = rows.lastOrNull()
            val tol = (last?.map { it.h }?.average()?.toFloat() ?: t.h) * yToleranceFrac
            if (last == null || kotlin.math.abs(last.last().cy - t.cy) > tol) {
                rows.add(mutableListOf(t))
            } else {
                last.add(t)
            }
        }
        return rows.map { row -> row.sortedBy { it.cx }.joinToString(" ") { it.text } }
    }

    // === Parsers ===
    private val TIME_RE = Regex("""\b(\d{1,3})\s*min\b""", RegexOption.IGNORE_CASE)
    private val DIST_RE = Regex("""\b(\d{1,3}(?:[.,]\d{1,2})?)\s*(?:km|quil[oó]metros?)\b""", RegexOption.IGNORE_CASE)

    private fun parseTimeDist(lines: List<String>): TimeDist? {
        var minutes: Int? = null
        var km: Double? = null

        for (line in lines) {
            val norm = normalizeOcr(line)
            if (minutes == null) minutes = TIME_RE.find(norm)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (km == null) km = DIST_RE.find(norm)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
        }
        return if (minutes != null || km != null) TimeDist(minutes, km) else null
    }
}
