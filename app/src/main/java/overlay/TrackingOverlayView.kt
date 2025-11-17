package com.example.smartdriver.overlay

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.example.smartdriver.map.MapPreviewActivity
import com.example.smartdriver.utils.BorderRating
import com.example.smartdriver.utils.IndividualRating
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.toDoubleOrNullWithCorrection
import com.example.smartdriver.utils.toIntOrNullWithCorrection
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@SuppressLint("ClickableViewAccessibility")
class TrackingOverlayView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams
) : View(context) {

    companion object {
        private val BG_COLOR = Color.parseColor("#E6FFFFFF")
        private val TEXT_COLOR_MAIN = Color.BLACK
        private val LEGEND_COLOR = Color.parseColor("#6B6B6B")

        private val BORDER_COLOR_GREEN = Color.parseColor("#4CAF50")
        private val BORDER_COLOR_YELLOW = Color.parseColor("#FFC107")
        private val BORDER_COLOR_RED = Color.parseColor("#F44336")
        private val BORDER_COLOR_GRAY = Color.parseColor("#9E9E9E")

        private val METRIC_COLOR_GOOD = BORDER_COLOR_GREEN
        private val METRIC_COLOR_MEDIUM = BORDER_COLOR_YELLOW
        private val METRIC_COLOR_POOR = BORDER_COLOR_RED
        private val METRIC_COLOR_UNKNOWN = Color.DKGRAY

        private const val DEFAULT_DIAMETER_DP = 104f
        private const val BORDER_WIDTH_DP = 3.5f
        private const val MAIN_TEXT_SP = 19f
        private const val LEGEND_TEXT_SP = 10.5f
        private const val PADDING_DP = 8f

        private const val SAFE_MARGIN_DP = 8f
        private const val TOP_BAND_RATIO = 0.40f

        private const val DRAG_ACTIVATION_DELAY_MS = 120L
        private const val DRAG_DISTANCE_FACTOR = 1.5f

        // Pulso
        private const val PULSE_DURATION_MS = 1800L
        private const val PULSE_MIN_ALPHA = 120
        private const val PULSE_MAX_ALPHA = 255
        private const val PULSE_EXTRA_WIDTH_FACTOR = 2.0f
    }

    // (mantemos referência, mesmo que só usemos a “página” 0)
    private var pageIndex = 0

    private var circleDiameterPx: Int
    private var paddingPx: Float
    private var borderWidthPx: Float
    private var safeMarginPx: Int

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BG_COLOR
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val haloPulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val mainPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR_MAIN
        typeface = Typeface.DEFAULT_BOLD
    }
    private val legendPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LEGEND_COLOR
    }

    private var mainTextHeight = 0f
    private var legendTextHeight = 0f

    // gestos/drag
    private val gestureDetector: GestureDetector
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isDragging = false
    private var dragReady = false
    private var dragDelayPosted = false
    private var touchSlop: Int = 0
    private var initialWindowX: Int = 0
    private var initialWindowY: Int = 0
    private var initialTouchRawX: Float = 0f
    private var initialTouchRawY: Float = 0f
    private val dragReadyRunnable = Runnable {
        dragReady = true
        dragDelayPosted = false
    }

    // dados tempo/€/h (tempo real)
    private var currentValuePerHour: Double? = null
    private var currentHourRating: IndividualRating = IndividualRating.UNKNOWN
    private var elapsedTimeSeconds: Long = 0

    // dados iniciais (semáforo)
    private var initialValuePerKm: Double? = null
    private var initialTotalDistance: Double? = null
    private var offerValueRaw: String? = null
    private var initialTotalDurationMinutes: Int? = null
    private var initialKmRating: IndividualRating = IndividualRating.UNKNOWN
    private var initialHourRating: IndividualRating? = null

    // cor/estado do halo
    private var initialBorderRating: BorderRating = BorderRating.GRAY
    private var currentBorderRating: BorderRating = BorderRating.GRAY

    // ecrã
    private var screenW = 1080
    private var screenH = 1920

    // formatação
    private val euroSymbols = DecimalFormatSymbols(Locale.US)
    private val dfHour1 = DecimalFormat("0.0", euroSymbols)
    private val dfKm1 = DecimalFormat("0.0", euroSymbols)
    private val dfVal = DecimalFormat("0.00", euroSymbols)
    private val dfEurKm = DecimalFormat("0.00", euroSymbols)

    // pulso
    private var pulseEnabled = true
    private var pulseProgress = 0f // 0..1
    private var pulseAnimator: ValueAnimator? = null

    // OfferData atual para alimentar o mapa
    private var offerForMap: OfferData? = null

    // estado local: mapa visível ou não (para toggle)
    private var isMapVisible: Boolean = false

    init {
        val dm = resources.displayMetrics
        val density = dm.density
        val scaled = dm.scaledDensity

        circleDiameterPx = (DEFAULT_DIAMETER_DP * density).toInt()
        borderWidthPx = BORDER_WIDTH_DP * density
        paddingPx = PADDING_DP * density
        safeMarginPx = (SAFE_MARGIN_DP * density).toInt()

        borderPaint.strokeWidth = borderWidthPx
        haloPulsePaint.strokeWidth = borderWidthPx * PULSE_EXTRA_WIDTH_FACTOR
        mainPaint.textSize = MAIN_TEXT_SP * scaled
        legendPaint.textSize = LEGEND_TEXT_SP * scaled
        recalcTextMetrics()

        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        try {
            val metrics = context.resources.displayMetrics
            screenW = metrics.widthPixels
            screenH = metrics.heightPixels
        } catch (_: Exception) {}

        gestureDetector =
            GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    initialWindowX = layoutParams.x
                    initialWindowY = layoutParams.y
                    initialTouchRawX = e.rawX
                    initialTouchRawY = e.rawY
                    isDragging = false
                    dragReady = false
                    if (!dragDelayPosted) {
                        dragDelayPosted = true
                        mainHandler.postDelayed(
                            dragReadyRunnable,
                            DRAG_ACTIVATION_DELAY_MS
                        )
                    }
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (isDragging) return false
                    cancelDragDelay()
                    // Esconde drop zone, se estiver visível
                    sendOverlayServiceSimpleAction(OverlayService.ACTION_HIDE_DROP_ZONE_AND_CHECK_DROP)

                    // Toggle do mapa
                    if (isMapVisible) {
                        hideMapFromTracking()
                        isMapVisible = false
                    } else {
                        openMapFromTracking()
                        isMapVisible = true
                    }
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent,
                    e2: MotionEvent,
                    dx: Float,
                    dy: Float
                ): Boolean {
                    val totalDeltaX = e2.rawX - initialTouchRawX
                    val totalDeltaY = e2.rawY - initialTouchRawY
                    val distanceEnough =
                        (abs(totalDeltaX) > touchSlop * DRAG_DISTANCE_FACTOR) ||
                                (abs(totalDeltaY) > touchSlop * DRAG_DISTANCE_FACTOR)

                    if (!isDragging && dragReady && distanceEnough) {
                        isDragging = true
                        sendOverlayServiceSimpleAction(OverlayService.ACTION_SHOW_DROP_ZONE)
                    }
                    if (isDragging) {
                        applyClampedPosition(
                            initialWindowX + totalDeltaX.toInt(),
                            initialWindowY + totalDeltaY.toInt()
                        )
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isDragging) return false
                    cancelDragDelay()
                    sendOverlayServiceSimpleAction(OverlayService.ACTION_STOP_TRACKING)
                    val intent = Intent(context, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_HIDE_DROP_ZONE_AND_CHECK_DROP
                        putExtra(OverlayService.EXTRA_UP_X, -1f)
                        putExtra(OverlayService.EXTRA_UP_Y, -1f)
                    }
                    context.startService(intent)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (isDragging) return
                    cancelDragDelay()

                    // mantém o comportamento antigo: passa para o ícone + quick menu
                    sendOverlayServiceSimpleAction(OverlayService.ACTION_SWITCH_TO_ICON)
                    mainHandler.postDelayed({
                        sendOverlayServiceSimpleAction(OverlayService.ACTION_SHOW_QUICK_MENU)
                    }, 60L)

                    try {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    } catch (_: Throwable) {
                    }
                }
            })

        setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                val intent = Intent(context, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_HIDE_DROP_ZONE_AND_CHECK_DROP
                    putExtra(OverlayService.EXTRA_UP_X, event.rawX)
                    putExtra(OverlayService.EXTRA_UP_Y, event.rawY)
                }
                context.startService(intent)
                isDragging = false
                cancelDragDelay()
            }
            true
        }
    }

    // ---------- Ciclo de vida p/ pulso ----------
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (pulseEnabled) startPulse()
        // por segurança, assumimos que o mapa está fechado quando o overlay entra
        isMapVisible = false
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    private fun startPulse() {
        if (pulseAnimator != null) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_DURATION_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
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
    }

    fun setPulseEnabled(enabled: Boolean) {
        pulseEnabled = enabled
        if (enabled) startPulse() else stopPulse()
        invalidate()
    }

    private fun cancelDragDelay() {
        if (dragDelayPosted) {
            mainHandler.removeCallbacks(dragReadyRunnable)
            dragDelayPosted = false
        }
        dragReady = false
    }

    private fun recalcTextMetrics() {
        mainTextHeight = mainPaint.descent() - mainPaint.ascent()
        legendTextHeight = legendPaint.descent() - legendPaint.ascent()
    }

    private fun sendOverlayServiceSimpleAction(action: String) {
        val intent = Intent(context, OverlayService::class.java).apply { this.action = action }
        try {
            context.startService(intent)
        } catch (_: Exception) {
        }
    }

    // ======== APIs ========
    fun updateInitialData(
        iVpk: Double?, iDist: Double?, iDur: Int?, oVal: String?,
        iKmR: IndividualRating, cBR: BorderRating
    ) {
        initialValuePerKm = iVpk
        initialTotalDistance = iDist
        initialTotalDurationMinutes = iDur
        offerValueRaw = oVal
        initialKmRating = iKmR

        initialBorderRating = cBR
        currentBorderRating = cBR
        invalidate()
    }

    fun setInitialHourRatingFromSemaphore(r: IndividualRating) {
        initialHourRating = r
        invalidate()
    }

    fun updateRealTimeData(cVph: Double?, hR: IndividualRating, elSec: Long) {
        currentValuePerHour = cVph
        currentHourRating = hR
        elapsedTimeSeconds = elSec
        invalidate()

        // Envia €/h atual para o cabeçalho do mapa (quando o header está visível)
        val currStr = cVph?.let { dfHour1.format(it) } ?: "--.-"
        val intent = Intent(MapPreviewActivity.ACTION_UPDATE_CARD_METRICS).apply {
            setPackage(context.packageName)
            putExtra(MapPreviewActivity.EXTRA_METRIC_EUR_PER_HOUR_CURRENT, currStr)
        }
        try {
            context.sendBroadcast(intent)
        } catch (_: Exception) {
        }
    }

    // OfferData atual para o mapa conhecer recolha/destino
    fun setOfferForMap(offer: OfferData?) {
        offerForMap = offer
    }

    fun setCircleDiameterDp(dp: Float) {
        val px = (dp * resources.displayMetrics.density).toInt()
        if (px != circleDiameterPx) {
            circleDiameterPx = px
            requestLayout()
            applyClampedPosition(layoutParams.x, layoutParams.y)
        }
    }

    fun setOpacity(alphaPercent: Int) {
        val a = (alphaPercent.coerceIn(0, 100) / 100f * 255).toInt()
        bgPaint.alpha = a
        invalidate()
    }

    fun snapToTopRight() {
        val side = measuredWidth.coerceAtLeast(circleDiameterPx)
        val x = screenW - side - safeMarginPx
        val y = safeMarginPx
        layoutParams.x = x
        layoutParams.y = y
        mainHandler.post {
            try {
                if (isAttachedToWindow) windowManager.updateViewLayout(this, layoutParams)
            } catch (_: Exception) {
            }
        }
    }

    // ======== Measure/Draw ========
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = circleDiameterPx
        val w = resolveSize(desired, widthMeasureSpec)
        val h = resolveSize(desired, heightMeasureSpec)
        val side = min(w, h)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        mainPaint.color = TEXT_COLOR_MAIN

        val borderColor = borderColorFor(currentBorderRating)
        borderPaint.color = borderColor

        val cx = width / 2f
        val cy = height / 2f

        // Raio base
        val radiusBase = (min(width, height) / 2f) - borderWidthPx / 2f - 0.5f

        // fundo + moldura
        canvas.drawCircle(cx, cy, radiusBase, bgPaint)
        canvas.drawCircle(cx, cy, radiusBase, borderPaint)

        // anel sólido
        val solidStroke = borderWidthPx * 1.25f
        val solidHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = solidStroke
            color = borderColor
            alpha = 230
        }
        val radiusSolid = radiusBase - (solidStroke - borderWidthPx) * 0.5f
        canvas.drawCircle(cx, cy, max(0f, radiusSolid), solidHaloPaint)

        // pulso suave
        if (pulseEnabled) {
            val alpha =
                (PULSE_MIN_ALPHA + (PULSE_MAX_ALPHA - PULSE_MIN_ALPHA) * pulseProgress).toInt()
            val pulseStroke =
                borderWidthPx * (1f + PULSE_EXTRA_WIDTH_FACTOR * pulseProgress)
            haloPulsePaint.color = withAlpha(borderColor, alpha)
            haloPulsePaint.strokeWidth = pulseStroke

            val radiusPulse = radiusBase - (pulseStroke - borderWidthPx) * 0.5f
            canvas.drawCircle(cx, cy, max(0f, radiusPulse), haloPulsePaint)
        }

        // Página única: tempo inicial / decorrido
        val topLegend = "inicial"
        val bottomLegend = "decorrido"
        val top = initialTotalDurationMinutes?.let { formatHM(it) } ?: "--h--"
        val bottom = formatElapsedHM(elapsedTimeSeconds)
        drawTwoLinesWithLegends(
            canvas, cx, cy, radiusBase,
            topLegend, top, TEXT_COLOR_MAIN,
            bottomLegend, bottom, TEXT_COLOR_MAIN
        )
    }

    private fun applyClampedPosition(targetX: Int, targetY: Int) {
        val maxX = screenW - measuredWidth - safeMarginPx
        val minX = safeMarginPx
        val clampedX = max(minX, min(targetX, maxX))

        val minY = safeMarginPx
        val maxY =
            ((screenH * TOP_BAND_RATIO) - measuredHeight - safeMarginPx).toInt()
        val clampedY = max(minY, min(targetY, maxY))

        layoutParams.x = clampedX
        layoutParams.y = clampedY
        mainHandler.post {
            try {
                if (isAttachedToWindow) windowManager.updateViewLayout(
                    this@TrackingOverlayView,
                    layoutParams
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun drawTwoLinesWithLegends(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        legendTop: String,
        lineTop: String,
        colorTop: Int,
        legendBottom: String,
        lineBottom: String,
        colorBottom: Int
    ) {
        val box = (radius - paddingPx) * 2f

        var mainSize = MAIN_TEXT_SP * resources.displayMetrics.scaledDensity
        var legSize = LEGEND_TEXT_SP * resources.displayMetrics.scaledDensity

        mainPaint.textSize = mainSize
        legendPaint.textSize = legSize
        recalcTextMetrics()

        fun widest(): Float {
            val w1 = maxOf(mainPaint.measureText(lineTop), legendPaint.measureText(legendTop))
            val w2 = maxOf(mainPaint.measureText(lineBottom), legendPaint.measureText(legendBottom))
            return maxOf(w1, w2)
        }

        fun totalHeight(): Float {
            val gapLegend = paddingPx * 0.1f
            val gapMain = paddingPx * 0.18f
            val topBlock = legendTextHeight + gapLegend + mainTextHeight
            val bottomBlock =
                if (lineBottom.isBlank() && legendBottom.isBlank()) 0f
                else legendTextHeight + gapLegend + mainTextHeight
            return topBlock + (if (bottomBlock == 0f) 0f else gapMain + bottomBlock)
        }

        var attempts = 0
        while ((widest() > box || totalHeight() > box) && attempts < 28) {
            mainSize *= 0.92f
            legSize *= 0.92f
            mainPaint.textSize = mainSize
            legendPaint.textSize = legSize
            recalcTextMetrics()
            attempts++
        }

        legendPaint.textAlign = Paint.Align.CENTER

        val gapLegend = paddingPx * 0.1f
        val gapMain = paddingPx * 0.18f

        val topBlock = legendTextHeight + gapLegend + mainTextHeight
        val bottomBlock =
            if (lineBottom.isBlank() && legendBottom.isBlank()) 0f
            else legendTextHeight + gapLegend + mainTextHeight
        val total = topBlock + (if (bottomBlock == 0f) 0f else gapMain + bottomBlock)

        var y = cy - total / 2f

        if (legendTop.isNotBlank()) {
            val yLegTop =
                y - (legendPaint.descent() + legendPaint.ascent()) / 2f + legendTextHeight / 2f
            canvas.drawText(legendTop, cx, yLegTop, legendPaint)
            y += legendTextHeight + gapLegend
        }

        val paintTop = TextPaint(mainPaint).apply {
            textAlign = Paint.Align.CENTER
            color = colorTop
        }
        val yTop =
            y - (paintTop.descent() + paintTop.ascent()) / 2f + mainTextHeight / 2f
        canvas.drawText(lineTop, cx, yTop, paintTop)
        y += mainTextHeight

        if (!(lineBottom.isBlank() && legendBottom.isBlank())) {
            y += gapMain
            if (legendBottom.isNotBlank()) {
                val yLegBottom =
                    y - (legendPaint.descent() + legendPaint.ascent()) / 2f + legendTextHeight / 2f
                canvas.drawText(legendBottom, cx, yLegBottom, legendPaint)
                y += legendTextHeight + gapLegend
            }

            val paintBottom = TextPaint(mainPaint).apply {
                textAlign = Paint.Align.CENTER
                color = colorBottom
            }
            val yBottom =
                y - (paintBottom.descent() + paintBottom.ascent()) / 2f + mainTextHeight / 2f
            canvas.drawText(lineBottom, cx, yBottom, paintBottom)
        }
    }

    // ---------- util cores ----------
    private fun ratingToColor(r: IndividualRating): Int = when (r) {
        IndividualRating.GOOD -> METRIC_COLOR_GOOD
        IndividualRating.MEDIUM -> METRIC_COLOR_MEDIUM
        IndividualRating.POOR -> METRIC_COLOR_POOR
        else -> METRIC_COLOR_UNKNOWN
    }

    private fun borderColorFor(br: BorderRating): Int = when (br) {
        BorderRating.GREEN -> BORDER_COLOR_GREEN
        BorderRating.YELLOW -> BORDER_COLOR_YELLOW
        BorderRating.RED -> BORDER_COLOR_RED
        else -> BORDER_COLOR_GRAY
    }

    private fun borderToIndividual(br: BorderRating): IndividualRating = when (br) {
        BorderRating.GREEN -> IndividualRating.GOOD
        BorderRating.YELLOW -> IndividualRating.MEDIUM
        BorderRating.RED -> IndividualRating.POOR
        else -> IndividualRating.UNKNOWN
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    // ---------- util formatação ----------
    private fun formatHM(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return String.format(Locale.getDefault(), "%dh%02d", h, m)
    }

    private fun formatElapsedHM(seconds: Long): String {
        val h = TimeUnit.SECONDS.toHours(seconds)
        val m = TimeUnit.SECONDS.toMinutes(seconds) % 60
        val s = seconds % 60
        return if (h == 0L) String.format(Locale.getDefault(), "%02dm%02d", m, s)
        else String.format(Locale.getDefault(), "%dh%02d", h, m)
    }

    private fun parseOfferValue(): Double? {
        val raw = offerValueRaw?.trim()?.replace("€", "") ?: return null
        val norm = raw.replace(",", ".").replace(Regex("[^0-9\\.]"), "")
        return norm.toDoubleOrNull()
    }

    private fun calcEuroPerHourPlanned(): String {
        val valNum = parseOfferValue()
        val durMin = initialTotalDurationMinutes
        if (valNum == null || durMin == null || durMin <= 0) return "--.-"
        val hours = durMin.toDouble() / 60.0
        if (hours <= 0.0) return "--.-"
        return dfHour1.format(valNum / hours)
    }

    // ----------- toggle do mapa a partir do tracking -----------
    private fun openMapFromTracking() {
        val offer = offerForMap
        val pickupAddr = offer?.moradaRecolha?.takeIf { !it.isNullOrBlank() }
        val destAddr = offer?.moradaDestino?.takeIf { !it.isNullOrBlank() }

        // preparar textos do card
        val offerVal = parseOfferValue()?.let { "€ " + dfVal.format(it) }
            ?: offerValueRaw?.let { raw ->
                val t = raw.trim()
                if (t.startsWith("€")) t else "€ $t"
            } ?: "€ —"

        val eurKm = initialValuePerKm?.let { "€ " + dfEurKm.format(it) } ?: "—"

        val totalKmNum = initialTotalDistance
            ?: offer?.calculateTotalDistance()
        val totalKm = totalKmNum?.let { dfKm1.format(it) } ?: "—"

        val plannedHour = calcEuroPerHourPlanned().let { s -> if (s != "--.-") "€ $s" else "—" }

        fun legSuffix(distRaw: String?, durRaw: String?): String? {
            val d = distRaw.toDoubleOrNullWithCorrection()
            val m = durRaw.toIntOrNullWithCorrection()
            if (d == null && m == null) return null
            val parts = mutableListOf<String>()
            d?.let { parts += dfKm1.format(it) + " km" }
            m?.let { parts += "$m min" }
            return parts.joinToString(" · ")
        }

        val pickupSuffix = legSuffix(offer?.pickupDistance, offer?.pickupDuration)
        val destSuffix   = legSuffix(offer?.tripDistance, offer?.tripDuration)

        // 1) UPDATE_MAP (broadcast) para atualizar markers/rotas
        runCatching {
            val upd = Intent(MapPreviewActivity.ACTION_UPDATE_MAP).apply {
                setPackage(context.packageName)
                pickupAddr?.let { putExtra(MapPreviewActivity.EXTRA_PICKUP_ADDRESS, it) }
                destAddr?.let { putExtra(MapPreviewActivity.EXTRA_DEST_ADDRESS, it) }

                putExtra(MapPreviewActivity.EXTRA_CARD_OFFER_VALUE, offerVal)
                putExtra(MapPreviewActivity.EXTRA_CARD_EUR_PER_KM, eurKm)
                putExtra(MapPreviewActivity.EXTRA_CARD_TOTAL_KM, totalKm)
                putExtra(MapPreviewActivity.EXTRA_CARD_EUR_PER_HOUR_PLANNED, plannedHour)

                pickupSuffix?.let { putExtra(MapPreviewActivity.EXTRA_PICKUP_SUFFIX_FROM_CARD, it) }
                destSuffix?.let   { putExtra(MapPreviewActivity.EXTRA_DEST_SUFFIX_FROM_CARD, it) }
            }
            context.sendBroadcast(upd)
        }

        // 2) Abrir (ou trazer à frente) o MapPreviewActivity, fullscreen com header
        runCatching {
            val open = Intent(context, MapPreviewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MapPreviewActivity.EXTRA_FULLSCREEN, true)
                putExtra(MapPreviewActivity.EXTRA_SHOW_HEADER, true)

                pickupAddr?.let { putExtra(MapPreviewActivity.EXTRA_PICKUP_ADDRESS, it) }
                destAddr?.let { putExtra(MapPreviewActivity.EXTRA_DEST_ADDRESS, it) }

                putExtra(MapPreviewActivity.EXTRA_CARD_OFFER_VALUE, offerVal)
                putExtra(MapPreviewActivity.EXTRA_CARD_EUR_PER_KM, eurKm)
                putExtra(MapPreviewActivity.EXTRA_CARD_TOTAL_KM, totalKm)
                putExtra(MapPreviewActivity.EXTRA_CARD_EUR_PER_HOUR_PLANNED, plannedHour)

                pickupSuffix?.let { putExtra(MapPreviewActivity.EXTRA_PICKUP_SUFFIX_FROM_CARD, it) }
                destSuffix?.let   { putExtra(MapPreviewActivity.EXTRA_DEST_SUFFIX_FROM_CARD, it) }
            }
            context.startActivity(open)
        }

        // 3) Pedir SHOW ao MapPreviewActivity (fica visível até mandarmos esconder)
        runCatching {
            val show = Intent(MapPreviewActivity.ACTION_SEMAFORO_SHOW_MAP).apply {
                setPackage(context.packageName)
                putExtra(MapPreviewActivity.EXTRA_AUTO_HIDE_MS, 0L) // 0 = não auto-hide
                putExtra(MapPreviewActivity.EXTRA_FADE_MS, 250L)
                putExtra(MapPreviewActivity.EXTRA_FULLSCREEN, true)
            }
            context.sendBroadcast(show)
        }
    }

    private fun hideMapFromTracking() {
        runCatching {
            val hide = Intent(MapPreviewActivity.ACTION_SEMAFORO_HIDE_MAP).apply {
                setPackage(context.packageName)
                putExtra(MapPreviewActivity.EXTRA_FADE_MS, 250L)
            }
            context.sendBroadcast(hide)
        }
    }
}
