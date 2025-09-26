package com.example.smartdriver.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.DisplayMetrics
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Auto-trim robusto para remover status bar / dock / barras pretas do screenshot.
 *
 * Melhorias nesta versão:
 * - Heurística explícita para a barra de navegação (gestures/3 botões) na cauda da imagem.
 * - “Mordida” extra adaptativa no fundo, dependente da densidade (dp) e do conteúdo.
 * - Passo final (polish) mais agressivo quando a cauda mantém baixa entropia/variação.
 * - Laterais com pequena margem.
 */
object ScreenshotCropper {

    fun cropToVisibleContentAuto(
        context: Context,
        source: Bitmap,
        extraMarginDp: Float = 0f
    ): Bitmap {
        if (source.isRecycled) return source
        val w = source.width
        val h = source.height
        if (w <= 8 || h <= 8) return source

        // ---------- parâmetros ----------
        val stepX = max(2, w / 480)
        val stepY = max(2, h / 800)
        val darkThr = 28
        val brightThr = 240
        val flatVarThr = 6.0
        val flatRatioThr = 0.88
        val sideTrimPct = 0.01f

        // TOP
        val topMaxFrac = 0.12f
        val topConfirmWinDp = 36f
        val topConfirmRatioThr = 0.70

        // BOTTOM
        val bottomMaxFrac = 0.70f             // pode cortar até 70% em cenários DeX / barras gigantes
        val entropyBins = 16
        val lowEntropyThr = 1.6
        val lowMeanDarkThr = 88               // um pouco mais agressivo que antes
        val bottomConfirmWinDp = 72f          // janela maior
        val bottomConfirmRatioThr = 0.50      // maioria simples
        val bottomExtraBiteDpBase = 24f       // base da mordida fixa

        // Heurística específica para barra de navegação (gestures / 3 botões)
        val navScanFrac = 0.18f               // analisar últimos 18%
        val navMinRunDp = 18f                 // comprimento mínimo de “run”
        val navMaxRunDp = 64f                 // comprimento máximo razoável
        val navLowEntropyThr = 1.45
        val navFlatVarThr = 7.5

        // POLISH (passo final)
        val polishFracMin = 0.10f
        val polishFracMax = 0.16f
        val polishEntropyThr = 1.75
        val polishDarkMeanThr = 100

        fun dp(v: Float): Int {
            val dm: DisplayMetrics = context.resources.displayMetrics
            return (v * (dm.densityDpi / 160f)).toInt()
        }
        val topWinPx = max(stepY, dp(topConfirmWinDp))
        val botWinPx = max(stepY, dp(bottomConfirmWinDp))
        val bottomBitePx = dp(bottomExtraBiteDpBase)

        fun lum(c: Int): Int {
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            return ((0.2126 * r) + (0.7152 * g) + (0.0722 * b)).roundToInt().coerceIn(0, 255)
        }

        data class Row(val mean: Double, val variance: Double, val flatRatio: Double, val entropy: Double)
        fun rowFeatures(bmp: Bitmap, y: Int): Row {
            val w = bmp.width
            var sum = 0.0; var sumSq = 0.0; var extremes = 0; var cnt = 0
            val bins = IntArray(entropyBins); val binW = 256.0 / entropyBins
            var x = 0
            while (x < w) {
                val L = lum(bmp.getPixel(x, y))
                sum += L; sumSq += L * L
                if (L <= darkThr || L >= brightThr) extremes++
                var idx = (L / binW).toInt(); if (idx >= entropyBins) idx = entropyBins - 1
                bins[idx]++; cnt++; x += stepX
            }
            if (cnt == 0) return Row(0.0, 0.0, 0.0, 0.0)
            val mean = sum / cnt
            val variance = (sumSq / cnt) - (mean * mean)
            val flatRatio = extremes.toDouble() / cnt.toDouble()
            var H = 0.0
            for (b in bins) if (b != 0) { val p = b.toDouble() / cnt.toDouble(); H -= p * ln(p) }
            return Row(mean, variance, flatRatio, H)
        }

        fun isBarRow(r: Row): Boolean {
            val a = r.variance < flatVarThr && r.flatRatio > flatRatioThr
            val b = r.mean < lowMeanDarkThr && r.entropy < lowEntropyThr
            val c = r.mean > (255 - lowMeanDarkThr) && r.entropy < lowEntropyThr
            return a || b || c
        }

        // ---------- TOP ----------
        var top = 0
        run {
            var y = 0
            val hard = min(h - 1, (h * topMaxFrac).toInt())
            var lastBar = -1
            while (y < hard) {
                val f = rowFeatures(source, y)
                if (isBarRow(f)) { lastBar = y; y += stepY } else break
            }
            top = if (lastBar >= 0) {
                val end = min(h - 1, lastBar + topWinPx)
                var bars = 0; var tot = 0; var yy = lastBar
                while (yy <= end) { if (isBarRow(rowFeatures(source, yy))) bars++; tot++; yy += stepY }
                val ratio = if (tot > 0) bars.toDouble() / tot.toDouble() else 0.0
                if (ratio > topConfirmRatioThr) (lastBar + stepY) else (h * 0.02f).toInt()
            } else (h * 0.02f).toInt()
            top = top.coerceAtLeast(0)
        }

        // ---------- BOTTOM (barra/dock genérico) ----------
        var bottom = h - 1
        run {
            var y = h - 1
            val limit = max(0, (h * (1f - bottomMaxFrac)).toInt())
            var lastBar = -1
            while (y > limit) {
                val f = rowFeatures(source, y)
                if (isBarRow(f)) { lastBar = y; y -= stepY } else break
            }
            if (lastBar >= 0) {
                val start = max(limit, lastBar - botWinPx)
                var bars = 0; var tot = 0; var yy = lastBar
                while (yy >= start) { if (isBarRow(rowFeatures(source, yy))) bars++; tot++; yy -= stepY }
                val ratio = if (tot > 0) bars.toDouble() / tot.toDouble() else 0.0
                if (ratio > bottomConfirmRatioThr) {
                    bottom = (lastBar - stepY - bottomBitePx).coerceAtLeast(0)
                }
            }
            bottom = bottom.coerceIn(0, h - 1)
        }

        // ---------- Heurística explícita para NAV BAR (cauda) ----------
        run {
            val scanStart = max(0, (h * (1f - navScanFrac)).toInt())
            val minRun = dp(navMinRunDp)
            val maxRun = dp(navMaxRunDp)
            var bestStart = -1
            var bestEnd = -1

            var y = h - 1
            while (y >= scanStart) {
                val f = rowFeatures(source, y)
                val looksLikeNav = (f.entropy < navLowEntropyThr && f.variance < navFlatVarThr)
                if (!looksLikeNav) { y -= stepY; continue }
                // expandir run contíguo para cima
                var runStart = y
                var runEnd = y
                var yy = y - stepY
                while (yy >= scanStart) {
                    val ff = rowFeatures(source, yy)
                    if (ff.entropy < navLowEntropyThr && ff.variance < navFlatVarThr) { runStart = yy; yy -= stepY }
                    else break
                }
                val runLen = (runEnd - runStart).coerceAtLeast(0)
                if (runLen in minRun..maxRun) {
                    bestStart = runStart; bestEnd = runEnd; break
                }
                y = runStart - stepY
            }
            if (bestStart >= 0) {
                val bite = max(bottomBitePx, dp(20f))
                bottom = (bestStart - bite).coerceAtLeast(0)
            }
        }

        // ---------- Laterais + margem ----------
        val side = (w * sideTrimPct).toInt().coerceAtLeast(0)
        var left = side
        var right = w - side - 1

        val m = dp(extraMarginDp)
        top = (top + m).coerceAtMost(h - 2)
        bottom = (bottom - m).coerceAtLeast(top + 1)
        left = (left + m).coerceAtMost(w - 2)
        right = (right - m).coerceAtLeast(left + 1)

        // Sanidade mínima de altura
        val minH = (h * 0.35f).toInt()
        if (bottom - top + 1 < minH) {
            val fbTop = (h * 0.02f).toInt()
            val fbBottom = (h * 0.84f).toInt()      // um pouco mais agressivo
            val fbLeft = (w * 0.01f).toInt()
            val fbRight = (w * 0.99f).toInt() - 1
            return safeCreateBitmap(source, fbLeft, fbTop, fbRight - fbLeft + 1, fbBottom - fbTop + 1)
        }

        var out = safeCreateBitmap(source, left, top, right - left + 1, bottom - top + 1)

        // ---------- PASSO FINAL (polish agressivo) ----------
        out = try {
            val ww = out.width; val hh = out.height
            val tailStart = (hh * (1f - polishFracMax)).toInt().coerceAtLeast(0)
            val tailEnd = hh - 1
            val chkStart = (hh * (1f - polishFracMin)).toInt().coerceAtMost(hh - 1)

            var sumMean = 0.0; var sumEnt = 0.0; var sumVar = 0.0; var cnt = 0
            var y = chkStart
            while (y <= tailEnd) {
                val f = rowFeatures(out, y)
                sumMean += f.mean; sumEnt += f.entropy; sumVar += f.variance; cnt++; y += max(1, stepY / 2)
            }
            val meanTail = if (cnt > 0) sumMean / cnt else 255.0
            val entTail = if (cnt > 0) sumEnt / cnt else 2.5
            val varTail = if (cnt > 0) sumVar / cnt else 0.0

            val needExtraCut = (entTail < polishEntropyThr && (meanTail < polishDarkMeanThr || varTail < 8.0))
            if (needExtraCut) {
                val extra = (hh * 0.10f).toInt().coerceAtLeast(dp(18f))
                val nh = (hh - extra).coerceAtLeast((h * 0.35f).toInt())
                Bitmap.createBitmap(out, 0, 0, ww, nh)
            } else out
        } catch (_: Throwable) { out }

        return out
    }

