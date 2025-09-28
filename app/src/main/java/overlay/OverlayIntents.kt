package com.example.smartdriver.overlay

import android.content.Context
import android.content.Intent
import com.example.smartdriver.map.MapPreviewActivity
import com.example.smartdriver.utils.OfferData

object OverlayIntents {
    fun openMapForOffer(
        context: Context,
        offer: OfferData?,
        showMs: Long = 12_000L,
        fadeMs: Long = 400L
    ) {
        val pickupAddr = offer?.moradaRecolha?.takeIf { it.isNotBlank() }
        val destAddr   = offer?.moradaDestino?.takeIf { it.isNotBlank() }

        // 1) Atualiza rota no mapa (broadcast)
        runCatching {
            val upd = Intent(MapPreviewActivity.ACTION_UPDATE_MAP).apply {
                setPackage(context.packageName)
                pickupAddr?.let { putExtra(MapPreviewActivity.EXTRA_PICKUP_ADDRESS, it) }
                destAddr?.let { putExtra(MapPreviewActivity.EXTRA_DEST_ADDRESS, it) }
            }
            context.sendBroadcast(upd)
        }

        // 2) Garante activity visível (singleTop evita duplicados)
        runCatching {
            val open = Intent(context, MapPreviewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                pickupAddr?.let { putExtra(MapPreviewActivity.EXTRA_PICKUP_ADDRESS, it) }
                destAddr?.let { putExtra(MapPreviewActivity.EXTRA_DEST_ADDRESS, it) }
            }
            context.startActivity(open)
        }

        // 3) Pede SHOW com auto-hide
        runCatching {
            val show = Intent(MapPreviewActivity.ACTION_SEMAFORO_SHOW_MAP).apply {
                setPackage(context.packageName)
                putExtra(MapPreviewActivity.EXTRA_AUTO_HIDE_MS, showMs)
                putExtra(MapPreviewActivity.EXTRA_FADE_MS, fadeMs)
            }
            context.sendBroadcast(show)
        }
    }
}
