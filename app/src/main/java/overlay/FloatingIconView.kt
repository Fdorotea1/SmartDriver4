package com.example.smartdriver.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ImageView circular com halo pulsante (verde/vermelho).
 * - O view é sempre quadrado e desenha um disco circular perfeito.
 * - A imagem é recortada em círculo.
 * - Usa setPulseColor() para alternar a cor do halo (p.ex. verde em tracking, vermelho sem tracking).
 */
class FloatingIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0x22000000
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.GREEN
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.0f)
        color = Color.GREEN
    }

    private val clipPath = Path()
    private var circleCx = 0f
    private var circleCy = 0f
    private var circleR  = 0f

    private var pulseAnimator: ValueAnimator? = null
    private var pulseProgress = 0f // 0..1

    private var pulseEnabled = true
    private var pulseColor: Int = Color.parseColor("#2E7D32") // default verde

    // Amplitudes do halo (ajustadas para ícone maior)
    private val ringMargin   = dp(2f)   // margem aos limites do view
    private val haloBasePad  = dp(6f)   // “folga” além do disco
    private val haloExtra    = dp(12f)  // expansão com a animação
    private val haloMaxAlpha = 110

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        scaleType = ScaleType.CENTER_INSIDE
        background = null

        // Outline circular
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val s = min(view.width, view.height)
                val l = ((view.width  - s) / 2f).roundToInt()
                val t = ((view.height - s) / 2f).roundToInt()
                outline.setOval(l, t, l + s, t + s)
            }
        }
        clipToOutline = false

        // padding um pouco menor para a imagem “encher” mais
        val p = dp(6f).toInt()
        setPadding(p, p, p, p)

        startPulseIfNeeded()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = min(w, h)
        val exact = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        super.onMeasure(exact, exact)
        setMeasuredDimension(side, side)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startPulseIfNeeded()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPulse()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val halfMin = min(w, h) / 2f
        circleCx = w / 2f
        circleCy = h / 2f

        // Garantir que o halo, no pico, cabe dentro do view.
        circleR = (halfMin - ringMargin - (haloBasePad + haloExtra)).coerceAtLeast(dp(14f))

        clipPath.reset()
        clipPath.addCircle(circleCx, circleCy, circleR, Path.Direction.CW)

        updateHaloShaders()
    }

    private fun updateHaloShaders() {
        haloPaint.color = pulseColor
        ringPaint.color = pulseColor
        haloPaint.maskFilter = BlurMaskFilter(dp(12f), BlurMaskFilter.Blur.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        // HALO pulsante (fora do clip, para não ser cortado)
        if (pulseEnabled) {
            val outerR = circleR + haloBasePad + (haloExtra * pulseProgress)
            val alpha = (haloMaxAlpha * (1f - pulseProgress)).toInt().coerceIn(0, haloMaxAlpha)
            haloPaint.alpha = alpha
            canvas.drawCircle(circleCx, circleCy, outerR, haloPaint)

            ringPaint.alpha = (180 * (1f - 0.6f * pulseProgress)).toInt().coerceIn(0, 180)
            canvas.drawCircle(circleCx, circleCy, outerR, ringPaint)
        }

        // Disco de fundo + borda
        canvas.drawCircle(circleCx, circleCy, circleR, bgPaint)
        canvas.drawCircle(circleCx, circleCy, circleR, borderPaint)

        // Clip circular para a imagem
        val save = canvas.save()
        canvas.clipPath(clipPath)
        super.onDraw(canvas)
        canvas.restoreToCount(save)
    }

    // ===== API =====
    fun setPulseEnabled(enabled: Boolean) {
        if (pulseEnabled == enabled) return
        pulseEnabled = enabled
        if (enabled) startPulseIfNeeded() else stopPulse()
        invalidate()
    }

    fun setPulseColor(color: Int) {
        pulseColor = color
        updateHaloShaders()
        invalidate()
    }

    // ===== animação =====
    private fun startPulseIfNeeded() {
        if (!pulseEnabled || pulseAnimator?.isRunning == true) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulseProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseProgress = 0f
        invalidate()
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
