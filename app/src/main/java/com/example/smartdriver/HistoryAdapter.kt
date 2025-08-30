package com.example.smartdriver

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.smartdriver.utils.BorderRating
import com.example.smartdriver.utils.TripHistoryEntry
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.concurrent.TimeUnit

class HistoryAdapter(
    private var historyList: List<TripHistoryEntry>,
    private val onItemClick: ((TripHistoryEntry, Int) -> Unit)? = null,
    private val onItemLongClick: ((TripHistoryEntry, Int) -> Unit)? = null
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    private val dateTimeFormatter = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    private val currencyPT: NumberFormat =
        NumberFormat.getCurrencyInstance(Locale("pt", "PT")).apply {
            minimumFractionDigits = 2; maximumFractionDigits = 2
        }
    private val placeholder = "--"

    inner class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val startTimeTextView: TextView = itemView.findViewById(R.id.textViewStartTime)
        val durationTextView: TextView = itemView.findViewById(R.id.textViewDuration)
        val offerValueTextView: TextView = itemView.findViewById(R.id.textViewOfferValue)
        val finalVphTextView: TextView = itemView.findViewById(R.id.textViewFinalVph)
        val initialVpkTextView: TextView = itemView.findViewById(R.id.textViewInitialVpk)
        val initialDistanceTextView: TextView = itemView.findViewById(R.id.textViewInitialDistance)
        val serviceTypeTextView: TextView = itemView.findViewById(R.id.textViewServiceType)
        val ratingIndicatorView: View = itemView.findViewById(R.id.view_rating_indicator)

        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick?.invoke(historyList[position], position)
                }
            }
            itemView.setOnLongClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClick?.invoke(historyList[position], position)
                }
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

        // €/h Real — valor (efetivo ou oferta) / duração (horas)
        val durHours = if (entry.durationSeconds > 0L) entry.durationSeconds / 3600.0 else 0.0
        val vphRealtime = if (durHours > 0)
            (entry.effectiveValue ?: entry.offerValue)?.let { it / durHours }
        else null
        holder.finalVphTextView.text =
            "€/h Real: ${vphRealtime?.let { String.format(Locale.US, "%.1f", it) } ?: placeholder}"

        holder.initialVpkTextView.text =
            "€/km Ini: ${entry.initialVpk?.let { String.format(Locale.US, "%.2f", it) } ?: placeholder}"

        // O campo pode ter sido editado; apresentamos simplesmente "Dist" para não confundir
        holder.initialDistanceTextView.text =
            "Dist: ${entry.initialDistanceKm?.let { String.format(Locale.US, "%.2f km", it) } ?: placeholder}"

        holder.serviceTypeTextView.text =
            entry.serviceType?.takeIf { it.isNotEmpty() } ?: placeholder

        val indicatorColorResId = getIndicatorColorResId(entry.originalBorderRating)
        val color = ContextCompat.getColor(holder.itemView.context, indicatorColorResId)
        holder.ratingIndicatorView.setBackgroundColor(color)
    }

    override fun getItemCount() = historyList.size

    fun updateData(newHistoryList: List<TripHistoryEntry>) {
        historyList = newHistoryList
        notifyDataSetChanged()
    }

    private fun getIndicatorColorResId(rating: BorderRating?): Int {
        return when (rating ?: BorderRating.GRAY) {
            BorderRating.GREEN -> R.color.history_indicator_good
            BorderRating.YELLOW -> R.color.history_indicator_medium
            BorderRating.RED -> R.color.history_indicator_poor
            BorderRating.GRAY -> R.color.history_indicator_unknown
        }
    }
}
