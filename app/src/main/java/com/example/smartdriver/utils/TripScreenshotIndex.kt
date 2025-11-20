package com.example.smartdriver.utils

import android.content.Context
import android.util.Log
import com.example.smartdriver.overlay.OverlayService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.*
import kotlin.math.abs

object TripScreenshotIndex {
    private const val TAG = "TripScreenshotIndex"
    private const val KEY_INDEX = "key_trip_screenshots_json_v1"
    private const val MAX_RECORDS = 800 // limpeza básica para não crescer infinitamente

    data class ShotRecord(
        val path: String,
        val ts: Long,
        val tripId: String? = null
    )

    private val gson = Gson()
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(OverlayService.HISTORY_PREFS_NAME, Context.MODE_PRIVATE)

    private fun load(ctx: Context): MutableList<ShotRecord> {
        val json = prefs(ctx).getString(KEY_INDEX, "[]")
        return try {
            val type = object : TypeToken<MutableList<ShotRecord>>() {}.type
            gson.fromJson<MutableList<ShotRecord>>(json, type) ?: mutableListOf()
        } catch (_: Throwable) { mutableListOf() }
    }

    private fun save(ctx: Context, list: List<ShotRecord>) {
        val pr = prefs(ctx).edit()
        pr.putString(KEY_INDEX, gson.toJson(list))
        pr.apply()
    }

    /** Adiciona registo e limpa entradas antigas/inexistentes. */
    fun add(ctx: Context, path: String, timestampMillis: Long, tripId: String?) {
        val list = load(ctx)

        // drop ficheiros que deixaram de existir
        val filtered = list.filter { File(it.path).exists() }.toMutableList()

        filtered.add(ShotRecord(path, timestampMillis, tripId))
        // mantém ordenado por ts ascendente e corta para ~MAX_RECORDS (mais recentes no fim)
        val trimmed = filtered.sortedBy { it.ts }.takeLast(MAX_RECORDS)
        save(ctx, trimmed)
    }

    /** Devolve o caminho da screenshot cujo timestamp está mais próximo do startMs, dentro de windowMs. */
    fun findNearestForStart(ctx: Context, startMs: Long, windowMs: Long = 15_000L): String? {
        val list = load(ctx)
        if (list.isEmpty()) return null
        var best: ShotRecord? = null
        var bestDelta = Long.MAX_VALUE
        for (r in list) {
            val d = abs(r.ts - startMs)
            if (d <= windowMs && d < bestDelta) {
                best = r
                bestDelta = d
            }
        }
        return best?.path?.takeIf { File(it).exists() }
    }

    fun hasNear(ctx: Context, startMs: Long, windowMs: Long = 15_000L): Boolean {
        return findNearestForStart(ctx, startMs, windowMs) != null
    }

    fun getAllForTripId(ctx: Context, tripId: String): List<String> {
        return load(ctx)
            .asSequence()
            .filter { it.tripId == tripId }
            .map { it.path }
            .filter { File(it).exists() }
            .toList()
    }
}
