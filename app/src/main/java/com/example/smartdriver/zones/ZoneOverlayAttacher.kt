package com.example.smartdriver.zones

import android.content.Context
import org.osmdroid.views.MapView

/**
 * Liga zonas ao mapa OSMDroid e refresca sempre que o ZoneRepository grava.
 */
class ZoneOverlayAttacher private constructor() {

    private var map: MapView? = null
    private var overlay: ZonesRenderOverlay? = null

    private val repoListener = object : ZoneRepository.SaveListener {
        override fun onDirty() { /* noop */ }
        override fun onSaved(success: Boolean) {
            map?.invalidate()
        }
    }

    fun attachOsmdroid(context: Context, mapView: MapView): ZoneOverlayAttacher {
        detach()
        map = mapView
        overlay = ZonesRenderOverlay(context).also { mapView.overlays.add(it) }
        mapView.invalidate()
        ZoneRepository.addListener(repoListener)
        return this
    }

    fun refresh() { map?.invalidate() }

    fun detach() {
        ZoneRepository.removeListener(repoListener)
        overlay?.let { ov ->
            map?.overlays?.remove(ov)
            map?.invalidate()
        }
        overlay = null
        map = null
    }

    companion object {
        fun create(): ZoneOverlayAttacher = ZoneOverlayAttacher()
    }
}
