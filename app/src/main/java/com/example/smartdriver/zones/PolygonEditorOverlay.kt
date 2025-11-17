package com.example.smartdriver.zones

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import org.osmdroid.util.GeoPoint
import kotlin.math.hypot
import kotlin.math.min

/**
 * Editor de polígonos por cima do GoogleMap.
 * - PONTOS: tap no mapa adiciona ponto; tocar perto do 1.º ponto (com 3+) conclui.
 * - DESENHO_LIVRE: arrastar no overlay; ao levantar conclui.
 * Só intercepta toques quando arrastas um handle ou estás a desenhar em livre.
 */
class PolygonEditorOverlay(
    private val context: Context,
    private val gmap: GoogleMap,
    private val overlayParent: ViewGroup
) {

    enum class Mode { PONTOS, DESENHO_LIVRE }
    var mode: Mode = Mode.PONTOS

    var onChange: (() -> Unit)? = null
    var onFinalize: ((List<GeoPoint>, ZoneType) -> Unit)? = null

    private var currentType: ZoneType = ZoneType.NO_GO
    private val points: MutableList<GeoPoint> = mutableListOf()

    // Undo/Redo
    private val history: MutableList<List<GeoPoint>> = mutableListOf()
    private var historyIndex = -1

    private var editorView: EditorView? = null

    init { ensureView() }

    // ===== API =====

    /** Tap do Google Map (PONTOS). */
    fun onMapTap(latLng: LatLng) {
        if (mode != Mode.PONTOS) return
        val gp = GeoPoint(latLng.latitude, latLng.longitude)
        if (points.size >= 3 && tapNearFirstPoint(latLng)) {
            finalizeIfPossible()
            return
        }
        addPoint(gp)
    }

    fun addPoint(gp: GeoPoint) {
        points.add(GeoPoint(gp.latitude, gp.longitude))
        pushHistory()
        editorView?.invalidate()
        onChange?.invoke()
    }

    /** Chamar do menu para tentar concluir. */
    fun finalizeIfPossible() {
        if (points.size >= 3) {
            onFinalize?.invoke(points.map { GeoPoint(it.latitude, it.longitude) }, currentType)
            clear()
        }
    }

    fun setPolygon(src: List<GeoPoint>, type: ZoneType) {
        currentType = type
        points.clear()
        points.addAll(src.map { GeoPoint(it.latitude, it.longitude) })
        pushHistory()
        editorView?.invalidate()
        onChange?.invoke()
    }

    fun undo(): Boolean {
        if (historyIndex <= 0) return false
        historyIndex--
        restoreHistory()
        return true
    }

    fun redo(): Boolean {
        if (historyIndex < 0 || historyIndex >= history.size - 1) return false
        historyIndex++
        restoreHistory()
        return true
    }

    fun clear() {
        points.clear()
        history.clear()
        historyIndex = -1
        editorView?.invalidate()
        onChange?.invoke()
    }

    fun dispose() {
        editorView?.let { overlayParent.removeView(it) }
        editorView = null
        history.clear()
        points.clear()
    }

    fun invalidateOverlay() { editorView?.invalidate() }

    // ===== Internos =====

    private fun ensureView() {
        if (editorView != null) return
        val v = EditorView(context)
        overlayParent.addView(
            v,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        editorView = v
    }

    private fun pushHistory() {
        val snap = points.map { GeoPoint(it.latitude, it.longitude) }
        if (historyIndex >= 0 && historyIndex < history.lastIndex) {
            while (history.size - 1 > historyIndex) history.removeAt(history.lastIndex)
        }
        history.add(snap)
        historyIndex = history.lastIndex
    }

    private fun restoreHistory() {
        points.clear()
        points.addAll(history[historyIndex].map { GeoPoint(it.latitude, it.longitude) })
        editorView?.invalidate()
        onChange?.invoke()
    }

    private fun tapNearFirstPoint(latLng: LatLng): Boolean {
        if (points.isEmpty()) return false
        val first = points.first()
        val pj = gmap.projection
        val pFirst = pj.toScreenLocation(LatLng(first.latitude, first.longitude))
        val pTap = pj.toScreenLocation(latLng)
        val dist = hypot((pTap.x - pFirst.x).toDouble(), (pTap.y - pFirst.y).toDouble())
        val slop = 28.0 // px
        return dist <= slop
    }

    // ===== View do Editor =====

    private inner class EditorView(ctx: Context) : View(ctx) {

        private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.BLACK
        }
        private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(48, 33, 33, 33)
        }
        private val paintHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val paintHandleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.BLACK
        }

        private val handleRadius = 16f
        private var draggingIndex = -1

        // desenho livre
        private var drawing = false
        private var freehandPath: Path = Path()
        private val freehandPtsPx: MutableList<PointF> = mutableListOf()

        override fun onDraw(c: Canvas) {
            super.onDraw(c)

            val scrPts = pointsToScreen(points)
            if (scrPts.size >= 2) {
                val path = Path()
                path.moveTo(scrPts[0].x, scrPts[0].y)
                for (i in 1 until scrPts.size) path.lineTo(scrPts[i].x, scrPts[i].y)
                if (scrPts.size >= 3) path.close()

                val (fill, stroke) = colorsFor(currentType)
                paintFill.color = fill
                paintLine.color = stroke
                c.drawPath(path, paintFill)
                c.drawPath(path, paintLine)

                for (p in scrPts) {
                    c.drawCircle(p.x, p.y, handleRadius, paintHandle)
                    c.drawCircle(p.x, p.y, handleRadius, paintHandleStroke)
                }
            }

            if (drawing && mode == Mode.DESENHO_LIVRE) {
                // Desenho livre em stroke enquanto desenhas
                paintLine.color = Color.BLACK
                c.drawPath(freehandPath, paintLine)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (mode == Mode.DESENHO_LIVRE) {
                        drawing = true
                        freehandPath.reset()
                        freehandPtsPx.clear()
                        freehandPath.moveTo(e.x, e.y)
                        freehandPtsPx += PointF(e.x, e.y)
                        parent.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                        return true
                    } else {
                        // PONTOS: só intercepta se agarrar handle
                        val idx = hitHandle(e.x, e.y)
                        if (idx >= 0) {
                            draggingIndex = idx
                            parent.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                        return false // deixa o mapa pan/zoom
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == Mode.DESENHO_LIVRE && drawing) {
                        val last = freehandPtsPx.lastOrNull()
                        val p = PointF(e.x, e.y)
                        if (last == null || hypot((p.x - last.x).toDouble(), (p.y - last.y).toDouble()) > 6.0) {
                            freehandPtsPx += p
                            freehandPath.lineTo(p.x, p.y)
                            invalidate()
                        }
                        return true
                    } else if (draggingIndex >= 0) {
                        val gp = screenToGeo(e.x, e.y)
                        points[draggingIndex] = gp
                        onChange?.invoke()
                        invalidate()
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode == Mode.DESENHO_LIVRE && drawing) {
                        drawing = false
                        val simplified = simplifyFreehand(freehandPtsPx)
                        val geo = simplified.map { screenToGeo(it.x, it.y) }
                        if (geo.size >= 3) {
                            points.clear(); points.addAll(geo)
                            pushHistory()
                            onChange?.invoke()
                            onFinalize?.invoke(points.map { GeoPoint(it.latitude, it.longitude) }, currentType)
                            clear()
                        }
                        parent.requestDisallowInterceptTouchEvent(false)
                        return true
                    } else if (draggingIndex >= 0) {
                        draggingIndex = -1
                        pushHistory()
                        parent.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                    return false
                }
            }
            return false
        }

        private fun hitHandle(x: Float, y: Float): Int {
            val scr = pointsToScreen(points)
            for (i in scr.indices.reversed()) {
                val p = scr[i]
                if (hypot((x - p.x).toDouble(), (y - p.y).toDouble()) <= handleRadius * 1.6) return i
            }
            return -1
        }

        private fun pointsToScreen(src: List<GeoPoint>): List<PointF> {
            if (src.isEmpty()) return emptyList()
            val pj = gmap.projection ?: return emptyList()
            val out = ArrayList<PointF>(src.size)
            for (gp in src) {
                val pt = pj.toScreenLocation(LatLng(gp.latitude, gp.longitude))
                out.add(PointF(pt.x.toFloat(), pt.y.toFloat()))
            }
            return out
        }

        private fun screenToGeo(x: Float, y: Float): GeoPoint {
            val latLng = gmap.projection.fromScreenLocation(android.graphics.Point(x.toInt(), y.toInt()))
            return GeoPoint(latLng.latitude, latLng.longitude)
        }

        private fun colorsFor(t: ZoneType): Pair<Int, Int> = when (t) {
            ZoneType.NO_GO      -> Color.argb(60, 183, 28, 28) to Color.parseColor("#B71C1C")
            ZoneType.SOFT_AVOID -> Color.argb(55, 230, 81, 0)  to Color.parseColor("#E65100")
            ZoneType.PREFERRED  -> Color.argb(55, 46, 125, 50) to Color.parseColor("#2E7D32")
        }

        // simplificação da linha livre
        private fun simplifyFreehand(src: List<PointF>): List<PointF> {
            if (src.size <= 3) return src
            val out = ArrayList<PointF>(src.size)
            var last = src.first()
            out += last
            for (i in 1 until src.size - 1) {
                val p = src[i]
                if (hypot((p.x - last.x).toDouble(), (p.y - last.y).toDouble()) >= 6.0) {
                    out += p
                    last = p
                }
            }
            out += src.last()
            // evita demasiados pontos (limita ~ 200)
            if (out.size > 200) {
                val step = maxOf(1, out.size / 200)
                return out.filterIndexed { idx, _ -> idx % step == 0 || idx == out.lastIndex }
            }
            return out
        }
    }
}
