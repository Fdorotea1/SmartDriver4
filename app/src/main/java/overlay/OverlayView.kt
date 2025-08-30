package com.example.smartdriver.overlay

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
import com.example.smartdriver.utils.BorderRating
import com.example.smartdriver.utils.EvaluationResult
import com.example.smartdriver.utils.IndividualRating
import com.example.smartdriver.utils.OfferData
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("ClickableViewAccessibility")
class OverlayView(context: Context) : View(context) {

    companion object {
        private const val TAG = "OverlayView"

        // Cores
        private val BORDER_COLOR_GREEN = Color.parseColor("#2E7D32")
        private val BORDER_COLOR_YELLOW = Color.parseColor("#F9A825")
        private val BORDER_COLOR_RED   = Color.parseColor("#C62828")
        private val BORDER_COLOR_GRAY  = Color.parseColor("#9E9E9E")

        private val INDICATOR_COLOR_GOOD    = BORDER_COLOR_GREEN
        private val INDICATOR_COLOR_MEDIUM  = BORDER_COLOR_YELLOW
        private val INDICATOR_COLOR_POOR    = BORDER_COLOR_RED
        private val INDICATOR_COLOR_UNKNOWN = Color.DKGRAY

        private val BACKGROUND_COLOR      = Color.WHITE
        private val TEXT_COLOR_LABEL      = Color.DKGRAY
        private val TEXT_COLOR_VALUE      = Color.BLACK
        private val PLACEHOLDER_TEXT_COLOR = Color.LTGRAY

        // Dimensões (DP/SP)
        private const val PADDING_DP = 12f
        private const val BORDER_WIDTH_DP = 8f
        private const val CORNER_RADIUS_DP = 12f
        private const val TEXT_SPACING_VERTICAL_DP = 3f
        private const val LINE_SPACING_VERTICAL_DP = 6f
        private const val TEXT_SPACING_HORIZONTAL_DP = 15f
        private const val INDICATOR_BAR_WIDTH_DP = 4f
        private const val INDICATOR_BAR_MARGIN_DP = 6f

        // Tamanhos texto
        private const val LABEL_TEXT_SIZE_SP = 11f
        private const val VALUE_TEXT_SIZE_SP = 13f
        private const val HIGHLIGHT_VALUE_TEXT_SIZE_SP = 14f
        private const val EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP = 15f

        private const val PLACEHOLDER_TEXT = "--"

        // Gestos (swipe)
        private const val SWIPE_MIN_DISTANCE_DP = 80f
        private const val SWIPE_THRESHOLD_VELOCITY_DP = 100f

        // Banner
        private const val BANNER_TEXT_SIZE_SP = 11.5f
        private const val BANNER_PAD_H_DP = 10f
        private const val BANNER_PAD_V_DP = 6f
        private const val BANNER_CORNER_DP = 10f
    }

    enum class BannerType { INFO, SUCCESS, WARNING }

    // Estado
    private var currentEvaluationResult: EvaluationResult? = null
    private var currentOfferData: OfferData? = null
    private var fontSizeScale = 1.0f
    private var viewAlpha = 0.92f

    // Banner
    private var bannerText: String? = null
    private var bannerType: BannerType = BannerType.INFO
    private var bannerClearAt: Long = 0L

