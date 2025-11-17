package com.example.smartdriver.zones

import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Repo simples em JSON: filesDir/zones.json
 * Agora com export runtime automático: filesDir/zones_runtime.geojson (GeoJSON)
 * e reload imediato do ZoneGeoStore para refletir alterações no overlay/semáforo.
 *
 * NOTA: list() devolve CÓPIAS defensivas para evitar mutações acidentais fora do repo.
 */
object ZoneRepository {

    interface SaveListener {
        fun onDirty()
        fun onSaved(success: Boolean)
    }

    private val gson = Gson()
    private lateinit var file: File
    private lateinit var appContext: Context
    private val listeners = CopyOnWriteArrayList<SaveListener>()
    private val zones: MutableList<Zone> = mutableListOf()
    private var dirty: Boolean = false

    private data class ZoneDTO(
        val id: String,
        val name: String,
        val type: ZoneType,
        val active: Boolean,
        val priority: Int,
        @SerializedName("points") val pts: List<GeoUtils.LL>,
        val style: ZoneStyle?,
        val updatedAt: Long
    )

    fun init(ctx: Context) {
        if (!::file.isInitialized) {
            appContext = ctx.applicationContext
            file = File(appContext.filesDir, "zones.json")
            loadFromDisk()
        }
    }

    fun addListener(l: SaveListener) { listeners.add(l) }
    fun removeListener(l: SaveListener) { listeners.remove(l) }

    private fun loadFromDisk() {
        zones.clear()
        if (file.exists()) {
            try {
                val txt = file.readText()
                val arr = gson.fromJson(txt, Array<ZoneDTO>::class.java) ?: emptyArray()
                for (dto in arr) {
                    zones.add(
                        Zone(
                            id = dto.id,
                            name = dto.name,
                            type = dto.type,
                            points = GeoUtils.fromLL(dto.pts),
                            active = dto.active,
                            priority = dto.priority,
                            style = dto.style,
                            updatedAt = dto.updatedAt
                        )
                    )
                }
            } catch (_: Throwable) {
                // ignora ficheiro corrompido
            }
        }
        // Reordena por prioridade
        zones.sortBy { it.priority }
    }

    /** Devolve CÓPIAS defensivas (incluindo cópia da lista de pontos). */
    fun list(): List<Zone> = zones
        .sortedBy { it.priority }
        .map { z -> z.copy(points = z.points.toMutableList(), style = z.style?.copy()) }

    fun size(): Int = zones.size

    fun getById(id: String): Zone? = zones.firstOrNull { it.id == id }?.let { z ->
        z.copy(points = z.points.toMutableList(), style = z.style?.copy())
    }

    fun add(z: Zone) {
        // evita partilha de lista de pontos
        var toAdd = z.copy(points = z.points.toMutableList(), style = z.style?.copy())

        // se já existir uma zona com o mesmo id, gera um novo id via copy()
        if (zones.any { it.id == toAdd.id }) {
            toAdd = toAdd.copy(id = java.util.UUID.randomUUID().toString())
        }

        markDirty()
        zones.add(toAdd)
        zones.sortBy { it.priority }
        save()
    }


    fun update(z: Zone) {
        val idx = zones.indexOfFirst { it.id == z.id }
        if (idx >= 0) {
            val updated = z.copy(points = z.points.toMutableList(), style = z.style?.copy())
            zones[idx] = updated
            zones.sortBy { it.priority }
            markDirty()
            save()
        }
    }

    fun delete(id: String) {
        zones.removeAll { it.id == id }
        markDirty()
        save()
    }

    fun setAll(newList: List<Zone>) {
        zones.clear()
        zones.addAll(newList.map { it.copy(points = it.points.toMutableList(), style = it.style?.copy()) })
        zones.sortBy { it.priority }
        markDirty()
        save()
    }

    private fun toDTO(z: Zone): ZoneDTO = ZoneDTO(
        id = z.id,
        name = z.name,
        type = z.type,
        active = z.active,
        priority = z.priority,
        pts = GeoUtils.toLL(z.points),
        style = z.style,
        updatedAt = z.updatedAt
    )

    fun hasPendingWrites(): Boolean = dirty

    fun save() {
        try {
            // 1) zones.json (editor)
            val arr = zones.sortedBy { it.priority }.map { toDTO(it) }
            file.writeText(gson.toJson(arr))

            // 2) zones_runtime.geojson (runtime do overlay)
            exportRuntimeGeoJson()

            // 3) Notificar runtime para recarregar imediatamente
            runCatching { ZoneGeoStore.reload(appContext) }

            dirty = false
            listeners.forEach { it.onSaved(true) }

            // 4) 🔔 Propagação para overlay/UI
            try {
                val intent = Intent("com.example.smartdriver.ZONES_UPDATED")
                appContext.sendBroadcast(intent)
            } catch (_: Throwable) { /* sem crash */ }
        } catch (_: Throwable) {
            listeners.forEach { it.onSaved(false) }
        }
    }

    private fun markDirty() {
        dirty = true
        listeners.forEach { it.onDirty() }
    }

    /**
     * Exporta as zonas atuais para GeoJSON (FeatureCollection)
     * compatível com ZoneGeoStore (Polygon/MultiPolygon + property "zoneType").
     *
     * - Usa apenas zonas ativas.
     * - Cada Zone -> Feature "Polygon" com 1 ring (ordem: [lon, lat]).
     * - Fecha o ring caso não esteja fechado.
     */
    private fun exportRuntimeGeoJson() {
        val runtimeFile = File(appContext.filesDir, "zones_runtime.geojson")
        val feats = StringBuilder()
        feats.append("""{"type":"FeatureCollection","features":[""")

        val listActive = zones.filter { it.active }
        listActive.forEachIndexed { idx, z ->
            val llPoints = GeoUtils.toLL(z.points)
            if (llPoints.isEmpty()) return@forEachIndexed

            // Garante fecho do ring
            val ring = ArrayList<Pair<Double,Double>>(llPoints.size + 1)
            llPoints.forEach { ring += (it.lon to it.lat) }
            if (ring.first() != ring.last()) ring += ring.first()

            val zoneType = when (z.type) {
                ZoneType.NO_GO      -> "NO_GO"
                ZoneType.PREFERRED  -> "PREFERRED"
                else                -> "NEUTRAL"
            }

            feats.append("""{"type":"Feature","properties":{"zoneType":"$zoneType","name":${jsonString(z.name)}},"geometry":{"type":"Polygon","coordinates":[[""")
            ring.forEachIndexed { i, (lon, lat) ->
                if (i > 0) feats.append(',')
                feats.append("[$lon,$lat]")
            }
            feats.append("""]]}""")
            if (idx < listActive.lastIndex) feats.append(',')
        }

        feats.append("]}")
        runtimeFile.writeText(feats.toString())
    }

    private fun jsonString(s: String?): String {
        if (s == null) return "null"
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
