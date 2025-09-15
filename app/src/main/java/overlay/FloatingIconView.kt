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
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * FloatingIconView – Ring pulse OUTWARD (afinada)
 * -----------------------------------------------
 * Anel sólido que cresce de **dentro para fora**, mantendo a borda interna fixa.
 * Cadência e gama de alpha alinhadas com a TrackingOverlayView.
 */
class FloatingIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    // === Parâmetros visuais (ajustáveis) ===
    private val BASE_STROKE_DP = 2.0f
    private val HALO_BASE_PAD_DP = 4f          // distância do anel ao disco
    private val RING_MARGIN_DP = 2f            // margem aos limites do view
    private val ANTI_CLIP_DP = 0.5f            // folga sub-pixel

    private val CADENCE_MS = 1800L             // igual ao TrackingOverlayView
    private val START_THICK_FACTOR = 0.6f      // começa 60% mais grosso que o base
    private val EXTRA_GROWTH_FACTOR = 1.4f     // e cresce +140% ao longo do pulso (→ 3.0x total)
    private val PULSE_ALPHA_MIN = 120          // igual ao TrackingOverlayView
    private val PULSE_ALPHA_MAX = 255

    // === Paints básicos ===
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0x22000000
    }
    private val ringBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(BASE_STROKE_DP)
        color = Color.GREEN
    }
    private val ringPulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(BASE_STROKE_DP) // dinâmico
        color = Color.GREEN
    }

    private val clipPath = Path()
    private var circleCx = 0f
    private var circleCy = 0f
    private var circleR  = 0f

    // Geometria e segurança
    private val ringMargin   = dp(RING_MARGIN_DP)
    private val antiClipPx   = dp(ANTI_CLIP_DP)
    private val haloBasePad  = dp(HALO_BASE_PAD_DP)
    private val baseStroke   = dp(BASE_STROKE_DP)

    private var outerRMax = 0f

    // Animação
    private var pulseAnimator: ValueAnimator? = null
    private var pulseProgress = 0f // 0..1

    private var pulseEnabled = true
    private var pulseColor: Int = Color.parseColor("#2E7D32") // default verde

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null) // strokes + animação mais estáveis
        scaleType = ScaleType.CENTER_INSIDE
        background = null

        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val s = min(view.width, view.height)
                val l = ((view.width  - s) / 2f).roundToInt()
                val t = ((view.height - s) / 2f).roundToInt()
                outline.setOval(l, t, l + s, t + s)
            }
        }
        clipToOutline = false

        val p = dp(6f).toInt()
        setPadding(p, p, p, p)

        setPulseColor(pulseColor)
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

        // Stroke máximo no pico (inicia já mais grosso e cresce mais um pouco)
        val ringMaxStroke = baseStroke * (1f + START_THICK_FACTOR + EXTRA_GROWTH_FACTOR)

        // Nada pode ultrapassar os bounds: halfMin - (margem + antiClip + stroke/2)
        val safety = ringMargin + antiClipPx + (ringMaxStroke / 2f)
        outerRMax = (halfMin - safety).coerceAtLeast(0f)

        // Borda interna fixa: innerEdge = circleR + haloBasePad
        // No pico: outerR = innerEdge + ringMaxStroke/2  → innerEdge <= outerRMax - ringMaxStroke/2
        circleR = (outerRMax - haloBasePad - ringMaxStroke / 2f).coerceAtLeast(dp(14f))

        clipPath.reset()
        clipPath.addCircle(circleCx, circleCy, circleR, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        // Disco de fundo + borda
        canvas.drawCircle(circleCx, circleCy, circleR, bgPaint)
        canvas.drawCircle(circleCx, circleCy, circleR, borderPaint)

        // Anel base fino (presença constante)
        val innerEdge = circleR + haloBasePad
        canvas.drawCircle(circleCx, circleCy, innerEdge, ringBasePaint)

        // Anel pulsante (cresce para fora mantendo innerEdge fixo)
        if (pulseEnabled) {
            // stroke = base * (1 + START_THICK_FACTOR + EXTRA_GROWTH_FACTOR * p)
            val stroke = baseStroke * (1f + START_THICK_FACTOR + EXTRA_GROWTH_FACTOR * pulseProgress)
            ringPulsePaint.strokeWidth = stroke
            ringPulsePaint.alpha = (PULSE_ALPHA_MIN + (PULSE_ALPHA_MAX - PULSE_ALPHA_MIN) * pulseProgress)
                .toInt().coerceIn(0, 255)

            // Mantém a borda interna fixa: r - stroke/2 = innerEdge
            var pulseRadius = innerEdge + stroke * 0.5f

            // Clamp exterior para não cortar
            val maxAllowedRadius = outerRMax - stroke * 0.5f
            if (pulseRadius > maxAllowedRadius) pulseRadius = maxAllowedRadius

            if (pulseRadius > 0f) {
                canvas.drawCircle(circleCx, circleCy, pulseRadius, ringPulsePaint)
            }
        }

        // Clip circular para a imagem (acima do halo)
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
        ringBasePaint.color = color
        ringPulsePaint.color = color
        invalidate()
    }

    fun setPulseCadenceMs(ms: Long) {
        val dur = ms.coerceAtLeast(400L)
        if (pulseAnimator != null) {
            stopPulse()
            startPulseIfNeeded(dur)
        } else {
            startPulseIfNeeded(dur)
        }
    }

    // ===== animação =====
    private fun startPulseIfNeeded(durationMs: Long = CADENCE_MS) {
        if (!pulseEnabled || pulseAnimator?.isRunning == true) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
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
