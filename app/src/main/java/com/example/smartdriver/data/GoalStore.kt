package com.example.smartdriver.data

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min

class GoalStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Meta diária (slider) ---
    fun getGoalEuro(): Int {
        return prefs.getInt(KEY_GOAL_EUR, DEFAULT_GOAL_EUR)
    }

    fun setGoalEuro(value: Int) {
        val v = min(max(0, value), 500)
        prefs.edit().putInt(KEY_GOAL_EUR, v).apply()
    }

    // --- “Já faturado hoje” (valor base do dia) ---
    fun getTodayInvoicedEuro(): Double {
        val key = keyForToday(KEY_TODAY_INVOICED_PREFIX)
        return prefs.getFloat(key, 0f).toDouble()
    }

    fun setTodayInvoicedEuro(value: Double) {
        val key = keyForToday(KEY_TODAY_INVOICED_PREFIX)
        prefs.edit().putFloat(key, value.toFloat()).apply()
    }

    // Opcional: reset do valor base de hoje
    fun clearTodayInvoiced() {
        val key = keyForToday(KEY_TODAY_INVOICED_PREFIX)
        prefs.edit().remove(key).apply()
    }

    // ---------- helpers ----------
    private fun keyForToday(prefix: String): String {
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date())
        return "${prefix}_$today"
    }

    companion object {
        private const val PREFS_NAME = "SmartDriverGoalPrefs"
        private const val KEY_GOAL_EUR = "key_goal_eur"
        private const val KEY_TODAY_INVOICED_PREFIX = "key_today_invoiced"
        private const val DEFAULT_GOAL_EUR = 130
    }
}
