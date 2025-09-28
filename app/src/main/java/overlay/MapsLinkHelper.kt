package com.example.smartdriver.overlay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import android.widget.Toast

/**
 * Torna um TextView "clicável" para abrir a morada no Google Maps.
 * - Single tap: abre o Maps (ou browser como fallback)
 * - Long press: copia a morada para o clipboard
 */
object MapsLinkHelper {

    fun bindAddressLink(tv: TextView, address: String?) {
        tv.isClickable = true
        tv.isFocusable = true
        tv.isLongClickable = true

        // estilo visual mínimo de "link"
        tv.paint.isUnderlineText = address?.isNotBlank() == true
        tv.alpha = if (address.isNullOrBlank()) 0.6f else 1f

        tv.setOnClickListener(null)
        tv.setOnLongClickListener(null)

        if (address.isNullOrBlank()) return

        tv.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            openAddressInMaps(v.context, address)
        }

        tv.setOnLongClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            copyToClipboard(v.context, address)
            Toast.makeText(v.context, "Morada copiada", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun openAddressInMaps(context: Context, address: String) {
        val q = Uri.encode(address)
        // Tenta abrir app Google Maps primeiro
        val mapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")).apply {
            setPackage("com.google.android.apps.maps")
        }
        try {
            context.startActivity(mapsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // fallback: qualquer app que trate https://maps.google.com/?q=
        }

        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$q"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(webIntent)
        } catch (_: Exception) {
            Toast.makeText(context, "Não foi possível abrir a morada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("address", text))
    }
}
