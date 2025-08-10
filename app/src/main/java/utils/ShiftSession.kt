package com.example.smartdriver.utils

import android.content.Context
import android.content.SharedPreferences
import com.example.smartdriver.R
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.round

/**
 * Gestão de turno (Shift) com:
 * - Persistência estável (valores monetários guardados em cêntimos, como Long)
 * - Cálculo de média por hora numérico (getter dedicado)
 * - Formatações robustas para UI
 */
class ShiftSession(private val context: Context) {

    companion object {
        private const val SHIFT_STATE_PREFS_NAME = "SmartDriverShiftStatePrefs"

        private const val KEY_SHIFT_ACTIVE = "shift_active"
        private const val KEY_SHIFT_PAUSED = "shift_paused"
        private const val KEY_SHIFT_START_TIME = "shift_start_time"
        private const val KEY_SHIFT_LAST_PAUSE_OR_RESUME_TIME = "shift_last_pause_resume_time"
        private const val KEY_SHIFT_ACCUMULATED_WORKED_TIME = "shift_accumulated_worked_time"

        // Monetários em cêntimos para não perder precisão
        private const val KEY_SHIFT_TOTAL_EARNINGS_CENTS = "shift_total_earnings_cents"
        private const val KEY_SHIFT_TARGET_EARNINGS_CENTS = "shift_target_earnings_cents"
    }

    var isActive: Boolean = false
        private set

    var isPaused: Boolean = false
        private set

    /** Ganhos totais em euros (conveniente para UI); persistência feita em cêntimos */
    var totalEarnings: Double = 0.0
        private set

    /** Objetivo do turno em euros; persistência feita em cêntimos */
    var targetEarnings: Double = 0.0
        private set

    /** Tempo efetivamente trabalhado (ms), incluindo período atual se não estiver pausado */
    private val workedTimeMillis: Long
        get() = calculateCurrentWorkedTimeMillis()

    private var startTimeMillis: Long = 0L
    private var lastPauseOrResumeTimeMillis: Long = 0L
    private var accumulatedWorkedTimeMillis: Long = 0L

    private val prefs: SharedPreferences =
        context.getSharedPreferences(SHIFT_STATE_PREFS_NAME, Context.MODE_PRIVATE)

    private val averageDecimalFormat = DecimalFormat("0.0")

    init {
        loadState()
    }

    fun start(target: Double) {
        if (isActive || target <= 0.0) return
        isActive = true
        isPaused = false
        targetEarnings = sanitizeMoney(target)
        startTimeMillis = System.currentTimeMillis()
        lastPauseOrResumeTimeMillis = startTimeMillis
        accumulatedWorkedTimeMillis = 0L
        totalEarnings = 0.0
        saveState()
    }

    fun togglePauseResume() {
        if (!isActive) return
        val now = System.currentTimeMillis()
        if (isPaused) {
            isPaused = false
            lastPauseOrResumeTimeMillis = now
        } else {
            isPaused = true
            val workedMillis = now - lastPauseOrResumeTimeMillis
            if (workedMillis > 0) accumulatedWorkedTimeMillis += workedMillis
            lastPauseOrResumeTimeMillis = now
        }
        saveState()
    }

    fun end(saveSummary: Boolean) {
        if (!isActive) return
        isActive = false
        isPaused = false
        if (saveSummary) {
            saveState()
        } else {
            prefs.edit().clear().apply()
        }
        totalEarnings = 0.0
        accumulatedWorkedTimeMillis = 0L
        targetEarnings = 0.0
        startTimeMillis = 0L
        lastPauseOrResumeTimeMillis = 0L
    }

    fun addTripEarnings(earnings: Double) {
        if (!isActive || earnings <= 0.0) return
        totalEarnings = sanitizeMoney(totalEarnings + earnings)
        saveState()
    }

    fun onServiceDestroyed() {
        if (isActive && !isPaused) {
            val now = System.currentTimeMillis()
            val workedMillis = now - lastPauseOrResumeTimeMillis
            if (workedMillis > 0) accumulatedWorkedTimeMillis += workedMillis
            lastPauseOrResumeTimeMillis = now
            isPaused = true
            saveState()
        }
    }

    fun getFormattedWorkedTime(): String = formatHhMm(workedTimeMillis)

    /**
     * Valor numérico da média €/h, ou:
     *  - null se ainda insuficiente para cálculo estável e já há ganhos
     *  - 0.0 se não há dados
     */
    fun getAveragePerHourValue(workedTime: Long = workedTimeMillis): Double? {
        if (!isActive) return null
        if (workedTime < 5_000L) return if (totalEarnings > 0.0) null else 0.0
        val hours = workedTime / 3_600_000.0
        if (hours <= 0.0) return if (totalEarnings > 0.0) null else 0.0
        return totalEarnings / hours
    }

