package com.example.smartdriver

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.smartdriver.utils.BorderRating
import com.example.smartdriver.utils.TripHistoryEntry
import java.net.URLEncoder
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HistoryAdapter(
    private var historyList: List<TripHistoryEntry>,
    private val onItemClick: ((TripHistoryEntry, Int) -> Unit)? = null,
    private val onItemLongClick: ((TripHistoryEntry, Int) -> Unit)? = null,
    private val onSelectionChanged: ((selectedCount: Int) -> Unit)? = null
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    private val dateTimeFormatter = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    private val currencyPT: NumberFormat =
        NumberFormat.getCurrencyInstance(Locale("pt", "PT")).apply {
            minimumFractionDigits = 2; maximumFractionDigits = 2
        }
    private val placeholder = "--"

    // --------- Multi-seleção ----------
    private var selectionMode: Boolean = false
    private val selectedKeys = linkedSetOf<Long>() // startTimeMillis como chave estável

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        historyList.getOrNull(position)?.startTimeMillis ?: RecyclerView.NO_ID

    fun isInSelectionMode(): Boolean = selectionMode

    fun getSelectedEntries(): List<TripHistoryEntry> {
        if (selectedKeys.isEmpty()) return emptyList()
        val keys = selectedKeys.toSet()
        return historyList.filter { it.startTimeMillis in keys }
    }

    fun clearSelection() {
        if (selectedKeys.isEmpty()) {
            selectionMode = false
            onSelectionChanged?.invoke(0)
            return
        }
        val affectedPositions = mutableListOf<Int>()
        val keySet = selectedKeys.toSet()
        historyList.forEachIndexed { index, e ->
            if (e.startTimeMillis in keySet) affectedPositions += index
        }
        selectedKeys.clear()
        selectionMode = false
        affectedPositions.forEach { pos ->
            if (pos in 0 until itemCount) notifyItemChanged(pos)
        }
        onSelectionChanged?.invoke(0)
    }

    private fun toggleSelection(key: Long) {
        if (!selectionMode) selectionMode = true
        if (!selectedKeys.add(key)) selectedKeys.remove(key)
        onSelectionChanged?.invoke(selectedKeys.size)
        notifyDataSetChanged()
    }
    // -----------------------------------

    inner class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val startTimeTextView: TextView = itemView.findViewById(R.id.textViewStartTime)
        val durationTextView: TextView = itemView.findViewById(R.id.textViewDuration)
        val offerValueTextView: TextView = itemView.findViewById(R.id.textViewOfferValue)
        val finalVphTextView: TextView = itemView.findViewById(R.id.textViewFinalVph)
        val initialVpkTextView: TextView = itemView.findViewById(R.id.textViewInitialVpk)
        val initialDistanceTextView: TextView = itemView.findViewById(R.id.textViewInitialDistance)
        val serviceTypeTextView: TextView = itemView.findViewById(R.id.textViewServiceType)
        val ratingIndicatorView: View = itemView.findViewById(R.id.view_rating_indicator)
        val pickupAddressTextView: TextView = itemView.findViewById(R.id.textViewPickupAddress)
        val dropoffAddressTextView: TextView = itemView.findViewById(R.id.textViewDropoffAddress)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val entry = historyList[position]
                if (selectionMode) {
                    toggleSelection(entry.startTimeMillis)
                } else {
                    onItemClick?.invoke(entry, position)
                }
            }
            itemView.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnLongClickListener true
                val entry = historyList[position]
                toggleSelection(entry.startTimeMillis)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val entry = historyList[position]

        holder.startTimeTextView.text =
            "Início: ${dateTimeFormatter.format(Date(entry.startTimeMillis))}"

        val durationMinutes = TimeUnit.SECONDS.toMinutes(entry.durationSeconds)
        val durationSecondsPart = entry.durationSeconds % 60
        holder.durationTextView.text = "Duração: ${durationMinutes}m ${durationSecondsPart}s"

        val effective = (entry.effectiveValue ?: entry.offerValue)?.takeIf { it > 0.0 }
        holder.offerValueTextView.text =
            "Valor: ${effective?.let { currencyPT.format(it) } ?: placeholder}"

        val durHours = if (entry.durationSeconds > 0L) entry.durationSeconds / 3600.0 else 0.0
        val vphRealtime = if (durHours > 0)
            (entry.effectiveValue ?: entry.offerValue)?.let { it / durHours }
        else null
        holder.finalVphTextView.text =
            "€/h Real: ${vphRealtime?.let { String.format(Locale.US, "%.1f", it) } ?: placeholder}"

        holder.initialVpkTextView.text =
            "€/km Ini: ${entry.initialVpk?.let { String.format(Locale.US, "%.2f", it) } ?: placeholder}"

        holder.initialDistanceTextView.text =
            "Dist: ${entry.initialDistanceKm?.let { String.format(Locale.US, "%.2f km", it) } ?: placeholder}"

        holder.serviceTypeTextView.text =
            entry.serviceType?.takeIf { it.isNotEmpty() } ?: placeholder

        // Moradas com link bonito para Maps
        applyAddressLink(
            holder.pickupAddressTextView,
            label = "Origem: ",
            address = entry.pickupAddress
        )
        applyAddressLink(
            holder.dropoffAddressTextView,
            label = "Destino: ",
            address = entry.dropoffAddress
        )

        // Cor base pela rating + “vitamina” para ficar mais vivo
        val indicatorColorResId = getIndicatorColorResId(entry.originalBorderRating)
        val base = ContextCompat.getColor(holder.itemView.context, indicatorColorResId)
        val vivid = makeVivid(base, satFactor = 1.35f, minLightness = 0.42f)
        holder.ratingIndicatorView.setBackgroundColor(vivid)

        val isSelected = selectedKeys.contains(entry.startTimeMillis)
        holder.itemView.alpha = if (isSelected) 0.6f else 1.0f
    }

    override fun getItemCount() = historyList.size

    fun updateData(newHistoryList: List<TripHistoryEntry>) {
        historyList = newHistoryList
        selectedKeys.retainAll(historyList.map { it.startTimeMillis }.toSet())
        if (selectedKeys.isEmpty()) selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedKeys.size)
    }

    private fun getIndicatorColorResId(rating: BorderRating?): Int {
        return when (rating ?: BorderRating.GRAY) {
            BorderRating.GREEN -> R.color.history_indicator_good
            BorderRating.YELLOW -> R.color.history_indicator_medium
            BorderRating.RED -> R.color.history_indicator_poor
            BorderRating.GRAY -> R.color.history_indicator_unknown
        }
    }

    /** Aumenta saturação e garante leveza mínima para não ficar “desbatido” em fundo escuro. */
    private fun makeVivid(color: Int, satFactor: Float, minLightness: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = (hsl[1] * satFactor).coerceIn(0f, 1f)        // saturação
        hsl[2] = maxOf(hsl[2], minLightness).coerceIn(0f, 1f) // leveza mínima
        return ColorUtils.HSLToColor(hsl)
    }

    // ---------------- Links para Google Maps ----------------

    private fun applyAddressLink(textView: TextView, label: String, address: String?) {
        val addr = address?.trim().takeUnless { it.isNullOrEmpty() }
        if (addr == null) {
            textView.text = "$label—"
            textView.movementMethod = null
            textView.isClickable = false
            ViewCompat.setTooltipText(textView, null)
            return
        }

        val full = "$label$addr"
        val spannable = SpannableString(full)
        val start = label.length
        val end = full.length

        // estilo: negrito + cor de link do tema, sem sublinhado (fica mais “clean”)
        spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val clickable = object : ClickableSpan() {
            override fun onClick(widget: View) {
                openInGoogleMaps(widget.context, addr)
            }
            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = false
                // tenta usar a cor de link do tema
                val linkColor = resolveThemeAttrColor(textView, android.R.attr.textColorLink)
                ds.color = linkColor
            }
        }
        spannable.setSpan(clickable, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        textView.text = spannable
        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.highlightColor = android.graphics.Color.TRANSPARENT
        ViewCompat.setTooltipText(textView, "Abrir no Google Maps")
        textView.isClickable = true
    }

    private fun resolveThemeAttrColor(view: View, attr: Int): Int {
        val tv = android.util.TypedValue()
        val theme = view.context.theme
        val ok = theme.resolveAttribute(attr, tv, true)
        return if (ok && tv.resourceId != 0) {
            ContextCompat.getColor(view.context, tv.resourceId)
        } else {
            tv.data
        }
    }

    private fun openInGoogleMaps(context: Context, query: String) {
        val encoded = try { URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { query }
        val geoUri = Uri.parse("geo:0,0?q=$encoded")
        val maps = Intent(Intent.ACTION_VIEW, geoUri).apply {
            // tenta abrir diretamente no Google Maps
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            if (maps.resolveActivity(context.packageManager) != null) {
                context.startActivity(maps); return
            }
        } catch (_: Exception) {}

        // fallback: browser com Maps
        val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$encoded")
        val web = Intent(Intent.ACTION_VIEW, webUri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { context.startActivity(web) } catch (_: Exception) { /* se nem browser houver, ignoramos */ }
    }
}
