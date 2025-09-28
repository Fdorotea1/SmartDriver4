package com.example.smartdriver.overlay

import android.os.Handler
import android.os.Looper
import android.view.View
import com.example.smartdriver.overlay.widgets.MiniMapOverlay.LatLngD

/**
 * Liga o "manter 2s" ao botão flutuante **apenas quando** o rótulo atual é "MAPA".
 * currentLabel() -> texto atual que o teu botão mostra (ex.: "MAPA", "€/h", "km", ...).
 * routeProvider() -> devolve a rota atual (pickup->dropoff) como lista de (lat,lon).
 */
object MapLongPressBinder {

    private const val HOLD_MS = 2000L

    fun attach(
        targetButton: View,
        currentLabel: () -> String,
        routeProvider: () -> List<LatLngD>
    ) {
        val h = Handler(Looper.getMainLooper())
        var isDown = false
        val trigger = Runnable {
            if (isDown && currentLabel().equals("MAPA", ignoreCase = true)) {
                val route = routeProvider()
                MiniMapController.show(targetButton.context, route)
            }
        }

        targetButton.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isDown = true
                    h.postDelayed(trigger, HOLD_MS)
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL,
                android.view.MotionEvent.ACTION_OUTSIDE -> {
                    isDown = false
                    h.removeCallbacks(trigger)
                }
            }
            // Deixa o onClick normal funcionar para ciclos de rótulos, por isso retorna false
            false
        }
    }
}
