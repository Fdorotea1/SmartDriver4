package com.example.smartdriver.overlay

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
// import com.example.smartdriver.R // Removido pois não parece ser usado diretamente aqui
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
        private val BACKGROUND_COLOR = Color.parseColor("#E6FFFFFF")
        private val BORDER_COLOR_GREEN = Color.parseColor("#4CAF50"); private val BORDER_COLOR_YELLOW = Color.parseColor("#FFC107")
        private val BORDER_COLOR_RED = Color.parseColor("#F44336"); private val BORDER_COLOR_GRAY = Color.parseColor("#9E9E9E")
        private val TEXT_COLOR_VALUE = Color.BLACK; private const val PLACEHOLDER_TEXT = "--"
        private val VPH_COLOR_GOOD = BORDER_COLOR_GREEN; private val VPH_COLOR_MEDIUM = Color.parseColor("#FF9800")
        private val VPH_COLOR_POOR = BORDER_COLOR_RED; private val VPH_COLOR_UNKNOWN = Color.DKGRAY
        private val VPK_COLOR_GOOD = BORDER_COLOR_GREEN; private val VPK_COLOR_MEDIUM = BORDER_COLOR_YELLOW
        private val VPK_COLOR_POOR = BORDER_COLOR_RED; private val VPK_COLOR_UNKNOWN = Color.DKGRAY
        private const val PADDING_DP = 12f; private const val BORDER_WIDTH_DP = 8f
        private const val CORNER_RADIUS_DP = 10f; private const val TEXT_SIZE_SP = 16f
        private const val LINE_SPACING_DP = 5f
    }

    private var currentValuePerHour: Double? = null; private var currentHourRating: IndividualRating = IndividualRating.UNKNOWN; private var elapsedTimeSeconds: Long = 0
    private var initialValuePerKm: Double? = null; private var initialTotalDistance: Double? = null; private var offerValue: String? = null
    private var initialTotalDurationMinutes: Int? = null; private var initialKmRating: IndividualRating = IndividualRating.UNKNOWN
    private var combinedBorderRating: BorderRating = BorderRating.GRAY

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG); private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG); private val vphTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val vpkTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private var paddingPx: Float = 0f; private var borderRadiusPx: Float = 0f
    private var textHeight: Float = 0f; private var lineSpacingPx: Float = 0f

    private var isDragging = false; private var touchSlop: Int
    private var initialWindowX: Int = 0; private var initialWindowY: Int = 0
    private var initialTouchRawX: Float = 0f; private var initialTouchRawY: Float = 0f

    private val gestureDetector: GestureDetector
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        val density = resources.displayMetrics.density; val scaledDensity = resources.displayMetrics.scaledDensity
        paddingPx = PADDING_DP * density; borderRadiusPx = CORNER_RADIUS_DP * density
        lineSpacingPx = LINE_SPACING_DP * density
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        backgroundPaint.apply { style = Paint.Style.FILL; color = BACKGROUND_COLOR }
        borderPaint.apply { style = Paint.Style.STROKE; strokeWidth = BORDER_WIDTH_DP * density }
        valueTextPaint.apply { color = TEXT_COLOR_VALUE; typeface = Typeface.DEFAULT_BOLD }
        vphTextPaint.apply { typeface = Typeface.DEFAULT_BOLD }
        vpkTextPaint.apply { typeface = Typeface.DEFAULT_BOLD }
        updateTextPaintSizes()

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                initialWindowX = layoutParams.x; initialWindowY = layoutParams.y
                initialTouchRawX = e.rawX; initialTouchRawY = e.rawY
                isDragging = false
                Log.d(TAG, "GestureDetector onDown: Enviando ACTION_SHOW_DROP_ZONE")
                sendOverlayServiceSimpleAction(OverlayService.ACTION_SHOW_DROP_ZONE)
                return true
            }

            override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val totalDeltaX = e2.rawX - initialTouchRawX; val totalDeltaY = e2.rawY - initialTouchRawY
                if (!isDragging && (abs(totalDeltaX) > touchSlop || abs(totalDeltaY) > touchSlop)) {
                    isDragging = true; Log.d(TAG, "Dragging started (onScroll)")
                }
                if (isDragging) {
                    layoutParams.x = initialWindowX + totalDeltaX.toInt(); layoutParams.y = initialWindowY + totalDeltaY.toInt()
                    mainHandler.post {
                        try { if (isAttachedToWindow) windowManager.updateViewLayout(this@TrackingOverlayView, layoutParams) }
                        catch (ex: Exception) { Log.e(TAG, "Erro updateViewLayout onScroll: ${ex.message}")}
                    }
                    return true
                }
                return false
            }

            override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                return isDragging
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isDragging) return false
                Log.d(TAG, "Duplo Toque (Parar e Salvar Tracking).")
                sendOverlayServiceSimpleAction(OverlayService.ACTION_STOP_TRACKING)
                sendOverlayServiceSimpleAction(OverlayService.ACTION_HIDE_DROP_ZONE_AND_CHECK_DROP)
                return true
            }
        })

        setOnTouchListener { _, event ->
            val consumedByGesture = gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                Log.d(TAG, "onTouchListener: ACTION_UP/CANCEL. isDragging: $isDragging. Coords:(${event.rawX}, ${event.rawY})")
                val intent = Intent(context, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_HIDE_DROP_ZONE_AND_CHECK_DROP
                    // Envia coordenadas GLOBAIS do ecrã, mesmo que não esteja a arrastar.
                    // O OverlayService pode decidir o que fazer com elas (ou ignorá-las se isDragging for falso no seu lado)
                    putExtra(OverlayService.EXTRA_UP_X, event.rawX)
                    putExtra(OverlayService.EXTRA_UP_Y, event.rawY)
                }
                context.startService(intent)
                if (isDragging) {
                    Log.d(TAG, "Dragging finished (onTouchListener).")
                    isDragging = false
                }
            }
            consumedByGesture || true
        }
    }

    private fun sendOverlayServiceSimpleAction(action: String) {
        val intent = Intent(context, OverlayService::class.java).apply { this.action = action }
        try { context.startService(intent) }
        catch (ex: Exception) { Log.e(TAG, "Erro ao enviar ação '$action': ${ex.message}") }
    }

    fun updateInitialData(iVpk: Double?, iDist: Double?, iDur: Int?, oVal: String?, iKmR: IndividualRating, cBR: BorderRating) { initialValuePerKm=iVpk; initialTotalDistance=iDist; initialTotalDurationMinutes=iDur; offerValue=oVal; initialKmRating=iKmR; combinedBorderRating=cBR; elapsedTimeSeconds=0; requestLayout(); invalidate() }
    fun updateRealTimeData(cVph: Double?, hR: IndividualRating, elSec: Long) { currentValuePerHour=cVph; currentHourRating=hR; elapsedTimeSeconds=elSec; invalidate() }

    override fun onMeasure(wMS: Int, hMS: Int) { updateTextPaintSizes(); val tVph="€/h: 999.9"; val tVpk="€/km Ini: 99.99"; val tDist="Dist Ini: 999.9 km"; val tOffer="Valor: 999.99 €"; val tTIni="Tempo Ini: 999 m"; val tTEl="Decorrido: 00:00:00"; val mW=listOf(tDist,tOffer,tTIni,tTEl).mapNotNull{valueTextPaint.measureText(it)}.maxOrNull()?:0f; val vW=vphTextPaint.measureText(tVph); val vpW=vpkTextPaint.measureText(tVpk); val fMW=maxOf(mW,vW,vpW); val rW=(paddingPx*2)+fMW; val rH=(paddingPx*2)+(textHeight*6)+(lineSpacingPx*5); setMeasuredDimension(resolveSize(rW.toInt(),wMS),resolveSize(rH.toInt(),hMS)) }
    override fun onDraw(cv: Canvas) { super.onDraw(cv); updateTextPaintSizes(); borderPaint.color=getBorderColor(combinedBorderRating); vphTextPaint.color=getVphIndicatorColor(currentHourRating); vpkTextPaint.color=getVpkIndicatorColor(initialKmRating); val wf=width.toFloat(); val hf=height.toFloat(); cv.drawRoundRect(0f,0f,wf,hf,borderRadiusPx,borderRadiusPx,backgroundPaint); cv.drawRoundRect(0f,0f,wf,hf,borderRadiusPx,borderRadiusPx,borderPaint); valueTextPaint.textAlign=Paint.Align.LEFT; vphTextPaint.textAlign=Paint.Align.LEFT; vpkTextPaint.textAlign=Paint.Align.LEFT; val tX=paddingPx; val tBH=(textHeight*6)+(lineSpacingPx*5); var cY=((hf-tBH)/2f)+textHeight-valueTextPaint.descent(); cv.drawText("€/h: ${currentValuePerHour?.let{String.format(Locale.US,"%.1f",it)}?:PLACEHOLDER_TEXT}",tX,cY,vphTextPaint); cY+=textHeight+lineSpacingPx; val h=TimeUnit.SECONDS.toHours(elapsedTimeSeconds); val m=TimeUnit.SECONDS.toMinutes(elapsedTimeSeconds)%60; val s=elapsedTimeSeconds%60; cv.drawText(String.format(Locale.getDefault(),"Decorrido: %02d:%02d:%02d",h,m,s),tX,cY,valueTextPaint); cY+=textHeight+lineSpacingPx; cv.drawText("€/km Ini: ${initialValuePerKm?.let{String.format(Locale.US,"%.2f",it)}?:PLACEHOLDER_TEXT}",tX,cY,vpkTextPaint); cY+=textHeight+lineSpacingPx; cv.drawText("Dist Ini: ${initialTotalDistance?.let{String.format(Locale.US,"%.1f km",it)}?:PLACEHOLDER_TEXT}",tX,cY,valueTextPaint); cY+=textHeight+lineSpacingPx; cv.drawText("Tempo Ini: ${initialTotalDurationMinutes?.let{"$it m"}?:PLACEHOLDER_TEXT}",tX,cY,valueTextPaint); cY+=textHeight+lineSpacingPx; cv.drawText("Valor: ${offerValue?.takeIf{it.isNotEmpty()}?.let{"$it €"}?:PLACEHOLDER_TEXT}",tX,cY,valueTextPaint) }
    private fun updateTextPaintSizes() {val sD=resources.displayMetrics.scaledDensity; val cTS=TEXT_SIZE_SP*sD; if(abs(valueTextPaint.textSize-cTS)>0.1f){valueTextPaint.textSize=cTS; vphTextPaint.textSize=cTS; vpkTextPaint.textSize=cTS; textHeight=valueTextPaint.descent()-valueTextPaint.ascent()}}
    private fun getBorderColor(r: BorderRating):Int=when(r){BorderRating.GREEN->BORDER_COLOR_GREEN; BorderRating.YELLOW->BORDER_COLOR_YELLOW; BorderRating.RED->BORDER_COLOR_RED; else->BORDER_COLOR_GRAY}
    private fun getVphIndicatorColor(r: IndividualRating):Int=when(r){IndividualRating.GOOD->VPH_COLOR_GOOD; IndividualRating.MEDIUM->VPH_COLOR_MEDIUM; IndividualRating.POOR->VPH_COLOR_POOR; else->VPH_COLOR_UNKNOWN}
    private fun getVpkIndicatorColor(r: IndividualRating):Int=when(r){IndividualRating.GOOD->VPK_COLOR_GOOD; IndividualRating.MEDIUM->VPK_COLOR_MEDIUM; IndividualRating.POOR->VPK_COLOR_POOR; else->VPK_COLOR_UNKNOWN}
}