package com.example.smartdriver.overlay // <<< VERIFIQUE O PACKAGE

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.example.smartdriver.utils.BorderRating
import com.example.smartdriver.utils.IndividualRating
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("ClickableViewAccessibility")
class TrackingOverlayView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams
) : View(context) {

    companion object {
        private const val TAG = "TrackingOverlayView"
        // --- Constantes de cores (inalteradas) ---
        private val BACKGROUND_COLOR = Color.parseColor("#E6FFFFFF")
        private val BORDER_COLOR_GREEN = Color.parseColor("#4CAF50")
        private val BORDER_COLOR_YELLOW = Color.parseColor("#FFC107")
        private val BORDER_COLOR_RED = Color.parseColor("#F44336")
        private val BORDER_COLOR_GRAY = Color.parseColor("#9E9E9E")
        private val TEXT_COLOR_VALUE = Color.BLACK
        private const val PLACEHOLDER_TEXT = "--"
        private val VPH_COLOR_GOOD = BORDER_COLOR_GREEN
        private val VPH_COLOR_MEDIUM = Color.parseColor("#FF9800") // Laranja para VPH Médio
        private val VPH_COLOR_POOR = BORDER_COLOR_RED
        private val VPH_COLOR_UNKNOWN = Color.DKGRAY
        private val VPK_COLOR_GOOD = BORDER_COLOR_GREEN
        private val VPK_COLOR_MEDIUM = BORDER_COLOR_YELLOW // Amarelo para VPK Médio
        private val VPK_COLOR_POOR = BORDER_COLOR_RED
        private val VPK_COLOR_UNKNOWN = Color.DKGRAY

        // --- Constantes de Dimensões (ALTERADAS) ---
        private const val PADDING_DP = 12f // Aumentado de 10f para 12f (Opcional)
        private const val BORDER_WIDTH_DP = 8f // Largura da borda (mantida)
        private const val CORNER_RADIUS_DP = 10f // Raio dos cantos (pode aumentar um pouco, ex: 10f)
        private const val TEXT_SIZE_SP = 16f // <<< AUMENTADO de 14f para 16f (Pode ajustar mais)
        private const val LINE_SPACING_DP = 5f // Espaçamento entre linhas (pode aumentar um pouco, ex: 5f)
    }

    // Dados a mostrar (inalterados)
    private var currentValuePerHour: Double? = null; private var currentHourRating: IndividualRating = IndividualRating.UNKNOWN; private var elapsedTimeSeconds: Long = 0; private var initialValuePerKm: Double? = null; private var initialTotalDistance: Double? = null; private var offerValue: String? = null; private var initialTotalDurationMinutes: Int? = null; private var initialKmRating: IndividualRating = IndividualRating.UNKNOWN; private var combinedBorderRating: BorderRating = BorderRating.GRAY

    // Paints (inalterados)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG); private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG); private val valueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG); private val vphTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG); private val vpkTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    // Dimensões (variáveis inalteradas, valores serão recalculados)
    private var paddingPx: Float = 0f; private var borderRadiusPx: Float = 0f; private var textHeight: Float = 0f; private var lineSpacingPx: Float = 0f

    // Arrastar (inalterado)
    private var isDragging = false; private var touchSlop: Int; private var initialWindowX: Int = 0; private var initialWindowY: Int = 0; private var initialTouchRawX: Float = 0f; private var initialTouchRawY: Float = 0f

    private val gestureDetector: GestureDetector
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        val density = resources.displayMetrics.density; val scaledDensity = resources.displayMetrics.scaledDensity
        // Recalcula valores em PX com base nas novas constantes DP/SP
        paddingPx = PADDING_DP * density
        borderRadiusPx = CORNER_RADIUS_DP * density
        lineSpacingPx = LINE_SPACING_DP * density
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        // Configuração dos Paints (inalterada, mas textSize será atualizado dinamicamente)
        backgroundPaint.apply { style = Paint.Style.FILL; color = BACKGROUND_COLOR }
        borderPaint.apply { style = Paint.Style.STROKE; strokeWidth = BORDER_WIDTH_DP * density; /* Cor definida em onDraw */ }
        valueTextPaint.apply { color = TEXT_COLOR_VALUE; /* textSize definido em updateTextPaintSizes */; typeface = Typeface.DEFAULT_BOLD }
        vphTextPaint.apply { /* color definido em onDraw */; /* textSize definido em updateTextPaintSizes */; typeface = Typeface.DEFAULT_BOLD }
        vpkTextPaint.apply { /* color definido em onDraw */; /* textSize definido em updateTextPaintSizes */; typeface = Typeface.DEFAULT_BOLD }
        // textHeight será calculado em updateTextPaintSizes

        // GestureDetector (lógica inalterada)
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                isDragging = false
                initialWindowX = layoutParams.x; initialWindowY = layoutParams.y
                initialTouchRawX = e.rawX; initialTouchRawY = e.rawY
                return true
            }
            override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val totalDeltaX = e2.rawX - initialTouchRawX
                val totalDeltaY = e2.rawY - initialTouchRawY
                if (!isDragging && (abs(totalDeltaX) > touchSlop || abs(totalDeltaY) > touchSlop)) {
                    isDragging = true; Log.d(TAG,"Dragging started")
                }
                if (isDragging) {
                    layoutParams.x = initialWindowX + totalDeltaX.toInt()
                    layoutParams.y = initialWindowY + totalDeltaY.toInt()
                    mainHandler.post {
                        try { windowManager.updateViewLayout(this@TrackingOverlayView, layoutParams) }
                        catch (e: Exception) { /* Ignora erro */ }
                    }
                    return true
                }
                return false
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isDragging) {
                    Log.d(TAG, "onDoubleTap - Enviando STOP_TRACKING.")
                    val stopIntent = Intent(context, OverlayService::class.java).apply { action = OverlayService.ACTION_STOP_TRACKING }
                    try { context.startService(stopIntent) } catch (ex: Exception) { Log.e(TAG, "Erro enviar STOP_TRACKING: ${ex.message}") }
                    return true
                }
                return false
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean { return false }
        })
        // Chamada inicial para definir os tamanhos corretos dos paints
        updateTextPaintSizes()
    } // Fim init View

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val consumed = gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            if (isDragging) { Log.d(TAG, "Dragging finished"); isDragging = false }
        }
        return consumed || super.onTouchEvent(event)
    }

    // --- Funções de atualização de dados (inalteradas) ---
    fun updateInitialData( initialVpk: Double?, initialDistance: Double?, initialDuration: Int?, offerVal: String?, initialKmRating: IndividualRating, combinedBorderRating: BorderRating ) { this.initialValuePerKm = initialVpk; this.initialTotalDistance = initialDistance; this.initialTotalDurationMinutes = initialDuration; this.offerValue = offerVal; this.initialKmRating = initialKmRating; this.combinedBorderRating = combinedBorderRating; this.elapsedTimeSeconds = 0; requestLayout(); invalidate() } // Adicionado requestLayout()
    fun updateRealTimeData(currentVph: Double?, hourRating: IndividualRating, elapsedSeconds: Long) { this.currentValuePerHour = currentVph; this.currentHourRating = hourRating; this.elapsedTimeSeconds = elapsedSeconds; invalidate() }

    // --- onMeasure: recalcula dimensões com base nas novas constantes ---
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateTextPaintSizes() // Garante que os paints têm o tamanho correto antes de medir

        // Textos de exemplo para cálculo da largura máxima (inalterados)
        val textVph = "€/h: 999.9"
        val textVpk = "€/km Ini: 99.99"
        val textDist = "Dist Ini: 999.9 km"
        val textOffer = "Valor: 999.99 €"
        val textTimeIni = "Tempo Ini: 999 m"
        val textTimeElapsed = "Decorrido: 00:00:00" // Considera 3 dígitos para horas

        // Calcula a largura máxima necessária para o texto
        val maxWidth = listOf(textDist, textOffer, textTimeIni, textTimeElapsed)
            .maxOfOrNull { valueTextPaint.measureText(it) } ?: 0f
        val vphWidth = vphTextPaint.measureText(textVph)
        val vpkWidth = vpkTextPaint.measureText(textVpk)
        val finalMaxWidth = maxOf(maxWidth, vphWidth, vpkWidth)

        // Calcula a largura total necessária (padding + texto + padding)
        val requiredWidth = (paddingPx * 2) + finalMaxWidth

        // Calcula a altura total necessária (padding + 6 linhas de texto + 5 espaçamentos + padding)
        val requiredHeight = (paddingPx * 2) + (textHeight * 6) + (lineSpacingPx * 5)

        // Define as dimensões medidas da View
        setMeasuredDimension(
            resolveSize(requiredWidth.toInt(), widthMeasureSpec),
            resolveSize(requiredHeight.toInt(), heightMeasureSpec)
        )
        // Log.d(TAG, "onMeasure: Width=$requiredWidth, Height=$requiredHeight") // Log para depuração
    }

    // --- onDraw: desenha com base nas novas dimensões e tamanhos ---
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateTextPaintSizes() // Garante tamanhos corretos

        // Define cores com base nos dados atuais (lógica inalterada)
        borderPaint.color = getBorderColor(combinedBorderRating)
        vphTextPaint.color = getIndicatorColor(currentHourRating) // Usa a cor correta para VPH
        vpkTextPaint.color = getIndicatorColor(initialKmRating) // Usa a cor correta para VPK

        val widthF = width.toFloat()
        val heightF = height.toFloat()

        // Desenha fundo e borda (inalterado)
        canvas.drawRoundRect(0f, 0f, widthF, heightF, borderRadiusPx, borderRadiusPx, backgroundPaint)
        canvas.drawRoundRect(0f, 0f, widthF, heightF, borderRadiusPx, borderRadiusPx, borderPaint)

        // Alinha texto à esquerda (inalterado)
        valueTextPaint.textAlign = Paint.Align.LEFT
        vphTextPaint.textAlign = Paint.Align.LEFT
        vpkTextPaint.textAlign = Paint.Align.LEFT

        // Calcula posição X inicial (inalterado)
        val textX = paddingPx

        // Calcula a altura total do bloco de texto para centralizar verticalmente (inalterado)
        val totalBlockHeight = (textHeight * 6) + (lineSpacingPx * 5)
        var currentY = ((heightF - totalBlockHeight) / 2f) + textHeight - valueTextPaint.descent() // Posição Y da primeira linha

        // --- Desenha as 6 linhas de texto (lógica de formatação e desenho inalterada) ---
        // 1. €/h Atual
        val textVphDraw = "€/h: ${currentValuePerHour?.let { String.format(Locale.US, "%.1f", it) } ?: PLACEHOLDER_TEXT}"
        canvas.drawText(textVphDraw, textX, currentY, vphTextPaint)
        currentY += textHeight + lineSpacingPx

        // 2. Tempo Decorrido
        val hours = TimeUnit.SECONDS.toHours(elapsedTimeSeconds)
        val minutes = TimeUnit.SECONDS.toMinutes(elapsedTimeSeconds) % 60
        val seconds = elapsedTimeSeconds % 60
        val textTimeElapsedDraw = String.format(Locale.getDefault(), "Decorrido: %02d:%02d:%02d", hours, minutes, seconds) // Formato H:M:S
        canvas.drawText(textTimeElapsedDraw, textX, currentY, valueTextPaint)
        currentY += textHeight + lineSpacingPx

        // 3. €/km Inicial
        val textVpkDraw = "€/km Ini: ${initialValuePerKm?.let { String.format(Locale.US, "%.2f", it) } ?: PLACEHOLDER_TEXT}"
        canvas.drawText(textVpkDraw, textX, currentY, vpkTextPaint)
        currentY += textHeight + lineSpacingPx

        // 4. Distância Inicial
        val textDistDraw = "Dist Ini: ${initialTotalDistance?.let { String.format(Locale.US, "%.1f km", it) } ?: PLACEHOLDER_TEXT}"
        canvas.drawText(textDistDraw, textX, currentY, valueTextPaint)
        currentY += textHeight + lineSpacingPx

        // 5. Tempo Inicial
        val textTimeIniDraw = "Tempo Ini: ${initialTotalDurationMinutes?.let { "$it m" } ?: PLACEHOLDER_TEXT}"
        canvas.drawText(textTimeIniDraw, textX, currentY, valueTextPaint)
        currentY += textHeight + lineSpacingPx

        // 6. Valor da Oferta
        val textOfferDraw = "Valor: ${offerValue?.takeIf { it.isNotEmpty() }?.let { "$it €" } ?: PLACEHOLDER_TEXT}"
        canvas.drawText(textOfferDraw, textX, currentY, valueTextPaint)
    }

    // --- Função auxiliar para atualizar os tamanhos dos paints (importante!) ---
    private fun updateTextPaintSizes() {
        val scaledDensity = resources.displayMetrics.scaledDensity
        val currentTextSize = TEXT_SIZE_SP * scaledDensity
        // Só atualiza se o tamanho calculado for diferente do atual (otimização)
        if (abs(valueTextPaint.textSize - currentTextSize) > 0.1f) {
            valueTextPaint.textSize = currentTextSize
            vphTextPaint.textSize = currentTextSize
            vpkTextPaint.textSize = currentTextSize
            // Recalcula a altura do texto APENAS quando o tamanho muda
            textHeight = valueTextPaint.descent() - valueTextPaint.ascent()
            // Log.d(TAG, "Text sizes updated to: $currentTextSize px, TextHeight: $textHeight px")
        }
    }

    // --- Funções auxiliares para cores (inalteradas) ---
    private fun getBorderColor(rating: BorderRating): Int { return when (rating) { BorderRating.GREEN -> BORDER_COLOR_GREEN; BorderRating.YELLOW -> BORDER_COLOR_YELLOW; BorderRating.RED -> BORDER_COLOR_RED; else -> BORDER_COLOR_GRAY } }
    private fun getIndicatorColor(rating: IndividualRating): Int { return when (rating) { IndividualRating.GOOD -> VPH_COLOR_GOOD; IndividualRating.MEDIUM -> VPH_COLOR_MEDIUM; IndividualRating.POOR -> VPH_COLOR_POOR; else -> VPH_COLOR_UNKNOWN } } // Ajustei para usar as cores de VPH como base

} // Fim da classe TrackingOverlayView