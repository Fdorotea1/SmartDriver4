package com.example.smartdriver.overlay

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.util.Log
import android.view.View
// Removido import de com.example.smartdriver.R se não for usado diretamente aqui
import com.example.smartdriver.utils.OfferData
// import com.example.smartdriver.utils.OfferRating // Removido - Não usamos mais
import com.example.smartdriver.utils.EvaluationResult // <<< NOVO
import com.example.smartdriver.utils.IndividualRating // <<< NOVO
import com.example.smartdriver.utils.BorderRating     // <<< NOVO
import java.math.RoundingMode
import java.text.DecimalFormat
import java.util.*
import kotlin.math.max

class OverlayView(context: Context) : View(context) {

    companion object {
        private const val TAG = "OverlayView"
        // --- Cores da Borda ---
        private val BORDER_COLOR_GREEN = Color.parseColor("#4CAF50") // Verde (Bom/Bom)
        private val BORDER_COLOR_YELLOW = Color.parseColor("#FFC107") // Amarelo (Misto/Médio)
        private val BORDER_COLOR_RED = Color.parseColor("#F44336")   // Vermelho (Mau/Mau)
        private val BORDER_COLOR_GRAY = Color.parseColor("#9E9E9E")  // Cinza (Desconhecido)

        // --- Cores das Barras Indicadoras Internas E DOS VALORES (€/km, €/h) ---
        private val INDICATOR_COLOR_GOOD = BORDER_COLOR_GREEN
        private val INDICATOR_COLOR_MEDIUM = BORDER_COLOR_YELLOW // Amarelo para Médio
        private val INDICATOR_COLOR_POOR = BORDER_COLOR_RED
        private val INDICATOR_COLOR_UNKNOWN = Color.DKGRAY // Cinza escuro para desconhecido

        // --- Outras Cores ---
        private val BACKGROUND_COLOR = Color.WHITE
        private val TEXT_COLOR_LABEL = Color.DKGRAY // Cinza escuro para labels
        private val TEXT_COLOR_VALUE = Color.BLACK // Preto para valores padrão (km, tempo, valor)

        // --- Dimensões (DP) ---
        private const val PADDING_DP = 12f
        private const val BORDER_WIDTH_DP = 3f
        private const val CORNER_RADIUS_DP = 12f
        private const val TEXT_SPACING_VERTICAL_DP = 3f
        private const val LINE_SPACING_VERTICAL_DP = 6f
        private const val TEXT_SPACING_HORIZONTAL_DP = 15f
        private const val INDICATOR_BAR_WIDTH_DP = 4f
        private const val INDICATOR_BAR_MARGIN_DP = 6f

        // --- Tamanhos Fonte Base (SP) ---
        private const val LABEL_TEXT_SIZE_SP = 11f
        private const val VALUE_TEXT_SIZE_SP = 13f // Para km Totais, Tempo, Valor Oferta
        private const val HIGHLIGHT_VALUE_TEXT_SIZE_SP = 14f // Para €/Km
        private const val EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP = 15f // Para €/Hora
    }

    // --- Estado e Configurações ---
    private var currentEvaluationResult: EvaluationResult? = null
    private var currentOfferData: OfferData? = null
    private var fontSizeScale = 1.0f
    private var viewAlpha = 0.90f

