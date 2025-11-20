package com.example.smartdriver.overlay

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.media.MediaPlayer
import android.os.Build
import android.text.TextPaint
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.example.smartdriver.R
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
     * Mantido por compatibilidade com o serviço.
     */
    enum class ZoneHighlightTarget { NONE, PICKUP, DEST, BOTH }

    // Dados da zona (Estado e Nome)
    private var currentZoneState: ZoneState = ZoneState.UNKNOWN
    private var currentZoneName: String? = null

    // Controle de som: guarda o último "ID de som" tocado para não repetir
    private var lastPlayedSoundId: Int? = null

    private var zoneHighlightTarget: ZoneHighlightTarget = ZoneHighlightTarget.NONE

    init {
        // Recuperar estado e nome persistidos
        val (savedState, savedName) = readLastZoneFromPrefs()
        currentZoneState = savedState
        currentZoneName = savedName
    }

    fun updateZoneState(state: ZoneState, name: String?) {
        if (state != currentZoneState || name != currentZoneName) {
            currentZoneState = state
            currentZoneName = name
            saveLastZoneToPrefs(state, name)
            invalidate()
        }
    }

    // Compatibilidade com chamadas antigas (sem nome)
    fun updateZoneState(state: ZoneState) {
        updateZoneState(state, currentZoneName)
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
        updateZoneState(ns, currentZoneName)
    }

    companion object {
        private const val TAG = "OverlayView"

        // Broadcast para dicas/alterações de zona
        const val ACTION_ZONE_HINT = "com.example.smartdriver.overlay.ACTION_ZONE_HINT"
        const val EXTRA_ZONE_KIND  = "zone_kind"
        const val EXTRA_ZONE_NAME  = "zone_name"

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

        // Fill do card → preto
        private val CARD_FILL_SOFT_BLUE    = Color.BLACK
        private val ZONE_NEUTRAL_FILL      = CARD_FILL_SOFT_BLUE
        private val ZONE_UNKNOWN_FILL      = CARD_FILL_SOFT_BLUE
        private val ZONE_PREFERRED_FILL    = CARD_FILL_SOFT_BLUE
        private val ZONE_NO_GO_FILL        = CARD_FILL_SOFT_BLUE

        // Dimensões
        private const val PADDING_DP = 16f
        private const val CORNER_RADIUS_DP = 24f
        private const val TEXT_SPACING_VERTICAL_DP = 12f
        private const val LINE_SPACING_VERTICAL_DP = 20f
        private const val TEXT_SPACING_HORIZONTAL_DP = 20f

        // --- AJUSTE DE FONTES ---
        private const val LABEL_TEXT_SIZE_SP = 14.8f
        private const val VALUE_TEXT_SIZE_SP = 19.5f
        private const val HIGHLIGHT_VALUE_TEXT_SIZE_SP = 18.9f
        private const val EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP = 20.2f

        private const val HEADER_TITLE_SP = 22.0f
        private const val HEADER_EURH_SP  = 33.8f
        private const val HEADER_EURKM_SP = 16.9f

        private const val RATING_SYMBOL_SIZE_SP = 20f
        private const val RATING_SYMBOL_HEADER_SIZE_SP = 30f

        private const val ZONE_NAME_SIZE_SP = 16.0f

        private const val PLACEHOLDER_TEXT = "--"

        // Gestos
        private const val SWIPE_MIN_DISTANCE_DP = 80f
        private const val SWIPE_THRESHOLD_VELOCITY_DP = 100f

        // Banner (Pequeno e discreto)
        private const val BANNER_TEXT_SIZE_SP = 13.5f
        private const val BANNER_PAD_H_DP = 10f
        private const val BANNER_PAD_V_DP = 4.5f
        private const val BANNER_CORNER_DP = 8f

        // Card metrics
        // [RESTAURADO] Voltou a 16f para não apertar o conteúdo interior
        private const val CARD_OUTER_MARGIN_DP = 16f

        // [NOVO] Espaço extra no topo da VIEW apenas para caber o popup
        private const val EXTRA_TOP_SPACE_DP = 12f

        private const val CARD_PAD_DP = 28f
        private const val SEPARATOR_HEIGHT_DP = 1.35f
        private const val BADGE_NOGO_DP = 24.3f

        private const val INNER_STROKE_DP = 1.35f
        private const val COLORED_BORDER_WIDTH_DP = 5.4f

        private const val ZONE_ICON_SIZE_SP = 35f
        private const val ZONE_DOT_RADIUS_DP = 5.4f
    }

    enum class BannerType { INFO, SUCCESS, WARNING }

    // Estado
    private var currentEvaluationResult: EvaluationResult? = null
    private var currentOfferData: OfferData? = null

    // alpha atual do semáforo
    private var viewAlpha = 0.95f

    // Banner
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

    // Zona
    private val zoneIconPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR_VALUE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }
    private val zoneNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }

    // Ratings
    private val ratingSymbolPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }
    private val ratingSymbolHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        flags = flags or Paint.SUBPIXEL_TEXT_FLAG
    }

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
    private var extraTopSpacePx = 0f

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
            val name = intent.getStringExtra(EXTRA_ZONE_NAME)

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
            updateZoneState(newState, name)
        }
    }

    // Escala global
    private var fontSizeScale = 1.0f

    init {
        alpha = viewAlpha
        updateDimensionsAndPaints()

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Apenas invalida, não faz layout (sem saltos), não abre nada (sem performClick)
                invalidate()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
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

    override fun performClick(): Boolean {
        super.performClick()
        return true
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
        runCatching {
            val hide = Intent(MapPreviewActivity.ACTION_SEMAFORO_HIDE_MAP).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(hide)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val consumed = gestureDetector.onTouchEvent(event)
        if (bannerText != null && bannerClearAt > 0 && System.currentTimeMillis() >= bannerClearAt) {
            bannerText = null
            bannerClearAt = 0
            // Só invalidate, para não causar o salto
            invalidate()
        }
        return consumed || super.onTouchEvent(event)
    }

    // =================== MAPA ===================
    private fun openOrUpdateMapForCurrentOffer(show: Boolean, forceShowMs: Long? = null) {
        val od = currentOfferData ?: return
        val pickupAddr = od.moradaRecolha?.takeIf { it.isNotBlank() }
        val destAddr   = od.moradaDestino?.takeIf { it.isNotBlank() }

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

        val vpk = od.calculateProfitability()
        val eurKm = vpk?.let { "€ " + String.format(Locale("pt", "PT"), "%.2f", it) } ?: "—"

        val totalKmNum = od.calculateTotalDistance()
        val totalKm = totalKmNum?.let { String.format(Locale("pt", "PT"), "%.1f", it) } ?: "—"

        val vph = od.calculateValuePerHour()
        val plannedHour = vph?.let { "€ " + euroHoraFormatter.format(it) } ?: "—"

        fun legSuffix(distRaw: String?, durRaw: String?): String? {
            val d = distRaw.toDoubleOrNullWithCorrection()
            val m = durRaw.toIntOrNullWithCorrection()
            if (d == null && m == null) return null
            val parts = mutableListOf<String>()
            d?.let { parts += String.format(Locale("pt", "PT"), "%.1f km", it) }
            m?.let { parts += "$it min" }
            return parts.joinToString(" · ")
        }

        val pickupSuffix = legSuffix(od.pickupDistance, od.pickupDuration)
        val destSuffix   = legSuffix(od.tripDistance, od.tripDuration)

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
        }

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
        try { dialog.show() } catch (ex: Exception) { startTrackingMode() }
    }

    private fun startTrackingMode() {
        if (currentOfferData != null && currentEvaluationResult != null) {
            val startTrackingIntent = Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START_TRACKING
                putExtra(OverlayService.EXTRA_OFFER_DATA, currentOfferData)
                putExtra(OverlayService.EXTRA_EVALUATION_RESULT, currentEvaluationResult)
            }
            try { context.startService(startTrackingIntent) } catch (ex: Exception) {
                Log.e(TAG, "Erro START_TRACKING: ${ex.message}")
            }
        } else {
            showBanner("Sem dados", BannerType.WARNING, 2000)
        }
    }

    // Dimensões
    private fun updateDimensionsAndPaints() {
        paddingPx = dp(PADDING_DP)
        borderRadiusPx = dp(CORNER_RADIUS_DP)
        textSpacingVerticalPx = dp(TEXT_SPACING_VERTICAL_DP)
        lineSpacingVerticalPx = dp(LINE_SPACING_VERTICAL_DP)
        textSpacingHorizontalPx = dp(TEXT_SPACING_HORIZONTAL_DP)
        swipeMinDistancePx = dp(SWIPE_MIN_DISTANCE_DP)
        swipeThresholdVelocityPx = dp(SWIPE_THRESHOLD_VELOCITY_DP)
        bannerPadHPx = dp(BANNER_PAD_H_DP)
        bannerPadVPx = dp(BANNER_PAD_V_DP)
        bannerCornerPx = dp(BANNER_CORNER_DP)

        cardOuterMarginPx = dp(CARD_OUTER_MARGIN_DP)
        extraTopSpacePx = dp(EXTRA_TOP_SPACE_DP)

        cardPadPx = dp(CARD_PAD_DP)
        separatorHeightPx = dp(SEPARATOR_HEIGHT_DP)
        badgeNoGoPx = dp(BADGE_NOGO_DP)

        innerStrokePaint.strokeWidth   = dp(INNER_STROKE_DP)
        coloredBorderPaint.strokeWidth = dp(COLORED_BORDER_WIDTH_DP)
        zoneDotRadiusPx = dp(ZONE_DOT_RADIUS_DP)

        labelTextPaint.textSize = sp(LABEL_TEXT_SIZE_SP)
        valueTextPaint.textSize = sp(VALUE_TEXT_SIZE_SP)
        addressHighlightPaint.textSize = sp(VALUE_TEXT_SIZE_SP)
        highlightValueTextPaint.textSize = sp(HIGHLIGHT_VALUE_TEXT_SIZE_SP)
        extraHighlightValueTextPaint.textSize = sp(EXTRA_HIGHLIGHT_VALUE_TEXT_SIZE_SP)
        placeholderTextPaint.textSize = sp(HIGHLIGHT_VALUE_TEXT_SIZE_SP)
        bannerTextPaint.textSize = sp(BANNER_TEXT_SIZE_SP)

        headerOfferPaint.textSize = sp(HEADER_TITLE_SP)
        headerHourPaint.textSize  = sp(HEADER_EURH_SP)
        headerKmPaint.textSize    = sp(HEADER_EURKM_SP)

        zoneIconPaint.textSize = sp(ZONE_ICON_SIZE_SP)
        zoneNamePaint.textSize = sp(ZONE_NAME_SIZE_SP)

        ratingSymbolPaint.textSize = sp(RATING_SYMBOL_SIZE_SP)
        ratingSymbolHeaderPaint.textSize = sp(RATING_SYMBOL_HEADER_SIZE_SP)

        labelHeight = labelTextPaint.descent() - labelTextPaint.ascent()
        valueHeight = valueTextPaint.descent() - valueTextPaint.ascent()
        highlightValueHeight = highlightValueTextPaint.descent() - highlightValueTextPaint.ascent()
        extraHighlightValueHeight = extraHighlightValueTextPaint.descent() - extraHighlightValueTextPaint.ascent()
    }

    private fun sp(sizeSp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp * fontSizeScale, resources.displayMetrics)

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v * fontSizeScale, resources.displayMetrics)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateDimensionsAndPaints()

        // O layout tem de contemplar o espaço extra no topo para o popup
        // para que ele não fique cortado fora da view.

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)

        val targetW = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> min(widthSize, dp(360f).toInt())
            else -> dp(340f).toInt()
        }

        val headerRowH = max(headerOfferPaint.textSize, headerKmPaint.textSize)
        val bottomRowH = valueHeight
        val iconRowH = zoneIconPaint.textSize
        val zoneNameH = if (currentZoneName != null) zoneNamePaint.textSize + dp(2f) else 0f

        var requiredH = 0f

        // Adiciona espaço para o popup no topo
        requiredH += extraTopSpacePx

        requiredH += cardOuterMarginPx
        requiredH += cardPadPx
        requiredH += headerRowH
        requiredH += lineSpacingVerticalPx
        requiredH += iconRowH
        requiredH += zoneNameH
        requiredH += lineSpacingVerticalPx
        requiredH += bottomRowH
        requiredH += cardPadPx
        requiredH += cardOuterMarginPx

        val minH = dp(140f) + extraTopSpacePx
        if (requiredH < minH) requiredH = minH

        val measuredW = resolveSize(targetW, widthMeasureSpec)
        val measuredH = resolveSize(requiredH.toInt(), heightMeasureSpec)
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

        // 1. Desenha o Card (sempre na mesma posição fixa)
        drawSingleCard(canvas, finalBorder)

        // 2. Desenha o Banner (se existir)
        drawBannerIfNeeded(canvas)
    }

    private fun drawSingleCard(canvas: Canvas, finalBorder: BorderRating) {
        val left   = cardOuterMarginPx

        // [IMPORTANTE] O Topo do card começa DEPOIS do espaço reservado para o popup
        val top    = extraTopSpacePx + cardOuterMarginPx

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

        // Header
        val offerStr = od?.value?.let { raw ->
            val num = raw.replace("€","").trim().replace(" ","").replace(",",".").toDoubleOrNull()
            num?.let { "€ " + euroOfferFormatter.format(it) } ?: raw.trim()
        } ?: PLACEHOLDER_TEXT

        val headerTop = contentRect.top
        val headerLeft = contentRect.left
        val headerRight = contentRect.right

        val vpk = od?.calculateProfitability()
        val kmVal = vpk?.let { String.format(Locale("pt", "PT"), "%.2f", it) }
        val kmStr = kmVal?.let { "€/km $it" } ?: "€/km —"

        val vph = od?.calculateValuePerHour()
        val hourVal = vph?.let { euroHoraFormatter.format(it) }
        val hourStr = hourVal?.let { "€/h $it" } ?: "€/h —"

        val hourRating = currentEvaluationResult?.hourRating ?: IndividualRating.UNKNOWN
        headerHourPaint.color = getIndicatorColor(hourRating)

        val headerBaseline = headerTop - headerOfferPaint.ascent()

        headerOfferPaint.color = TEXT_COLOR_VALUE
        canvas.drawText(offerStr, headerLeft, headerBaseline, headerOfferPaint)

        val hourSymbol = getRatingSymbol(hourRating)
        val hourBaseline = headerBaseline
        val hourPaintRight = TextPaint(headerHourPaint).apply { textAlign = Paint.Align.RIGHT }

        ratingSymbolHeaderPaint.textAlign = Paint.Align.RIGHT
        ratingSymbolHeaderPaint.color = getIndicatorColor(hourRating)
        canvas.drawText(hourSymbol, headerRight, hourBaseline, ratingSymbolHeaderPaint)

        val hourSymbolWidth = ratingSymbolHeaderPaint.measureText(hourSymbol)
        canvas.drawText(hourStr, headerRight - hourSymbolWidth - dp(2.5f), hourBaseline, hourPaintRight)

        // Ícone Centro
        val iconCenterY = contentRect.top + (contentRect.height() / 2f)
        drawZoneSymbolCenter(canvas, iconCenterY)

        // Footer
        val totalMinutes: Int? = run {
            val p = od?.pickupDuration?.trim()?.toIntOrNull()
            val t = od?.tripDuration?.trim()?.toIntOrNull()
            val list = listOfNotNull(p, t)
            if (list.isNotEmpty()) list.sum() else null
        }
        val totalMinStr = totalMinutes?.let { "${it} m" } ?: "—"

        val totalKmVal = od?.calculateTotalDistance()
        val totalKmStr = totalKmVal?.let { String.format(Locale("pt", "PT"), "%.1f km", it) } ?: "—"

        val bottomText = "$totalMinStr   •   $totalKmStr"

        valueTextPaint.color = TEXT_COLOR_VALUE
        valueTextPaint.alpha = (viewAlpha * 255).toInt()

        val bottomBaseline = contentRect.bottom - valueTextPaint.descent()

        canvas.drawText(bottomText, contentRect.left, bottomBaseline, valueTextPaint)

        val kmRating = currentEvaluationResult?.kmRating ?: IndividualRating.UNKNOWN
        val kmPaint = TextPaint(valueTextPaint).apply {
            color = getIndicatorColor(kmRating)
            textAlign = Paint.Align.RIGHT
        }

        val kmSymbol = getRatingSymbol(kmRating)
        ratingSymbolPaint.textAlign = Paint.Align.RIGHT
        ratingSymbolPaint.color = getIndicatorColor(kmRating)
        canvas.drawText(kmSymbol, contentRect.right, bottomBaseline, ratingSymbolPaint)

        val kmSymbolWidth = ratingSymbolPaint.measureText(kmSymbol)
        canvas.drawText(kmStr, contentRect.right - kmSymbolWidth - dp(2.5f), bottomBaseline, kmPaint)
    }

    private fun drawZoneSymbolCenter(canvas: Canvas, centerY: Float) {
        val icon = when (currentZoneState) {
            ZoneState.PREFERRED -> "✅"
            ZoneState.NO_GO     -> "🚫"
            ZoneState.NEUTRAL   -> "⚠️"
            ZoneState.UNKNOWN   -> ""
        }
        if (icon.isEmpty()) return

        zoneIconPaint.textAlign = Paint.Align.CENTER
        val x = (contentRect.left + contentRect.right) / 2f

        val yOffset = if (currentZoneName != null)
            -(zoneNamePaint.textSize * 0.2f)
        else
            (zoneIconPaint.textSize / 3f)

        val baseline = centerY + yOffset
        canvas.drawText(icon, x, baseline, zoneIconPaint)

        currentZoneName?.let { name ->
            if (name.isNotBlank()) {
                val textY = baseline + (zoneNamePaint.textSize) + dp(4f)
                canvas.drawText(name, x, textY, zoneNamePaint)
            }
        }
    }

    private fun drawBannerIfNeeded(canvas: Canvas) {
        val text = bannerText?.trim().orEmpty()
        if (text.isEmpty()) return

        val textWidth = bannerTextPaint.measureText(text)
        val textHeight = bannerTextPaint.textSize

        val centerX = width / 2f
        val rectWidth = textWidth + (bannerPadHPx * 2)
        val rectHeight = textHeight + (bannerPadVPx * 2)

        // Posição: Topo do popup alinhado com o topo do espaço reservado (extraTopSpacePx)
        // Com uma ligeira sobreposição no card.

        val overlap = dp(2f)
        val bottom = cardRect.top + overlap
        val top = bottom - rectHeight

        val left = centerX - (rectWidth / 2f)
        val right = centerX + (rectWidth / 2f)

        bannerRect.set(left, top, right, bottom)

        bannerBgPaint.color = when (bannerType) {
            BannerType.INFO -> Color.parseColor("#1976D2")
            BannerType.SUCCESS -> Color.parseColor("#2E7D32")
            BannerType.WARNING -> Color.parseColor("#E65100")
        }
        bannerBgPaint.alpha = 255

        val r = bannerCornerPx
        canvas.drawRoundRect(bannerRect, r, r, bannerBgPaint)

        val textX = left + bannerPadHPx
        val textY = top + bannerPadVPx - bannerTextPaint.ascent()

        bannerTextPaint.alpha = 255
        canvas.drawText(text, textX, textY, bannerTextPaint)
    }

    // Helpers
    fun updateFontSize(scale: Float) {
        fontSizeScale = scale.coerceIn(0.9f, 2.0f)
        updateDimensionsAndPaints()
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

        // 1. Calcular individual ratings
        val km = evaluationResult?.kmRating ?: IndividualRating.UNKNOWN
        val hour = evaluationResult?.hourRating ?: IndividualRating.UNKNOWN

        // 2. Verificar som com a lógica rigorosa (Hora vermelha = Mau)
        checkAndPlaySound(km, hour, currentZoneState)

        requestLayout()
        invalidate()
        openOrUpdateMapForCurrentOffer(show = false)
    }

    private fun checkAndPlaySound(km: IndividualRating, hour: IndividualRating, zone: ZoneState) {
        // IDs: 1=Bom, 2=Mau, 3=Neutro, 0=Nenhum
        val soundStateId = when {
            // 1. Zonas (Prioridade Máxima)
            zone == ZoneState.NO_GO -> 2 // Mau
            zone == ZoneState.PREFERRED -> 1 // Bom

            // 2. Cenário Perfeito (Ambos Verdes) -> Bom
            hour == IndividualRating.GOOD && km == IndividualRating.GOOD -> 1

            // 3. Hora Vermelha -> Mau (Regra: "hora vermelha e km verde mau" / "Ambos vermelhos mau")
            hour == IndividualRating.POOR -> 2

            // 4. Tudo o resto -> Neutro
            // Isto cobre:
            // - Hora Verde + Km Vermelho (O teu pedido principal)
            // - Hora Verde + Km Amarelo
            // - Hora Amarela + Km (Qualquer coisa)
            // - Ambos Amarelos
            else -> 3
        }

        // Se o estado não mudou, não toca som de novo
        if (soundStateId == lastPlayedSoundId) return
        lastPlayedSoundId = soundStateId

        val prefs = context.getSharedPreferences("SmartDriverPrefs", Context.MODE_PRIVATE)
        val soundEnabled = prefs.getBoolean("sound_enabled", true)
        if (!soundEnabled) return

        val soundResId = when (soundStateId) {
            1 -> R.raw.som_bom
            2 -> R.raw.som_mau
            3 -> R.raw.neutro
            else -> null
        }

        if (soundResId != null) {
            try {
                val mp = MediaPlayer.create(context, soundResId)
                mp.setOnCompletionListener { it.release() }
                mp.start()
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao tocar som: ${e.message}")
            }
        }
    }

    fun showBanner(text: String, type: BannerType = BannerType.INFO, durationMs: Long = 2500L) {
        bannerText = text.take(60)
        bannerType = type
        bannerClearAt = if (durationMs > 0) System.currentTimeMillis() + durationMs else 0L
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

    private fun recomputeCombinedBorder(km: IndividualRating, hour: IndividualRating): BorderRating {
        return when {
            km == IndividualRating.UNKNOWN || hour == IndividualRating.UNKNOWN -> BorderRating.GRAY
            km == IndividualRating.GOOD && hour == IndividualRating.GOOD       -> BorderRating.GREEN
            km == IndividualRating.POOR && hour == IndividualRating.POOR       -> BorderRating.RED
            else                                                               -> BorderRating.YELLOW
        }
    }

    private fun getRatingSymbol(rating: IndividualRating): String = when (rating) {
        IndividualRating.GOOD    -> "✔"
        IndividualRating.MEDIUM  -> "△"
        IndividualRating.POOR    -> "✖"
        IndividualRating.UNKNOWN -> "○"
    }

    private fun readLastZoneFromPrefs(): Pair<ZoneState, String?> {
        return try {
            val prefs = context.getSharedPreferences("smartdriver_map_state", Context.MODE_PRIVATE)
            val raw = prefs.getString("last_zone_kind", null)
            val name = prefs.getString("last_zone_name", null)
            val state = when (raw?.uppercase(Locale.getDefault())) {
                "NO_GO"     -> ZoneState.NO_GO
                "PREFERRED" -> ZoneState.PREFERRED
                "NEUTRAL"   -> ZoneState.NEUTRAL
                "UNKNOWN"   -> ZoneState.UNKNOWN
                else        -> ZoneState.UNKNOWN
            }
            state to name
        } catch (_: Exception) { ZoneState.UNKNOWN to null }
    }

    private fun saveLastZoneToPrefs(state: ZoneState, name: String?) {
        runCatching {
            val prefs = context.getSharedPreferences("smartdriver_map_state", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("last_zone_kind", state.name)
                .putString("last_zone_name", name)
                .apply()
        }
    }
}