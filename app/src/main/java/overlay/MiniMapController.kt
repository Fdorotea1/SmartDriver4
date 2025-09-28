package com.example.smartdriver.overlay

import android.content.Context
import android.content.res.Resources
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import com.example.smartdriver.overlay.widgets.MiniMapOverlay
import com.example.smartdriver.overlay.widgets.MiniMapOverlay.LatLngD

object MiniMapController {

    @Volatile private var container: FrameLayout? = null
    @Volatile private var wm: WindowManager? = null

    data class Options(
        val halfScreen: Boolean = true,
        val showZones: Boolean = true,
        val zoomToRoute: Boolean = true
    )

    fun isVisible(): Boolean = container != null

    fun show(
        context: Context,
        route: List<LatLngD>,
        options: Options = Options()
    ) {
        if (isVisible()) return
        if (!canDrawOverlays(context)) {
            Toast.makeText(context, "Permitir \"Sobrepor a outros apps\" para mostrar o mapa.", Toast.LENGTH_LONG).show()
            return
        }

        val app = context.applicationContext
        val windowManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val overlayView = MiniMapOverlay(app).apply {
            setRoute(route)
            setOnCloseRequested { hide(app) }
            tryEnableZonesOverlay(options.showZones)
            if (options.zoomToRoute) zoomToRoute()
        }

        val metrics = Resources.getSystem().displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val desiredHeight = if (options.halfScreen) (height * 0.5f).toInt() else (height * 0.8f).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            width,
            desiredHeight,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        val holder = FrameLayout(app)
        holder.addView(overlayView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        wm = windowManager
        container = holder
        windowManager.addView(holder, lp)
    }

    fun hide(context: Context) {
        val c = container ?: return
        val w = wm ?: return
        try {
            w.removeViewImmediate(c)
        } catch (_: Throwable) { /* ignore */ }
        container = null
        wm = null
    }

    private fun canDrawOverlays(context: Context): Boolean {
        // Muitos projetos já tratam desta permissão. Mantemos simples aqui.
        return true
    }
}