    // --- Paints ---
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = BACKGROUND_COLOR }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = BORDER_COLOR_GRAY }
    private val labelTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_LABEL; typeface = Typeface.DEFAULT }
    private val valueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD }
    private val highlightValueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD } // Para €/km
    private val extraHighlightValueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD } // Para €/h
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // --- Dimensões em Pixels ---
    private var paddingPx: Float = 0f; private var borderRadiusPx: Float = 0f
    private var textSpacingVerticalPx: Float = 0f; private var lineSpacingVerticalPx: Float = 0f
    private var textSpacingHorizontalPx: Float = 0f
    private var labelHeight: Float = 0f; private var valueHeight: Float = 0f
    private var highlightValueHeight: Float = 0f
    private var extraHighlightValueHeight: Float = 0f
    private var density: Float = 0f
    private var indicatorBarWidthPx: Float = 0f
    private var indicatorBarMarginPx: Float = 0f

    // Formatador
    private val euroHoraFormatter = DecimalFormat("0.0").apply { roundingMode = RoundingMode.HALF_UP }

    // Retângulos
    private val backgroundRect = RectF(); private val borderRect = RectF()
    private val indicatorRect = RectF()

    init {
        // Log.d(TAG, "OverlayView inicializada") // Menos verboso
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
        extraHighlightValueTextPaint.textSize = EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP * scaledDensity * fontSizeScale

        labelHeight = labelTextPaint.descent() - labelTextPaint.ascent()
        valueHeight = valueTextPaint.descent() - valueTextPaint.ascent()
        highlightValueHeight = highlightValueTextPaint.descent() - highlightValueTextPaint.ascent()
        extraHighlightValueHeight = extraHighlightValueTextPaint.descent() - extraHighlightValueTextPaint.ascent()

        indicatorBarWidthPx = INDICATOR_BAR_WIDTH_DP * density
        indicatorBarMarginPx = INDICATOR_BAR_MARGIN_DP * density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateDimensionsAndPaints()

        val col1Width = max(labelTextPaint.measureText("km totais"), highlightValueTextPaint.measureText("99.99")) + indicatorBarMarginPx + indicatorBarWidthPx
        val col2Width = max(labelTextPaint.measureText("tempo"), valueTextPaint.measureText("999m"))
        val col3Width = max(labelTextPaint.measureText("Valor Oferta"), extraHighlightValueTextPaint.measureText("999.9€")) + indicatorBarMarginPx + indicatorBarWidthPx

        val requiredWidth = (paddingPx * 2) + col1Width + textSpacingHorizontalPx + col2Width + textSpacingHorizontalPx + col3Width

        val topHighlightHeight = max(highlightValueHeight, extraHighlightValueHeight)
        val firstRowHeight = labelHeight + textSpacingVerticalPx + topHighlightHeight
        val secondRowHeight = labelHeight + textSpacingVerticalPx + valueHeight
        val requiredHeight = (paddingPx * 2) + firstRowHeight + lineSpacingVerticalPx + secondRowHeight

        val measuredWidth = resolveSize(requiredWidth.toInt(), widthMeasureSpec)
        val measuredHeight = resolveSize(requiredHeight.toInt(), heightMeasureSpec)

        setMeasuredDimension(measuredWidth, measuredHeight)
        // Log.d(TAG,"onMeasure c/ Fonte €/h Maior - Required: ${requiredWidth.toInt()}x${requiredHeight.toInt()}, Measured: ${measuredWidth}x$measuredHeight") // Menos verboso
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        backgroundRect.set(0f,0f,w.toFloat(),h.toFloat())
        val halfBorder = borderPaint.strokeWidth / 2f
        borderRect.set(halfBorder, halfBorder, w - halfBorder, h - halfBorder)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        borderPaint.color = getBorderColor(currentEvaluationResult?.combinedBorderRating ?: BorderRating.GRAY)
        canvas.drawRoundRect(backgroundRect, borderRadiusPx, borderRadiusPx, backgroundPaint)
        canvas.drawRoundRect(borderRect, borderRadiusPx, borderRadiusPx, borderPaint)
        drawOfferDetailsWithIndicators(canvas)
    }

    /** Desenha os detalhes da oferta em layout 2x3 com barras indicadoras laterais E valores coloridos */
    private fun drawOfferDetailsWithIndicators(canvas: Canvas) {
        updateDimensionsAndPaints()

        // --- Obter e Formatar Dados ---
        val euroPerKmStr = currentOfferData?.calculateProfitability()?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
        val euroPerHourStr = currentOfferData?.calculateValuePerHour()?.let { euroHoraFormatter.format(it).replace(",",".") + "€" } ?: "--"
        val totalKmStr = currentOfferData?.calculateTotalDistance()?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f km", it) } ?: "--"
        val totalTimeStr = currentOfferData?.calculateTotalTimeMinutes()?.takeIf { it > 0 }?.let { "$it m" } ?: "--"
        val mainValueStr = currentOfferData?.value?.takeIf { it.isNotEmpty() }?.let { "$it €" } ?: "--"

        val kmRating = currentEvaluationResult?.kmRating ?: IndividualRating.UNKNOWN
        val hourRating = currentEvaluationResult?.hourRating ?: IndividualRating.UNKNOWN

        // --- Calcular Posições ---
        val leftColX = paddingPx
        val centerColX = measuredWidth / 2f
        val rightColX = measuredWidth - paddingPx

        val topHighlightHeight = max(highlightValueHeight, extraHighlightValueHeight)
        val topLabelY = paddingPx + labelHeight - labelTextPaint.descent()
        val topValueY = topLabelY + topHighlightHeight + textSpacingVerticalPx
        val topValueKmBaseline = topValueY - highlightValueTextPaint.descent()
        val topValueHourBaseline = topValueY - extraHighlightValueTextPaint.descent()

        val bottomLabelY = topValueY + lineSpacingVerticalPx + labelHeight - labelTextPaint.descent()
        val bottomValueY = bottomLabelY + valueHeight + textSpacingVerticalPx - valueTextPaint.descent()

        // Calcula larguras dos textos para posicionar barras
        // Define um valor mínimo para evitar que a barra encoste no texto se o valor for muito curto (ex: "0.5")
        val minTextWidthForBarSpacing = labelTextPaint.measureText("00.00")
        val kmValueTextWidth = max(highlightValueTextPaint.measureText(euroPerKmStr), minTextWidthForBarSpacing)
        val kmIndicatorLeft = leftColX + kmValueTextWidth + indicatorBarMarginPx
        val kmIndicatorRight = kmIndicatorLeft + indicatorBarWidthPx

        val hourValueTextWidth = max(extraHighlightValueTextPaint.measureText(euroPerHourStr), minTextWidthForBarSpacing)
        val hourIndicatorRight = rightColX - hourValueTextWidth - indicatorBarMarginPx
        val hourIndicatorLeft = hourIndicatorRight - indicatorBarWidthPx

        val kmIndicatorTop = topValueKmBaseline + highlightValueTextPaint.ascent()
        val kmIndicatorBottom = topValueKmBaseline + highlightValueTextPaint.descent()
        val hourIndicatorTop = topValueHourBaseline + extraHighlightValueTextPaint.ascent()
        val hourIndicatorBottom = topValueHourBaseline + extraHighlightValueTextPaint.descent()


        // --- Definir Cores e Desenhar Textos ---

        // Coluna 1: €/Km e km totais
        labelTextPaint.textAlign = Paint.Align.LEFT
        highlightValueTextPaint.textAlign = Paint.Align.LEFT
        valueTextPaint.textAlign = Paint.Align.LEFT
        labelTextPaint.color = TEXT_COLOR_LABEL // Garante cor label
        canvas.drawText("€/Km", leftColX, topLabelY, labelTextPaint)
        // Define cor do €/KM baseado no rating
        highlightValueTextPaint.color = getIndicatorColor(kmRating)
        canvas.drawText(euroPerKmStr, leftColX, topValueKmBaseline, highlightValueTextPaint)
        labelTextPaint.color = TEXT_COLOR_LABEL // Garante cor label
        canvas.drawText("km totais", leftColX, bottomLabelY, labelTextPaint)
        valueTextPaint.color = TEXT_COLOR_VALUE // Garante cor padrão
        canvas.drawText(totalKmStr, leftColX, bottomValueY, valueTextPaint)

        // Coluna 2: Tempo
        labelTextPaint.textAlign = Paint.Align.CENTER
        valueTextPaint.textAlign = Paint.Align.CENTER
        labelTextPaint.color = TEXT_COLOR_LABEL // Garante cor label
        canvas.drawText("tempo", centerColX, topLabelY, labelTextPaint)
        valueTextPaint.color = TEXT_COLOR_VALUE // Garante cor padrão
        canvas.drawText(totalTimeStr, centerColX, topValueKmBaseline, valueTextPaint)

        // Coluna 3: €/Hora e Valor Oferta
        labelTextPaint.textAlign = Paint.Align.RIGHT
        extraHighlightValueTextPaint.textAlign = Paint.Align.RIGHT
        valueTextPaint.textAlign = Paint.Align.RIGHT
        labelTextPaint.color = TEXT_COLOR_LABEL // Garante cor label
        canvas.drawText("€/Hora", rightColX, topLabelY, labelTextPaint)
        // Define cor do €/HORA baseado no rating
        extraHighlightValueTextPaint.color = getIndicatorColor(hourRating)
        canvas.drawText(euroPerHourStr, rightColX, topValueHourBaseline, extraHighlightValueTextPaint)
        labelTextPaint.color = TEXT_COLOR_LABEL // Garante cor label
        canvas.drawText("Valor Oferta", rightColX, bottomLabelY, labelTextPaint)
        valueTextPaint.color = TEXT_COLOR_VALUE // Garante cor padrão
        canvas.drawText(mainValueStr, rightColX, bottomValueY, valueTextPaint)

        // --- Desenhar Barras Indicadoras ---

        // Barra Indicadora para €/km
        indicatorPaint.color = getIndicatorColor(kmRating) // Usa a cor já definida
        indicatorRect.set(kmIndicatorLeft, kmIndicatorTop, kmIndicatorRight, kmIndicatorBottom)
        canvas.drawRect(indicatorRect, indicatorPaint)

        // Barra Indicadora para €/hora
        indicatorPaint.color = getIndicatorColor(hourRating) // Usa a cor já definida
        indicatorRect.set(hourIndicatorLeft, hourIndicatorTop, hourIndicatorRight, hourIndicatorBottom)
        canvas.drawRect(indicatorRect, indicatorPaint)
    }

    // --- Métodos de atualização ---
    fun updateFontSize(scale: Float) {
        // Log.d(TAG, "Atualizando escala fonte: $scale") // Menos verboso
        fontSizeScale = scale.coerceIn(0.5f, 2.0f)
        updateDimensionsAndPaints()
        requestLayout()
        invalidate()
    }
    fun updateAlpha(alphaValue: Float) {
        // Log.d(TAG, "Atualizando alpha: $alphaValue") // Menos verboso
        viewAlpha = alphaValue.coerceIn(0.0f, 1.0f)
        this.alpha = viewAlpha
        invalidate()
    }
    fun updateState(evaluationResult: EvaluationResult?, offerData: OfferData?) {
        // Log.d(TAG, "Atualizando estado: Borda=${evaluationResult?.combinedBorderRating}, Km=${evaluationResult?.kmRating}, Hora=${evaluationResult?.hourRating}") // Menos verboso
        currentEvaluationResult = evaluationResult
        currentOfferData = offerData
        requestLayout()
        invalidate()
    }
    private fun getBorderColor(rating: BorderRating): Int {
        return when (rating) {
            BorderRating.GREEN -> BORDER_COLOR_GREEN
            BorderRating.YELLOW -> BORDER_COLOR_YELLOW
            BorderRating.RED -> BORDER_COLOR_RED
            BorderRating.GRAY -> BORDER_COLOR_GRAY
        }
    }
    // Método agora usado para cor das barras E dos valores €/km, €/h
    private fun getIndicatorColor(rating: IndividualRating): Int {
        return when (rating) {
            IndividualRating.GOOD -> INDICATOR_COLOR_GOOD
            IndividualRating.MEDIUM -> INDICATOR_COLOR_MEDIUM
            IndividualRating.POOR -> INDICATOR_COLOR_POOR
            IndividualRating.UNKNOWN -> INDICATOR_COLOR_UNKNOWN
        }
    }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Log.d(TAG, "OverlayView detached.") // Menos verboso
    }
}