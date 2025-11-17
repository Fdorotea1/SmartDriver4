package com.example.smartdriver.overlay

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.Build
import android.text.TextPaint
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.example.smartdriver.map.MapPreviewActivity
import com.example.smartdriver.utils.BorderRating
import com.example.smartdriver.utils.EvaluationResult
import com.example.smartdriver.utils.IndividualRating
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.toDoubleOrNullWithCorrection
import com.example.smartdriver.utils.toIntOrNullWithCorrection
import java.lang.StringBuilder
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@SuppressLint("ClickableViewAccessibility")
class OverlayView(context: Context) : View(context) {

    enum class ZoneState { UNKNOWN, NEUTRAL, PREFERRED, NO_GO }

    /**
     * Mantido por compatibilidade com o serviço (mesmo sem desenhar moradas).
     */
    enum class ZoneHighlightTarget { NONE, PICKUP, DEST, BOTH }

    private var currentZoneState: ZoneState = readLastZoneFromPrefs() ?: ZoneState.UNKNOWN
    private var zoneHighlightTarget: ZoneHighlightTarget = ZoneHighlightTarget.NONE

    fun updateZoneState(state: ZoneState) {
        if (state != currentZoneState) {
            currentZoneState = state
            saveLastZoneToPrefs(state)
            invalidate()
        }
    }

    fun setZoneHighlightTarget(target: ZoneHighlightTarget) {
        zoneHighlightTarget = target
        invalidate()
    }

    fun setAddressZoneKinds(pickupKind: String, destKind: String) {
        val pkNoGo = pickupKind.equals("NO_GO", ignoreCase = true)
        val dtNoGo = destKind.equals("NO_GO", ignoreCase = true)

        zoneHighlightTarget = when {
            pkNoGo && dtNoGo -> ZoneHighlightTarget.BOTH
            pkNoGo           -> ZoneHighlightTarget.PICKUP
            dtNoGo           -> ZoneHighlightTarget.DEST
            else             -> ZoneHighlightTarget.NONE
        }
        invalidate()
    }

    // Override opcional da cor de preenchimento do card (interior).
    private var zoneFillOverride: Int? = null

    fun setZoneFillColor(color: Int?) {
        zoneFillOverride = color
        invalidate()
    }

    fun setZoneVisualFromKind(kind: String?) {
        val ns = when (kind?.trim()?.uppercase(Locale.getDefault())) {
            "NO_GO"     -> ZoneState.NO_GO
            "PREFERRED" -> ZoneState.PREFERRED
            "NEUTRAL"   -> ZoneState.NEUTRAL
            "UNKNOWN", null -> ZoneState.UNKNOWN
            else -> ZoneState.UNKNOWN
        }
        updateZoneState(ns)
    }

    companion object {
        private const val TAG = "OverlayView"

        // Broadcast para dicas/alterações de zona
        const val ACTION_ZONE_HINT = "com.example.smartdriver.overlay.ACTION_ZONE_HINT"
        const val EXTRA_ZONE_KIND  = "zone_kind"

        // Cores rebordo
        private val BORDER_COLOR_GREEN = Color.parseColor("#2E7D32")
        private val BORDER_COLOR_YELLOW = Color.parseColor("#F9A825")
        private val BORDER_COLOR_RED   = Color.parseColor("#C62828")
        private val BORDER_COLOR_GRAY  = Color.parseColor("#9E9E9E")

        // Indicadores (€/hora / €/km)
        private val INDICATOR_COLOR_GOOD    = Color.parseColor("#4CAF50")
        private val INDICATOR_COLOR_MEDIUM  = Color.parseColor("#FFC107")
        private val INDICATOR_COLOR_POOR    = Color.parseColor("#F44336")
        private val INDICATOR_COLOR_UNKNOWN = Color.parseColor("#757575")

        // Fundo da view é transparente
        private val BACKGROUND_COLOR       = Color.TRANSPARENT
        private val TEXT_COLOR_LABEL       = Color.parseColor("#BDBDBD")
        private val TEXT_COLOR_VALUE       = Color.WHITE
        private val PLACEHOLDER_TEXT_COLOR = Color.parseColor("#757575")

        // Fill do card → preto (como o card do mapa)
        private val CARD_FILL_SOFT_BLUE    = Color.BLACK
        private val ZONE_NEUTRAL_FILL      = CARD_FILL_SOFT_BLUE
        private val ZONE_UNKNOWN_FILL      = CARD_FILL_SOFT_BLUE
        private val ZONE_PREFERRED_FILL    = CARD_FILL_SOFT_BLUE
        private val ZONE_NO_GO_FILL        = CARD_FILL_SOFT_BLUE

        // Dimensões base (em dp/sp)
        private const val PADDING_DP = 12f
        private const val CORNER_RADIUS_DP = 18f
        private const val TEXT_SPACING_VERTICAL_DP = 3f
        private const val LINE_SPACING_VERTICAL_DP = 6f
        private const val TEXT_SPACING_HORIZONTAL_DP = 15f

        private const val LABEL_TEXT_SIZE_SP = 11f
        private const val VALUE_TEXT_SIZE_SP = 13f
        private const val HIGHLIGHT_VALUE_TEXT_SIZE_SP = 14f
        private const val EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP = 15f

        // Cabeçalho
        private const val HEADER_TITLE_SP = 12.5f
        // Fonte do €/hora mais pequena para não bater nos valores de cima
        private const val HEADER_EURH_SP  = 18f
        private const val HEADER_EURKM_SP = 12.5f

        private const val PLACEHOLDER_TEXT = "--"

        // Gestos
        private const val SWIPE_MIN_DISTANCE_DP = 80f
        private const val SWIPE_THRESHOLD_VELOCITY_DP = 100f

        // Banner
        private const val BANNER_TEXT_SIZE_SP = 11.5f
        private const val BANNER_PAD_H_DP = 10f
        private const val BANNER_PAD_V_DP = 6f
        private const val BANNER_CORNER_DP = 10f

        // Card metrics
        private const val CARD_OUTER_MARGIN_DP = 12f
        private const val CARD_PAD_DP = 16f
        private const val SEPARATOR_HEIGHT_DP = 1f
        private const val BADGE_NOGO_DP = 18f

        // Rebordos
        private const val INNER_STROKE_DP = 1f
        private const val COLORED_BORDER_WIDTH_DP = 4f

        // Zona / ícone (reduzido para ficar mais discreto)
        private const val ZONE_ICON_SIZE_SP = 20f
        private const val ZONE_DOT_RADIUS_DP = 4f
    }

