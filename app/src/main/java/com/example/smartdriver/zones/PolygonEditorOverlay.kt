package com.example.smartdriver.zones

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Point
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot

/**
 * Editor de polígonos:
 *  • Modo PONTOS: toque adiciona ponto; duplo toque finaliza.
 *  • Modo DESENHO_LIVRE: arrasta o dedo para desenhar; ao levantar finaliza.
 *  • Arrastar "handles": tocar perto de um vértice e mover para ajustar.
 *  • Undo/Redo e Limpar.
 *  • Long-press (quando não estás a desenhar) → pede edição da zona por baixo do dedo.
 */
class PolygonEditorOverlay(
    context: Context
) : Overlay() {

    enum class Mode { PONTOS, DESENHO_LIVRE }

    var onChange: (() -> Unit)? = null
    var onFinalize: ((List<GeoPoint>, ZoneType) -> Unit)? = null

    /** Pedido de edição ao long-press. O MapEditorActivity decide qual zona editar. */
    var onLongPressRequestEdit: ((GeoPoint) -> Unit)? = null

    private val points: MutableList<GeoPoint> = mutableListOf()
    private val undoStack: MutableList<List<GeoPoint>> = mutableListOf()
    private val redoStack: MutableList<List<GeoPoint>> = mutableListOf()

    private var currentType: ZoneType = ZoneType.NO_GO

    // Propriedade do modo
    var mode: Mode = Mode.PONTOS

    // Drag de vértices
    private var dragIndex: Int = -1
    private var dragging = false
    private val handleRadiusPx: Float
    private val touchSlop: Int

    // Desenho livre
    private var freehandActive = false
    private var lastX = 0f
    private var lastY = 0f
    private val freehandMinDeltaPx = 8f

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (mode != Mode.PONTOS || dragging || freehandActive) return false
            val mv = mapView ?: return false
            val p = mv.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
            snapshotForUndo()
            points.add(p)
            onChange?.invoke()
            mv.invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (mode == Mode.PONTOS) {
                finalizePolygonIfValid()
                mapView?.invalidate()
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            // Só dispara quando não estamos a desenhar/draggar
            if (dragging || freehandActive || points.isNotEmpty()) return
            val mv = mapView ?: return
            val gp = mv.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
            onLongPressRequestEdit?.invoke(gp)
        }
    })

    init {
        val vc = ViewConfiguration.get(context)
        touchSlop = vc.scaledTouchSlop
        handleRadiusPx = 24f * (context.resources.displayMetrics.density)
    }

    // API pública
    fun setPolygon(pts: List<GeoPoint>, type: ZoneType) {
        points.clear()
        points.addAll(pts.map { GeoPoint(it.latitude, it.longitude) })
        currentType = type
        undoStack.clear()
        redoStack.clear()
        dragIndex = -1
        dragging = false
        freehandActive = false
        onChange?.invoke()
        mapView?.invalidate()
    }

    fun setType(type: ZoneType) {
        currentType = type
        mapView?.invalidate()
    }

    fun clear() {
        if (points.isNotEmpty()) snapshotForUndo()
        points.clear()
        dragIndex = -1
        dragging = false
        freehandActive = false
        onChange?.invoke()
        mapView?.invalidate()
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val last = undoStack.removeAt(undoStack.size - 1)
        redoStack.add(points.map { GeoPoint(it.latitude, it.longitude) })
        points.clear()
        points.addAll(last)
        onChange?.invoke()
        mapView?.invalidate()
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val next = redoStack.removeAt(redoStack.size - 1)
        undoStack.add(points.map { GeoPoint(it.latitude, it.longitude) })
        points.clear()
        points.addAll(next)
        onChange?.invoke()
        mapView?.invalidate()
        return true
    }

    private fun snapshotForUndo() {
        undoStack.add(points.map { GeoPoint(it.latitude, it.longitude) })
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
    }

    private fun finalizePolygonIfValid() {
        if (points.size >= 3) {
            onFinalize?.invoke(points.toList(), currentType)
        }
        points.clear()
        undoStack.clear()
        redoStack.clear()
        dragIndex = -1
        dragging = false
        freehandActive = false
        onChange?.invoke()
    }

    // ----- Input -----
    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        this.mapView = mapView

        // 1) Drag de handle tem prioridade
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Procura handle perto
                val idx = findHandleNear(event.x, event.y, mapView)
                if (idx >= 0) {
                    dragIndex = idx
                    dragging = true
                    snapshotForUndo()
                    return true
                }

                if (mode == Mode.DESENHO_LIVRE) {
                    // Inicia desenho livre
                    snapshotForUndo()
                    points.clear()
                    val gp = (mapView.projection.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint)
                    points.add(gp)
                    freehandActive = true
                    lastX = event.x
                    lastY = event.y
                    onChange?.invoke()
                    mapView.invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging && dragIndex >= 0) {
                    val gp = (mapView.projection.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint)
                    points[dragIndex] = gp
                    onChange?.invoke()
                    mapView.invalidate()
                    return true
                }
                if (freehandActive && mode == Mode.DESENHO_LIVRE) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (hypot(dx.toDouble(), dy.toDouble()) >= freehandMinDeltaPx) {
                        lastX = event.x
                        lastY = event.y
                        val gp = (mapView.projection.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint)
                        points.add(gp)
                        onChange?.invoke()
                        mapView.invalidate()
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    dragIndex = -1
                    mapView.invalidate()
                    return true
                }
                if (freehandActive && mode == Mode.DESENHO_LIVRE) {
                    freehandActive = false
                    finalizePolygonIfValid()
                    mapView.invalidate()
                    return true
                }
            }
        }

        // 2) Se não houve drag/desenho, o GestureDetector trata toques e long-press
        return gesture.onTouchEvent(event)
    }

    private fun findHandleNear(x: Float, y: Float, mapView: MapView): Int {
        if (points.isEmpty()) return -1
        val pj = mapView.projection
        val pt = Point()
        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        for (i in points.indices) {
            pj.toPixels(points[i], pt)
            val d = hypot((pt.x - x).toDouble(), (pt.y - y).toDouble()).toFloat()
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return if (bestDist <= handleRadiusPx) bestIdx else -1
    }

    // ----- Draw -----
    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        this.mapView = mapView

        if (points.isEmpty()) return

        val style = ZoneDefaults.styleFor(currentType)
        val fill = ZonePainter.fillPaint(style)
        val stroke = ZonePainter.strokePaint(style)

        val pj = mapView.projection
        val path = Path()
        val pt = Point()

        points.firstOrNull()?.let {
            pj.toPixels(it, pt)
            path.moveTo(pt.x.toFloat(), pt.y.toFloat())
        }

        for (i in 1 until points.size) {
            pj.toPixels(points[i], pt)
            path.lineTo(pt.x.toFloat(), pt.y.toFloat())
        }

        // No desenho livre não fechamos; no modo PONTOS fecha só ao finalizar
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)

        // Handles
        val handleStroke = ZonePainter.strokePaint(
            ZoneStyle(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 2f)
        )
        for (gp in points) {
            pj.toPixels(gp, pt)
            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 6f, handleStroke)
        }
    }

    private var mapView: MapView? = null
}
