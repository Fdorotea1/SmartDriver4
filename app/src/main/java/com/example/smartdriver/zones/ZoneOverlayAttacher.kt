package com.example.smartdriver.zones

import android.app.Activity
import com.google.android.gms.maps.GoogleMap

/**
 * Adaptador minimal para ligar o renderer de zonas ao Google Maps.
 * Mantém o nome do ficheiro/classe para compatibilidade com chamadas antigas.
 */
object ZoneOverlayAttacher {

    /**
     * Cria e devolve um renderer de zonas para o mapa fornecido.
     * (Substitui a antiga assinatura que devolvia um Overlay do OSMDroid.)
     */
    fun attach(activity: Activity, map: GoogleMap): ZonesRenderOverlay {
        return ZonesRenderOverlay(activity, map)
    }

    /**
     * Compat: algumas versões antigas chamavam "detach" no overlay.
     * Aqui apenas limpa os polígonos do renderer.
     */
    fun detach(renderer: ZonesRenderOverlay?) {
        renderer?.clear()
    }
}
