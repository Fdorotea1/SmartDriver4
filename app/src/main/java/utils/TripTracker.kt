package com.example.smartdriver.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.smartdriver.overlay.OverlayService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.max

class TripTracker(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onTripUpdate(elapsedSeconds: Long, currentVph: Double?, rating: IndividualRating)
        fun onTripFinished(historyEntry: TripHistoryEntry)
        fun onTripDiscarded()
    }

    companion object {
        private const val TAG = "TripTracker"
        private const val TRACKING_UPDATE_INTERVAL_MS = 1000L
        private const val MIN_TRACKING_TIME_SEC = 1L
    }

    var isTracking: Boolean = false
        private set

    private var startTimeMs: Long = 0L
    private var offerData: OfferData? = null
    private var initialVph: Double? = null
    private var initialVpk: Double? = null
    private var offerValue: Double = 0.0
    private var initialKmRating: IndividualRating = IndividualRating.UNKNOWN
    private var combinedBorderRating: BorderRating = BorderRating.GRAY

    private val updateHandler = Handler(Looper.getMainLooper())
    private lateinit var updateRunnable: Runnable
    private val gson = Gson()

    init {
        setupRunnable()
    }

    private fun setupRunnable() {
        updateRunnable = Runnable {
            if (isTracking) {
                val elapsedSeconds =
                    max(MIN_TRACKING_TIME_SEC, (System.currentTimeMillis() - startTimeMs) / 1000L)
                val elapsedHours = elapsedSeconds / 3600.0
                val currentVph =
                    if (offerValue > 0 && elapsedHours > 0) offerValue / elapsedHours else null
                listener.onTripUpdate(elapsedSeconds, currentVph, IndividualRating.UNKNOWN)
                updateHandler.postDelayed(updateRunnable, TRACKING_UPDATE_INTERVAL_MS)
            }
        }
    }

    fun start(offerData: OfferData, evalResult: EvaluationResult) {
        if (isTracking) return
        isTracking = true
        startTimeMs = System.currentTimeMillis()
        this.offerData = offerData
        this.offerValue = offerData.value.replace(",", ".").toDoubleOrNull() ?: 0.0
        this.initialVph = offerData.calculateValuePerHour()
        this.initialVpk = offerData.calculateProfitability()
        this.initialKmRating = evalResult.kmRating
        this.combinedBorderRating = evalResult.combinedBorderRating
        updateHandler.removeCallbacks(updateRunnable)
        updateHandler.post(updateRunnable)
    }

    fun stopAndSave() {
        if (!isTracking) return
        val endTime = System.currentTimeMillis()
        stopTimer()
        val elapsed = max(MIN_TRACKING_TIME_SEC, (endTime - startTimeMs) / 1000L)
        val finalVph = if (offerValue > 0) offerValue / (elapsed / 3600.0) else null

        val entry = TripHistoryEntry(
            startTimeMillis = startTimeMs,
            endTimeMillis = endTime,
            durationSeconds = elapsed,
            offerValue = offerValue.takeIf { it > 0 },
            initialVph = initialVph,
            finalVph = finalVph,
            initialVpk = initialVpk,
            initialDistanceKm = offerData?.calculateTotalDistance()?.takeIf { it > 0 },
            initialDurationMinutes = offerData?.calculateTotalTimeMinutes()?.takeIf { it > 0 },
            serviceType = offerData?.serviceType?.takeIf { it.isNotEmpty() },
            originalBorderRating = this.combinedBorderRating
        )
        saveHistoryEntry(entry)
        resetState()
        listener.onTripFinished(entry)
    }

    fun discard() {
        if (!isTracking) return
        stopTimer()
        resetState()
        listener.onTripDiscarded()
    }

    private fun stopTimer() {
        updateHandler.removeCallbacks(updateRunnable)
    }

    private fun resetState() {
        isTracking = false
        startTimeMs = 0L
        offerData = null
        offerValue = 0.0
    }

    private fun saveHistoryEntry(newEntry: TripHistoryEntry) {
        val prefs = context.getSharedPreferences(OverlayService.HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val json = prefs.getString(OverlayService.KEY_TRIP_HISTORY, "[]")
            val list: MutableList<String> =
                gson.fromJson(json, object : TypeToken<MutableList<String>>() {}.type) ?: mutableListOf()
            list.add(gson.toJson(newEntry))
            prefs.edit().putString(OverlayService.KEY_TRIP_HISTORY, gson.toJson(list)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar histórico: ${e.message}", e)
        }
    }
}
