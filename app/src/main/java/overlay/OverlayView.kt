// (arquivo completo - OverlayView.kt)
package com.example.smartdriver.overlay

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.text.TextPaint
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
import kotlin.math.min
import java.lang.StringBuilder
import com.example.smartdriver.map.MapPreviewActivity

@SuppressLint("ClickableViewAccessibility")
class OverlayView(context: Context) : View(context) {

    // ======================= Zonas (estado vindo do mapa) =======================
    enum class ZoneState { UNKNOWN, NEUTRAL, PREFERRED, NO_GO }
    private var currentZoneState: ZoneState = readLastZoneFromPrefs() ?: ZoneState.UNKNOWN
    fun updateZoneState(state: ZoneState) { currentZoneState = state; invalidate() }

    companion object {
        private const val TAG = "OverlayView"

        // Broadcast para receber dicas de zona (opcional)
        const val ACTION_ZONE_HINT = "com.example.smartdriver.overlay.ACTION_ZONE_HINT"
        const val EXTRA_ZONE_KIND  = "zone_kind" // "NO_GO" | "PREFERRED" | "NEUTRAL" | "UNKNOWN"

        // Cores base
        private val BORDER_COLOR_GREEN = Color.parseColor("#2E7D32")
        private val BORDER_COLOR_YELLOW = Color.parseColor("#F9A825")
        private val BORDER_COLOR_RED   = Color.parseColor("#C62828")
        private val BORDER_COLOR_GRAY  = Color.parseColor("#9E9E9E")

        private val INDICATOR_COLOR_GOOD    = BORDER_COLOR_GREEN
        private val INDICATOR_COLOR_MEDIUM  = BORDER_COLOR_YELLOW
        private val INDICATOR_COLOR_POOR    = BORDER_COLOR_RED
        private val INDICATOR_COLOR_UNKNOWN = Color.DKGRAY

        private val BACKGROUND_COLOR       = Color.WHITE
        private val TEXT_COLOR_LABEL       = Color.DKGRAY
        private val TEXT_COLOR_VALUE       = Color.BLACK
        private val PLACEHOLDER_TEXT_COLOR = Color.LTGRAY

        // Zonas (fundo do verso e miolo do círculo)
        private val ZONE_NEUTRAL_FILL   = Color.parseColor("#FAFAFA")
        private val ZONE_PREFERRED_FILL = Color.parseColor("#C8E6C9")
        private val ZONE_NO_GO_FILL     = Color.parseColor("#FFCDD2")

        // Miolo do semáforo — BRANCO (pedido)
        private val INNER_FILL_COLOR    = Color.parseColor("#FFFFFF")

        // Dimensões (DP/SP)
        private const val PADDING_DP = 12f
        private const val BORDER_WIDTH_DP = 8f
        private const val CORNER_RADIUS_DP = 12f
        private const val TEXT_SPACING_VERTICAL_DP = 3f
        private const val LINE_SPACING_VERTICAL_DP = 6f
        private const val TEXT_SPACING_HORIZONTAL_DP = 15f
        private const val INDICATOR_BAR_WIDTH_DP = 4f
        private const val INDICATOR_BAR_MARGIN_DP = 6f

        // Círculo (semáforo)
        private const val OUTER_STROKE_DP = 14f
        private const val INNER_PADDING_DP = 8f
        private const val PROHIBIT_SIZE_DP = 22f
        private const val PROHIBIT_STROKE_DP = 3f
        private const val MIN_CIRCLE_DIAMETER_DP = 136f

        // Tamanhos de texto
        private const val LABEL_TEXT_SIZE_SP = 11f
        private const val VALUE_TEXT_SIZE_SP = 13f
        private const val HIGHLIGHT_VALUE_TEXT_SIZE_SP = 14f
        private const val EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP = 15f

        private const val HOUR_TEXT_SP = 22f        // €/h central
        private const val OFFER_TEXT_SP = 12.5f     // valor da oferta (acima do €/h)
        private const val PLATFORM_TEXT_SP = 12.5f  // plataforma (abaixo do €/h)
        private const val KM_TEXT_SP = 12.5f        // €/km no rodapé (aumentado)

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

    // ======================= Estado do componente =======================
    private var currentEvaluationResult: EvaluationResult? = null
    private var currentOfferData: OfferData? = null
    private var fontSizeScale = 1.0f
    private var viewAlpha = 0.95f
    private var showBack = false

    // Sincronização com mapa
    private var mapShownOnce = false
    private var defaultAutoHideMs = 10_000L
    private var defaultFadeMs = 400L
    private var lastAlphaSent = -1f

    // Banner
    private var bannerText: String? = null
    private var bannerType: BannerType = BannerType.INFO
    private var bannerClearAt: Long = 0L

    // ======================= Paints =======================
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = BACKGROUND_COLOR }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = BORDER_COLOR_GRAY }

    // Texto geral
    private val labelTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR_LABEL
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }
    private val valueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR_VALUE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }
    private val highlightValueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR_VALUE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }
    private val extraHighlightValueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR_VALUE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }
    private val placeholderTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PLACEHOLDER_TEXT_COLOR
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }

    // Banner
    private val bannerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bannerTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }

    // Semáforo redondo
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hourTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR_VALUE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }
    private val offerTinyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR_VALUE // ajustado por uso
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }
    private val kmTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR_VALUE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = BORDER_COLOR_RED }
    private val prohibitRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = BORDER_COLOR_RED }
    private val prohibitBarPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }

    // Quadro do verso
    private val backCardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val backCardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // ======================= Dimensões calculadas (PX) =======================
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

    // Círculo
    private var outerStrokePx = 0f
    private var innerPaddingPx = 0f
    private var prohibitSizePx = 0f
    private var prohibitStrokePx = 0f

    // Verso (quadro)
    private var backCardCornerPx = 0f
    private var backCardPadPx = 0f

    // Gap extra entre linhas do verso
    private var lineGapPx = 0f

    // ======================= Formatação PT-PT =======================
    private val euroSymbols = DecimalFormatSymbols(Locale("pt", "PT"))
    private val euroHoraFormatter = DecimalFormat("0.0", euroSymbols).apply { roundingMode = RoundingMode.HALF_UP }
    private val euroOfferFormatter = DecimalFormat("0.00", euroSymbols).apply { roundingMode = RoundingMode.HALF_UP }

    // ======================= Retângulos auxiliares =======================
    private val backgroundRect = RectF()
    private val borderRect = RectF()
    private val bannerRect = RectF()

    // ======================= Gestos =======================
    private val gestureDetector: GestureDetector

    init {
        alpha = viewAlpha
        updateDimensionsAndPaints()

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                showBack = !showBack
                requestLayout()
                invalidate()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                openOrUpdateMapForCurrentOffer(show = true, forceShowMs = 12_000L)
            }

            override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x

                if (abs(diffY) > abs(diffX)) {
                    if (abs(diffY) > swipeMinDistancePx && abs(velocityY) > swipeThresholdVelocityPx) {
                        val action = if (diffY < 0)
                            OverlayService.ACTION_SHOW_PREV_OVERLAY
                        else
                            OverlayService.ACTION_SHOW_NEXT_OVERLAY
                        try {
                            context.startService(Intent(context, OverlayService::class.java).apply { this.action = action })
                        } catch (ex: Exception) {
                            Log.e(TAG, "Erro ao enviar ação de navegação: ${ex.message}")
                        }
                        return true
                    }
                } else {
                    if (abs(diffX) > swipeMinDistancePx && abs(velocityX) > swipeThresholdVelocityPx) {
                        if (diffX > 0) {
                            showStartConfirmDialog()
                            return true
                        } else {
                            val confirmDismiss = Intent(context, OverlayService::class.java).apply {
                                action = OverlayService.ACTION_CONFIRM_DISMISS_MAIN_OVERLAY
                            }
                            try { context.startService(confirmDismiss) } catch (ex: Exception) {
                                Log.e(TAG, "Erro ao enviar CONFIRM_DISMISS_MAIN_OVERLAY (swipe): ${ex.message}")
                            }
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

    // ======================= Eventos de toque =======================
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val consumed = gestureDetector.onTouchEvent(event)
        if (bannerText != null && bannerClearAt > 0 && System.currentTimeMillis() >= bannerClearAt) {
            bannerText = null
            bannerClearAt = 0
            invalidate()
        }
        return consumed || super.onTouchEvent(event)
    }

    // ======================= Integração com o mapa =======================
    private fun openOrUpdateMapForCurrentOffer(show: Boolean, forceShowMs: Long? = null) {
        val od = currentOfferData
        val pickupAddr = od?.moradaRecolha?.takeIf { it.isNotBlank() }
        val destAddr   = od?.moradaDestino?.takeIf { it.isNotBlank() }

        // 1) Atualiza o mapa (se ele já estiver aberto) via broadcast
        runCatching {
            val upd = Intent(MapPreviewActivity.ACTION_UPDATE_MAP).apply {
                setPackage(context.packageName)
                pickupAddr?.let { putExtra(MapPreviewActivity.EXTRA_PICKUP_ADDRESS, it) }
                destAddr?.let   { putExtra(MapPreviewActivity.EXTRA_DEST_ADDRESS,   it) }
            }
            context.sendBroadcast(upd)
        }

        // 2) Se NÃO for para mostrar, paramos aqui (não abrimos a activity!)
        if (!show) return

        // 3) Abrir/Trazer à frente o mapa e auto-hide
        runCatching {
            val open = Intent(context, MapPreviewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                pickupAddr?.let { putExtra(MapPreviewActivity.EXTRA_PICKUP_ADDRESS, it) }
                destAddr?.let   { putExtra(MapPreviewActivity.EXTRA_DEST_ADDRESS,   it) }
            }
            context.startActivity(open)
        }.onFailure { Log.e(TAG, "Falha ao abrir MapPreviewActivity: ${it.message}") }

        val dur = forceShowMs ?: defaultAutoHideMs
        runCatching {
            val showIntent = Intent(MapPreviewActivity.ACTION_SEMAFORO_SHOW_MAP).apply {
                setPackage(context.packageName)
                putExtra(MapPreviewActivity.EXTRA_AUTO_HIDE_MS, dur)
                putExtra(MapPreviewActivity.EXTRA_FADE_MS, defaultFadeMs)
            }
            context.sendBroadcast(showIntent)
        }
        syncMapAlpha(viewAlpha)
    }
    private fun syncMapAlpha(a: Float) {
        val aClamped = a.coerceIn(0f, 1f)
        if (kotlin.math.abs(aClamped - lastAlphaSent) < 0.005f) return
        lastAlphaSent = aClamped
        runCatching {
            val i = Intent(MapPreviewActivity.ACTION_SEMAFORO_ALPHA).apply {
                setPackage(context.packageName)
                putExtra(MapPreviewActivity.EXTRA_ALPHA, aClamped)
            }
            context.sendBroadcast(i)
        }
        if (aClamped <= 0.01f) {
            runCatching {
                val hide = Intent(MapPreviewActivity.ACTION_SEMAFORO_HIDE_MAP).apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(hide)
            }
        }
    }

    // ======================= Ações =======================
    private fun openMapPreview() { openOrUpdateMapForCurrentOffer(show = true, forceShowMs = 12_000L) }

    private fun showStartConfirmDialog() {
        val dialog = AlertDialog.Builder(context)
            .setTitle("Iniciar viagem")
            .setMessage("Iniciar o acompanhamento da viagem?")
            .setPositiveButton("Iniciar") { d, _ ->
                startTrackingMode()
                d.dismiss()
            }
            .setNegativeButton("Cancelar") { d, _ -> d.dismiss() }
            .create()

        dialog.window?.let { w ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                w.setType(WindowManager.LayoutParams.TYPE_PHONE)
            }
        }

        try { dialog.show() } catch (ex: Exception) {
            Log.e(TAG, "Falha ao mostrar confirmação de início: ${ex.message}")
            showBanner("A iniciar viagem…", BannerType.INFO, 1500)
            startTrackingMode()
        }
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

    private fun showRawTextDialog() {
        val txt = currentOfferData?.rawText?.takeIf { it.isNotBlank() } ?: "(rawText vazio)"
        val dialog = AlertDialog.Builder(context)
            .setTitle("OCR bruto (rawText)")
            .setMessage(txt)
            .setPositiveButton("Copiar") { d, _ ->
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("rawText", txt))
                } catch (_: Exception) {}
                d.dismiss()
            }
            .setNegativeButton("Fechar") { d, _ -> d.dismiss() }
            .create()

        dialog.window?.let { w ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                w.setType(WindowManager.LayoutParams.TYPE_PHONE)
            }
        }
        try { dialog.show() } catch (ex: Exception) {
            Log.e(TAG, "Falha ao mostrar rawText: ${ex.message}")
        }
    }

    // ======================= Medidas e escalas =======================
    private fun updateDimensionsAndPaints() {
        val dm = resources.displayMetrics

        // dp “fixos” do cartão e textos
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

        // Textos (SP) — escalam com fontSizeScale via sp()
        labelTextPaint.textSize = sp(LABEL_TEXT_SIZE_SP)
        valueTextPaint.textSize = sp(VALUE_TEXT_SIZE_SP)
        highlightValueTextPaint.textSize = sp(HIGHLIGHT_VALUE_TEXT_SIZE_SP)
        extraHighlightValueTextPaint.textSize = sp(EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP)
        placeholderTextPaint.textSize = sp(HIGHLIGHT_VALUE_TEXT_SIZE_SP)
        bannerTextPaint.textSize = sp(BANNER_TEXT_SIZE_SP)

        // ===== Escala PROPORCIONAL do semáforo (geometria) =====
        val ui = fontSizeScale.coerceIn(0.7f, 1.6f)

        // Círculo / anel
        outerStrokePx    = dp(OUTER_STROKE_DP)    * ui
        innerPaddingPx   = dp(INNER_PADDING_DP)   * ui
        prohibitSizePx   = dp(PROHIBIT_SIZE_DP)   * ui
        prohibitStrokePx = dp(PROHIBIT_STROKE_DP) * ui
        ringPaint.strokeWidth = outerStrokePx
        prohibitRingPaint.strokeWidth = prohibitStrokePx

        // Textos do semáforo
        hourTextPaint.textSize = sp(HOUR_TEXT_SP)
        offerTinyPaint.textSize = sp(OFFER_TEXT_SP)
        kmTextPaint.textSize = sp(KM_TEXT_SP)

        // Quadro do verso
        backCardCornerPx = dp(12f) * ui
        backCardPadPx    = dp(16f) * ui
        backCardStrokePaint.strokeWidth = dp(1f) * ui

        // Alturas derivadas (dependem do tamanho atual do texto)
        labelHeight = labelTextPaint.descent() - labelTextPaint.ascent()
        valueHeight = valueTextPaint.descent() - valueTextPaint.ascent()
        highlightValueHeight = highlightValueTextPaint.descent() - highlightValueTextPaint.ascent()
        extraHighlightValueHeight = extraHighlightValueTextPaint.descent() - extraHighlightValueTextPaint.ascent()

        // Espaço entre linhas com respiração proporcional
        lineGapPx = max(dp(8f) * ui, valueHeight * 0.32f)
    }

    private fun sp(sizeSp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp * fontSizeScale, resources.displayMetrics)

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    // ======================= onMeasure =======================
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateDimensionsAndPaints()
        val bannerExtra = if (bannerText != null) (bannerPadVPx * 2 + bannerTextPaint.textSize) + textSpacingVerticalPx else 0f

        if (!showBack) {
            // Frente — círculo
            val sample = "88,8 €"
            val textW = hourTextPaint.measureText(sample)
            val textH = hourTextPaint.descent() - hourTextPaint.ascent()

            val ui = fontSizeScale.coerceIn(0.7f, 1.6f)

            // O que o texto precisaria
            val byText = (max(textW, textH) + innerPaddingPx * 2f + outerStrokePx * 2f)
            // Mínimo em dp que também escala com o slider
            val byDp   = dp(MIN_CIRCLE_DIAMETER_DP) * ui
            val minDiameter = max(byText, byDp)

            // Respiração extra para o €/km (proporcional)
            val extraBottomForSubtext = dp(22f) * ui
            val sideMargin = dp(18f) * ui

            val desiredW = (minDiameter + sideMargin).toInt()
            val desiredH = (minDiameter + bannerExtra + extraBottomForSubtext).toInt()

            val w = resolveSize(desiredW, widthMeasureSpec)
            val h = resolveSize(desiredH, heightMeasureSpec)
            setMeasuredDimension(w, h)
            return
        }

        // Verso — medir pelo conteúdo
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val targetW = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> min(widthSize, dp(460f).toInt())
            else -> dp(360f).toInt()
        }

        val outerMargin = dp(12f)
        val contentWidth = (targetW - (outerMargin * 2f) - (backCardPadPx * 2f)).coerceAtLeast(dp(200f))

        val od = currentOfferData
        val pickupAddr = od?.moradaRecolha?.takeIf { it.isNotBlank() } ?: "—"
        val destAddr   = od?.moradaDestino?.takeIf { it.isNotBlank() } ?: "—"

        val pickupLines = wrapLinesCount(pickupAddr, contentWidth, valueTextPaint)
        val destLines   = wrapLinesCount(destAddr,   contentWidth, valueTextPaint)

        var requiredH = 0f
        requiredH += bannerExtra
        requiredH += outerMargin          // margem sup.
        requiredH += backCardPadPx        // padding top card

        // Plataforma
        requiredH += labelHeight + lineGapPx
        requiredH += valueHeight + lineGapPx

        // Recolha
        requiredH += labelHeight + lineGapPx
        requiredH += pickupLines * (valueHeight + lineGapPx)
        requiredH += lineGapPx + labelHeight + lineGapPx + valueHeight

        // separador + gap
        requiredH += valueHeight + lineGapPx + dp(6f)
        requiredH += dp(12f)

        // Destino
        requiredH += labelHeight + lineGapPx
        requiredH += destLines * (valueHeight + lineGapPx)
        requiredH += lineGapPx + labelHeight + lineGapPx + valueHeight

        requiredH += backCardPadPx        // padding bottom
        requiredH += outerMargin          // margem inf.

        val measuredW = resolveSize(targetW, widthMeasureSpec)
        val measuredH = resolveSize(requiredH.toInt(), heightMeasureSpec)
        setMeasuredDimension(measuredW, measuredH)
    }

    // ======================= Tamanho e desenho =======================
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
        val finalBorder = if (providedCombined == recomputed) providedCombined else recomputed

        backgroundPaint.alpha = (viewAlpha * 255).toInt()
        borderPaint.alpha = (viewAlpha * 255).toInt()

        if (showBack) {
            // **Só** no verso desenhamos o cartão
            borderPaint.color = getBorderColor(finalBorder)
            canvas.drawRoundRect(backgroundRect, borderRadiusPx, borderRadiusPx, backgroundPaint)
            canvas.drawRoundRect(borderRect,    borderRadiusPx, borderRadiusPx, borderPaint)
            drawBannerIfNeeded(canvas)
            drawBackPanel(canvas)
        } else {
            // Frente limpa (sem cartão)
            drawBannerIfNeeded(canvas)
            drawFrontRound(canvas, finalBorder)
        }
    }

    // ======================= Frente (semáforo redondo) =======================
    // Cor do texto da plataforma (Bolt a verde)
    private fun getPlatformColor(serviceType: String?): Int {
        val s = serviceType?.lowercase(Locale.ROOT) ?: return TEXT_COLOR_VALUE
        return if (s.contains("bolt")) BORDER_COLOR_GREEN else TEXT_COLOR_VALUE
    }

    private fun drawFrontRound(canvas: Canvas, finalBorder: BorderRating) {
        val textAlpha = (viewAlpha * 255).toInt()
        ringPaint.alpha = textAlpha
        innerPaint.alpha = textAlpha
        hourTextPaint.alpha = textAlpha
        offerTinyPaint.alpha = textAlpha
        kmTextPaint.alpha = textAlpha

        val ui = fontSizeScale.coerceIn(0.7f, 1.6f)

        val cx = width.toFloat() / 2f
        val bannerOffset = if (bannerText != null) (bannerPadVPx * 2 + bannerTextPaint.textSize) + dp(6f) * ui else 0f
        val availableTop = bannerOffset + dp(6f) * ui
        val availableBottom = height.toFloat() - dp(6f) * ui
        val cy = (availableTop + availableBottom) / 2f

        val maxDiameterAvail = min(width.toFloat(), (availableBottom - availableTop))
        val outerR = maxDiameterAvail / 2f
        val innerR = outerR - outerStrokePx - innerPaddingPx

        // Miolo: BRANCO (pedido)
        innerPaint.color = INNER_FILL_COLOR
        canvas.drawCircle(cx, cy, innerR, innerPaint)

        // Anel (cor combinada €/h + €/km)
        ringPaint.color = getBorderColor(finalBorder)
        canvas.drawCircle(cx, cy, innerR + innerPaddingPx + outerStrokePx / 2f, ringPaint)

        // ===== VALOR DA OFERTA (POR CIMA do €/h), como estava antes =====
        val offerStr = currentOfferData?.value?.takeIf { it.isNotBlank() }?.let { raw ->
            val num = raw.replace("€", "").trim().replace(" ", "").replace(",", ".").toDoubleOrNull()
            val formatted = num?.let { euroOfferFormatter.format(it) } ?: raw.trim()
            "$formatted €"
        } ?: ""
        if (offerStr.isNotEmpty()) {
            offerTinyPaint.textSize = sp(OFFER_TEXT_SP)
            offerTinyPaint.color = Color.BLACK
            val offerGap = dp(8f) * ui
            val offerBaseline = (cy - (hourTextPaint.descent() + hourTextPaint.ascent()) / 2f) + hourTextPaint.ascent() - offerGap
            val maxTextWidth = innerR * 1.5f
            var sz = sp(OFFER_TEXT_SP)
            offerTinyPaint.textSize = sz
            while (offerTinyPaint.measureText(offerStr) > maxTextWidth && sz > sp(9f)) {
                sz *= 0.92f
                offerTinyPaint.textSize = sz
            }
            canvas.drawText(offerStr, cx, offerBaseline, offerTinyPaint)
        }

        // Texto central: €/h (cor por rating da hora)
        val valuePerHour = currentOfferData?.calculateValuePerHour()
        val hourStr = valuePerHour?.let { "${euroHoraFormatter.format(it)} €" } ?: "--"
        val hourBaseline = (cy - (hourTextPaint.descent() + hourTextPaint.ascent()) / 2f)
        hourTextPaint.color = getIndicatorColor(currentEvaluationResult?.hourRating ?: IndividualRating.UNKNOWN)
        canvas.drawText(hourStr, cx, hourBaseline, hourTextPaint)

        // ===== Plataforma por BAIXO do €/h, mais acima (longe do €/km) =====
        val platformPart = currentOfferData?.serviceType?.trim().orEmpty()
        if (platformPart.isNotEmpty()) {
            var sz = sp(PLATFORM_TEXT_SP)
            offerTinyPaint.textSize = sz
            val maxAllowed = innerR * 1.65f
            var totalW = offerTinyPaint.measureText(platformPart)
            while (totalW > maxAllowed && sz > sp(9f)) {
                sz *= 0.92f
                offerTinyPaint.textSize = sz
                totalW = offerTinyPaint.measureText(platformPart)
            }
            val subGap = dp(4f) * ui // <<<< MAIS PERTO DO VALOR/HORA
            val subBaseline = hourBaseline + subGap + (offerTinyPaint.textSize)
            offerTinyPaint.color = getPlatformColor(platformPart)
            canvas.drawText(platformPart, cx, subBaseline, offerTinyPaint)
        }

        // €/km no rodapé do círculo — cor por rating de KM (TAMANHO AUMENTADO)
        val profitability = currentOfferData?.calculateProfitability()
        val kmStr = profitability?.let { String.format(Locale.US, "%.2f €/km", it) } ?: "--"
        val kmColorBase = getIndicatorColor(currentEvaluationResult?.kmRating ?: IndividualRating.UNKNOWN)
        kmTextPaint.color = kmColorBase
        val kmBottomMargin = dp(14f) * ui // <<<< MAIS AFASTADO DA PLATAFORMA
        val kmBaseline = cy + innerR - kmBottomMargin
        val maxKmWidth = innerR * 1.5f
        var kmSz = sp(KM_TEXT_SP)
        kmTextPaint.textSize = kmSz
        while (kmTextPaint.measureText(kmStr) > maxKmWidth && kmSz > sp(8.5f)) {
            kmSz *= 0.92f
            kmTextPaint.textSize = kmSz
        }
        canvas.drawText(kmStr, cx, kmBaseline, kmTextPaint)

        // Badge 🚫 se NO_GO (mantido)
        if (currentZoneState == ZoneState.NO_GO) {
            val badgeR = prohibitSizePx / 2f
            val badgeCx = cx
            val badgeCy = cy + innerR - badgeR - dp(4f) * ui
            canvas.drawCircle(badgeCx, badgeCy, badgeR, badgeBgPaint)
            canvas.drawCircle(badgeCx, badgeCy, badgeR - prohibitStrokePx / 2f, prohibitRingPaint)
            val barH = prohibitStrokePx * 1.6f
            val barLeft = badgeCx - badgeR * 0.55f
            val barRight = badgeCx + badgeR * 0.55f
            val barTop = badgeCy - barH / 2f
            val barBottom = badgeCy + barH / 2f
            canvas.drawRect(barLeft, barTop, barRight, barBottom, prohibitBarPaint)
        }
    }

    // ======================= Banner =======================
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

        val r = bannerCornerPx
        canvas.drawRoundRect(bannerRect, r, r, bannerBgPaint)

        val textX = left + bannerPadHPx
        val textY = top + bannerPadVPx - bannerTextPaint.ascent()
        bannerTextPaint.alpha = (viewAlpha * 255).toInt()
        canvas.drawText(text, textX, textY, bannerTextPaint)
    }

    // ======================= Verso (quadro + moradas) =======================
    private fun drawBackPanel(canvas: Canvas) {
        val bannerOffset = if (bannerText != null)
            (bannerPadVPx * 2 + bannerTextPaint.textSize) + dp(6f) else 0f

        val left   = dp(12f)
        val top    = bannerOffset + dp(12f)
        val right  = width.toFloat() - dp(12f)
        val bottom = height.toFloat() - dp(12f)
        val card   = RectF(left, top, right, bottom)

        backCardFillPaint.color = when (currentZoneState) {
            ZoneState.PREFERRED -> ZONE_PREFERRED_FILL
            ZoneState.NO_GO     -> ZONE_NO_GO_FILL
            ZoneState.NEUTRAL   -> ZONE_NEUTRAL_FILL
            ZoneState.UNKNOWN   -> Color.WHITE
        }
        backCardFillPaint.alpha  = (viewAlpha * 255).toInt()
        backCardStrokePaint.color = Color.parseColor("#22000000")
        backCardStrokePaint.alpha = (viewAlpha * 255).toInt()

        canvas.drawRoundRect(card, backCardCornerPx, backCardCornerPx, backCardFillPaint)
        canvas.drawRoundRect(card, backCardCornerPx, backCardCornerPx, backCardStrokePaint)

        val contentLeft  = card.left + backCardPadPx
        val contentTop   = card.top  + backCardPadPx
        val contentRight = card.right - backCardPadPx
        drawBackDetailsInto(canvas, contentLeft, contentTop, contentRight)
    }

    private fun drawBackDetailsInto(canvas: Canvas, leftX: Float, startTop: Float, rightX: Float) {
        val od = currentOfferData

        val pickupAddr = od?.moradaRecolha?.takeIf { it.isNotBlank() } ?: "—"
        val destAddr   = od?.moradaDestino?.takeIf { it.isNotBlank() } ?: "—"

        val pMin = od?.pickupDuration?.takeIf { it.isNotBlank() } ?: "—"
        val pKm  = od?.pickupDistance?.takeIf { it.isNotBlank() }?.let { "$it km" } ?: "—"

        val tMin = od?.tripDuration?.takeIf { it.isNotBlank() } ?: "—"
        val tKm  = od?.tripDistance?.takeIf { it.isNotBlank() }?.let { "$it km" } ?: "—"

        val platform = od?.serviceType?.takeIf { !it.isNullOrBlank() } ?: "—"
        val platformColor = getPlatformColor(platform)

        val textAlpha = (viewAlpha * 255).toInt()
        labelTextPaint.alpha = textAlpha
        valueTextPaint.alpha = textAlpha

        var y = startTop
        val maxWidth = rightX - leftX

        labelTextPaint.textAlign = Paint.Align.LEFT
        valueTextPaint.textAlign = Paint.Align.LEFT

        // Plataforma
        labelTextPaint.color = TEXT_COLOR_LABEL
        canvas.drawText("Plataforma", leftX, y - labelTextPaint.ascent(), labelTextPaint)
        y += labelHeight + lineGapPx

        valueTextPaint.color = platformColor
        canvas.drawText(platform, leftX, y - valueTextPaint.ascent(), valueTextPaint)
        y += valueHeight + lineGapPx

        // Recolha
        labelTextPaint.color = TEXT_COLOR_LABEL
        canvas.drawText("Recolha", leftX, y - labelTextPaint.ascent(), labelTextPaint)
        y += labelHeight + lineGapPx

        valueTextPaint.color = TEXT_COLOR_VALUE
        y = drawMultilineText(canvas, pickupAddr, leftX, y, maxWidth, valueTextPaint)
        y += lineGapPx

        labelTextPaint.color = TEXT_COLOR_LABEL
        canvas.drawText("Tempo / Distância", leftX, y - labelTextPaint.ascent(), labelTextPaint)
        y += labelHeight + lineGapPx

        valueTextPaint.color = TEXT_COLOR_VALUE
        canvas.drawText("${pMin} m   •   $pKm", leftX, y - valueTextPaint.ascent(), valueTextPaint)

        // Separador
        y += valueHeight + lineGapPx + dp(6f)
        canvas.drawLine(leftX, y, rightX, y, backCardStrokePaint)
        y += dp(12f)

        // Destino
        labelTextPaint.color = TEXT_COLOR_LABEL
        canvas.drawText("Destino", leftX, y - labelTextPaint.ascent(), labelTextPaint)
        y += labelHeight + lineGapPx

        valueTextPaint.color = TEXT_COLOR_VALUE
        y = drawMultilineText(canvas, destAddr, leftX, y, maxWidth, valueTextPaint)
        y += lineGapPx

        labelTextPaint.color = TEXT_COLOR_LABEL
        canvas.drawText("Tempo / Distância", leftX, y - labelTextPaint.ascent(), labelTextPaint)
        y += labelHeight + lineGapPx

        valueTextPaint.color = TEXT_COLOR_VALUE
        canvas.drawText("${tMin} m   •   $tKm", leftX, y - valueTextPaint.ascent(), valueTextPaint)
    }

    // ======================= Helpers de texto =======================
    private fun wrapLinesCount(text: String, maxWidth: Float, paint: TextPaint): Int {
        val words = text.split(" ")
        var lines = 0
        val sb = StringBuilder()
        for (w in words) {
            val test = if (sb.isEmpty()) w else sb.toString() + " " + w
            if (paint.measureText(test) <= maxWidth) {
                if (sb.isEmpty()) sb.append(w) else sb.append(" ").append(w)
            } else {
                if (sb.isNotEmpty()) { lines++; sb.setLength(0) }
                sb.append(w)
            }
        }
        if (sb.isNotEmpty()) lines++
        if (lines == 0) lines = 1
        return lines
    }

    private fun drawMultilineText(
        canvas: Canvas,
        text: String,
        x: Float,
        firstTopY: Float,
        maxWidth: Float,
        paint: TextPaint
    ): Float {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (w in words) {
            val test = if (current.isEmpty()) w else current.toString() + " " + w
            if (paint.measureText(test) <= maxWidth) {
                if (current.isEmpty()) current.append(w) else { current.append(" "); current.append(w) }
            } else {
                if (current.isNotEmpty()) lines += current.toString()
                current = StringBuilder(w)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()

        var top = firstTopY
        for ((i, ln) in lines.withIndex()) {
            val baseline = top - paint.ascent()
            canvas.drawText(ln, x, baseline, paint)
            top = baseline + paint.descent()
            if (i < lines.lastIndex) top += lineGapPx
        }
        return top
    }

    // ======================= API pública =======================
    fun updateFontSize(scale: Float) {
        fontSizeScale = scale.coerceIn(0.5f, 2.0f)
        requestLayout()
        invalidate()
    }

    fun updateAlpha(alphaValue: Float) {
        val a = alphaValue.coerceIn(0.0f, 1.0f)
        viewAlpha = a
        alpha = a
        syncMapAlpha(a)
        invalidate()
    }

    fun updateState(evaluationResult: EvaluationResult?, offerData: OfferData?) {
        currentEvaluationResult = evaluationResult
        currentOfferData = offerData
        requestLayout()
        invalidate()

        // Só enviamos UPDATE_MAP; abrir, só com long-press
        openOrUpdateMapForCurrentOffer(show = false)
    }

    fun showBanner(text: String, type: BannerType = BannerType.INFO, durationMs: Long = 2500L) {
        bannerText = text.take(60)
        bannerType = type
        bannerClearAt = if (durationMs > 0) System.currentTimeMillis() + durationMs else 0L
        requestLayout()
        invalidate()
    }

    // ======================= Helpers de rating/cor =======================
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

    private fun recomputeCombinedBorder(km: IndividualRating, hour: IndividualRating): BorderRating {
        return when {
            km == IndividualRating.UNKNOWN || hour == IndividualRating.UNKNOWN -> BorderRating.GRAY
            km == IndividualRating.GOOD && hour == IndividualRating.GOOD       -> BorderRating.GREEN
            km == IndividualRating.POOR && hour == IndividualRating.POOR       -> BorderRating.RED
            else                                                               -> BorderRating.YELLOW
        }
    }

    // ======================= Ciclo de vida =======================
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        readLastZoneFromPrefs()?.let { updateZoneState(it) }

        // Receiver opcional para ACTION_ZONE_HINT (anónimo)
        runCatching {
            val f = android.content.IntentFilter(ACTION_ZONE_HINT)
            context.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (i?.action != ACTION_ZONE_HINT) return
                    val k = i.getStringExtra(EXTRA_ZONE_KIND)?.uppercase(Locale.getDefault())
                    val st = when (k) {
                        "NO_GO" -> ZoneState.NO_GO
                        "PREFERRED" -> ZoneState.PREFERRED
                        "NEUTRAL" -> ZoneState.NEUTRAL
                        else -> ZoneState.UNKNOWN
                    }
                    updateZoneState(st)
                }
            }, f)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching {
            val hide = Intent(MapPreviewActivity.ACTION_SEMAFORO_HIDE_MAP).apply { setPackage(context.packageName) }
            context.sendBroadcast(hide)
        }
    }

    // ======================= Prefs: última zona (fallback) =======================
    private fun readLastZoneFromPrefs(): ZoneState? {
        return try {
            val prefs = context.getSharedPreferences("smartdriver_map_state", Context.MODE_PRIVATE)
            val s = prefs.getString("last_zone_kind", null) ?: return null
            when (s.uppercase(Locale.getDefault())) {
                "NO_GO"      -> ZoneState.NO_GO
                "PREFERRED"  -> ZoneState.PREFERRED
                "NEUTRAL"    -> ZoneState.NEUTRAL
                else         -> ZoneState.UNKNOWN
            }
        } catch (_: Exception) {
            null
        }
    }
}
