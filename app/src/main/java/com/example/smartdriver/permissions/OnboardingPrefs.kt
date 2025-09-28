package com.example.smartdriver.permissions

import android.content.Context
import android.content.SharedPreferences

object OnboardingPrefs {
    private const val PREFS = "smartdriver_prefs"
    private const val KEY_DONE = "onboarding_done"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOnboardingDone(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DONE, false)

    fun setOnboardingDone(ctx: Context, done: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DONE, done).apply()
    }
}
