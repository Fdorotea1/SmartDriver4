package com.example.smartdriver.zones

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * ZoneGeoStore — runtime leve para consulta de zonas via GeoJSON.
 *
 * Como usar:
 *  - Coloca um ficheiro "zones_runtime.geojson" em context.filesDir.
 *    Formato: GeoJSON FeatureCollection, com Features "Polygon" ou "MultiPolygon"
 *    e propriedade "zoneType": "NO_GO" | "PREFERRED".
 *
 * Exemplo mínimo:
 * {
 *   "type":"FeatureCollection",
 *   "features":[
 *     {"type":"Feature","properties":{"zoneType":"NO_GO"},
 *      "geometry":{"type":"Polygon","coordinates":[[[-9.2,38.7],[-9.21,38.7],[-9.21,38.71],[-9.2,38.71],[-9.2,38.7]]]}}
 *   ]
 * }
 *
 * Em produção, este store pode ser trocado por um wrapper do teu ZoneRuntime
 * (mantendo a mesma API pública).
 */
object ZoneGeoStore {

    enum class ZoneKind { NEUTRAL, PREFERRED, NO_GO }

    private const val TAG = "ZoneGeoStore"
    private const val FILENAME = "zones_runtime.geojson"

    // Estruturas carregadas
    private val noGoPolys = mutableListOf<PolygonSet>()
    private val prefPolys = mutableListOf<PolygonSet>()
    private var loaded = false

    data class PolygonSet(
        val rings: List<List<LngLat>> // Cada polygon com 1 outer-ring (primeiro) e restantes holes (ignorados aqui)
    )
    data class LngLat(val lon: Double, val lat: Double)

    /** Carrega (lazy) o GeoJSON. Chama em background antes de avaliar pontos. */
    @Synchronized fun ensureLoaded(ctx: Context) {
        if (loaded) return
        reload(ctx)
    }

    /** Recarrega explicitamente a partir de disco (usar quando o editor salvar). */
    @Synchronized fun reload(ctx: Context) {
        noGoPolys.clear(); prefPolys.clear()
        val f = File(ctx.filesDir, FILENAME)
        if (!f.exists()) {
            Log.i(TAG, "GeoJSON não encontrado (${f.absolutePath}). Store vazio (NEUTRAL).")
            loaded = true
            return
        }
        runCatching { parseGeoJson(f.readText()) }
            .onFailure { Log.e(TAG, "Erro a ler GeoJSON: ${it.message}") }
        loaded = true
    }

    /** Avalia um ponto. Devolve NO_GO se cair em qualquer NO_GO, senão PREFERRED se cair em alguma preferida, senão NEUTRAL. */
    fun stateForPoint(lat: Double, lon: Double): ZoneKind {
        val p = LngLat(lon, lat)
        for (poly in noGoPolys) if (polyContains(poly, p)) return ZoneKind.NO_GO
        for (poly in prefPolys) if (polyContains(poly, p)) return ZoneKind.PREFERRED
        return ZoneKind.NEUTRAL
    }

    // ---------- Parser ----------

    private fun parseGeoJson(txt: String) {
        val root = JSONObject(txt)
        val typ = root.optString("type")
        require(typ == "FeatureCollection") { "GeoJSON: 'type' != FeatureCollection" }
        val feats = root.getJSONArray("features")
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val props = f.optJSONObject("properties") ?: JSONObject()
            val kind = when (props.optString("zoneType", "").uppercase()) {
                "NO_GO"     -> ZoneKind.NO_GO
                "PREFERRED" -> ZoneKind.PREFERRED
                else        -> ZoneKind.NEUTRAL
            }
            val geom = f.getJSONObject("geometry")
            val gType = geom.getString("type")
            when (gType) {
                "Polygon" -> {
                    val rings = parsePolygonRings(geom.getJSONArray("coordinates"))
                    addPoly(kind, PolygonSet(rings))
                }
                "MultiPolygon" -> {
                    val arr = geom.getJSONArray("coordinates")
                    for (j in 0 until arr.length()) {
                        val rings = parsePolygonRings(arr.getJSONArray(j))
                        addPoly(kind, PolygonSet(rings))
                    }
                }
            }
        }
        Log.i(TAG, "GeoJSON carregado: no-go=${noGoPolys.size}, preferred=${prefPolys.size}")
    }

    private fun parsePolygonRings(ringsArray: JSONArray): List<List<LngLat>> {
        val rings = mutableListOf<List<LngLat>>()
        for (r in 0 until ringsArray.length()) {
            val ring = ringsArray.getJSONArray(r)
            val pts = mutableListOf<LngLat>()
            for (k in 0 until ring.length()) {
                val pair = ring.getJSONArray(k)
                val lon = pair.getDouble(0)
                val lat = pair.getDouble(1)
                pts += LngLat(lon, lat)
            }
            // opcional: garantir fecho do anel
            if (pts.size >= 2 && !almostSame(pts.first(), pts.last())) {
                pts += pts.first()
            }
            rings += pts
        }
        return rings
    }

    private fun addPoly(kind: ZoneKind, poly: PolygonSet) {
        when (kind) {
            ZoneKind.NO_GO     -> noGoPolys += poly
            ZoneKind.PREFERRED -> prefPolys += poly
            ZoneKind.NEUTRAL   -> {} // ignorar
        }
    }

    // ---------- Geometria ----------

    private fun polyContains(poly: PolygonSet, p: LngLat): Boolean {
        if (poly.rings.isEmpty()) return false
        // Consideramos apenas o outer ring (índice 0). Holes são ignorados para simplificar.
        return ringContains(poly.rings[0], p)
    }

    // Ray casting (lon/lat)
    private fun ringContains(ring: List<LngLat>, p: LngLat): Boolean {
        var c = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val pi = ring[i]; val pj = ring[j]
            val intersect = ((pi.lat > p.lat) != (pj.lat > p.lat)) &&
                    (p.lon < (pj.lon - pi.lon) * (p.lat - pi.lat) / (pj.lat - pi.lat + 1e-12) + pi.lon)
            if (intersect) c = !c
            j = i
        }
        return c
    }

    private fun almostSame(a: LngLat, b: LngLat): Boolean =
        abs(a.lon - b.lon) < 1e-9 && abs(a.lat - b.lat) < 1e-9
}
