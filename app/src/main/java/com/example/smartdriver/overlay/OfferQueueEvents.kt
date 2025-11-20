package com.example.smartdriver.overlay

import android.content.Context
import android.content.Intent

object OfferQueueEvents {
    const val ACTION_QUEUED_OFFERS_CHANGED = "com.example.smartdriver.ACTION_QUEUED_OFFER_CHANGED"
    const val EXTRA_QUEUED_COUNT = "queued_count"

    fun notifyQueuedOffersChanged(context: Context, count: Int) {
        val intent = Intent(ACTION_QUEUED_OFFERS_CHANGED).apply {
            putExtra(EXTRA_QUEUED_COUNT, count)
        }
        context.sendBroadcast(intent)
    }
}
