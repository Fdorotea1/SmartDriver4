package com.example.smartdriver.data

import android.content.Context

object TelemetryStore {
    private const val PREFS = "SmartDriverTelemetry"
    private const val KEY_EARNINGS = "telemetry_earnings_rawbits"
    private const val KEY_AVGPH   = "telemetry_avgph_rawbits"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun write(ctx: Context, earningsEuro: Double, avgPerHour: Double) {
        sp(ctx).edit()
            .putLong(KEY_EARNINGS, java.lang.Double.doubleToRawLongBits(earningsEuro))
            .putLong(KEY_AVGPH,    java.lang.Double.doubleToRawLongBits(avgPerHour))
            .apply()
    }

    fun read(ctx: Context): Pair<Double, Double> {
        val s = sp(ctx)
        val e = java.lang.Double.longBitsToDouble(s.getLong(KEY_EARNINGS, 0L))
        val a = java.lang.Double.longBitsToDouble(s.getLong(KEY_AVGPH,    0L))
        return e to a
    }
}
