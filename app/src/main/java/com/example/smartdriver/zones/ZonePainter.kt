package com.example.smartdriver.zones

import android.graphics.Paint

/**
 * Gera Paints simples (sem padrões) com cache.
 * Mantém também *wrappers* compatíveis com nomes antigos (makeFillPaint/makeStrokePaint).
 */
object ZonePainter {

    private val fillCache = HashMap<Int, Paint>()
    private val strokeCache = HashMap<String, Paint>() // "${color}_${width}"

    fun fillPaint(style: ZoneStyle): Paint {
        val key = style.fillColor
        return fillCache[key] ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            this.style = Paint.Style.FILL
            color = style.fillColor
            fillCache[key] = this
        }
    }

    fun strokePaint(style: ZoneStyle): Paint {
        val key = "${style.strokeColor}_${style.strokeWidthPx}"
        return strokeCache[key] ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            this.style = Paint.Style.STROKE
            strokeWidth = style.strokeWidthPx
            color = style.strokeColor
            strokeCache[key] = this
        }
    }

    // ---------- Compat wrappers (para código antigo) ----------
    fun makeFillPaint(style: ZoneStyle) = fillPaint(style)
    fun makeStrokePaint(style: ZoneStyle) = strokePaint(style)
}