    fun getFormattedAveragePerHour(workedTime: Long = workedTimeMillis): String {
        val avg = getAveragePerHourValue(workedTime)
        return when {
            avg != null && avg.isFinite() -> "${averageDecimalFormat.format(avg)} €/h"
            isActive && totalEarnings > 0.0 && workedTime < 5_000L ->
                context.getString(R.string.shift_calculating_average)
            isActive && avg == 0.0 -> "0.0 €/h"
            else -> "-- €/h"
        }
    }

    fun getFormattedTimeToTarget(): String {
        if (!isActive) return "--:--:--"
        if (targetEarnings <= 0.0) return context.getString(R.string.shift_target_not_set)

        val earningsToGo = targetEarnings - totalEarnings
        if (earningsToGo <= 0.0) return context.getString(R.string.shift_target_reached)

        val avg = getAveragePerHourValue()
        return if (avg != null && avg > 0.0 && avg.isFinite()) {
            val millis = ((earningsToGo / avg) * 3_600_000.0).toLong()
            if (millis < 0) context.getString(R.string.shift_target_reached) else formatHhMmSs(millis)
        } else context.getString(R.string.shift_calculating_time_to_target)
    }

    // ---------- Persistência ----------

    private fun loadState() {
        isActive = prefs.getBoolean(KEY_SHIFT_ACTIVE, false)
        isPaused = prefs.getBoolean(KEY_SHIFT_PAUSED, false)
        startTimeMillis = prefs.getLong(KEY_SHIFT_START_TIME, 0L)
        lastPauseOrResumeTimeMillis = prefs.getLong(KEY_SHIFT_LAST_PAUSE_OR_RESUME_TIME, 0L)
        accumulatedWorkedTimeMillis = prefs.getLong(KEY_SHIFT_ACCUMULATED_WORKED_TIME, 0L)

        val totalCents = prefs.getLong(KEY_SHIFT_TOTAL_EARNINGS_CENTS, 0L)
        val targetCents = prefs.getLong(KEY_SHIFT_TARGET_EARNINGS_CENTS, 0L)
        totalEarnings = centsToEuros(totalCents)
        targetEarnings = centsToEuros(targetCents)

        // Se o serviço morrer em ativo, volta pausado para consistência
        if (isActive && !isPaused) {
            isPaused = true
            lastPauseOrResumeTimeMillis = System.currentTimeMillis()
            saveState()
        }
    }

    private fun saveState() {
        prefs.edit().apply {
            putBoolean(KEY_SHIFT_ACTIVE, isActive)
            putBoolean(KEY_SHIFT_PAUSED, isPaused)
            putLong(KEY_SHIFT_START_TIME, startTimeMillis)
            putLong(KEY_SHIFT_LAST_PAUSE_OR_RESUME_TIME, lastPauseOrResumeTimeMillis)
            putLong(KEY_SHIFT_ACCUMULATED_WORKED_TIME, accumulatedWorkedTimeMillis)
            putLong(KEY_SHIFT_TOTAL_EARNINGS_CENTS, eurosToCents(totalEarnings))
            putLong(KEY_SHIFT_TARGET_EARNINGS_CENTS, eurosToCents(targetEarnings))
            apply()
        }
    }

    // ---------- Helpers ----------

    private fun calculateCurrentWorkedTimeMillis(): Long {
        if (!isActive) return 0L
        var current = accumulatedWorkedTimeMillis
        if (!isPaused) {
            val sinceResume = System.currentTimeMillis() - lastPauseOrResumeTimeMillis
            if (sinceResume > 0) current += sinceResume
        }
        return max(0L, current)
    }

    private fun formatHhMm(millis: Long): String {
        if (millis < 0) return "00:00"
        val h = TimeUnit.MILLISECONDS.toHours(millis)
        val m = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        return String.format("%02d:%02d", h, m)
    }

    private fun formatHhMmSs(millis: Long): String {
        if (millis < 0) return "00:00:00"
        val h = TimeUnit.MILLISECONDS.toHours(millis)
        val m = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun eurosToCents(value: Double): Long = round(value * 100.0).toLong()
    private fun centsToEuros(cents: Long): Double = cents / 100.0

    private fun sanitizeMoney(value: Double): Double {
        // Normaliza para 2 casas decimais (sem acumular erros binários)
        return centsToEuros(eurosToCents(value))
    }
}
