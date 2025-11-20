package com.example.smartdriver.utils

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Utilitário de OCR otimizado para ML Kit Standalone (Text Recognition v2).
 * - Downscale adaptativo (~2MP por defeito) para reduzir latência e GC.
 * - Garante ARGB_8888 quando necessário.
 * - Método com throttling opcional para fontes de frames frequentes.
 *
 * Uso básico:
 *   OcrTextRecognizer.getInstance().process(bitmap).addOnSuccessListener { text -> ... }
 */
class OcrTextRecognizer private constructor(
    private val targetMegapixels: Double = 2.0,  // ~2MP por defeito (bom equilíbrio)
    private val maxEdgePx: Int = 2048,           // limite superior de lado
    private val minEdgePx: Int = 720             // não reduzir abaixo disto em lado maior
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val lastCallUptimeMs = AtomicLong(0L)

    /** Processa bitmap com otimização. */
    fun process(bitmap: Bitmap, rotationDegrees: Int = 0): Task<Text> {
        val prepped = prepareBitmap(bitmap)
        return recognizer.process(InputImage.fromBitmap(prepped, rotationDegrees))
    }

    /**
     * Versão com “throttling”: só deixa avançar se passaram [minIntervalMs] ms
     * desde a última chamada. Caso contrário lança IllegalStateException.
     */
    fun processThrottled(bitmap: Bitmap, rotationDegrees: Int = 0, minIntervalMs: Long = 300L): Task<Text> {
        val now = SystemClock.uptimeMillis()
        val last = lastCallUptimeMs.get()
        if (now - last < minIntervalMs) {
            throw IllegalStateException("OCR throttled: ${now - last}ms < $minIntervalMs ms")
        }
        lastCallUptimeMs.set(now)
        return process(bitmap, rotationDegrees)
    }

    fun close() {
        try { recognizer.close() } catch (_: Exception) {}
    }

    // ----------------- Internos -----------------

    private fun prepareBitmap(src: Bitmap): Bitmap {
        // Garantir ARGB_8888 (alguns formatos penalizam o OCR)
        val base = if (src.config != Bitmap.Config.ARGB_8888 && src.config != null) {
            src.copy(Bitmap.Config.ARGB_8888, false)
        } else src

        val w = base.width
        val h = base.height
        val pixels = w.toLong() * h.toLong()

        // Target de píxeis pelo objetivo de MP
        val targetPixels = (targetMegapixels * 1_000_000.0).toLong().coerceAtLeast(400_000L)
        val scaleByMp = sqrt(targetPixels.toDouble() / pixels.toDouble()).coerceAtMost(1.0)

        val maxSide = max(w, h).toDouble()
        val scaleMaxEdge = min(1.0, maxEdgePx / maxSide)
        val scaleMinEdge = min(1.0, (minEdgePx / maxSide))

        val rawScale = min(scaleByMp, scaleMaxEdge)
        val finalScale = max(rawScale, scaleMinEdge)

        // Se a diferença de escala for insignificante, não escalar
        if (abs(finalScale - 1.0) < 0.02) return base

        val newW = (w * finalScale).toInt().coerceAtLeast(1)
        val newH = (h * finalScale).toInt().coerceAtLeast(1)

        return try {
            Bitmap.createScaledBitmap(base, newW, newH, /* filter= */ true)
        } catch (_: Exception) {
            base
        }
    }

    companion object {
        @Volatile private var INSTANCE: OcrTextRecognizer? = null

        fun getInstance(): OcrTextRecognizer =
            INSTANCE ?: synchronized(this) { INSTANCE ?: OcrTextRecognizer().also { INSTANCE = it } }

        /** Opcional: reconfigurar parâmetros (p.ex., mais qualidade ou mais velocidade). */
        @JvmStatic
        fun configure(targetMegapixels: Double = 2.0, maxEdgePx: Int = 2048, minEdgePx: Int = 720) {
            synchronized(this) { INSTANCE = OcrTextRecognizer(targetMegapixels, maxEdgePx, minEdgePx) }
        }
    }
}
