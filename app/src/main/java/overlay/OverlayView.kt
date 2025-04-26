package com.example.smartdriver.overlay // <<< VERIFIQUE O PACKAGE

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.text.TextPaint
import android.util.Log
import android.util.TypedValue
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
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("ClickableViewAccessibility")
class OverlayView(context: Context) : View(context) {

    companion object {
        private const val TAG = "OverlayView"
        // Cores (inalteradas)
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
        // Dimensões (DP/SP) (inalteradas)
        private const val PADDING_DP = 12f
        private const val BORDER_WIDTH_DP = 8f
        private const val CORNER_RADIUS_DP = 12f
        private const val TEXT_SPACING_VERTICAL_DP = 3f
        private const val LINE_SPACING_VERTICAL_DP = 6f
        private const val TEXT_SPACING_HORIZONTAL_DP = 15f
        private const val INDICATOR_BAR_WIDTH_DP = 4f
        private const val INDICATOR_BAR_MARGIN_DP = 6f
        // Tamanhos Fonte Base (SP) (inalterados)
        private const val LABEL_TEXT_SIZE_SP = 11f
        private const val VALUE_TEXT_SIZE_SP = 13f
        private const val HIGHLIGHT_VALUE_TEXT_SIZE_SP = 14f
        private const val EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP = 15f
        private const val PLACEHOLDER_TEXT = "--"

        // --- CONSTANTES PARA DETEÇÃO DE SWIPE ---
        private const val SWIPE_MIN_DISTANCE_DP = 80f // Usar Float aqui
        private const val SWIPE_MAX_OFF_PATH_DP = 100f // Usar Float aqui
        private const val SWIPE_THRESHOLD_VELOCITY_DP = 100f // Usar Float aqui
    }

    // --- Estado e Configurações ---
    private var currentEvaluationResult: EvaluationResult? = null
    private var currentOfferData: OfferData? = null
    private var fontSizeScale = 1.0f
    private var viewAlpha = 0.90f

