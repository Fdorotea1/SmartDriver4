package com.example.smartdriver.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Resources // Import para Resources.NotFoundException
import android.graphics.*
import android.text.TextPaint
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.example.smartdriver.R // Import R
import com.example.smartdriver.utils.IndividualRating
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

@SuppressLint("ClickableViewAccessibility")
class TrackingOverlayView(context: Context) : View(context) {

    companion object {
        private const val TAG = "TrackingOverlayView"
        // Cores
        private val BACKGROUND_COLOR = Color.parseColor("#E6FFFFFF")
        private val BORDER_COLOR = Color.GRAY
        private val TEXT_COLOR_VALUE = Color.BLACK
        private const val PLACEHOLDER_TEXT = "--"
        // Cores VPH
        private val VPH_COLOR_GOOD = Color.parseColor("#4CAF50")
        private val VPH_COLOR_MEDIUM = Color.parseColor("#FF9800")
        private val VPH_COLOR_POOR = Color.parseColor("#F44336")
        private val VPH_COLOR_UNKNOWN = Color.DKGRAY

        // Dimensões
        private const val PADDING_DP = 10f
        private const val BORDER_WIDTH_DP = 1f
        private const val CORNER_RADIUS_DP = 8f
        private const val TEXT_SIZE_SP = 14f
        private const val LINE_SPACING_DP = 4f
    }

    // --- Dados a mostrar ---
    private var currentValuePerHour: Double? = null
    private var currentHourRating: IndividualRating = IndividualRating.UNKNOWN
    private var elapsedTimeSeconds: Long = 0
    private var initialValuePerKm: Double? = null // <<< €/km
    private var initialTotalDistance: Double? = null // <<< Distância
    private var initialTotalDurationMinutes: Int? = null // <<< Duração
    private var offerValue: String? = null // <<< Valor String

    // Paints
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val vphTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    // Dimensões em Pixels
    private var paddingPx: Float = 0f
    private var borderRadiusPx: Float = 0f
    private var textHeight: Float = 0f
    private var lineSpacingPx: Float = 0f

    // GestureDetector
    private val gestureDetector: GestureDetector

    init {
        val density = resources.displayMetrics.density
        val scaledDensity = resources.displayMetrics.scaledDensity
        paddingPx = PADDING_DP * density; borderRadiusPx = CORNER_RADIUS_DP * density
        lineSpacingPx = LINE_SPACING_DP * density
        backgroundPaint.apply { style = Paint.Style.FILL; color = BACKGROUND_COLOR }
        borderPaint.apply { style = Paint.Style.STROKE; color = BORDER_COLOR; strokeWidth = BORDER_WIDTH_DP * density }
        valueTextPaint.apply { color = TEXT_COLOR_VALUE; textSize = TEXT_SIZE_SP * scaledDensity; typeface = Typeface.DEFAULT_BOLD }
        vphTextPaint.apply { color = VPH_COLOR_UNKNOWN; textSize = TEXT_SIZE_SP * scaledDensity; typeface = Typeface.DEFAULT_BOLD }
        textHeight = valueTextPaint.descent() - valueTextPaint.ascent()

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.d(TAG, "Duplo toque! Enviando ACTION_STOP_TRACKING.")
                val stopIntent = Intent(context, OverlayService::class.java).apply { action = OverlayService.ACTION_STOP_TRACKING }
                try { context.startService(stopIntent) } catch (ex: Exception) { Log.e(TAG, "Erro enviar STOP_TRACKING: ${ex.message}") }
                return true
            }
            override fun onDown(e: MotionEvent): Boolean { return true }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    // --- VERIFIQUE A ASSINATURA DESTA FUNÇÃO ---
    // Ordem: €/km, Distância, Duração, Valor String
    fun updateInitialData(
        initialVpk: Double?,
        initialDistance: Double?,
        initialDuration: Int?,
        offerVal: String? // Valor como String
    ) {
        // ---------------------------------------
        initialValuePerKm = initialVpk
        initialTotalDistance = initialDistance
        initialTotalDurationMinutes = initialDuration
        offerValue = offerVal
        elapsedTimeSeconds = 0
        Log.d(TAG, "Dados iniciais TrackingView: €/km=$initialVpk, Dist=$initialDistance, TempoIni=$initialDuration, Valor=$offerVal")
        requestLayout(); invalidate()
    }

