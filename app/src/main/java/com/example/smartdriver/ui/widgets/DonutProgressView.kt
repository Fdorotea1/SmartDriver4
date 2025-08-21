package com.example.smartdriver.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

class DonutProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ---------- Config ----------
    private var strokeWidthPx = dp(12f)
    private var startAngle = -90f
    private var capRound = true
    private var animateChanges = true

    // Cores (defaults neutros)
    private var trackColor = Color.parseColor("#22000000")
    private var progressColor = Color.parseColor("#2E7D32")   // verde + visível
    private var secondaryColor = Color.parseColor("#5533B5E5")
    private var haloColor = Color.parseColor("#882E7D32")
    private var textColor = Color.parseColor("#222222")

    // Texto
    private var centerText = "—"
    private var centerSubtext: String? = null
    private var textSizePx = sp(18f)
    private var subtextSizePx = sp(12f)
    private var tfMain: Typeface? = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private var tfSub: Typeface? = Typeface.create("sans-serif", Typeface.NORMAL)

    // Estado
    private var progress = 0f             // 0..1
    private var secondaryProgress = 0f    // 0..1
    private var haloExcess = 0f           // 0..1

    // Paints
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = trackColor
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = progressColor
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = secondaryColor
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx * 0.45f
        color = haloColor
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = textSizePx
        typeface = tfMain
    }
    private val subtextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = subtextSizePx
        alpha = 200
        typeface = tfSub
    }

    private val arcBounds = RectF()

    init {
        if (capRound) {
            val cap = Paint.Cap.ROUND
            progressPaint.strokeCap = cap
            secondaryPaint.strokeCap = cap
            trackPaint.strokeCap = cap
            haloPaint.strokeCap = cap
        }
        setWillNotDraw(false)
    }

    // ---------- API pública ----------
    fun setCenterText(line1: String, line2: String? = null) {
        centerText = line1
        centerSubtext = line2
        invalidate()
    }

    fun setProgress(value: Float, animate: Boolean = animateChanges) {
        val v = value.coerceIn(0f, 1f)
        if (!animate) {
            progress = v
            invalidate()
            return
        }
        ValueAnimator.ofFloat(progress, v).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
        }.start()
    }

    fun setSecondaryProgress(value: Float) {
        secondaryProgress = value.coerceIn(0f, 1f)
        invalidate()
    }

    /** Excedente da meta (0..1) desenhado como anel fino exterior. */
    fun setHaloExcess(value: Float) {
        haloExcess = value.coerceIn(0f, 1f)
        invalidate()
    }

    fun setColors(
        track: Int = trackColor,
        progressC: Int = progressColor,
        secondaryC: Int = secondaryColor,
        haloC: Int = haloColor,
        textC: Int = textColor
    ) {
        trackColor = track; progressColor = progressC; secondaryColor = secondaryC
        haloColor = haloC; textColor = textC
        trackPaint.color = trackColor
        progressPaint.color = progressColor
        secondaryPaint.color = secondaryColor
        haloPaint.color = haloColor
        textPaint.color = textColor
        subtextPaint.color = textColor
        invalidate()
    }

    fun setStrokeWidthDp(widthDp: Float) {
        strokeWidthPx = dp(widthDp)
        trackPaint.strokeWidth = strokeWidthPx
        progressPaint.strokeWidth = strokeWidthPx
        secondaryPaint.strokeWidth = strokeWidthPx
        haloPaint.strokeWidth = strokeWidthPx * 0.45f
        invalidate()
        requestLayout()
    }

    fun setTextSizesSp(mainSp: Float, subSp: Float = 12f) {
        textSizePx = sp(mainSp)
        subtextSizePx = sp(subSp)
        textPaint.textSize = textSizePx
        subtextPaint.textSize = subtextSizePx
        invalidate()
    }

    fun setTypeface(main: Typeface?, sub: Typeface? = null) {
        tfMain = main
        tfSub = sub ?: tfSub
        textPaint.typeface = tfMain
        subtextPaint.typeface = tfSub
        invalidate()
    }

    // ---------- Medidas & desenho ----------
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = min(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = (min(width, height) / 2f) - strokeWidthPx / 2f - dp(2f)

        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Track (fundo)
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)

        // Secondary (projeção)
        if (secondaryProgress > 0f) {
            canvas.drawArc(arcBounds, startAngle, 360f * secondaryProgress, false, secondaryPaint)
        }

        // Progress principal
        if (progress > 0f) {
            canvas.drawArc(arcBounds, startAngle, 360f * progress, false, progressPaint)
        }

        // Halo de excedente (anel exterior)
        if (haloExcess > 0f) {
            val outer = RectF(
                arcBounds.left - haloPaint.strokeWidth,
                arcBounds.top - haloPaint.strokeWidth,
                arcBounds.right + haloPaint.strokeWidth,
                arcBounds.bottom + haloPaint.strokeWidth
            )
            canvas.drawArc(outer, startAngle, 360f * haloExcess, false, haloPaint)
        }

        // Texto central
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(centerText, cx, textY, textPaint)

        centerSubtext?.let {
            val subY = textY + dp(16f)
            canvas.drawText(it, cx, subY, subtextPaint)
        }
    }

    // ---------- Utils ----------
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