    // Paints
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = BACKGROUND_COLOR }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = BORDER_COLOR_GRAY }
    private val labelTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_LABEL; typeface = Typeface.DEFAULT }
    private val valueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD }
    private val highlightValueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD }
    private val extraHighlightValueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val placeholderTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = PLACEHOLDER_TEXT_COLOR; typeface = Typeface.DEFAULT_BOLD }

    private val bannerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bannerTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }

    // Dimensões calculadas
    private var paddingPx = 0f
    private var borderRadiusPx = 0f
    private var textSpacingVerticalPx = 0f
    private var lineSpacingVerticalPx = 0f
    private var textSpacingHorizontalPx = 0f
    private var labelHeight = 0f
    private var valueHeight = 0f
    private var highlightValueHeight = 0f
    private var extraHighlightValueHeight = 0f
    private var indicatorBarWidthPx = 0f
    private var indicatorBarMarginPx = 0f
    private var swipeMinDistancePx = 0f
    private var swipeThresholdVelocityPx = 0f
    private var bannerPadHPx = 0f
    private var bannerPadVPx = 0f
    private var bannerCornerPx = 0f

    // Formatação PT-PT
    private val euroSymbols = DecimalFormatSymbols(Locale("pt", "PT"))
    private val euroHoraFormatter = DecimalFormat("0.0", euroSymbols).apply { roundingMode = RoundingMode.HALF_UP }

    // Retângulos
    private val backgroundRect = RectF()
    private val borderRect = RectF()
    private val indicatorRect = RectF()
    private val bannerRect = RectF()

    // Gestos
    private val gestureDetector: GestureDetector

    init {
        alpha = viewAlpha
        updateDimensionsAndPaints()

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val dismissIntent = Intent(context, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_DISMISS_MAIN_OVERLAY_ONLY
                }
                try { context.startService(dismissIntent) } catch (ex: Exception) {
                    Log.e(TAG, "Erro ao enviar DISMISS_MAIN_OVERLAY_ONLY: ${ex.message}")
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Não consumimos — deixa o serviço gerir o fade, etc.
                return false
            }

            // >>> Assinatura corrigida: MotionEvent? (nullable)
            override fun onFling(
                e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (kotlin.math.abs(diffX) > kotlin.math.abs(diffY)) {
                    if (kotlin.math.abs(diffX) > swipeMinDistancePx && kotlin.math.abs(velocityX) > swipeThresholdVelocityPx) {
                        if (diffX > 0) {
                            startTrackingMode()
                            return true
                        }
                    }
                }
                return false
            }

        })
        contentDescription = "SmartDriver Overlay"
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val consumed = gestureDetector.onTouchEvent(event)
        if (bannerText != null && bannerClearAt > 0 && System.currentTimeMillis() >= bannerClearAt) {
            bannerText = null
            bannerClearAt = 0
            invalidate()
        }
        return consumed || super.onTouchEvent(event)
    }

    private fun startTrackingMode() {
        if (currentOfferData != null && currentEvaluationResult != null) {
            val startTrackingIntent = Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START_TRACKING
                putExtra(OverlayService.EXTRA_OFFER_DATA, currentOfferData)
                putExtra(OverlayService.EXTRA_EVALUATION_RESULT, currentEvaluationResult)
            }
            try { context.startService(startTrackingIntent) } catch (ex: Exception) {
                Log.e(TAG, "Erro ao enviar START_TRACKING: ${ex.message}")
            }
        } else {
            showBanner("Sem dados para iniciar", BannerType.WARNING, 2000)
        }
    }

    // ----------------- Desenho -----------------

    private fun updateDimensionsAndPaints() {
        val dm = resources.displayMetrics

        paddingPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, PADDING_DP, dm)
        borderRadiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, CORNER_RADIUS_DP, dm)
        textSpacingVerticalPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_SPACING_VERTICAL_DP, dm)
        lineSpacingVerticalPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, LINE_SPACING_VERTICAL_DP, dm)
        textSpacingHorizontalPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_SPACING_HORIZONTAL_DP, dm)
        indicatorBarWidthPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, INDICATOR_BAR_WIDTH_DP, dm)
        indicatorBarMarginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, INDICATOR_BAR_MARGIN_DP, dm)
        swipeMinDistancePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SWIPE_MIN_DISTANCE_DP, dm)
        swipeThresholdVelocityPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SWIPE_THRESHOLD_VELOCITY_DP, dm)
        bannerPadHPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, BANNER_PAD_H_DP, dm)
        bannerPadVPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, BANNER_PAD_V_DP, dm)
        bannerCornerPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, BANNER_CORNER_DP, dm)

        borderPaint.strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, BORDER_WIDTH_DP, dm)

        labelTextPaint.textSize = sp(LABEL_TEXT_SIZE_SP)
        valueTextPaint.textSize = sp(VALUE_TEXT_SIZE_SP)
        highlightValueTextPaint.textSize = sp(HIGHLIGHT_VALUE_TEXT_SIZE_SP)
        extraHighlightValueTextPaint.textSize = sp(EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP)
        placeholderTextPaint.textSize = sp(HIGHLIGHT_VALUE_TEXT_SIZE_SP)
        bannerTextPaint.textSize = sp(BANNER_TEXT_SIZE_SP)

        labelHeight = labelTextPaint.descent() - labelTextPaint.ascent()
        valueHeight = valueTextPaint.descent() - valueTextPaint.ascent()
        highlightValueHeight = highlightValueTextPaint.descent() - highlightValueTextPaint.ascent()
        extraHighlightValueHeight = extraHighlightValueTextPaint.descent() - extraHighlightValueTextPaint.ascent()
    }

    private fun sp(sizeSp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp * fontSizeScale, resources.displayMetrics)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateDimensionsAndPaints()
        val placeholderWidth = placeholderTextPaint.measureText(PLACEHOLDER_TEXT)

        val col1Width = maxOf(
            labelTextPaint.measureText("km totais"),
            highlightValueTextPaint.measureText("99.99"),
            placeholderWidth
        ) + indicatorBarMarginPx + indicatorBarWidthPx

        val col2Width = kotlin.math.max(
            labelTextPaint.measureText("tempo"),
            valueTextPaint.measureText("999m")
        )

        val col3Width = maxOf(
            labelTextPaint.measureText("Valor Oferta"),
            extraHighlightValueTextPaint.measureText("999,9 €"),
            placeholderWidth
        ) + indicatorBarMarginPx + indicatorBarWidthPx

        val requiredWidth = (paddingPx * 2) + col1Width + textSpacingHorizontalPx + col2Width + textSpacingHorizontalPx + col3Width

        val topHighlightHeight = kotlin.math.max(highlightValueHeight, extraHighlightValueHeight)
        val firstRowHeight = labelHeight + textSpacingVerticalPx + topHighlightHeight
        val secondRowHeight = labelHeight + textSpacing_vertical_for_bottom() + valueHeight

        val bannerExtra = if (bannerText != null) (bannerPadVPx * 2 + bannerTextPaint.textSize) + textSpacingVerticalPx else 0f
        val requiredHeight = (paddingPx * 2) + firstRowHeight + lineSpacingVerticalPx + secondRowHeight + bannerExtra

        val measuredWidth = resolveSize(requiredWidth.toInt(), widthMeasureSpec)
        val measuredHeight = resolveSize(requiredHeight.toInt(), heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    private fun textSpacing_vertical_for_bottom(): Float = textSpacingVerticalPx

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backgroundRect.set(0f, 0f, w.toFloat(), h.toFloat())
        val halfBorder = borderPaint.strokeWidth / 2f
        borderRect.set(halfBorder, halfBorder, w - halfBorder, h - halfBorder)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val kmRating   = currentEvaluationResult?.kmRating   ?: IndividualRating.UNKNOWN
        val hourRating = currentEvaluationResult?.hourRating ?: IndividualRating.UNKNOWN

        val providedCombined = currentEvaluationResult?.combinedBorderRating ?: BorderRating.GRAY
        val recomputed = recomputeCombinedBorder(kmRating, hourRating)
        val finalBorder = if (providedCombined == recomputed) providedCombined else {
            Log.w(TAG, "Combined mismatch: provided=$providedCombined, recomputed=$recomputed. Using recomputed.")
            recomputed
        }

        backgroundPaint.alpha = (viewAlpha * 255).toInt()
        borderPaint.alpha = (viewAlpha * 255).toInt()
        borderPaint.color = getBorderColor(finalBorder)
        canvas.drawRoundRect(backgroundRect, borderRadiusPx, borderRadiusPx, backgroundPaint)
        canvas.drawRoundRect(borderRect, borderRadiusPx, borderRadiusPx, borderPaint)

        drawBannerIfNeeded(canvas)
        drawOfferDetailsWithIndicators(canvas)
    }

    private fun drawBannerIfNeeded(canvas: Canvas) {
        val text = bannerText?.trim().orEmpty()
        if (text.isEmpty()) return

        val textWidth = bannerTextPaint.measureText(text)
        val left = paddingPx
        val top = paddingPx
        val right = left + textWidth + (bannerPadHPx * 2)
        val bottom = top + bannerTextPaint.textSize + (bannerPadVPx * 2)
        bannerRect.set(left, top, right, bottom)

        bannerBgPaint.color = when (bannerType) {
            BannerType.INFO -> Color.parseColor("#1976D2")
            BannerType.SUCCESS -> Color.parseColor("#2E7D32")
            BannerType.WARNING -> Color.parseColor("#E65100")
        }
        bannerBgPaint.alpha = (viewAlpha * 255).toInt()
        canvas.drawRoundRect(bannerRect, bannerCornerPx, bannerCornerPx, bannerBgPaint)

        val textX = left + bannerPadHPx
        val textY = top + bannerPadVPx - bannerTextPaint.ascent()
        bannerTextPaint.alpha = (viewAlpha * 255).toInt()
        canvas.drawText(text, textX, textY, bannerTextPaint)
    }

    private fun drawOfferDetailsWithIndicators(canvas: Canvas) {
        val profitability = currentOfferData?.calculateProfitability()
        val valuePerHour  = currentOfferData?.calculateValuePerHour()

        val euroPerKmStr   = profitability?.let { String.format(Locale.US, "%.2f", it) } ?: PLACEHOLDER_TEXT
        val euroPerHourStr = valuePerHour?.let { "${euroHoraFormatter.format(it)} €" } ?: PLACEHOLDER_TEXT

        val totalKmStr = currentOfferData?.calculateTotalDistance()?.takeIf { it > 0 }
            ?.let { String.format(Locale.US, "%.1f km", it) } ?: PLACEHOLDER_TEXT
        val totalTimeStr = currentOfferData?.calculateTotalTimeMinutes()?.takeIf { it > 0 }
            ?.let { "$it m" } ?: PLACEHOLDER_TEXT
        val mainValueStr = currentOfferData?.value?.takeIf { it.isNotEmpty() }?.let { "$it €" } ?: PLACEHOLDER_TEXT

        val kmRating   = currentEvaluationResult?.kmRating   ?: IndividualRating.UNKNOWN
        val hourRating = currentEvaluationResult?.hourRating ?: IndividualRating.UNKNOWN

        val leftColX   = paddingPx
        val centerColX = measuredWidth / 2f
        val rightColX  = measuredWidth - paddingPx

        val bannerOffsetY = if (bannerText != null)
            (bannerPadVPx * 2 + bannerTextPaint.textSize) + textSpacingVerticalPx else 0f

        val topHighlightHeight = kotlin.math.max(highlightValueHeight, extraHighlightValueHeight)
        val topLabelY = paddingPx + bannerOffsetY + labelHeight - labelTextPaint.descent()
        val topValueY = topLabelY + topHighlightHeight + textSpacingVerticalPx

        val topValueKmBaseline   = topValueY - highlightValueTextPaint.descent()
        val topValueHourBaseline = topValueY - extraHighlightValueTextPaint.descent()

        val bottomLabelY = topValueY + lineSpacingVerticalPx + labelHeight - labelTextPaint.descent()
        val bottomValueY = bottomLabelY + valueHeight + textSpacingVerticalPx - valueTextPaint.descent()

        val minTextWidthForBarSpacing = max(
            highlightValueTextPaint.measureText("00.00"),
            placeholderTextPaint.measureText(PLACEHOLDER_TEXT)
        )

        val kmValueTextWidth = kotlin.math.max(highlightValueTextPaint.measureText(euroPerKmStr), minTextWidthForBarSpacing)
        val kmIndicatorLeft  = leftColX + kmValueTextWidth + indicatorBarMarginPx
        val kmIndicatorRight = kmIndicatorLeft + indicatorBarWidthPx

        val hourValueTextWidth = kotlin.math.max(extraHighlightValueTextPaint.measureText(euroPerHourStr), minTextWidthForBarSpacing)
        val hourIndicatorRight = rightColX - hourValueTextWidth - indicatorBarMarginPx
        val hourIndicatorLeft  = hourIndicatorRight - indicatorBarWidthPx

        val kmIndicatorTop    = topValueKmBaseline + highlightValueTextPaint.ascent()
        val kmIndicatorBottom = topValueKmBaseline + highlightValueTextPaint.descent()
        val hourIndicatorTop    = topValueHourBaseline + extraHighlightValueTextPaint.ascent()
        val hourIndicatorBottom = topValueHourBaseline + extraHighlightValueTextPaint.descent()

        val textAlpha = (viewAlpha * 255).toInt()
        labelTextPaint.alpha = textAlpha
        valueTextPaint.alpha = textAlpha
        highlightValueTextPaint.alpha = textAlpha
        extraHighlightValueTextPaint.alpha = textAlpha
        placeholderTextPaint.alpha = textAlpha
        indicatorPaint.alpha = textAlpha

        // Coluna 1 (€/Km + km totais)
        labelTextPaint.textAlign = Paint.Align.LEFT
        highlightValueTextPaint.textAlign = Paint.Align.LEFT
        valueTextPaint.textAlign = Paint.Align.LEFT
        placeholderTextPaint.textAlign = Paint.Align.LEFT

        labelTextPaint.color = TEXT_COLOR_LABEL
        canvas.drawText("€/Km", leftColX, topLabelY, labelTextPaint)

        if (profitability != null) {
            val c = getIndicatorColor(kmRating)
            highlightValueTextPaint.color = c
            canvas.drawText(euroPerKmStr, leftColX, topValueKmBaseline, highlightValueTextPaint)
            indicatorPaint.color = c
            indicatorRect.set(kmIndicatorLeft, kmIndicatorTop, kmIndicatorRight, kmIndicatorBottom)
            canvas.drawRect(indicatorRect, indicatorPaint)
        } else {
            canvas.drawText(PLACEHOLDER_TEXT, leftColX, topValueKmBaseline, placeholderTextPaint)
        }

        labelTextPaint.color = TEXT_COLOR_LABEL
        canvas.drawText("km totais", leftColX, bottomLabelY, labelTextPaint)
        valueTextPaint.color = TEXT_COLOR_VALUE
        canvas.drawText(totalKmStr, leftColX, bottomValueY, valueTextPaint)

        // Coluna 2 (tempo)
        labelTextPaint.textAlign = Paint.Align.CENTER
        valueTextPaint.textAlign = Paint.Align.CENTER
        labelTextPaint.color = TEXT_COLOR_LABEL
        canvas.drawText("tempo", centerColX, topLabelY, labelTextPaint)
        valueTextPaint.color = TEXT_COLOR_VALUE
        canvas.drawText(totalTimeStr, centerColX, topValueKmBaseline, valueTextPaint)

        // Coluna 3 (€/Hora + valor oferta)
        labelTextPaint.textAlign = Paint.Align.RIGHT
        extraHighlightValueTextPaint.textAlign = Paint.Align.RIGHT
        valueTextPaint.textAlign = Paint.Align.RIGHT
        placeholderTextPaint.textAlign = Paint.Align.RIGHT

        labelTextPaint.color = TEXT_COLOR_LABEL
        canvas.drawText("€/Hora", rightColX, topLabelY, labelTextPaint)
        if (valuePerHour != null) {
            val c = getIndicatorColor(hourRating)
            extraHighlightValueTextPaint.color = c
            canvas.drawText(euroPerHourStr, rightColX, topValueHourBaseline, extraHighlightValueTextPaint)
            indicatorPaint.color = c
            indicatorRect.set(hourIndicatorLeft, hourIndicatorTop, hourIndicatorRight, hourIndicatorBottom)
            canvas.drawRect(indicatorRect, indicatorPaint)
        } else {
            canvas.drawText(PLACEHOLDER_TEXT, rightColX, topValueHourBaseline, placeholderTextPaint)
        }

        labelTextPaint.color = TEXT_COLOR_LABEL
        canvas.drawText("Valor Oferta", rightColX, bottomLabelY, labelTextPaint)
        valueTextPaint.color = TEXT_COLOR_VALUE
        canvas.drawText(mainValueStr, rightColX, bottomValueY, valueTextPaint)
    }

    // ----------------- API pública -----------------

    fun updateFontSize(scale: Float) {
        fontSizeScale = scale.coerceIn(0.5f, 2.0f)
        requestLayout()
        invalidate()
    }

    fun updateAlpha(alphaValue: Float) {
        viewAlpha = alphaValue.coerceIn(0.0f, 1.0f)
        invalidate()
    }

    fun updateState(evaluationResult: EvaluationResult?, offerData: OfferData?) {
        currentEvaluationResult = evaluationResult
        currentOfferData = offerData
        requestLayout()
        invalidate()
    }

    /** Mostra um pequeno banner no topo do overlay. */
    fun showBanner(text: String, type: BannerType = BannerType.INFO, durationMs: Long = 2500L) {
        bannerText = text.take(60)
        bannerType = type
        bannerClearAt = if (durationMs > 0) System.currentTimeMillis() + durationMs else 0L
        announceForAccessibility(text)
        requestLayout()
        invalidate()
    }

    // ----------------- Helpers -----------------

    private fun getBorderColor(rating: BorderRating): Int = when (rating) {
        BorderRating.GREEN -> BORDER_COLOR_GREEN
        BorderRating.YELLOW -> BORDER_COLOR_YELLOW
        BorderRating.RED    -> BORDER_COLOR_RED
        BorderRating.GRAY   -> BORDER_COLOR_GRAY
    }

    private fun getIndicatorColor(rating: IndividualRating): Int = when (rating) {
        IndividualRating.GOOD    -> INDICATOR_COLOR_GOOD
        IndividualRating.MEDIUM  -> INDICATOR_COLOR_MEDIUM
        IndividualRating.POOR    -> INDICATOR_COLOR_POOR
        IndividualRating.UNKNOWN -> INDICATOR_COLOR_UNKNOWN
    }

    /** Regra canónica do halo. */
    private fun recomputeCombinedBorder(km: IndividualRating, hour: IndividualRating): BorderRating {
        return when {
            km == IndividualRating.UNKNOWN || hour == IndividualRating.UNKNOWN -> BorderRating.GRAY
            km == IndividualRating.GOOD && hour == IndividualRating.GOOD       -> BorderRating.GREEN
            km == IndividualRating.POOR && hour == IndividualRating.POOR       -> BorderRating.RED
            else                                                               -> BorderRating.YELLOW
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "OverlayView detached.")
    }
}