    fun updateRealTimeData(currentVph: Double?, hourRating: IndividualRating, elapsedSeconds: Long) {
        currentValuePerHour = currentVph; currentHourRating = hourRating; elapsedTimeSeconds = elapsedSeconds
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateTextPaintSizes()
        val textVph = "€/h: 999.9"; val textVpk = "€/km Ini: 99.99"; val textDist = "Dist Ini: 999.9 km"
        val textOffer = "Valor: 999.99 €"; val textTimeIni = "Tempo Ini: 999 m"; val textTimeElapsed = "Decorrido: 00:00"
        val maxWidth = listOf(textVpk, textDist, textOffer, textTimeIni, textTimeElapsed).maxOfOrNull { valueTextPaint.measureText(it) } ?: 0f
        val vphWidth = vphTextPaint.measureText(textVph); val finalMaxWidth = max(maxWidth, vphWidth)
        val requiredWidth = (paddingPx * 2) + finalMaxWidth; val requiredHeight = (paddingPx * 2) + (textHeight * 6) + (lineSpacingPx * 5)
        setMeasuredDimension(resolveSize(requiredWidth.toInt(), widthMeasureSpec), resolveSize(requiredHeight.toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); updateTextPaintSizes()
        val widthF = width.toFloat(); val heightF = height.toFloat()
        canvas.drawRoundRect(0f, 0f, widthF, heightF, borderRadiusPx, borderRadiusPx, backgroundPaint)
        val halfBorder = borderPaint.strokeWidth / 2f; canvas.drawRoundRect(halfBorder, halfBorder, widthF - halfBorder, heightF - halfBorder, borderRadiusPx, borderRadiusPx, borderPaint)
        valueTextPaint.textAlign = Paint.Align.LEFT; vphTextPaint.textAlign = Paint.Align.LEFT
        val textX = paddingPx; val totalBlockHeight = (textHeight * 6) + (lineSpacingPx * 5)
        var currentY = ((heightF - totalBlockHeight) / 2f) + textHeight - valueTextPaint.descent()

        // Linha 1: €/h
        val textVphDraw = "€/h: ${currentValuePerHour?.let { String.format(Locale.US, "%.1f", it) } ?: PLACEHOLDER_TEXT}"
        vphTextPaint.color = when (currentHourRating) { IndividualRating.GOOD -> VPH_COLOR_GOOD; IndividualRating.MEDIUM -> VPH_COLOR_MEDIUM; IndividualRating.POOR -> VPH_COLOR_POOR; else -> VPH_COLOR_UNKNOWN }
        canvas.drawText(textVphDraw, textX, currentY, vphTextPaint); currentY += textHeight + lineSpacingPx

        // Linha 2: Tempo Decorrido
        val minutes = TimeUnit.SECONDS.toMinutes(elapsedTimeSeconds); val seconds = elapsedTimeSeconds % 60
        val textTimeElapsedDraw = String.format(Locale.getDefault(), "Decorrido: %02d:%02d", minutes, seconds)
        canvas.drawText(textTimeElapsedDraw, textX, currentY, valueTextPaint); currentY += textHeight + lineSpacingPx

        // Linha 3: €/km Inicial
        val textVpkDraw = "€/km Ini: ${initialValuePerKm?.let { String.format(Locale.US, "%.2f", it) } ?: PLACEHOLDER_TEXT}"
        canvas.drawText(textVpkDraw, textX, currentY, valueTextPaint); currentY += textHeight + lineSpacingPx

        // Linha 4: Distância Inicial
        val textDistDraw = "Dist Ini: ${initialTotalDistance?.let { String.format(Locale.US, "%.1f km", it) } ?: PLACEHOLDER_TEXT}"
        canvas.drawText(textDistDraw, textX, currentY, valueTextPaint); currentY += textHeight + lineSpacingPx

        // Linha 5: Tempo Inicial
        val textTimeIniDraw = "Tempo Ini: ${initialTotalDurationMinutes?.let { "$it m" } ?: PLACEHOLDER_TEXT}"
        canvas.drawText(textTimeIniDraw, textX, currentY, valueTextPaint); currentY += textHeight + lineSpacingPx

        // Linha 6: Valor Oferta
        val textOfferDraw = "Valor: ${offerValue?.takeIf { it.isNotEmpty() }?.let { "$it €" } ?: PLACEHOLDER_TEXT}"
        canvas.drawText(textOfferDraw, textX, currentY, valueTextPaint)
    }

    private fun updateTextPaintSizes() {
        val scaledDensity = resources.displayMetrics.scaledDensity; val currentTextSize = TEXT_SIZE_SP * scaledDensity
        if (valueTextPaint.textSize != currentTextSize) { valueTextPaint.textSize = currentTextSize; vphTextPaint.textSize = currentTextSize; textHeight = valueTextPaint.descent() - valueTextPaint.ascent() }
    }
}