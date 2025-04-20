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
import com.example.smartdriver.utils.IndividualRating // <<< IMPORT NECESSÁRIO
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
        private val TEXT_COLOR_LABEL = Color.DKGRAY
        private val TEXT_COLOR_VALUE = Color.BLACK
        private const val PLACEHOLDER_TEXT = "--"
        // --- >>> Cores para Valor €/h <<< ---
        private val VPH_COLOR_GOOD = Color.parseColor("#4CAF50")    // Verde
        private val VPH_COLOR_MEDIUM = Color.parseColor("#FF9800")  // Laranja (em vez de amarelo para contraste)
        private val VPH_COLOR_POOR = Color.parseColor("#F44336")    // Vermelho
        private val VPH_COLOR_UNKNOWN = Color.DKGRAY                // Cinza Escuro
        // Dimensões
        private const val PADDING_DP = 11f
        private const val BORDER_WIDTH_DP = 1f
        private const val CORNER_RADIUS_DP = 9f
        private const val TEXT_SIZE_SP = 15f
        private const val LINE_SPACING_DP = 5f
    }

    // --- Dados a mostrar ---
    private var currentValuePerHour: Double? = null
    private var currentHourRating: IndividualRating = IndividualRating.UNKNOWN // <<< GUARDA A CLASSIFICAÇÃO ATUAL
    private var elapsedTimeSeconds: Long = 0
    private var initialValuePerKm: Double? = null
    private var initialTotalDistance: Double? = null
    private var offerValue: String? = null
    private var initialTotalDurationMinutes: Int? = null

    // Paints
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = BACKGROUND_COLOR }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = BORDER_COLOR }
    private val labelTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_LABEL; typeface = Typeface.DEFAULT }
    private val valueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD }
    private val vphTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { // <<< Paint separado para €/h (para cor)
        typeface = Typeface.DEFAULT_BOLD
        // A cor será definida dinamicamente em onDraw
    }


    // Dimensões
    private var paddingPx: Float = 0f; private var borderRadiusPx: Float = 0f
    private var textHeight: Float = 0f; private var lineSpacingPx: Float = 0f

    // GestureDetector
    private val gestureDetector: GestureDetector

    init {
        val density = resources.displayMetrics.density
        val scaledDensity = resources.displayMetrics.scaledDensity
        paddingPx = PADDING_DP * density; borderRadiusPx = CORNER_RADIUS_DP * density
        borderPaint.strokeWidth = BORDER_WIDTH_DP * density
        labelTextPaint.textSize = TEXT_SIZE_SP * scaledDensity
        valueTextPaint.textSize = TEXT_SIZE_SP * scaledDensity
        vphTextPaint.textSize = TEXT_SIZE_SP * scaledDensity // Mesmo tamanho
        textHeight = valueTextPaint.descent() - valueTextPaint.ascent()
        lineSpacingPx = LINE_SPACING_DP * density

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.d(TAG, "Duplo toque na Janela de Tracking! Enviando STOP_TRACKING.")
                val stopIntent = Intent(context, OverlayService::class.java).apply { action = OverlayService.ACTION_STOP_TRACKING }
                try { context.startService(stopIntent) }
                catch (ex: Exception) { Log.e(TAG, "Erro ao enviar STOP_TRACKING via startService: ${ex.message}") }
                return true
            }
            override fun onDown(e: MotionEvent): Boolean { return true }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    // --- Método para definir TODOS os dados iniciais ---
    fun updateInitialData(
        initialVph: Double?,
        initialVpk: Double?, initialDistance: Double?, initialDuration: Int?, offerVal: String?
    ) {
        // A classificação inicial do VpH virá no primeiro updateRealTimeData
        currentValuePerHour = initialVph
        initialValuePerKm = initialVpk
        initialTotalDistance = initialDistance; initialTotalDurationMinutes = initialDuration
        offerValue = offerVal; elapsedTimeSeconds = 0
        Log.d(TAG, "Dados iniciais recebidos: €/h=$initialVph, €/km=$initialVpk, Dist=$initialDistance, TempoIni=$initialDuration, Valor=$offerVal")
        requestLayout(); invalidate()
    }

    // --- Método ATUALIZADO para receber a classificação do €/h ---
    fun updateRealTimeData(currentVph: Double?, hourRating: IndividualRating, elapsedSeconds: Long) {
        currentValuePerHour = currentVph
        currentHourRating = hourRating // <<< Guarda a classificação
        elapsedTimeSeconds = elapsedSeconds
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateTextPaintSizes()
        val textVph = "€/h: 999.9"; val textVpk = "€/km Ini: 99.99"
        val textDist = "Dist Ini: 999.9 km"; val textOffer = "Valor: 999.99 €"
        val textTimeIni = "Tempo Ini: 999 m"; val textTimeElapsed = "Decorrido: 00:00"
        // Usa vphTextPaint para medir a linha de €/h
        val maxWidth = listOf(textVpk, textDist, textOffer, textTimeIni, textTimeElapsed)
            .maxOfOrNull { valueTextPaint.measureText(it) } ?: 0f
        val vphWidth = vphTextPaint.measureText(textVph)
        val finalMaxWidth = max(maxWidth, vphWidth)

        val requiredWidth = (paddingPx * 2) + finalMaxWidth
        val requiredHeight = (paddingPx * 2) + (textHeight * 6) + (lineSpacingPx * 5)
        setMeasuredDimension( resolveSize(requiredWidth.toInt(), widthMeasureSpec), resolveSize(requiredHeight.toInt(), heightMeasureSpec) )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateTextPaintSizes()

        val widthF = width.toFloat(); val heightF = height.toFloat()
        canvas.drawRoundRect(0f, 0f, widthF, heightF, borderRadiusPx, borderRadiusPx, backgroundPaint)
        val halfBorder = borderPaint.strokeWidth / 2f
        canvas.drawRoundRect(halfBorder, halfBorder, widthF - halfBorder, heightF - halfBorder, borderRadiusPx, borderRadiusPx, borderPaint)

        labelTextPaint.textAlign = Paint.Align.LEFT
        valueTextPaint.textAlign = Paint.Align.LEFT
        vphTextPaint.textAlign = Paint.Align.LEFT // <<< Alinha o paint de €/h
        val textX = paddingPx
        val totalBlockHeight = (textHeight * 6) + (lineSpacingPx * 5)
        var currentY = ((heightF - totalBlockHeight) / 2f) + textHeight - valueTextPaint.descent()

        // --- Desenha as linhas ---

        // 1. €/h (Dinâmico com Cor)
        val textVphDraw = "€/h: ${currentValuePerHour?.let { String.format(Locale.US, "%.1f", it) } ?: PLACEHOLDER_TEXT}"
        // <<< Define a cor do vphTextPaint baseado na classificação >>>
        vphTextPaint.color = when (currentHourRating) {
            IndividualRating.GOOD -> VPH_COLOR_GOOD
            IndividualRating.MEDIUM -> VPH_COLOR_MEDIUM
            IndividualRating.POOR -> VPH_COLOR_POOR
            IndividualRating.UNKNOWN -> VPH_COLOR_UNKNOWN
        }
        canvas.drawText(textVphDraw, textX, currentY, vphTextPaint); currentY += textHeight + lineSpacingPx // <<< Usa vphTextPaint

        // 2. Tempo Decorrido (Dinâmico)
        val minutes = TimeUnit.SECONDS.toMinutes(elapsedTimeSeconds); val seconds = elapsedTimeSeconds % 60
        val textTimeElapsedDraw = String.format(Locale.getDefault(), "Decorrido: %02d:%02d", minutes, seconds)
        canvas.drawText(textTimeElapsedDraw, textX, currentY, valueTextPaint); currentY += textHeight + lineSpacingPx // Usa valueTextPaint normal

        // 3. €/km Inicial (Estático)
        val textVpkDraw = "€/km Ini: ${initialValuePerKm?.let { String.format(Locale.US, "%.2f", it) } ?: PLACEHOLDER_TEXT}"
        canvas.drawText(textVpkDraw, textX, currentY, valueTextPaint); currentY += textHeight + lineSpacingPx

        // 4. Distância Inicial (Estático)
        val textDistDraw = "Dist Ini: ${initialTotalDistance?.let { String.format(Locale.US, "%.1f km", it) } ?: PLACEHOLDER_TEXT}"
        canvas.drawText(textDistDraw, textX, currentY, valueTextPaint); currentY += textHeight + lineSpacingPx

        // 5. Tempo Inicial (Estático)
        val textTimeIniDraw = "Tempo Ini: ${initialTotalDurationMinutes?.let { "$it m" } ?: PLACEHOLDER_TEXT}"
        canvas.drawText(textTimeIniDraw, textX, currentY, valueTextPaint); currentY += textHeight + lineSpacingPx

        // 6. Valor Oferta (Estático)
        val textOfferDraw = "Valor: ${offerValue?.takeIf { it.isNotEmpty() }?.let { "$it €" } ?: PLACEHOLDER_TEXT}"
        canvas.drawText(textOfferDraw, textX, currentY, valueTextPaint)
    }

    // Função auxiliar para garantir que os tamanhos dos paints estão corretos
    private fun updateTextPaintSizes() {
        val scaledDensity = resources.displayMetrics.scaledDensity
        labelTextPaint.textSize = TEXT_SIZE_SP * scaledDensity
        valueTextPaint.textSize = TEXT_SIZE_SP * scaledDensity
        vphTextPaint.textSize = TEXT_SIZE_SP * scaledDensity // <<< Atualiza tamanho do paint €/h
        textHeight = valueTextPaint.descent() - valueTextPaint.ascent()
    }
}