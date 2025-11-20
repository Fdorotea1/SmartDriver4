package com.example.smartdriver.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.smartdriver.utils.OfferData

/**
 * Mantém um temporizador que dispara depois de HOLD_MS se o rótulo atual for "MAPA"
 * e não tiver havido mudanças entretanto. Não usa long-press nem gestos.
 */
object MapAutoOpenOnLabel {
    private const val HOLD_MS_DEFAULT = 2000L

    private val handler = Handler(Looper.getMainLooper())
    private var lastLabel: String = ""
    private var lastChangeAt: Long = 0L
    private var posted = false

    private var ctx: Context? = null
    private var holdMs: Long = HOLD_MS_DEFAULT
    private var isMenuVisible: () -> Boolean = { true }
    private var currentOffer: () -> OfferData? = { null }

    fun init(
        appContext: Context,
        holdMs: Long = HOLD_MS_DEFAULT,
        isMenuVisible: () -> Boolean = { true },
        currentOffer: () -> OfferData? = { null }
    ) {
        this.ctx = appContext.applicationContext
        this.holdMs = holdMs
        this.isMenuVisible = isMenuVisible
        this.currentOffer = currentOffer
    }

    fun onLabelChanged(newLabel: String) {
        lastLabel = newLabel
        lastChangeAt = System.currentTimeMillis()
        cancel()
        if (newLabel.equals("MAPA", ignoreCase = true)) {
            posted = true
            handler.postDelayed(checkRunnable, holdMs)
        }
    }

    fun cancel() {
        if (posted) {
            handler.removeCallbacks(checkRunnable)
            posted = false
        }
    }

    private val checkRunnable = Runnable {
        if (!posted) return@Runnable
        val stillMap = lastLabel.equals("MAPA", ignoreCase = true)
        val stable = (System.currentTimeMillis() - lastChangeAt) >= holdMs
        if (stillMap && stable && isMenuVisible()) {
            openMap()
        }
        posted = false
    }

    private fun openMap() {
        val c = ctx ?: return
        val offer = currentOffer()
        OverlayIntents.openMapForOffer(c, offer, showMs = 12_000L)
    }
}