    enum class BannerType { INFO, SUCCESS, WARNING }

    // Estado
    private var currentEvaluationResult: EvaluationResult? = null
    private var currentOfferData: OfferData? = null

    // alpha atual do semáforo (controlado pelo slider / settings)
    private var viewAlpha = 0.95f

    // Banner genérico
    private var bannerText: String? = null
    private var bannerType: BannerType = BannerType.INFO
    private var bannerClearAt: Long = 0L

    // Paints
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BACKGROUND_COLOR
    }

    private val innerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#22000000")
        strokeWidth = 0f
    }
    private val coloredBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = BORDER_COLOR_GRAY
        strokeWidth = 0f
    }

    // Texto
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
    private val addressHighlightPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEB3B")
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

    // Cabeçalho
    private val headerOfferPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR_VALUE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }
    private val headerHourPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR_VALUE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }
    private val headerKmPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR_VALUE
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }

    // Zona / ícone grande
    private val zoneIconPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR_VALUE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }

    // Bolinhas (compatibilidade)
    private val pickupDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2E7D32")
    }
    private val destDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#C62828")
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BORDER_COLOR_RED
    }

    // Métricas
    private var paddingPx = 0f
    private var borderRadiusPx = 0f
    private var textSpacingVerticalPx = 0f
    private var lineSpacingVerticalPx = 0f
    private var textSpacingHorizontalPx = 0f
    private var labelHeight = 0f
    private var valueHeight = 0f
    private var highlightValueHeight = 0f
    private var extraHighlightValueHeight = 0f
    private var swipeMinDistancePx = 0f
    private var swipeThresholdVelocityPx = 0f
    private var bannerPadHPx = 0f
    private var bannerPadVPx = 0f
    private var bannerCornerPx = 0f

    private var cardOuterMarginPx = 0f
    private var cardPadPx = 0f
    private var separatorHeightPx = 0f
    private var badgeNoGoPx = 0f
    private var zoneDotRadiusPx = 0f

    // Formatação
    private val euroSymbols = DecimalFormatSymbols(Locale("pt", "PT"))
    private val euroHoraFormatter = DecimalFormat("0.0", euroSymbols).apply { roundingMode = RoundingMode.HALF_UP }
    private val euroOfferFormatter = DecimalFormat("0.00", euroSymbols).apply { roundingMode = RoundingMode.HALF_UP }

    // Rects
    private val backgroundRect = RectF()
    private val bannerRect = RectF()
    private val cardRect = RectF()
    private val contentRect = RectF()

    // Gestos
    private val gestureDetector: GestureDetector

    // Receiver zona
    private var zoneReceiver: BroadcastReceiver? = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_ZONE_HINT) return
            val raw = intent.getStringExtra(EXTRA_ZONE_KIND)?.trim()?.uppercase(Locale.getDefault())
            val newState = when (raw) {
                "NO_GO"     -> ZoneState.NO_GO
                "PREFERRED" -> ZoneState.PREFERRED
                "NEUTRAL"   -> ZoneState.NEUTRAL
                "UNKNOWN", null -> ZoneState.UNKNOWN
                else -> {
                    Log.w(TAG, "Zona desconhecida no broadcast: $raw")
                    return
                }
            }
            updateZoneState(newState)
        }
    }

    // Escala de fonte (slider)
    private var fontSizeScale = 1.0f

    init {
        alpha = viewAlpha
        updateDimensionsAndPaints()

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Semáforo mantém estado; clique simples apenas força redraw
                requestLayout()
                invalidate()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // Long press: abre mapa fullscreen com o mesmo card
                openOrUpdateMapForCurrentOffer(show = true, forceShowMs = 0L)
            }

            override fun onFling(e1: MotionEvent, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y

                if (abs(dy) > abs(dx)) {
                    if (abs(dy) > swipeMinDistancePx && abs(vy) > swipeThresholdVelocityPx) {
                        val action = if (dy < 0)
                            OverlayService.ACTION_SHOW_PREV_OVERLAY
                        else
                            OverlayService.ACTION_SHOW_NEXT_OVERLAY

                        try {
                            context.startService(Intent(context, OverlayService::class.java).apply { this.action = action })
                        } catch (ex: Exception) {
                            Log.e(TAG, "Erro navegação: ${ex.message}")
                        }
                        return true
                    }
                } else {
                    if (abs(dx) > swipeMinDistancePx && abs(vx) > swipeThresholdVelocityPx) {
                        if (dx > 0) {
                            showStartConfirmDialog()
                            return true
                        } else {
                            val i = Intent(context, OverlayService::class.java).apply {
                                action = OverlayService.ACTION_CONFIRM_DISMISS_MAIN_OVERLAY
                            }
                            try {
                                context.startService(i)
                            } catch (ex: Exception) {
                                Log.e(TAG, "Erro CONFIRM_DISMISS: ${ex.message}")
                            }
                            return true
                        }
                    }
                }
                return false
            }
        })

        contentDescription = "SmartDriver card de oferta"
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            val f = IntentFilter(ACTION_ZONE_HINT)
            context.registerReceiver(zoneReceiver, f)
        } catch (e: Exception) {
            Log.w(TAG, "Falha a registar zoneReceiver: ${e.message}")
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try { context.unregisterReceiver(zoneReceiver) } catch (_: Exception) {}

        // Esconde o mapa se o semáforo sair da janela (por segurança)
        runCatching {
            val hide = Intent(MapPreviewActivity.ACTION_SEMAFORO_HIDE_MAP).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(hide)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val consumed = gestureDetector.onTouchEvent(event)

        val now = System.currentTimeMillis()
        if (bannerText != null && bannerClearAt > 0 && now >= bannerClearAt) {
            bannerText = null
            bannerClearAt = 0
            invalidate()
        }
        return consumed || super.onTouchEvent(event)
    }

    // =================== MAPA ===================
    private fun openOrUpdateMapForCurrentOffer(show: Boolean, forceShowMs: Long? = null) {
        val od = currentOfferData ?: return

        val pickupAddr = od.moradaRecolha?.takeIf { it.isNotBlank() }
        val destAddr   = od.moradaDestino?.takeIf { it.isNotBlank() }

        // Valor da oferta formatado
        val offerRaw = od.value?.trim()
        val offerNumeric = offerRaw
            ?.replace("€", "")
            ?.replace(" ", "")
            ?.replace(",", ".")
            ?.toDoubleOrNull()

        val offerVal = offerNumeric?.let { "€ " + euroOfferFormatter.format(it) }
            ?: offerRaw?.let { raw ->
                val t = raw.trim()
                if (t.startsWith("€")) t else "€ $t"
            } ?: "€ —"

        // €/km planeado
        val vpk = od.calculateProfitability()
        val eurKm = vpk?.let {
            "€ " + String.format(Locale("pt", "PT"), "%.2f", it)
        } ?: "—"

        // km totais (pickup + viagem)
        val totalKmNum = od.calculateTotalDistance()
        val totalKm = totalKmNum?.let {
            String.format(Locale("pt", "PT"), "%.1f", it)
        } ?: "—"

        // €/hora planeado
        val vph = od.calculateValuePerHour()
        val plannedHour = vph?.let { "€ " + euroHoraFormatter.format(it) } ?: "—"

        fun legSuffix(distRaw: String?, durRaw: String?): String? {
            val d = distRaw.toDoubleOrNullWithCorrection()
            val m = durRaw.toIntOrNullWithCorrection()
            if (d == null && m == null) return null
            val parts = mutableListOf<String>()
            d?.let { parts += String.format(Locale("pt", "PT"), "%.1f km", it) }
            m?.let { parts += "$m min" }
            return parts.joinToString(" · ")
        }

        val pickupSuffix = legSuffix(od.pickupDistance, od.pickupDuration)
        val destSuffix   = legSuffix(od.tripDistance, od.tripDuration)

        // 1) UPDATE_MAP para atualizar markers/rotas + card
        runCatching {
            val upd = Intent(MapPreviewActivity.ACTION_UPDATE_MAP).apply {
                setPackage(context.packageName)
                pickupAddr?.let { putExtra(MapPreviewActivity.EXTRA_PICKUP_ADDRESS, it) }
                destAddr?.let   { putExtra(MapPreviewActivity.EXTRA_DEST_ADDRESS,   it) }

                putExtra(MapPreviewActivity.EXTRA_CARD_OFFER_VALUE, offerVal)
                putExtra(MapPreviewActivity.EXTRA_CARD_EUR_PER_KM, eurKm)
                putExtra(MapPreviewActivity.EXTRA_CARD_TOTAL_KM, totalKm)
                putExtra(MapPreviewActivity.EXTRA_CARD_EUR_PER_HOUR_PLANNED, plannedHour)

                pickupSuffix?.let { putExtra(MapPreviewActivity.EXTRA_PICKUP_SUFFIX_FROM_CARD, it) }
                destSuffix?.let   { putExtra(MapPreviewActivity.EXTRA_DEST_SUFFIX_FROM_CARD, it) }
            }
            context.sendBroadcast(upd)
        }

        if (!show) return

        // 2) Abrir / trazer à frente o MapPreviewActivity, fullscreen com header
        runCatching {
            val open = Intent(context, MapPreviewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MapPreviewActivity.EXTRA_FULLSCREEN, true)
                putExtra(MapPreviewActivity.EXTRA_SHOW_HEADER, true)

                pickupAddr?.let { putExtra(MapPreviewActivity.EXTRA_PICKUP_ADDRESS, it) }
                destAddr?.let   { putExtra(MapPreviewActivity.EXTRA_DEST_ADDRESS,   it) }

                putExtra(MapPreviewActivity.EXTRA_CARD_OFFER_VALUE, offerVal)
                putExtra(MapPreviewActivity.EXTRA_CARD_EUR_PER_KM, eurKm)
                putExtra(MapPreviewActivity.EXTRA_CARD_TOTAL_KM, totalKm)
                putExtra(MapPreviewActivity.EXTRA_CARD_EUR_PER_HOUR_PLANNED, plannedHour)

                pickupSuffix?.let { putExtra(MapPreviewActivity.EXTRA_PICKUP_SUFFIX_FROM_CARD, it) }
                destSuffix?.let   { putExtra(MapPreviewActivity.EXTRA_DEST_SUFFIX_FROM_CARD, it) }
            }
            context.startActivity(open)
        }.onFailure {
            Log.e(TAG, "Falha ao abrir MapPreviewActivity: ${it.message}")
        }

        // 3) Pedir SHOW ao MapPreviewActivity (sem auto-hide, como no tracking)
        val dur = forceShowMs ?: 0L
        runCatching {
            val showIntent = Intent(MapPreviewActivity.ACTION_SEMAFORO_SHOW_MAP).apply {
                setPackage(context.packageName)
                putExtra(MapPreviewActivity.EXTRA_AUTO_HIDE_MS, dur)
                putExtra(MapPreviewActivity.EXTRA_FADE_MS, 400L)
                putExtra(MapPreviewActivity.EXTRA_FULLSCREEN, true)
            }
            context.sendBroadcast(showIntent)
        }
    }

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

        try {
            dialog.show()
        } catch (ex: Exception) {
            Log.e(TAG, "Falha confirmação: ${ex.message}")
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
            try {
                context.startService(startTrackingIntent)
            } catch (ex: Exception) {
                Log.e(TAG, "Erro START_TRACKING: ${ex.message}")
            }
        } else {
            showBanner("Sem dados para iniciar", BannerType.WARNING, 2000)
        }
    }

    // ================= Dimensões =================
    private fun updateDimensionsAndPaints() {
        val ui = fontSizeScale.coerceIn(0.7f, 1.6f)

        paddingPx = dp(PADDING_DP) * ui
        borderRadiusPx = dp(CORNER_RADIUS_DP) * ui
        textSpacingVerticalPx = dp(TEXT_SPACING_VERTICAL_DP) * ui
        lineSpacingVerticalPx = dp(LINE_SPACING_VERTICAL_DP) * ui
        textSpacingHorizontalPx = dp(TEXT_SPACING_HORIZONTAL_DP) * ui
        swipeMinDistancePx = dp(SWIPE_MIN_DISTANCE_DP)
        swipeThresholdVelocityPx = dp(SWIPE_THRESHOLD_VELOCITY_DP)
        bannerPadHPx = dp(BANNER_PAD_H_DP)
        bannerPadVPx = dp(BANNER_PAD_V_DP)
        bannerCornerPx = dp(BANNER_CORNER_DP)

        cardOuterMarginPx = dp(CARD_OUTER_MARGIN_DP) * ui
        cardPadPx = dp(CARD_PAD_DP) * ui
        separatorHeightPx = dp(SEPARATOR_HEIGHT_DP)
        badgeNoGoPx = dp(BADGE_NOGO_DP) * ui

        innerStrokePaint.strokeWidth   = dp(INNER_STROKE_DP)
        coloredBorderPaint.strokeWidth = dp(COLORED_BORDER_WIDTH_DP) * ui

        zoneDotRadiusPx = dp(ZONE_DOT_RADIUS_DP) * ui

        // Textos
        labelTextPaint.textSize = sp(LABEL_TEXT_SIZE_SP)
        valueTextPaint.textSize = sp(VALUE_TEXT_SIZE_SP)
        addressHighlightPaint.textSize = sp(VALUE_TEXT_SIZE_SP)
        highlightValueTextPaint.textSize = sp(HIGHLIGHT_VALUE_TEXT_SIZE_SP)
        extraHighlightValueTextPaint.textSize = sp(EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP)
        placeholderTextPaint.textSize = sp(HIGHLIGHT_VALUE_TEXT_SIZE_SP)
        bannerTextPaint.textSize = sp(BANNER_TEXT_SIZE_SP)

        // Cabeçalho
        headerOfferPaint.textSize = sp(HEADER_TITLE_SP)
        headerHourPaint.textSize  = sp(HEADER_EURH_SP)
        headerKmPaint.textSize    = sp(HEADER_EURKM_SP)

        // Zona ícone
        zoneIconPaint.textSize = sp(ZONE_ICON_SIZE_SP)

        labelHeight = labelTextPaint.descent() - labelTextPaint.ascent()
        valueHeight = valueTextPaint.descent() - valueTextPaint.ascent()
        highlightValueHeight = highlightValueTextPaint.descent() - highlightValueTextPaint.ascent()
        extraHighlightValueHeight = extraHighlightValueTextPaint.descent() - extraHighlightValueTextPaint.ascent()
    }

    private fun sp(sizeSp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp * fontSizeScale, resources.displayMetrics)

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateDimensionsAndPaints()

        val bannerExtra = if (bannerText != null)
            (bannerPadVPx * 2 + bannerTextPaint.textSize) + textSpacingVerticalPx
        else 0f

        // Queremos um cartão quase quadrado e um pouco maior
        val sample = "88,8 €"
        val textW = headerHourPaint.measureText(sample)
        val textH = headerHourPaint.descent() - headerHourPaint.ascent()

        val ui = fontSizeScale.coerceIn(0.7f, 1.6f)

        val byText = max(textW, textH) + dp(8f) * 2f * ui
        val byDp   = dp(150f) * ui         // base maior que antes
        val baseSize = max(byText, byDp)

        val widthFactor = 1.05f           // quase quadrado
        val heightFactor = 1.05f
        val extraBottom = dp(18f) * ui

        val desiredW = (baseSize * widthFactor).toInt()
        val desiredH = (baseSize * heightFactor + bannerExtra + extraBottom).toInt()

        val measuredW = resolveSize(desiredW, widthMeasureSpec)
        val measuredH = resolveSize(desiredH.toInt(), heightMeasureSpec)
        setMeasuredDimension(measuredW, measuredH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backgroundRect.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val kmRating   = currentEvaluationResult?.kmRating   ?: IndividualRating.UNKNOWN
        val hourRating = currentEvaluationResult?.hourRating ?: IndividualRating.UNKNOWN
        val providedCombined = currentEvaluationResult?.combinedBorderRating ?: BorderRating.GRAY
        val recomputed = recomputeCombinedBorder(kmRating, hourRating)
        val finalBorder = if (providedCombined == recomputed) providedCombined else recomputed

        backgroundPaint.alpha = (viewAlpha * 255).toInt()

        // Banner normal (para outros textos, nunca "Oferta em fila")
        drawBannerIfNeeded(canvas)

        // Semáforo sempre visível aqui (o fade de 5s é gerido no OverlayService via alpha)
        drawSingleCard(canvas, finalBorder)
    }

    private fun drawSingleCard(canvas: Canvas, finalBorder: BorderRating) {
        val topOffset = if (bannerText != null)
            (bannerPadVPx * 2 + bannerTextPaint.textSize) + dp(6f)
        else 0f

        val left   = cardOuterMarginPx
        val top    = topOffset + cardOuterMarginPx
        val right  = width.toFloat() - cardOuterMarginPx
        val bottom = height.toFloat() - cardOuterMarginPx
        cardRect.set(left, top, right, bottom)

        val zoneFillBase = when (currentZoneState) {
            ZoneState.PREFERRED -> ZONE_PREFERRED_FILL
            ZoneState.NO_GO     -> ZONE_NO_GO_FILL
            ZoneState.NEUTRAL   -> ZONE_NEUTRAL_FILL
            ZoneState.UNKNOWN   -> ZONE_UNKNOWN_FILL
        }
        val zoneFill = zoneFillOverride ?: zoneFillBase

        val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = zoneFill
            alpha = (viewAlpha * 255).toInt()
        }

        coloredBorderPaint.color = getBorderColor(finalBorder)
        coloredBorderPaint.alpha = (viewAlpha * 255).toInt()
        val borderW = coloredBorderPaint.strokeWidth
        val borderHalf = borderW / 2f
        canvas.drawRoundRect(cardRect, borderRadiusPx, borderRadiusPx, coloredBorderPaint)

        val fillRect = RectF(
            cardRect.left   + borderHalf,
            cardRect.top    + borderHalf,
            cardRect.right  - borderHalf,
            cardRect.bottom - borderHalf
        )
        val fillRadius = (borderRadiusPx - borderHalf).coerceAtLeast(0f)
        canvas.drawRoundRect(fillRect, fillRadius, fillRadius, cardFillPaint)

        val innerInset = innerStrokePaint.strokeWidth / 2f
        val innerRect = RectF(
            fillRect.left   + innerInset,
            fillRect.top    + innerInset,
            fillRect.right  - innerInset,
            fillRect.bottom - innerInset
        )
        val innerRadius = (fillRadius - innerInset).coerceAtLeast(0f)
        canvas.drawRoundRect(innerRect, innerRadius, innerRadius, innerStrokePaint)

        contentRect.set(
            cardRect.left + cardPadPx,
            cardRect.top  + cardPadPx,
            cardRect.right - cardPadPx,
            cardRect.bottom - cardPadPx
        )

        val od = currentOfferData

        // -------- Topo esquerdo: oferta --------
        val offerStr = od?.value?.let { raw ->
            val num = raw
                .replace("€", "")
                .trim()
                .replace(" ", "")
                .replace(",", ".")
                .toDoubleOrNull()
            num?.let { "€ " + euroOfferFormatter.format(it) } ?: raw.trim()
        } ?: PLACEHOLDER_TEXT

        val headerTop = contentRect.top
        val headerLeft = contentRect.left
        val headerRight = contentRect.right
        val centerX = (contentRect.left + contentRect.right) / 2f

        val headerBaseline = headerTop - headerOfferPaint.ascent()

        headerOfferPaint.color = TEXT_COLOR_VALUE
        headerOfferPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(offerStr, headerLeft, headerBaseline, headerOfferPaint)

        // -------- €/km: canto superior direito (lado oposto à oferta) --------
        val vpk = od?.calculateProfitability()
        val kmVal = vpk?.let { String.format(Locale("pt", "PT"), "%.2f", it) }
        val kmStr = kmVal?.let { "€/km $it" } ?: "€/km —"

        val kmPaint = TextPaint(valueTextPaint).apply {
            color = getIndicatorColor(currentEvaluationResult?.kmRating ?: IndividualRating.UNKNOWN)
            textAlign = Paint.Align.RIGHT
            textSize = headerKmPaint.textSize
        }
        canvas.drawText(kmStr, headerRight, headerBaseline, kmPaint)

        // -------- tempo + distância (canto inferior esquerdo) --------
        val totalMinutes: Int? = run {
            val p = od?.pickupDuration?.trim()?.toIntOrNull()
            val t = od?.tripDuration?.trim()?.toIntOrNull()
            val list = listOfNotNull(p, t)
            if (list.isNotEmpty()) list.sum() else null
        }
        val totalMinStr = totalMinutes?.let { "${it} m" } ?: "—"

        val totalKmVal = od?.calculateTotalDistance()
        val totalKmStr = totalKmVal?.let {
            String.format(Locale("pt", "PT"), "%.1f km", it)
        } ?: "—"

        val bottomText = "$totalMinStr   •   $totalKmStr"

        valueTextPaint.color = TEXT_COLOR_VALUE
        valueTextPaint.alpha = (viewAlpha * 255).toInt()

        val bottomBaseline = contentRect.bottom - valueTextPaint.descent()

        val timePaint = TextPaint(valueTextPaint).apply {
            textAlign = Paint.Align.LEFT
            color = TEXT_COLOR_VALUE
        }
        canvas.drawText(bottomText, contentRect.left, bottomBaseline, timePaint)

        // -------- €/hora (ao centro do semáforo, com fonte menor) --------
        val vph = od?.calculateValuePerHour()
        val hourVal = vph?.let { euroHoraFormatter.format(it) }
        val hourStr = hourVal?.let { "€/hora $it" } ?: "€/hora —"

        headerHourPaint.color = getIndicatorColor(currentEvaluationResult?.hourRating ?: IndividualRating.UNKNOWN)
        headerHourPaint.textAlign = Paint.Align.CENTER

        // Posiciona um pouco mais abaixo para dar espaço ao topo
        val hourCenterY = contentRect.top + contentRect.height() * 0.35f
        val hourBaseline = hourCenterY - (headerHourPaint.descent() + headerHourPaint.ascent()) / 2f
        canvas.drawText(hourStr, centerX, hourBaseline, headerHourPaint)

        // -------- Ícone da zona no centro do semáforo --------
        drawZoneSymbolCenter(canvas)
    }

    private fun drawZoneSymbolCenter(canvas: Canvas) {
        val icon = when (currentZoneState) {
            ZoneState.PREFERRED -> "✅"
            ZoneState.NO_GO     -> "🚫"   // sentido proibido, mais pequeno
            ZoneState.NEUTRAL   -> "⚠️"
            ZoneState.UNKNOWN   -> ""
        }
        if (icon.isEmpty()) return

        zoneIconPaint.textAlign = Paint.Align.CENTER

        val cx = (contentRect.left + contentRect.right) / 2f
        val cy = contentRect.top + contentRect.height() * 0.7f
        val baseline = cy - (zoneIconPaint.descent() + zoneIconPaint.ascent()) / 2f
        canvas.drawText(icon, cx, baseline, zoneIconPaint)
    }

    // Helpers antigos mantidos (não usados no layout atual)
    private fun drawLabelWithDot(
        canvas: Canvas,
        text: String,
        xLeft: Float,
        topY: Float,
        isPickup: Boolean
    ) {
        val cy = topY - labelTextPaint.ascent() / 2f
        val dotPaint = if (isPickup) pickupDotPaint else destDotPaint
        val dotCx = xLeft + zoneDotRadiusPx
        canvas.drawCircle(dotCx, cy, zoneDotRadiusPx, dotPaint)

        val textX = dotCx + zoneDotRadiusPx + dp(4f)
        drawLabel(canvas, text, textX, topY)
    }

    private fun drawLabel(canvas: Canvas, text: String, xLeft: Float, topY: Float) {
        labelTextPaint.color = TEXT_COLOR_LABEL
        labelTextPaint.alpha = (viewAlpha * 255).toInt()
        canvas.drawText(text, xLeft, topY - labelTextPaint.ascent(), labelTextPaint)
    }

    private fun drawValue(canvas: Canvas, text: String, xLeft: Float, topY: Float) {
        valueTextPaint.color = TEXT_COLOR_VALUE
        valueTextPaint.alpha = (viewAlpha * 255).toInt()
        canvas.drawText(text, xLeft, topY - valueTextPaint.ascent(), valueTextPaint)
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

        val r = bannerCornerPx
        canvas.drawRoundRect(bannerRect, r, r, bannerBgPaint)

        val textX = left + bannerPadHPx
        val textY = top + bannerPadVPx - bannerTextPaint.ascent()
        bannerTextPaint.alpha = (viewAlpha * 255).toInt()
        canvas.drawText(text, textX, textY, bannerTextPaint)
    }

    private fun wrapLinesCountClamped(
        text: String,
        maxWidth: Float,
        paint: TextPaint,
        maxLines: Int
    ): Int {
        val words = text.split(" ")
        var lines = 0
        var sb = StringBuilder()
        for (w in words) {
            val test = if (sb.isEmpty()) w else sb.toString() + " " + w
            if (paint.measureText(test) <= maxWidth) {
                if (sb.isEmpty()) sb.append(w) else sb.append(" ").append(w)
            } else {
                lines++
                sb = StringBuilder(w)
                if (lines >= maxLines) break
            }
        }
        if (lines < maxLines && sb.isNotEmpty()) lines++
        if (lines == 0) lines = 1
        return min(lines, maxLines)
    }

    private fun drawMultilineClamped(
        canvas: Canvas,
        text: String,
        x: Float,
        firstTopY: Float,
        maxWidth: Float,
        paint: TextPaint,
        maxLines: Int
    ): Float {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()

        fun pushLine() {
            if (current.isNotEmpty()) {
                lines += current.toString()
                current = StringBuilder()
            }
        }

        for (w in words) {
            val test = if (current.isEmpty()) w else current.toString() + " " + w
            if (paint.measureText(test) <= maxWidth) {
                if (current.isEmpty()) current.append(w)
                else {
                    current.append(" ")
                    current.append(w)
                }
            } else {
                pushLine()
                current.append(w)
                if (lines.size >= maxLines - 1) break
            }
        }
        pushLine()

        return if (lines.size > maxLines) {
            val cut = lines.take(maxLines).toMutableList()
            val last = cut.last()
            var ell = "$last…"
            while (paint.measureText(ell) > maxWidth && ell.length > 2) {
                ell = ell.dropLast(2) + "…"
            }
            cut[cut.lastIndex] = ell
            cut.forEachIndexed { i, ln ->
                val baseline = (if (i == 0) firstTopY else firstTopY +
                        i * (paint.descent() - paint.ascent() + lineSpacingVerticalPx)
                        ) - paint.ascent()
                canvas.drawText(ln, x, baseline, paint)
            }
            firstTopY + cut.size *
                    (paint.descent() - paint.ascent() + lineSpacingVerticalPx) -
                    lineSpacingVerticalPx
        } else {
            var top = firstTopY
            for ((i, ln) in lines.withIndex()) {
                val baseline = top - paint.ascent()
                canvas.drawText(ln, x, baseline, paint)
                top = baseline + paint.descent()
                if (i < lines.lastIndex) top += lineSpacingVerticalPx
            }
            top
        }
    }

    fun updateFontSize(scale: Float) {
        fontSizeScale = scale.coerceIn(0.9f, 2.0f)
        requestLayout()
        invalidate()
    }

    fun updateAlpha(alphaValue: Float) {
        val a = alphaValue.coerceIn(0.0f, 1.0f)
        viewAlpha = a
        alpha = a
        invalidate()
    }

    fun updateState(evaluationResult: EvaluationResult?, offerData: OfferData?) {
        currentEvaluationResult = evaluationResult
        currentOfferData = offerData
        requestLayout()
        invalidate()
        // Atualiza markers/rotas no mapa, mas sem abrir
        openOrUpdateMapForCurrentOffer(show = false)
    }

    fun showBanner(
        text: String,
        type: BannerType = BannerType.INFO,
        durationMs: Long = 2500L
    ) {
        val trimmed = text.trim()
        val upper = trimmed.uppercase(Locale.getDefault())

        // "Oferta em fila" não desenha nada no semáforo (sem texto, sem seta)
        if (upper.contains("OFERTA EM FILA")) {
            return
        }

        bannerText = trimmed.take(60)
        bannerType = type
        bannerClearAt = if (durationMs > 0)
            System.currentTimeMillis() + durationMs
        else 0L
        requestLayout()
        invalidate()
    }

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

    private fun recomputeCombinedBorder(
        km: IndividualRating,
        hour: IndividualRating
    ): BorderRating {
        return when {
            km == IndividualRating.UNKNOWN || hour == IndividualRating.UNKNOWN -> BorderRating.GRAY
            km == IndividualRating.GOOD && hour == IndividualRating.GOOD       -> BorderRating.GREEN
            km == IndividualRating.POOR && hour == IndividualRating.POOR       -> BorderRating.RED
            else                                                               -> BorderRating.YELLOW
        }
    }

    private fun readLastZoneFromPrefs(): ZoneState? {
        return try {
            val prefs = context.getSharedPreferences(
                "smartdriver_map_state",
                Context.MODE_PRIVATE
            )
            val raw = prefs.getString("last_zone_kind", null) ?: return null
            when (raw.uppercase(Locale.getDefault())) {
                "NO_GO"     -> ZoneState.NO_GO
                "PREFERRED" -> ZoneState.PREFERRED
                "NEUTRAL"   -> ZoneState.NEUTRAL
                "UNKNOWN"   -> ZoneState.UNKNOWN
                else        -> ZoneState.UNKNOWN
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveLastZoneToPrefs(state: ZoneState) {
        runCatching {
            val prefs = context.getSharedPreferences(
                "smartdriver_map_state",
                Context.MODE_PRIVATE
            )
            prefs.edit().putString("last_zone_kind", state.name).apply()
        }
    }
}