    // --- Paints (inalterados) ---
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = BACKGROUND_COLOR }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = BORDER_COLOR_GRAY }
    private val labelTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_LABEL; typeface = Typeface.DEFAULT }
    private val valueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD }
    private val highlightValueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD }
    private val extraHighlightValueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val placeholderTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = PLACEHOLDER_TEXT_COLOR; typeface = Typeface.DEFAULT_BOLD }

    // --- Dimensões e Formatador ---
    private var paddingPx: Float = 0f; private var borderRadiusPx: Float = 0f
    private var textSpacingVerticalPx: Float = 0f; private var lineSpacingVerticalPx: Float = 0f
    private var textSpacingHorizontalPx: Float = 0f
    private var labelHeight: Float = 0f; private var valueHeight: Float = 0f
    private var highlightValueHeight: Float = 0f; private var extraHighlightValueHeight: Float = 0f
    private var density: Float = 0f
    private var indicatorBarWidthPx: Float = 0f; private var indicatorBarMarginPx: Float = 0f
    private val euroHoraFormatter = DecimalFormat("0.0").apply { roundingMode = RoundingMode.HALF_UP }
    // --- Dimensões para Swipe em PX ---
    private var swipeMinDistancePx: Float = 0f
    private var swipeMaxOffPathPx: Float = 0f
    private var swipeThresholdVelocityPx: Float = 0f

    // --- Retângulos (inalterados) ---
    private val backgroundRect = RectF(); private val borderRect = RectF()
    private val indicatorRect = RectF()

    // --- GestureDetector ---
    private val gestureDetector: GestureDetector

    init {
        alpha = viewAlpha
        density = resources.displayMetrics.density
        updateDimensionsAndPaints()

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.d(TAG, "Duplo toque no Overlay Principal! Enviando DISMISS_MAIN_OVERLAY_ONLY.")
                // *** ENVIA A NOVA AÇÃO PARA SÓ DISPENSAR O SEMÁFORO ***
                val dismissIntent = Intent(context, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_DISMISS_MAIN_OVERLAY_ONLY
                }
                try { context.startService(dismissIntent) }
                catch (ex: Exception) { Log.e(TAG, "Erro ao enviar DISMISS_MAIN_OVERLAY_ONLY: ${ex.message}") }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                Log.v(TAG, "Toque único ignorado.") // Usar Verbose para menos ruído
                return false
            }

            override fun onFling(
                e1: MotionEvent, // Assinatura corrigida
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                try {
                    val diffY = e2.y - e1.y
                    val diffX = e2.x - e1.x

                    Log.d(TAG, "onFling: diffX=$diffX, diffY=$diffY, velX=$velocityX, velY=$velocityY")

                    if (abs(diffX) > abs(diffY)) { // Mais horizontal
                        if (abs(diffX) > swipeMinDistancePx && abs(velocityX) > swipeThresholdVelocityPx) {
                            if (diffX > 0) { // Para a direita
                                Log.i(TAG, ">>> Swipe para DIREITA detectado! Iniciando Tracking... <<<")
                                startTrackingMode()
                                return true
                            } else { Log.d(TAG, "Swipe para esquerda ignorado.") }
                        } else { Log.d(TAG, "Swipe horizontal muito curto ou lento.") }
                    } else { // Mais vertical
                        if (abs(diffY) > swipeMinDistancePx && abs(velocityY) > swipeThresholdVelocityPx) {
                            Log.d(TAG, "Swipe vertical ignorado.")
                        } else { Log.d(TAG, "Movimento de fling não foi um swipe claro.") }
                    }
                } catch (exception: Exception) {
                    Log.e(TAG, "Erro em onFling: ${exception.message}")
                }
                return false
            }
        })
    } // Fim do init

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Passa o evento para o detector E permite que a view processe outros eventos se o detector não consumir
        val consumed = gestureDetector.onTouchEvent(event)
        return consumed || super.onTouchEvent(event)
    }

    private fun startTrackingMode() {
        if (currentOfferData != null && currentEvaluationResult != null) {
            Log.d(TAG, "Enviando ACTION_START_TRACKING para OverlayService.")
            val startTrackingIntent = Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START_TRACKING
                putExtra(OverlayService.EXTRA_OFFER_DATA, currentOfferData)
                putExtra(OverlayService.EXTRA_EVALUATION_RESULT, currentEvaluationResult)
            }
            try { context.startService(startTrackingIntent) }
            catch (ex: Exception) { Log.e(TAG, "Erro ao enviar START_TRACKING: ${ex.message}") }
        } else {
            Log.w(TAG, "Swipe detectado, mas sem dados válidos para iniciar tracking.")
        }
    }

    // *** REMOVIDA a função hideThisOverlay, pois a ação agora é específica ***

    // --- Desenho e Medição ---
    private fun updateDimensionsAndPaints() {
        val displayMetrics = resources.displayMetrics
        density = displayMetrics.density
        val scaledDensity = displayMetrics.scaledDensity

        paddingPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, PADDING_DP, displayMetrics)
        borderRadiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, CORNER_RADIUS_DP, displayMetrics)
        textSpacingVerticalPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_SPACING_VERTICAL_DP, displayMetrics)
        lineSpacingVerticalPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, LINE_SPACING_VERTICAL_DP, displayMetrics)
        textSpacingHorizontalPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_SPACING_HORIZONTAL_DP, displayMetrics)
        borderPaint.strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, BORDER_WIDTH_DP, displayMetrics)

        labelTextPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LABEL_TEXT_SIZE_SP * fontSizeScale, displayMetrics)
        valueTextPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, VALUE_TEXT_SIZE_SP * fontSizeScale, displayMetrics)
        highlightValueTextPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, HIGHLIGHT_VALUE_TEXT_SIZE_SP * fontSizeScale, displayMetrics)
        extraHighlightValueTextPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP * fontSizeScale, displayMetrics)
        placeholderTextPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, HIGHLIGHT_VALUE_TEXT_SIZE_SP * fontSizeScale, displayMetrics) // Usa mesmo tamanho do highlight

        labelHeight = labelTextPaint.descent() - labelTextPaint.ascent(); valueHeight = valueTextPaint.descent() - valueTextPaint.ascent()
        highlightValueHeight = highlightValueTextPaint.descent() - highlightValueTextPaint.ascent()
        extraHighlightValueHeight = extraHighlightValueTextPaint.descent() - extraHighlightValueTextPaint.ascent()

        indicatorBarWidthPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, INDICATOR_BAR_WIDTH_DP, displayMetrics)
        indicatorBarMarginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, INDICATOR_BAR_MARGIN_DP, displayMetrics)

        // Calcula limites de swipe em pixels
        swipeMinDistancePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SWIPE_MIN_DISTANCE_DP, displayMetrics)
        swipeMaxOffPathPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SWIPE_MAX_OFF_PATH_DP, displayMetrics)
        swipeThresholdVelocityPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SWIPE_THRESHOLD_VELOCITY_DP, displayMetrics)
    }

    // onMeasure (inalterado)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateDimensionsAndPaints()
        val placeholderWidth = placeholderTextPaint.measureText(PLACEHOLDER_TEXT)
        val col1Width = maxOf(labelTextPaint.measureText("km totais"), highlightValueTextPaint.measureText("99.99"), placeholderWidth) + indicatorBarMarginPx + indicatorBarWidthPx
        val col2Width = max(labelTextPaint.measureText("tempo"), valueTextPaint.measureText("999m"))
        val col3Width = maxOf(labelTextPaint.measureText("Valor Oferta"), extraHighlightValueTextPaint.measureText("999.9€"), placeholderWidth) + indicatorBarMarginPx + indicatorBarWidthPx
        val requiredWidth = (paddingPx * 2) + col1Width + textSpacingHorizontalPx + col2Width + textSpacingHorizontalPx + col3Width
        val topHighlightHeight = max(highlightValueHeight, extraHighlightValueHeight)
        val firstRowHeight = labelHeight + textSpacingVerticalPx + topHighlightHeight
        val secondRowHeight = labelHeight + textSpacingVerticalPx + valueHeight
        val requiredHeight = (paddingPx * 2) + firstRowHeight + lineSpacingVerticalPx + secondRowHeight
        val measuredWidth = resolveSize(requiredWidth.toInt(), widthMeasureSpec); val measuredHeight = resolveSize(requiredHeight.toInt(), heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    // onSizeChanged (inalterado)
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); backgroundRect.set(0f, 0f, w.toFloat(), h.toFloat())
        val halfBorder = borderPaint.strokeWidth / 2f; borderRect.set(halfBorder, halfBorder, w - halfBorder, h - halfBorder)
    }

    // onDraw (inalterado)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        borderPaint.color = getBorderColor(currentEvaluationResult?.combinedBorderRating ?: BorderRating.GRAY)
        backgroundPaint.alpha = (viewAlpha * 255).toInt()
        borderPaint.alpha = (viewAlpha * 255).toInt()
        canvas.drawRoundRect(backgroundRect, borderRadiusPx, borderRadiusPx, backgroundPaint)
        canvas.drawRoundRect(borderRect, borderRadiusPx, borderRadiusPx, borderPaint)
        drawOfferDetailsWithIndicators(canvas)
    }

    // drawOfferDetailsWithIndicators (inalterado)
    private fun drawOfferDetailsWithIndicators(canvas: Canvas) {
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
        val minTextWidthForBarSpacing = max(highlightValueTextPaint.measureText("00.00"), placeholderTextPaint.measureText(PLACEHOLDER_TEXT))
        val kmValueTextWidth = max(highlightValueTextPaint.measureText(euroPerKmStr), minTextWidthForBarSpacing)
        val kmIndicatorLeft = leftColX + kmValueTextWidth + indicatorBarMarginPx; val kmIndicatorRight = kmIndicatorLeft + indicatorBarWidthPx
        val hourValueTextWidth = max(extraHighlightValueTextPaint.measureText(euroPerHourStr), minTextWidthForBarSpacing)
        val hourIndicatorRight = rightColX - hourValueTextWidth - indicatorBarMarginPx; val hourIndicatorLeft = hourIndicatorRight - indicatorBarWidthPx
        val kmIndicatorTop = topValueKmBaseline + highlightValueTextPaint.ascent(); val kmIndicatorBottom = topValueKmBaseline + highlightValueTextPaint.descent()
        val hourIndicatorTop = topValueHourBaseline + extraHighlightValueTextPaint.ascent(); val hourIndicatorBottom = topValueHourBaseline + extraHighlightValueTextPaint.descent()
        val textAlpha = (viewAlpha * 255).toInt()
        labelTextPaint.alpha = textAlpha; valueTextPaint.alpha = textAlpha; highlightValueTextPaint.alpha = textAlpha
        extraHighlightValueTextPaint.alpha = textAlpha; placeholderTextPaint.alpha = textAlpha; indicatorPaint.alpha = textAlpha

        // Coluna 1: €/Km e Dist Total
        labelTextPaint.textAlign = Paint.Align.LEFT; highlightValueTextPaint.textAlign = Paint.Align.LEFT; valueTextPaint.textAlign = Paint.Align.LEFT; placeholderTextPaint.textAlign = Paint.Align.LEFT
        labelTextPaint.color = TEXT_COLOR_LABEL; canvas.drawText("€/Km", leftColX, topLabelY, labelTextPaint)
        if (profitability != null) {
            highlightValueTextPaint.color = getIndicatorColor(kmRating); canvas.drawText(euroPerKmStr, leftColX, topValueKmBaseline, highlightValueTextPaint)
            indicatorPaint.color = getIndicatorColor(kmRating); indicatorRect.set(kmIndicatorLeft, kmIndicatorTop, kmIndicatorRight, kmIndicatorBottom); canvas.drawRect(indicatorRect, indicatorPaint)
        } else { canvas.drawText(PLACEHOLDER_TEXT, leftColX, topValueKmBaseline, placeholderTextPaint) }
        labelTextPaint.color = TEXT_COLOR_LABEL; canvas.drawText("km totais", leftColX, bottomLabelY, labelTextPaint)
        valueTextPaint.color = TEXT_COLOR_VALUE; canvas.drawText(totalKmStr, leftColX, bottomValueY, valueTextPaint)

        // Coluna 2: Tempo Total
        labelTextPaint.textAlign = Paint.Align.CENTER; valueTextPaint.textAlign = Paint.Align.CENTER
        labelTextPaint.color = TEXT_COLOR_LABEL; canvas.drawText("tempo", centerColX, topLabelY, labelTextPaint)
        valueTextPaint.color = TEXT_COLOR_VALUE; canvas.drawText(totalTimeStr, centerColX, topValueKmBaseline, valueTextPaint)

        // Coluna 3: €/Hora e Valor Oferta
        labelTextPaint.textAlign = Paint.Align.RIGHT; extraHighlightValueTextPaint.textAlign = Paint.Align.RIGHT; valueTextPaint.textAlign = Paint.Align.RIGHT; placeholderTextPaint.textAlign = Paint.Align.RIGHT
        labelTextPaint.color = TEXT_COLOR_LABEL; canvas.drawText("€/Hora", rightColX, topLabelY, labelTextPaint)
        if (valuePerHour != null) {
            extraHighlightValueTextPaint.color = getIndicatorColor(hourRating); canvas.drawText(euroPerHourStr, rightColX, topValueHourBaseline, extraHighlightValueTextPaint)
            indicatorPaint.color = getIndicatorColor(hourRating); indicatorRect.set(hourIndicatorLeft, hourIndicatorTop, hourIndicatorRight, hourIndicatorBottom); canvas.drawRect(indicatorRect, indicatorPaint)
        } else { canvas.drawText(PLACEHOLDER_TEXT, rightColX, topValueHourBaseline, placeholderTextPaint) }
        labelTextPaint.color = TEXT_COLOR_LABEL; canvas.drawText("Valor Oferta", rightColX, bottomLabelY, labelTextPaint)
        valueTextPaint.color = TEXT_COLOR_VALUE; canvas.drawText(mainValueStr, rightColX, bottomValueY, valueTextPaint)
    }


    // --- Métodos de atualização e cores (inalterados) ---
    fun updateFontSize(scale: Float) { fontSizeScale = scale.coerceIn(0.5f, 2.0f); updateDimensionsAndPaints(); requestLayout(); invalidate() }
    fun updateAlpha(alphaValue: Float) { viewAlpha = alphaValue.coerceIn(0.0f, 1.0f); invalidate() }
    fun updateState(evaluationResult: EvaluationResult?, offerData: OfferData?) {
        currentEvaluationResult = evaluationResult; currentOfferData = offerData;
        requestLayout(); invalidate()
    }
    private fun getBorderColor(rating: BorderRating): Int { return when (rating) { BorderRating.GREEN -> BORDER_COLOR_GREEN; BorderRating.YELLOW -> BORDER_COLOR_YELLOW; BorderRating.RED -> BORDER_COLOR_RED; BorderRating.GRAY -> BORDER_COLOR_GRAY } }
    private fun getIndicatorColor(rating: IndividualRating): Int { return when (rating) { IndividualRating.GOOD -> INDICATOR_COLOR_GOOD; IndividualRating.MEDIUM -> INDICATOR_COLOR_MEDIUM; IndividualRating.POOR -> INDICATOR_COLOR_POOR; IndividualRating.UNKNOWN -> INDICATOR_COLOR_UNKNOWN } }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); Log.d(TAG, "OverlayView detached.") }
}