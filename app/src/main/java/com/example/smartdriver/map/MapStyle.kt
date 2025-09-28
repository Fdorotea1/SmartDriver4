package com.example.smartdriver.map

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.views.MapView

/**
 * Estilos para os tiles do OSMDroid.
 * - applyHighContrastColorStyle: mantém cor, aumenta contraste e dá brilho leve (look limpo e nítido).
 * - applyCleanColorStyle: só dá um pequeno lift de brilho, sem mexer no contraste.
 * - applyUberLiteStyle: (anterior) P&B clarinho – mantida aqui caso queiras voltar.
 * - clear: remove qualquer filtro.
 */
object MapStyle {

    /** Mais nítido, com cor viva (sem desaturar). Bom para manter verdes e overlays destacados. */
    fun applyHighContrastColorStyle(mapView: MapView) {
        // Contraste ~ +12%
        val c = 1.12f
        val t = 128f * (1f - c) // offset para centragem do contraste
        val contrast = ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f
        ))

        // Pequeno lift de brilho global (+8)
        val lift = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 8f,
            0f, 1f, 0f, 0f, 8f,
            0f, 0f, 1f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f
        ))

        contrast.postConcat(lift)

        mapView.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(contrast))
        mapView.setTilesScaledToDpi(true)
    }

    /** Look “clean” mantendo cores originais; só levanta brilho ligeiramente. */
    fun applyCleanColorStyle(mapView: MapView) {
        val lift = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 12f,
            0f, 1f, 0f, 0f, 12f,
            0f, 0f, 1f, 0f, 12f,
            0f, 0f, 0f, 1f,  0f
        ))
        mapView.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(lift))
        mapView.setTilesScaledToDpi(true)
    }

    /** (Antigo) P&B clarinho – mantido caso queiras voltar atrás. */
    fun applyUberLiteStyle(mapView: MapView) {
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val lift = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 24f,
            0f, 1f, 0f, 0f, 24f,
            0f, 0f, 1f, 0f, 24f,
            0f, 0f, 0f, 1f,  0f
        ))
        cm.postConcat(lift)
        mapView.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(cm))
        mapView.setTilesScaledToDpi(true)
    }

    /** Remove qualquer estilo e volta ao Mapnik puro. */
    fun clear(mapView: MapView) {
        mapView.overlayManager.tilesOverlay.setColorFilter(null)
        mapView.setTilesScaledToDpi(true)
    }
}
