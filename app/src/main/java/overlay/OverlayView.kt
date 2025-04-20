package com.example.smartdriver.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.text.TextPaint
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.EvaluationResult
import com.example.smartdriver.utils.IndividualRating
import com.example.smartdriver.utils.BorderRating
import java.math.RoundingMode
import java.text.DecimalFormat
import java.util.*
// Removido import de TimeUnit e Handler/Looper
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("ClickableViewAccessibility")
class OverlayView(context: Context) : View(context) {

    companion object {
        private const val TAG = "OverlayView"
        // --- Cores ---
        private val BORDER_COLOR_GREEN = Color.parseColor("#4CAF50")
        private val BORDER_COLOR_YELLOW = Color.parseColor("#FFC107")
        private val BORDER_COLOR_RED = Color.parseColor("#F44336")
        private val BORDER_COLOR_GRAY = Color.parseColor("#9E9E9E")
        private val INDICATOR_COLOR_GOOD = BORDER_COLOR_GREEN
        private val INDICATOR_COLOR_MEDIUM = BORDER_COLOR_YELLOW
        private val INDICATOR_COLOR_POOR = BORDER_COLOR_RED
        private val INDICATOR_COLOR_UNKNOWN = Color.DKGRAY
        private val BACKGROUND_COLOR = Color.WHITE
        private val TEXT_COLOR_LABEL = Color.DKGRAY
        private val TEXT_COLOR_VALUE = Color.BLACK
        private val PLACEHOLDER_TEXT_COLOR = Color.LTGRAY
        // Removido TIMER_TEXT_COLOR
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
        private const val VALUE_TEXT_SIZE_SP = 13f
        private const val HIGHLIGHT_VALUE_TEXT_SIZE_SP = 14f
        private const val EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP = 15f
        // Removido TIMER_TEXT_SIZE_SP
        // --- Textos ---
        private const val PLACEHOLDER_TEXT = "--"
        // Removido TIMER_DEFAULT_TEXT
        // --- Timer ---
        // Removido TIMER_UPDATE_INTERVAL_MS
        // --- Swipe ---
        // Removido constantes de Swipe
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
    private val extraHighlightValueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val placeholderTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = PLACEHOLDER_TEXT_COLOR; typeface = Typeface.DEFAULT_BOLD }
    // Removido timerTextPaint

    // --- Dimensões e Formatador ---
    private var paddingPx: Float = 0f; private var borderRadiusPx: Float = 0f
    private var textSpacingVerticalPx: Float = 0f; private var lineSpacingVerticalPx: Float = 0f
    private var textSpacingHorizontalPx: Float = 0f
    private var labelHeight: Float = 0f; private var valueHeight: Float = 0f
    private var highlightValueHeight: Float = 0f; private var extraHighlightValueHeight: Float = 0f
    // Removido timerValueHeight
    private var density: Float = 0f
    private var indicatorBarWidthPx: Float = 0f; private var indicatorBarMarginPx: Float = 0f
    private val euroHoraFormatter = DecimalFormat("0.0").apply { roundingMode = RoundingMode.HALF_UP }
    // Removido variáveis de Swipe

    // --- Retângulos ---
    private val backgroundRect = RectF(); private val borderRect = RectF()
    private val indicatorRect = RectF()

    // --- Timer State REMOVIDO ---

    // --- GestureDetector ---
    private val gestureDetector: GestureDetector

    init {
        alpha = viewAlpha; density = resources.displayMetrics.density
        updateDimensionsAndPaints()

        // Inicializa GestureDetector apenas para toque único e duplo
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.d(TAG, "Duplo toque detectado! Enviando comando HIDE_OVERLAY.")
                hideOverlay() // Esconde esta view
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                Log.d(TAG, "Toque único detectado! Solicitando INÍCIO de Acompanhamento.")
                // <<< Envia Intent para iniciar o tracking >>>
                if (currentOfferData != null && currentEvaluationResult != null) {
                    val startTrackingIntent = Intent(context, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_START_TRACKING
                        // Passa os dados necessários para o serviço iniciar o acompanhamento
                        putExtra(OverlayService.EXTRA_OFFER_DATA, currentOfferData)
                        putExtra(OverlayService.EXTRA_EVALUATION_RESULT, currentEvaluationResult)
                    }
                    try {
                        context.startService(startTrackingIntent)
                    } catch (ex: Exception) {
                        Log.e(TAG, "Erro ao enviar START_TRACKING via startService: ${ex.message}")
                    }
                } else {
                    Log.w(TAG, "Toque único ignorado: Sem dados de oferta/avaliação válidos para iniciar acompanhamento.")
                }
                return true
            }

