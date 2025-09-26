package com.example.smartdriver.utils

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Limpeza automática de media antigos para controlar o espaço usado.
 * - Por defeito, limpa screenshots com mais de `RETENTION_DAYS_DEFAULT` dias.
 * - Apanha tanto os pendentes (PendingOfferStore) como as pastas comuns do app
 *   (heurística por nomes). Ajusta os ROOTS se necessário para o teu projeto.
 */
object MediaRetention {
    private const val TAG = "MediaRetention"
    private const val PREFS = "smartdriver_prefs"
    private const val KEY_RETENTION_DAYS = "media_retention_days"
    private const val RETENTION_DAYS_DEFAULT = 7L

    // Evita correr a limpeza demasiadas vezes (mín. 30 minutos entre execuções)
    private val lastRunElapsed = AtomicLong(0L)
    private const val MIN_INTERVAL_MS = 30L * 60L * 1000L

    fun setRetentionDays(ctx: Context, days: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_RETENTION_DAYS, max(1L, days))
            .apply()
    }

    fun getRetentionDays(ctx: Context): Long {
        return max(1L, ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_RETENTION_DAYS, RETENTION_DAYS_DEFAULT))
    }

    fun cleanupOldMedia(ctx: Context) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val last = lastRunElapsed.get()
        if (last != 0L && nowElapsed - last < MIN_INTERVAL_MS) return
        lastRunElapsed.set(nowElapsed)

        val days = getRetentionDays(ctx)
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L

        var deleted = 0
        var scanned = 0

        fun tryDelete(f: File) {
            if (!f.exists()) return
            if (f.isDirectory) {
                f.listFiles()?.forEach { tryDelete(it) }
                if (f.listFiles()?.isEmpty() == true) f.delete()
                return
            }
            scanned++
            if (f.lastModified() < cutoff) {
                if (f.delete()) deleted++
            }
        }

        try {
            // 1) Diretório de pendentes
            val pendDir = File(ctx.cacheDir, "offer_pending")
            tryDelete(pendDir)

            // 2) Diretórios de media guardados (heurística — ajusta se necessário)
            val candidates = arrayOf(
                File(ctx.filesDir, "SmartDriverTrips"),
                File(ctx.filesDir, "SmartDriverMedia"),
                ctx.getExternalFilesDir(null)?.let { File(it, "SmartDriverTrips") },
                ctx.getExternalFilesDir(null)?.let { File(it, "SmartDriverMedia") },
                ctx.getExternalFilesDir("Pictures")?.let { File(it, "SmartDriver") },
                ctx.getExternalFilesDir("Pictures")?.let { File(it, "SmartDriverTrips") }
            ).filterNotNull().toTypedArray()
            candidates.forEach { tryDelete(it) }
        } catch (e: Exception) {
            Log.w(TAG, "cleanup: erro: ${e.message}")
        }

        Log.i(TAG, "cleanup: retention=${days}d, scanned=$scanned, deleted=$deleted")
    }
}
