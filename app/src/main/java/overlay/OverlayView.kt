package com.example.smartdriver.overlay

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.util.Log
import android.view.View
import com.example.smartdriver.R
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.OfferRating
import java.math.RoundingMode
import java.text.DecimalFormat
import java.util.*
import kotlin.math.max

class OverlayView(context: Context) : View(context) {

    companion object {
        private const val TAG = "OverlayView"
        // Cores
        private val BORDER_COLOR_EXCELLENT = Color.parseColor("#4CAF50"); private val BORDER_COLOR_GOOD = Color.parseColor("#8BC34A")
        private val BORDER_COLOR_MEDIUM = Color.parseColor("#FFC107"); private val BORDER_COLOR_POOR = Color.parseColor("#F44336")
        private val BORDER_COLOR_UNKNOWN = Color.parseColor("#9E9E9E"); private val BACKGROUND_COLOR = Color.WHITE
        private val TEXT_COLOR_LABEL = Color.DKGRAY; private val TEXT_COLOR_VALUE = Color.BLACK

        // Dimensões (DP)
        private const val PADDING_DP = 12f // Aumentar padding geral
        private const val BORDER_WIDTH_DP = 3f
        private const val CORNER_RADIUS_DP = 12f
        private const val TEXT_SPACING_VERTICAL_DP = 3f // Espaço entre label e valor
        private const val LINE_SPACING_VERTICAL_DP = 6f // Espaço entre as linhas de dados
        private const val TEXT_SPACING_HORIZONTAL_DP = 15f // Espaço entre colunas

        // Tamanhos Fonte Base (SP) - Ajustados para caber melhor
        private const val LABEL_TEXT_SIZE_SP = 11f
        private const val VALUE_TEXT_SIZE_SP = 13f // Tamanho para km, tempo, valor oferta
        private const val HIGHLIGHT_VALUE_TEXT_SIZE_SP = 14f // Tamanho para €/km e €/h
    }

    // Estado e Configurações
    private var currentRating = OfferRating.UNKNOWN
    private var currentOfferData: OfferData? = null
    private var fontSizeScale = 1.0f
    private var viewAlpha = 0.90f

