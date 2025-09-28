package com.example.smartdriver.zones

import org.osmdroid.util.GeoPoint
import java.util.UUID

enum class ZoneType { NO_GO, SOFT_AVOID, PREFERRED }

data class ZoneStyle(
    val fillColor: Int,     // Android color ARGB (Int)
    val strokeColor: Int,   // Android color ARGB (Int)
    val strokeWidthPx: Float
)

data class Zone(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Zona",
    var type: ZoneType = ZoneType.NO_GO,
    var points: MutableList<GeoPoint> = mutableListOf(),
    var active: Boolean = true,
    var priority: Int = 0,
    var style: ZoneStyle? = null,
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun touch() { updatedAt = System.currentTimeMillis() }
}

object ZoneDefaults {
    // ARGB (usar .toInt() para garantir Int e não Long)
    private val RED_20   = 0x33FF0000.toInt() // ~20% alpha
    private val RED_100  = 0xFFFF0000.toInt()

    private val YELLOW_25  = 0x40FFC107.toInt()
    private val YELLOW_100 = 0xFFFFC107.toInt()

    private val GREEN_25 = 0x4032CD32.toInt()
    private val GREEN_100 = 0xFF32CD32.toInt()

    fun styleFor(type: ZoneType): ZoneStyle = when (type) {
        ZoneType.NO_GO -> ZoneStyle(
            fillColor = RED_20,
            strokeColor = RED_100,
            strokeWidthPx = 3f
        )
        ZoneType.SOFT_AVOID -> ZoneStyle(
            fillColor = YELLOW_25,
            strokeColor = YELLOW_100,
            strokeWidthPx = 2f
        )
        ZoneType.PREFERRED -> ZoneStyle(
            fillColor = GREEN_25,
            strokeColor = GREEN_100,
            strokeWidthPx = 2f
        )
    }
}
