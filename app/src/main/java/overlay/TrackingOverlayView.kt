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
        // ... (Constantes de cores, dimensões - inalteradas) ...
        private val BACKGROUND_COLOR = Color.parseColor("#E6FFFFFF"); private val BORDER_COLOR_GREEN = Color.parseColor("#4CAF50"); private val BORDER_COLOR_YELLOW = Color.parseColor("#FFC107"); private val BORDER_COLOR_RED = Color.parseColor("#F44336"); private val BORDER_COLOR_GRAY = Color.parseColor("#9E9E9E"); private val TEXT_COLOR_VALUE = Color.BLACK; private const val PLACEHOLDER_TEXT = "--"; private val VPH_COLOR_GOOD = BORDER_COLOR_GREEN; private val VPH_COLOR_MEDIUM = Color.parseColor("#FF9800"); private val VPH_COLOR_POOR = BORDER_COLOR_RED; private val VPH_COLOR_UNKNOWN = Color.DKGRAY; private val VPK_COLOR_GOOD = BORDER_COLOR_GREEN; private val VPK_COLOR_MEDIUM = BORDER_COLOR_YELLOW; private val VPK_COLOR_POOR = BORDER_COLOR_RED; private val VPK_COLOR_UNKNOWN = Color.DKGRAY; private const val PADDING_DP = 10f; private const val BORDER_WIDTH_DP = 8f; private const val CORNER_RADIUS_DP = 8f; private const val TEXT_SIZE_SP = 14f; private const val LINE_SPACING_DP = 4f
    }

    // Dados a mostrar
    private var currentValuePerHour: Double? = null; private var currentHourRating: IndividualRating = IndividualRating.UNKNOWN; private var elapsedTimeSeconds: Long = 0; private var initialValuePerKm: Double? = null; private var initialTotalDistance: Double? = null; private var offerValue: String? = null; private var initialTotalDurationMinutes: Int? = null; private var initialKmRating: IndividualRating = IndividualRating.UNKNOWN; private var combinedBorderRating: BorderRating = BorderRating.GRAY

    // Paints
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG); private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG); private val valueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG); private val vphTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG); private val vpkTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    // Dimensões
    private var paddingPx: Float = 0f; private var borderRadiusPx: Float = 0f; private var textHeight: Float = 0f; private var lineSpacingPx: Float = 0f

    // Arrastar
    private var isDragging = false; private var touchSlop: Int; private var initialWindowX: Int = 0; private var initialWindowY: Int = 0; private var initialTouchRawX: Float = 0f; private var initialTouchRawY: Float = 0f

    private val gestureDetector: GestureDetector
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        val density = resources.displayMetrics.density; val scaledDensity = resources.displayMetrics.scaledDensity
        paddingPx = PADDING_DP * density; borderRadiusPx = CORNER_RADIUS_DP * density; lineSpacingPx = LINE_SPACING_DP * density
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        backgroundPaint.apply { style = Paint.Style.FILL; color = BACKGROUND_COLOR }
        borderPaint.apply { style = Paint.Style.STROKE; strokeWidth = BORDER_WIDTH_DP * density; color = getBorderColor(combinedBorderRating) }
        valueTextPaint.apply { color = TEXT_COLOR_VALUE; textSize = TEXT_SIZE_SP * scaledDensity; typeface = Typeface.DEFAULT_BOLD }
        vphTextPaint.apply { color = getIndicatorColor(currentHourRating); textSize = TEXT_SIZE_SP * scaledDensity; typeface = Typeface.DEFAULT_BOLD }
        vpkTextPaint.apply { color = getIndicatorColor(initialKmRating); textSize = TEXT_SIZE_SP * scaledDensity; typeface = Typeface.DEFAULT_BOLD }
        textHeight = valueTextPaint.descent() - valueTextPaint.ascent()

        // --- Usa SimpleOnGestureListener ---
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean {
                isDragging = false
                initialWindowX = layoutParams.x; initialWindowY = layoutParams.y
                initialTouchRawX = e.rawX; initialTouchRawY = e.rawY
                return true
            }

            // --- Assinatura onScroll com e1 NÃO NULO ---
            override fun onScroll(
                e1: MotionEvent, // <<< TENTATIVA COM NÃO NULO
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // ----------------------------------------
                // Calcula delta total desde onDown
                val totalDeltaX = e2.rawX - initialTouchRawX
                val totalDeltaY = e2.rawY - initialTouchRawY

                if (!isDragging && (abs(totalDeltaX) > touchSlop || abs(totalDeltaY) > touchSlop)) {
                    isDragging = true; Log.d(TAG,"Dragging started")
                }

                if (isDragging) {
                    // Atualiza posição usando delta total (mais suave)
                    layoutParams.x = initialWindowX + totalDeltaX.toInt()
                    layoutParams.y = initialWindowY + totalDeltaY.toInt()

                    mainHandler.post {
                        try { windowManager.updateViewLayout(this@TrackingOverlayView, layoutParams) }
                        catch (e: Exception) { /* Ignora erro */ }
                    }
                    return true // Consome scroll
                }
                return false // Não consome
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

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                return false
            }

        }) // Fim GestureDetector init
    } // Fim init View

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val consumed = gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            if (isDragging) { Log.d(TAG, "Dragging finished"); isDragging = false }
        }
        return consumed || super.onTouchEvent(event)
    }

    // --- Funções Inalteradas ---
    fun updateInitialData( initialVpk: Double?, initialDistance: Double?, initialDuration: Int?, offerVal: String?, initialKmRating: IndividualRating, combinedBorderRating: BorderRating ) { this.initialValuePerKm = initialVpk; this.initialTotalDistance = initialDistance; this.initialTotalDurationMinutes = initialDuration; this.offerValue = offerVal; this.initialKmRating = initialKmRating; this.combinedBorderRating = combinedBorderRating; this.elapsedTimeSeconds = 0; invalidate() }
    fun updateRealTimeData(currentVph: Double?, hourRating: IndividualRating, elapsedSeconds: Long) { this.currentValuePerHour = currentVph; this.currentHourRating = hourRating; this.elapsedTimeSeconds = elapsedSeconds; invalidate() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) { updateTextPaintSizes(); val textVph = "€/h: 999.9"; val textVpk = "€/km Ini: 99.99"; val textDist = "Dist Ini: 999.9 km"; val textOffer = "Valor: 999.99 €"; val textTimeIni = "Tempo Ini: 999 m"; val textTimeElapsed = "Decorrido: 00:00"; val maxWidth = listOf(textDist, textOffer, textTimeIni, textTimeElapsed).maxOfOrNull { valueTextPaint.measureText(it) } ?: 0f; val vphWidth = vphTextPaint.measureText(textVph); val vpkWidth = vpkTextPaint.measureText(textVpk); val finalMaxWidth = maxOf(maxWidth, vphWidth, vpkWidth); val requiredWidth = (paddingPx * 2) + finalMaxWidth; val requiredHeight = (paddingPx * 2) + (textHeight * 6) + (lineSpacingPx * 5); setMeasuredDimension( resolveSize(requiredWidth.toInt(), widthMeasureSpec), resolveSize(requiredHeight.toInt(), heightMeasureSpec) ) }
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas); updateTextPaintSizes(); borderPaint.color = getBorderColor(combinedBorderRating); vphTextPaint.color = getIndicatorColor(currentHourRating); vpkTextPaint.color = getIndicatorColor(initialKmRating); val widthF = width.toFloat(); val heightF = height.toFloat(); canvas.drawRoundRect(0f, 0f, widthF, heightF, borderRadiusPx, borderRadiusPx, backgroundPaint); canvas.drawRoundRect(0f, 0f, widthF, heightF, borderRadiusPx, borderRadiusPx, borderPaint); valueTextPaint.textAlign = Paint.Align.LEFT; vphTextPaint.textAlign = Paint.Align.LEFT; vpkTextPaint.textAlign = Paint.Align.LEFT; val textX = paddingPx; val totalBlockHeight = (textHeight * 6) + (lineSpacingPx * 5); var currentY = ((heightF - totalBlockHeight) / 2f) + textHeight - valueTextPaint.descent(); val textVphDraw = "€/h: ${currentValuePerHour?.let { String.format(Locale.US, "%.1f", it) } ?: PLACEHOLDER_TEXT}"; canvas.drawText(textVphDraw, textX, currentY, vphTextPaint); currentY += textHeight + lineSpacingPx; val minutes = TimeUnit.SECONDS.toMinutes(elapsedTimeSeconds); val seconds = elapsedTimeSeconds % 60; val textTimeElapsedDraw = String.format(Locale.getDefault(), "Decorrido: %02d:%02d", minutes, seconds); canvas.drawText(textTimeElapsedDraw, textX, currentY, valueTextPaint); currentY += textHeight + lineSpacingPx; val textVpkDraw = "€/km Ini: ${initialValuePerKm?.let { String.format(Locale.US, "%.2f", it) } ?: PLACEHOLDER_TEXT}"; canvas.drawText(textVpkDraw, textX, currentY, vpkTextPaint); currentY += textHeight + lineSpacingPx; val textDistDraw = "Dist Ini: ${initialTotalDistance?.let { String.format(Locale.US, "%.1f km", it) } ?: PLACEHOLDER_TEXT}"; canvas.drawText(textDistDraw, textX, currentY, valueTextPaint); currentY += textHeight + lineSpacingPx; val textTimeIniDraw = "Tempo Ini: ${initialTotalDurationMinutes?.let { "$it m" } ?: PLACEHOLDER_TEXT}"; canvas.drawText(textTimeIniDraw, textX, currentY, valueTextPaint); currentY += textHeight + lineSpacingPx; val textOfferDraw = "Valor: ${offerValue?.takeIf { it.isNotEmpty() }?.let { "$it €" } ?: PLACEHOLDER_TEXT}"; canvas.drawText(textOfferDraw, textX, currentY, valueTextPaint) }
    private fun updateTextPaintSizes() { val scaledDensity = resources.displayMetrics.scaledDensity; val currentTextSize = TEXT_SIZE_SP * scaledDensity; if (valueTextPaint.textSize != currentTextSize) { valueTextPaint.textSize = currentTextSize; vphTextPaint.textSize = currentTextSize; vpkTextPaint.textSize = currentTextSize; textHeight = valueTextPaint.descent() - valueTextPaint.ascent() } }
    private fun getBorderColor(rating: BorderRating): Int { return when (rating) { BorderRating.GREEN -> BORDER_COLOR_GREEN; BorderRating.YELLOW -> BORDER_COLOR_YELLOW; BorderRating.RED -> BORDER_COLOR_RED; else -> BORDER_COLOR_GRAY } }
    private fun getIndicatorColor(rating: IndividualRating): Int { return when (rating) { IndividualRating.GOOD -> VPK_COLOR_GOOD; IndividualRating.MEDIUM -> VPK_COLOR_MEDIUM; IndividualRating.POOR -> VPK_COLOR_POOR; else -> VPK_COLOR_UNKNOWN } }

} // Fim da classe