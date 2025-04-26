package com.example.smartdriver // Ou o teu pacote .adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.smartdriver.R // Import R
import com.example.smartdriver.utils.BorderRating // Import BorderRating
import com.example.smartdriver.utils.TripHistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HistoryAdapter(
    private var historyList: List<TripHistoryEntry>,
    private var itemLongClickListener: ((TripHistoryEntry, Int) -> Unit)? = null
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    private val dateTimeFormatter = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
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
            itemView.setOnLongClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    itemLongClickListener?.invoke(historyList[position], position)
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

        // Define os textos...
        holder.startTimeTextView.text = "Início: ${dateTimeFormatter.format(Date(entry.startTimeMillis))}"
        val durationMinutes = TimeUnit.SECONDS.toMinutes(entry.durationSeconds)
        val durationSecondsPart = entry.durationSeconds % 60
        holder.durationTextView.text = "Duração: ${durationMinutes}m ${durationSecondsPart}s"
        holder.offerValueTextView.text = "Valor: ${entry.offerValue?.let { String.format(Locale.US, "%.2f €", it) } ?: placeholder}"
        holder.finalVphTextView.text = "€/h Real: ${entry.finalVph?.let { String.format(Locale.US, "%.1f", it) } ?: placeholder}"
        holder.initialVpkTextView.text = "€/km Ini: ${entry.initialVpk?.let { String.format(Locale.US, "%.2f", it) } ?: placeholder}"
        holder.initialDistanceTextView.text = "Dist Ini: ${entry.initialDistanceKm?.let { String.format(Locale.US, "%.1f km", it) } ?: placeholder}"
        holder.serviceTypeTextView.text = entry.serviceType?.takeIf { it.isNotEmpty() } ?: placeholder

        // Define a cor da barra lateral, tratando o caso de originalBorderRating ser null (histórico antigo)
        val indicatorColorResId = getIndicatorColorResId(entry.originalBorderRating) // Passa o valor (pode ser null)
        val color = ContextCompat.getColor(holder.itemView.context, indicatorColorResId)
        holder.ratingIndicatorView.setBackgroundColor(color)
    }

    override fun getItemCount() = historyList.size

    fun setOnItemLongClickListener(listener: (TripHistoryEntry, Int) -> Unit) {
        this.itemLongClickListener = listener
    }

    // Função para atualizar os dados (pode ser útil após exclusão ou carregamento)
    fun updateData(newHistoryList: List<TripHistoryEntry>) {
        historyList = newHistoryList
        notifyDataSetChanged() // Ou usar DiffUtil para performance
    }

    /**
     * Retorna o Resource ID da cor com base no BorderRating.
     * Trata o caso de 'rating' ser null (histórico antigo) retornando a cor UNKNOWN.
     */
    private fun getIndicatorColorResId(rating: BorderRating?): Int { // <<< Aceita Nullable
        // Usa o operador elvis (?:) para fornecer GRAY como default se rating for null
        return when (rating ?: BorderRating.GRAY) {
            BorderRating.GREEN -> R.color.history_indicator_good
            BorderRating.YELLOW -> R.color.history_indicator_medium
            BorderRating.RED -> R.color.history_indicator_poor
            BorderRating.GRAY -> R.color.history_indicator_unknown // Cor para nulo ou explicitamente GRAY
        }
    }
}