            // <<< Lógica onFling REMOVIDA >>>

            override fun onDown(e: MotionEvent): Boolean { return true }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    // --- Métodos do Timer REMOVIDOS ---

    // --- Método Helper para Esconder ---
    private fun hideOverlay() {
        // Apenas envia o comando para esconder, o serviço trata de remover
        val hideIntent = Intent(context, OverlayService::class.java).apply { action = OverlayService.ACTION_HIDE_OVERLAY }
        try { context.startService(hideIntent) }
        catch (ex: Exception) { Log.e(TAG, "Erro ao enviar HIDE_OVERLAY via startService: ${ex.message}") }
    }

    // --- Desenho e Medição ---
    private fun updateDimensionsAndPaints() {
        val scaledDensity = resources.displayMetrics.scaledDensity
        paddingPx = PADDING_DP * density; borderRadiusPx = CORNER_RADIUS_DP * density
        textSpacingVerticalPx = TEXT_SPACING_VERTICAL_DP * density; lineSpacingVerticalPx = LINE_SPACING_VERTICAL_DP * density
        textSpacingHorizontalPx = TEXT_SPACING_HORIZONTAL_DP * density; borderPaint.strokeWidth = BORDER_WIDTH_DP * density
        labelTextPaint.textSize = LABEL_TEXT_SIZE_SP * scaledDensity * fontSizeScale
        valueTextPaint.textSize = VALUE_TEXT_SIZE_SP * scaledDensity * fontSizeScale
        highlightValueTextPaint.textSize = HIGHLIGHT_VALUE_TEXT_SIZE_SP * scaledDensity * fontSizeScale
        extraHighlightValueTextPaint.textSize = EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP * scaledDensity * fontSizeScale
        placeholderTextPaint.textSize = HIGHLIGHT_VALUE_TEXT_SIZE_SP * scaledDensity * fontSizeScale
        // Removido timerTextPaint.textSize
        labelHeight = labelTextPaint.descent() - labelTextPaint.ascent(); valueHeight = valueTextPaint.descent() - valueTextPaint.ascent()
        highlightValueHeight = highlightValueTextPaint.descent() - highlightValueTextPaint.ascent()
        extraHighlightValueHeight = extraHighlightValueTextPaint.descent() - extraHighlightValueTextPaint.ascent()
        // Removido timerValueHeight
        indicatorBarWidthPx = INDICATOR_BAR_WIDTH_DP * density; indicatorBarMarginPx = INDICATOR_BAR_MARGIN_DP * density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateDimensionsAndPaints()
        val placeholderWidth = placeholderTextPaint.measureText(PLACEHOLDER_TEXT)
        // Removida medição do timer
        val col1Width = maxOf(labelTextPaint.measureText("km totais"), highlightValueTextPaint.measureText("99.99"), placeholderWidth) + indicatorBarMarginPx + indicatorBarWidthPx
        val col2Width = max(labelTextPaint.measureText("tempo"), valueTextPaint.measureText("999m")) // Só mede tempo
        val col3Width = maxOf(labelTextPaint.measureText("Valor Oferta"), extraHighlightValueTextPaint.measureText("999.9€"), placeholderWidth) + indicatorBarMarginPx + indicatorBarWidthPx
        val requiredWidth = (paddingPx * 2) + col1Width + textSpacingHorizontalPx + col2Width + textSpacingHorizontalPx + col3Width
        val topHighlightHeight = max(highlightValueHeight, extraHighlightValueHeight)
        val firstRowHeight = labelHeight + textSpacingVerticalPx + topHighlightHeight
        val secondRowHeight = labelHeight + textSpacingVerticalPx + valueHeight
        // Removido timerRowHeight
        val requiredHeight = (paddingPx * 2) + firstRowHeight + lineSpacingVerticalPx + secondRowHeight // Apenas 2 linhas
        val measuredWidth = resolveSize(requiredWidth.toInt(), widthMeasureSpec); val measuredHeight = resolveSize(requiredHeight.toInt(), heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); backgroundRect.set(0f, 0f, w.toFloat(), h.toFloat())
        val halfBorder = borderPaint.strokeWidth / 2f; borderRect.set(halfBorder, halfBorder, w - halfBorder, h - halfBorder)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); borderPaint.color = getBorderColor(currentEvaluationResult?.combinedBorderRating ?: BorderRating.GRAY)
        canvas.drawRoundRect(backgroundRect, borderRadiusPx, borderRadiusPx, backgroundPaint)
        canvas.drawRoundRect(borderRect, borderRadiusPx, borderRadiusPx, borderPaint)
        drawOfferDetailsWithIndicators(canvas)
    }

    private fun drawOfferDetailsWithIndicators(canvas: Canvas) {
        // Lógica de desenho quase igual, mas sem desenhar o timer
        updateDimensionsAndPaints()
        val profitability = currentOfferData?.calculateProfitability(); val valuePerHour = currentOfferData?.calculateValuePerHour()
        val euroPerKmStr = profitability?.let { String.format(Locale.US, "%.2f", it) } ?: PLACEHOLDER_TEXT
        val euroPerHourStr = valuePerHour?.let { euroHoraFormatter.format(it).replace(",", ".") + "€" } ?: PLACEHOLDER_TEXT
        val totalKmStr = currentOfferData?.calculateTotalDistance()?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f km", it) } ?: PLACEHOLDER_TEXT
        val totalTimeStr = currentOfferData?.calculateTotalTimeMinutes()?.takeIf { it > 0 }?.let { "$it m" } ?: PLACEHOLDER_TEXT
        val mainValueStr = currentOfferData?.value?.takeIf { it.isNotEmpty() }?.let { "$it €" } ?: PLACEHOLDER_TEXT
        val kmRating = currentEvaluationResult?.kmRating ?: IndividualRating.UNKNOWN; val hourRating = currentEvaluationResult?.hourRating ?: IndividualRating.UNKNOWN
        val leftColX = paddingPx; val centerColX = measuredWidth / 2f; val rightColX = measuredWidth - paddingPx
        val topHighlightHeight = max(highlightValueHeight, extraHighlightValueHeight)
        val topLabelY = paddingPx + labelHeight - labelTextPaint.descent(); val topValueY = topLabelY + topHighlightHeight + textSpacingVerticalPx
        val topValueKmBaseline = topValueY - highlightValueTextPaint.descent(); val topValueHourBaseline = topValueY - extraHighlightValueTextPaint.descent()
        val bottomLabelY = topValueY + lineSpacingVerticalPx + labelHeight - labelTextPaint.descent(); val bottomValueY = bottomLabelY + valueHeight + textSpacingVerticalPx - valueTextPaint.descent()
        // Removido timerBaselineY
        val minTextWidthForBarSpacing = max(labelTextPaint.measureText("00.00"), placeholderTextPaint.measureText(PLACEHOLDER_TEXT))
        val kmValueTextWidth = max(highlightValueTextPaint.measureText(euroPerKmStr), minTextWidthForBarSpacing)
        val kmIndicatorLeft = leftColX + kmValueTextWidth + indicatorBarMarginPx; val kmIndicatorRight = kmIndicatorLeft + indicatorBarWidthPx
        val hourValueTextWidth = max(extraHighlightValueTextPaint.measureText(euroPerHourStr), minTextWidthForBarSpacing)
        val hourIndicatorRight = rightColX - hourValueTextWidth - indicatorBarMarginPx; val hourIndicatorLeft = hourIndicatorRight - indicatorBarWidthPx
        val kmIndicatorTop = topValueKmBaseline + highlightValueTextPaint.ascent(); val kmIndicatorBottom = topValueKmBaseline + highlightValueTextPaint.descent()
        val hourIndicatorTop = topValueHourBaseline + extraHighlightValueTextPaint.ascent(); val hourIndicatorBottom = topValueHourBaseline + extraHighlightValueTextPaint.descent()

        // Coluna 1
        labelTextPaint.textAlign = Paint.Align.LEFT; highlightValueTextPaint.textAlign = Paint.Align.LEFT; valueTextPaint.textAlign = Paint.Align.LEFT; placeholderTextPaint.textAlign = Paint.Align.LEFT
        labelTextPaint.color = TEXT_COLOR_LABEL; canvas.drawText("€/Km", leftColX, topLabelY, labelTextPaint)
        if (profitability != null) {
            highlightValueTextPaint.color = getIndicatorColor(kmRating); canvas.drawText(euroPerKmStr, leftColX, topValueKmBaseline, highlightValueTextPaint)
            indicatorPaint.color = getIndicatorColor(kmRating); indicatorRect.set(kmIndicatorLeft, kmIndicatorTop, kmIndicatorRight, kmIndicatorBottom); canvas.drawRect(indicatorRect, indicatorPaint)
        } else { canvas.drawText(PLACEHOLDER_TEXT, leftColX, topValueKmBaseline, placeholderTextPaint) }
        labelTextPaint.color = TEXT_COLOR_LABEL; canvas.drawText("km totais", leftColX, bottomLabelY, labelTextPaint)
        valueTextPaint.color = TEXT_COLOR_VALUE; canvas.drawText(totalKmStr, leftColX, bottomValueY, valueTextPaint)

        // Coluna 2
        labelTextPaint.textAlign = Paint.Align.CENTER; valueTextPaint.textAlign = Paint.Align.CENTER
        labelTextPaint.color = TEXT_COLOR_LABEL; canvas.drawText("tempo", centerColX, topLabelY, labelTextPaint)
        valueTextPaint.color = TEXT_COLOR_VALUE; canvas.drawText(totalTimeStr, centerColX, topValueKmBaseline, valueTextPaint)
        // Removido desenho do timer

        // Coluna 3
        labelTextPaint.textAlign = Paint.Align.RIGHT; extraHighlightValueTextPaint.textAlign = Paint.Align.RIGHT; valueTextPaint.textAlign = Paint.Align.RIGHT; placeholderTextPaint.textAlign = Paint.Align.RIGHT
        labelTextPaint.color = TEXT_COLOR_LABEL; canvas.drawText("€/Hora", rightColX, topLabelY, labelTextPaint)
        if (valuePerHour != null) {
            extraHighlightValueTextPaint.color = getIndicatorColor(hourRating); canvas.drawText(euroPerHourStr, rightColX, topValueHourBaseline, extraHighlightValueTextPaint)
            indicatorPaint.color = getIndicatorColor(hourRating); indicatorRect.set(hourIndicatorLeft, hourIndicatorTop, hourIndicatorRight, hourIndicatorBottom); canvas.drawRect(indicatorRect, indicatorPaint)
        } else { canvas.drawText(PLACEHOLDER_TEXT, rightColX, topValueHourBaseline, placeholderTextPaint) }
        labelTextPaint.color = TEXT_COLOR_LABEL; canvas.drawText("Valor Oferta", rightColX, bottomLabelY, labelTextPaint)
        valueTextPaint.color = TEXT_COLOR_VALUE; canvas.drawText(mainValueStr, rightColX, bottomValueY, valueTextPaint)
    }

    // --- Métodos de atualização e cores ---
    fun updateFontSize(scale: Float) { fontSizeScale = scale.coerceIn(0.5f, 2.0f); updateDimensionsAndPaints(); requestLayout(); invalidate() }
    fun updateAlpha(alphaValue: Float) { viewAlpha = alphaValue.coerceIn(0.0f, 1.0f); this.alpha = viewAlpha; invalidate() }
    fun updateState(evaluationResult: EvaluationResult?, offerData: OfferData?) {
        currentEvaluationResult = evaluationResult; currentOfferData = offerData;
        // Removido resetTimer()
        requestLayout(); invalidate()
    }
    private fun getBorderColor(rating: BorderRating): Int { return when (rating) { BorderRating.GREEN -> BORDER_COLOR_GREEN; BorderRating.YELLOW -> BORDER_COLOR_YELLOW; BorderRating.RED -> BORDER_COLOR_RED; BorderRating.GRAY -> BORDER_COLOR_GRAY } }
    private fun getIndicatorColor(rating: IndividualRating): Int { return when (rating) { IndividualRating.GOOD -> INDICATOR_COLOR_GOOD; IndividualRating.MEDIUM -> INDICATOR_COLOR_MEDIUM; IndividualRating.POOR -> INDICATOR_COLOR_POOR; IndividualRating.UNKNOWN -> INDICATOR_COLOR_UNKNOWN } }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); /* Removido stopTimer() */ Log.d(TAG, "OverlayView detached.") }
}