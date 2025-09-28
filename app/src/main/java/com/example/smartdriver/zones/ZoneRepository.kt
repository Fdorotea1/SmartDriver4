package com.example.smartdriver.zones

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.osmdroid.util.GeoPoint
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Repo simples em JSON: filesDir/zones.json
 * Sem threads complexas: operações síncronas e leve notificação.
 */
object ZoneRepository {

    interface SaveListener {
        fun onDirty()
        fun onSaved(success: Boolean)
    }

    private val gson = Gson()
    private lateinit var file: File
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
            file = File(ctx.filesDir, "zones.json")
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

    fun list(): List<Zone> = zones.sortedBy { it.priority }

    fun size(): Int = zones.size

    fun add(z: Zone) {
        zones.add(z.copy(points = z.points.toMutableList()))
        markDirty()
        save()
    }

    fun update(z: Zone) {
        val idx = zones.indexOfFirst { it.id == z.id }
        if (idx >= 0) zones[idx] = z
        markDirty()
        save()
    }

    fun delete(id: String) {
        zones.removeAll { it.id == id }
        markDirty()
        save()
    }

    fun setAll(newList: List<Zone>) {
        zones.clear()
        zones.addAll(newList.map { it.copy(points = it.points.toMutableList()) })
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
            val arr = list().map { toDTO(it) }
            file.writeText(gson.toJson(arr))
            dirty = false
            listeners.forEach { it.onSaved(true) }
        } catch (_: Throwable) {
            listeners.forEach { it.onSaved(false) }
        }
    }

    private fun markDirty() {
        dirty = true
        listeners.forEach { it.onDirty() }
    }
}
