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
        private val BORDER_COLOR_GREEN = Color.parseColor("#4CAF50")
        private val BORDER_COLOR_YELLOW = Color.parseColor("#FFC107")
        private val BORDER_COLOR_RED = Color.parseColor("#F44336")
        private val BORDER_COLOR_GRAY = Color.parseColor("#9E9E9E")

        // --- Cores das Barras Indicadoras Internas ---
        private val INDICATOR_COLOR_GOOD = BORDER_COLOR_GREEN
        private val INDICATOR_COLOR_MEDIUM = BORDER_COLOR_YELLOW
        private val INDICATOR_COLOR_POOR = BORDER_COLOR_RED
        private val INDICATOR_COLOR_UNKNOWN = BORDER_COLOR_GRAY

        // --- Outras Cores ---
        private val BACKGROUND_COLOR = Color.WHITE
        private val TEXT_COLOR_LABEL = Color.DKGRAY
        private val TEXT_COLOR_VALUE = Color.BLACK

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
        // <<< NOVO TAMANHO FONTE para €/Hora >>>
        private const val EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP = 15f // Ligeiramente maior para €/Hora
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
    private val highlightValueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD }
    // <<< NOVO PAINT para €/Hora >>>
    private val extraHighlightValueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // --- Dimensões em Pixels ---
    private var paddingPx: Float = 0f; private var borderRadiusPx: Float = 0f
    private var textSpacingVerticalPx: Float = 0f; private var lineSpacingVerticalPx: Float = 0f
    private var textSpacingHorizontalPx: Float = 0f
    private var labelHeight: Float = 0f; private var valueHeight: Float = 0f
    private var highlightValueHeight: Float = 0f
    private var extraHighlightValueHeight: Float = 0f // <<< NOVA ALTURA
    private var density: Float = 0f
    private var indicatorBarWidthPx: Float = 0f
    private var indicatorBarMarginPx: Float = 0f
    // private var indicatorBarHeight: Float = 0f // Altura será baseada na fonte correspondente

    // Formatador
    private val euroHoraFormatter = DecimalFormat("0.0").apply { roundingMode = RoundingMode.HALF_UP }

    // Retângulos
    private val backgroundRect = RectF(); private val borderRect = RectF()
    private val indicatorRect = RectF()

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

        // Atualiza tamanhos de texto
        labelTextPaint.textSize = LABEL_TEXT_SIZE_SP * scaledDensity * fontSizeScale
        valueTextPaint.textSize = VALUE_TEXT_SIZE_SP * scaledDensity * fontSizeScale
        highlightValueTextPaint.textSize = HIGHLIGHT_VALUE_TEXT_SIZE_SP * scaledDensity * fontSizeScale
        extraHighlightValueTextPaint.textSize = EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP * scaledDensity * fontSizeScale // <<< USA NOVO TAMANHO BASE

        // Calcula alturas de texto
        labelHeight = labelTextPaint.descent() - labelTextPaint.ascent()
        valueHeight = valueTextPaint.descent() - valueTextPaint.ascent()
        highlightValueHeight = highlightValueTextPaint.descent() - highlightValueTextPaint.ascent()
        extraHighlightValueHeight = extraHighlightValueTextPaint.descent() - extraHighlightValueTextPaint.ascent() // <<< CALCULA NOVA ALTURA

        // Atualiza dimensões das barras
        indicatorBarWidthPx = INDICATOR_BAR_WIDTH_DP * density
        indicatorBarMarginPx = INDICATOR_BAR_MARGIN_DP * density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateDimensionsAndPaints()

        // Medir largura MÁXIMA das colunas
        val col1Width = max(labelTextPaint.measureText("km totais"), highlightValueTextPaint.measureText("99.99")) + indicatorBarMarginPx + indicatorBarWidthPx // €/km + barra
        val col2Width = max(labelTextPaint.measureText("tempo"), valueTextPaint.measureText("999m"))
        // Coluna 3: Compara largura do label "Valor Oferta" com o valor €/Hora (que é maior) + barra
        val col3Width = max(labelTextPaint.measureText("Valor Oferta"), extraHighlightValueTextPaint.measureText("999.9€")) + indicatorBarMarginPx + indicatorBarWidthPx // €/h + barra

        val requiredWidth = (paddingPx * 2) + col1Width + textSpacingHorizontalPx + col2Width + textSpacingHorizontalPx + col3Width

        // Calcular altura necessária - Linha de cima agora usa a maior altura entre €/km e €/hora
        val topHighlightHeight = max(highlightValueHeight, extraHighlightValueHeight) // <<< USA A MAIOR ALTURA DA LINHA DE CIMA
        val firstRowHeight = labelHeight + textSpacingVerticalPx + topHighlightHeight // <<< USA topHighlightHeight
        val secondRowHeight = labelHeight + textSpacingVerticalPx + valueHeight
        val requiredHeight = (paddingPx * 2) + firstRowHeight + lineSpacingVerticalPx + secondRowHeight

        val measuredWidth = resolveSize(requiredWidth.toInt(), widthMeasureSpec)
        val measuredHeight = resolveSize(requiredHeight.toInt(), heightMeasureSpec)

        setMeasuredDimension(measuredWidth, measuredHeight)
        Log.d(TAG,"onMeasure c/ Fonte €/h Maior - Required: ${requiredWidth.toInt()}x${requiredHeight.toInt()}, Measured: ${measuredWidth}x$measuredHeight")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        backgroundRect.set(0f,0f,w.toFloat(),h.toFloat())
        val halfBorder = borderPaint.strokeWidth / 2f
        borderRect.set(halfBorder, halfBorder, w - halfBorder, h - halfBorder)
        // Log.d(TAG,"onSizeChanged: ${w}x$h") // Menos verboso
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        borderPaint.color = getBorderColor(currentEvaluationResult?.combinedBorderRating ?: BorderRating.GRAY)
        canvas.drawRoundRect(backgroundRect, borderRadiusPx, borderRadiusPx, backgroundPaint)
        canvas.drawRoundRect(borderRect, borderRadiusPx, borderRadiusPx, borderPaint)
        drawOfferDetailsWithIndicators(canvas)
    }

    /** Desenha os detalhes da oferta em layout 2x3 com barras indicadoras laterais */
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

        // Baselines Y (a linha de cima agora depende da maior altura entre €/km e €/hora)
        val topHighlightHeight = max(highlightValueHeight, extraHighlightValueHeight)
        val topLabelY = paddingPx + labelHeight - labelTextPaint.descent()
        val topValueY = topLabelY + topHighlightHeight + textSpacingVerticalPx // Baseline comum para valores da linha de cima
        // Ajuste individual das baselines de €/km e €/h baseado em suas alturas específicas
        val topValueKmBaseline = topValueY - highlightValueTextPaint.descent()
        val topValueHourBaseline = topValueY - extraHighlightValueTextPaint.descent() // <<< USA ALTURA EXTRA

        // Baselines da linha de baixo
        val bottomLabelY = topValueY + lineSpacingVerticalPx + labelHeight - labelTextPaint.descent()
        val bottomValueY = bottomLabelY + valueHeight + textSpacingVerticalPx - valueTextPaint.descent()

        // Posições X para as barras indicadoras
        val kmValueTextWidth = highlightValueTextPaint.measureText(euroPerKmStr)
        val kmIndicatorLeft = leftColX + kmValueTextWidth + indicatorBarMarginPx
        val kmIndicatorRight = kmIndicatorLeft + indicatorBarWidthPx

        val hourValueTextWidth = extraHighlightValueTextPaint.measureText(euroPerHourStr) // <<< USA PAINT EXTRA
        val hourIndicatorRight = rightColX - hourValueTextWidth - indicatorBarMarginPx
        val hourIndicatorLeft = hourIndicatorRight - indicatorBarWidthPx

        // Posições Y para as barras (alinhadas com os textos correspondentes)
        val kmIndicatorTop = topValueKmBaseline + highlightValueTextPaint.ascent()
        val kmIndicatorBottom = topValueKmBaseline + highlightValueTextPaint.descent()
        val hourIndicatorTop = topValueHourBaseline + extraHighlightValueTextPaint.ascent() // <<< USA PAINT EXTRA
        val hourIndicatorBottom = topValueHourBaseline + extraHighlightValueTextPaint.descent() // <<< USA PAINT EXTRA


        // --- Desenhar Textos ---

        // Coluna 1: €/Km e km totais
        labelTextPaint.textAlign = Paint.Align.LEFT
        highlightValueTextPaint.textAlign = Paint.Align.LEFT
        valueTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("€/Km", leftColX, topLabelY, labelTextPaint)
        canvas.drawText(euroPerKmStr, leftColX, topValueKmBaseline, highlightValueTextPaint) // Usa baseline específica
        canvas.drawText("km totais", leftColX, bottomLabelY, labelTextPaint)
        canvas.drawText(totalKmStr, leftColX, bottomValueY, valueTextPaint)

        // Coluna 2: Tempo
        labelTextPaint.textAlign = Paint.Align.CENTER
        valueTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("tempo", centerColX, topLabelY, labelTextPaint)
        canvas.drawText(totalTimeStr, centerColX, topValueKmBaseline, valueTextPaint) // Usa baseline da linha de cima

        // Coluna 3: €/Hora e Valor Oferta <<< POSIÇÃO CORRIGIDA
        labelTextPaint.textAlign = Paint.Align.RIGHT
        extraHighlightValueTextPaint.textAlign = Paint.Align.RIGHT // <<< USA PAINT EXTRA
        valueTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("€/Hora", rightColX, topLabelY, labelTextPaint) // €/Hora em cima
        canvas.drawText(euroPerHourStr, rightColX, topValueHourBaseline, extraHighlightValueTextPaint) // Usa baseline e paint específicos
        canvas.drawText("Valor Oferta", rightColX, bottomLabelY, labelTextPaint) // Valor em baixo
        canvas.drawText(mainValueStr, rightColX, bottomValueY, valueTextPaint) // Valor em baixo

        // --- Desenhar Barras Indicadoras ---

        // Barra Indicadora para €/km (Coluna 1, linha de cima)
        indicatorPaint.color = getIndicatorColor(kmRating)
        indicatorRect.set(kmIndicatorLeft, kmIndicatorTop, kmIndicatorRight, kmIndicatorBottom)
        canvas.drawRect(indicatorRect, indicatorPaint)

        // Barra Indicadora para €/hora (Coluna 3, linha de cima) <<< POSIÇÃO CORRIGIDA
        indicatorPaint.color = getIndicatorColor(hourRating)
        indicatorRect.set(hourIndicatorLeft, hourIndicatorTop, hourIndicatorRight, hourIndicatorBottom) // Usa top/bottom do €/Hora
        canvas.drawRect(indicatorRect, indicatorPaint)
    }

    // --- Métodos de atualização (inalterados) ---
    fun updateFontSize(scale: Float) {
        Log.d(TAG, "Atualizando escala fonte: $scale")
        fontSizeScale = scale.coerceIn(0.5f, 2.0f)
        updateDimensionsAndPaints()
        requestLayout()
        invalidate()
    }
    fun updateAlpha(alphaValue: Float) {
        Log.d(TAG, "Atualizando alpha: $alphaValue")
        viewAlpha = alphaValue.coerceIn(0.0f, 1.0f)
        this.alpha = viewAlpha
        invalidate()
    }
    fun updateState(evaluationResult: EvaluationResult?, offerData: OfferData?) {
        Log.d(TAG, "Atualizando estado: Borda=${evaluationResult?.combinedBorderRating}, Km=${evaluationResult?.kmRating}, Hora=${evaluationResult?.hourRating}")
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
        Log.d(TAG, "OverlayView detached.")
    }
}