    // Antigo (percentagens) mantido por compatibilidade
    fun cropToSystemBarsSafeArea(
        context: Context,
        source: Bitmap,
        extraMarginDp: Float = 0f,
        extraBottomTrimPercent: Float = 0.05f,
        extraTopTrimPercent: Float = 0.02f,
        extraLeftTrimPercent: Float = 0.01f,
        extraRightTrimPercent: Float = 0.01f
    ): Bitmap {
        if (source.isRecycled) return source
        val w = source.width
        val h = source.height
        if (w <= 8 || h <= 8) return source

        fun dp(v: Float): Int {
            val dm: DisplayMetrics = context.resources.displayMetrics
            return (v * (dm.densityDpi / 160f)).toInt()
        }

        val m = dp(extraMarginDp)
        val top = (h * extraTopTrimPercent).toInt() + m
        val bottom = h - (h * extraBottomTrimPercent).toInt() - m
        val left = (w * extraLeftTrimPercent).toInt() + m
        val right = w - (w * extraRightTrimPercent).toInt() - m
        val cw = (right - left).coerceAtLeast(1)
        val ch = (bottom - top).coerceAtLeast(1)
        return safeCreateBitmap(source, left.coerceAtLeast(0), top.coerceAtLeast(0), cw, ch)
    }

    // ---------- helpers ----------
    private fun safeCreateBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap {
        val sx = x.coerceIn(0, src.width - 1)
        val sy = y.coerceIn(0, src.height - 1)
        val sw = min(w, src.width - sx).coerceAtLeast(1)
        val sh = min(h, src.height - sy).coerceAtLeast(1)
        return try { Bitmap.createBitmap(src, sx, sy, sw, sh) } catch (_: Throwable) { src }
    }
}