    // Paints
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = BACKGROUND_COLOR }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = BORDER_COLOR_UNKNOWN }
    private val labelTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_LABEL; typeface = Typeface.DEFAULT } // Alinhamento definido no draw
    private val valueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD } // Alinhamento definido no draw
    private val highlightValueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD } // Alinhamento definido no draw

    // Dimensões em Pixels
    private var paddingPx: Float = 0f; private var borderRadiusPx: Float = 0f
    private var textSpacingVerticalPx: Float = 0f; private var lineSpacingVerticalPx: Float = 0f
    private var textSpacingHorizontalPx: Float = 0f
    private var labelHeight: Float = 0f; private var valueHeight: Float = 0f; private var highlightValueHeight: Float = 0f
    private var density: Float = 0f

    // Formatador para arredondamento de €/Hora
    private val euroHoraFormatter = DecimalFormat("0.0").apply { // Formato com UMA casa decimal
        roundingMode = RoundingMode.HALF_UP
    }

    // Retângulos
    private val backgroundRect = RectF(); private val borderRect = RectF()

    init {
        Log.d(TAG, "OverlayView inicializada")
        alpha = viewAlpha; density = resources.displayMetrics.density
        updateDimensionsAndPaints()
    }

    private fun updateDimensionsAndPaints() {
        val scaledDensity = resources.displayMetrics.scaledDensity
        paddingPx = PADDING_DP * density; borderRadiusPx = CORNER_RADIUS_DP * density
        textSpacingVerticalPx = TEXT_SPACING_VERTICAL_DP * density
        lineSpacingVerticalPx = LINE_SPACING_VERTICAL_DP * density
        textSpacingHorizontalPx = TEXT_SPACING_HORIZONTAL_DP * density
        borderPaint.strokeWidth = BORDER_WIDTH_DP * density

        labelTextPaint.textSize = LABEL_TEXT_SIZE_SP * scaledDensity * fontSizeScale
        valueTextPaint.textSize = VALUE_TEXT_SIZE_SP * scaledDensity * fontSizeScale
        highlightValueTextPaint.textSize = HIGHLIGHT_VALUE_TEXT_SIZE_SP * scaledDensity * fontSizeScale

        labelHeight = labelTextPaint.descent() - labelTextPaint.ascent()
        valueHeight = valueTextPaint.descent() - valueTextPaint.ascent()
        highlightValueHeight = highlightValueTextPaint.descent() - highlightValueTextPaint.ascent()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateDimensionsAndPaints()

        // --- Calcular Largura Mínima ---
        // Medir largura das colunas para estimar largura mínima necessária
        val col1Width = max(labelTextPaint.measureText("km totais"), highlightValueTextPaint.measureText("99.99"))
        val col2Width = max(labelTextPaint.measureText("tempo"), valueTextPaint.measureText("999m"))
        val col3Width = max(labelTextPaint.measureText("Valor Oferta"), highlightValueTextPaint.measureText("999.9€")) // €/h é highlight

        val requiredWidth = (paddingPx * 2) + col1Width + textSpacingHorizontalPx + col2Width + textSpacingHorizontalPx + col3Width

        // --- Calcular Altura Mínima ---
        // Altura = 2*padding + AlturaLinha1 + espacoLinhas + AlturaLinha2
        // AlturaLinha = alturaLabel + espacoTexto + alturaValor
        val firstRowHeight = labelHeight + textSpacingVerticalPx + highlightValueHeight // Linha de cima usa highlight
        val secondRowHeight = labelHeight + textSpacingVerticalPx + valueHeight // Linha de baixo usa normal
        val requiredHeight = (paddingPx * 2) + firstRowHeight + lineSpacingVerticalPx + secondRowHeight

        // Resolve usando o tamanho calculado como mínimo sugerido
        val measuredWidth = resolveSize(requiredWidth.toInt(), widthMeasureSpec)
        val measuredHeight = resolveSize(requiredHeight.toInt(), heightMeasureSpec)

        setMeasuredDimension(measuredWidth, measuredHeight)
        Log.d(TAG,"onMeasure 2x3 v2 - Required: ${requiredWidth.toInt()}x${requiredHeight.toInt()}, Measured: ${measuredWidth}x$measuredHeight")
    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { /* ... (inalterado) ... */ super.onSizeChanged(w,h,oldw,oldh); backgroundRect.set(0f,0f,w.toFloat(),h.toFloat()); val hb=borderPaint.strokeWidth/2f; borderRect.set(hb,hb,w-hb,h-hb); Log.d(TAG,"onSizeChanged: ${w}x$h") }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(backgroundRect, borderRadiusPx, borderRadiusPx, backgroundPaint)
        borderPaint.color = getBorderColor(currentRating)
        canvas.drawRoundRect(borderRect, borderRadiusPx, borderRadiusPx, borderPaint)
        drawOfferDetailsLayout2x3ColumnsAligned(canvas) // Chama a função de desenho correta
    }

    /** Desenha os detalhes da oferta em layout 2x3 colunas com alinhamento ajustado */
    private fun drawOfferDetailsLayout2x3ColumnsAligned(canvas: Canvas) {
        updateDimensionsAndPaints()

        // --- Obter e Formatar Dados ---
        val euroPerKmStr = currentOfferData?.calculateProfitability()?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
        // Arredonda €/Hora para 1 casa decimal e formata
        val euroPerHourStr = currentOfferData?.calculateValuePerHour()?.let {
            val roundedValue = euroHoraFormatter.format(it).replace(",",".") // Arredonda e usa ponto
            "${roundedValue}€" // Adiciona símbolo €
        } ?: "--"
        val totalKmStr = currentOfferData?.calculateTotalDistance()?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f km", it) } ?: "--"
        val totalTimeStr = currentOfferData?.calculateTotalTimeMinutes()?.takeIf { it > 0 }?.let { "$it m" } ?: "--"
        val mainValueStr = currentOfferData?.value?.takeIf { it.isNotEmpty() }?.let { "$it €" } ?: "--"

        // --- Calcular Posições ---
        // Posições X: Coluna 1 (Esquerda), Coluna 2 (Centro), Coluna 3 (Direita)
        val leftColX = paddingPx
        val centerColX = measuredWidth / 2f
        val rightColX = measuredWidth - paddingPx

        // Posições Y (Baselines)
        val topLabelY = paddingPx + labelHeight - labelTextPaint.descent()
        val topValueY = topLabelY + highlightValueHeight + textSpacingVerticalPx - highlightValueTextPaint.descent() // Usa altura do highlight
        val bottomLabelY = topValueY + lineSpacingVerticalPx + labelHeight - labelTextPaint.descent()
        val bottomValueY = bottomLabelY + valueHeight + textSpacingVerticalPx - valueTextPaint.descent()

        // --- Desenhar ---

        // Coluna 1: €/Km e km totais (Alinhado à Esquerda)
        labelTextPaint.textAlign = Paint.Align.LEFT
        highlightValueTextPaint.textAlign = Paint.Align.LEFT
        valueTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("€/Km", leftColX, topLabelY, labelTextPaint)
        canvas.drawText(euroPerKmStr, leftColX, topValueY, highlightValueTextPaint)
        canvas.drawText("km totais", leftColX, bottomLabelY, labelTextPaint)
        canvas.drawText(totalKmStr, leftColX, bottomValueY, valueTextPaint)

        // Coluna 2: Tempo (Centralizado)
        labelTextPaint.textAlign = Paint.Align.CENTER
        valueTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("tempo", centerColX, topLabelY, labelTextPaint) // Desenha label tempo
        canvas.drawText(totalTimeStr, centerColX, topValueY, valueTextPaint) // Desenha valor tempo

        // Coluna 3: €/Hora e Valor Oferta (Alinhado à Direita)
        labelTextPaint.textAlign = Paint.Align.RIGHT
        highlightValueTextPaint.textAlign = Paint.Align.RIGHT
        valueTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("€/Hora", rightColX, topLabelY, labelTextPaint)
        canvas.drawText(euroPerHourStr, rightColX, topValueY, highlightValueTextPaint)
        canvas.drawText("Valor Oferta", rightColX, bottomLabelY, labelTextPaint) // Label alterado
        canvas.drawText(mainValueStr, rightColX, bottomValueY, valueTextPaint)
    }

    // --- Métodos de atualização (inalterados) ---
    fun updateFontSize(scale: Float) { Log.d(TAG, "Atualizando escala fonte: $scale"); fontSizeScale = scale.coerceIn(0.5f, 2.0f); updateDimensionsAndPaints(); requestLayout(); invalidate() }
    fun updateAlpha(alphaValue: Float) { Log.d(TAG, "Atualizando alpha: $alphaValue"); viewAlpha = alphaValue.coerceIn(0.0f, 1.0f); this.alpha = viewAlpha; invalidate() }
    fun updateState(rating: OfferRating, offerData: OfferData?) { Log.d(TAG, "Atualizando estado: $rating"); currentRating = rating; currentOfferData = offerData; requestLayout(); invalidate() }
    private fun getBorderColor(rating: OfferRating): Int { return when (rating) { OfferRating.EXCELLENT -> BORDER_COLOR_EXCELLENT; OfferRating.GOOD -> BORDER_COLOR_GOOD; OfferRating.MEDIUM -> BORDER_COLOR_MEDIUM; OfferRating.POOR -> BORDER_COLOR_POOR; else -> BORDER_COLOR_UNKNOWN } }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); Log.d(TAG, "OverlayView detached.") }
}