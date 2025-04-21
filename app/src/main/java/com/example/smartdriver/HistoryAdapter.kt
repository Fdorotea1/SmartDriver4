package com.example.smartdriver // Ou o teu pacote .adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.smartdriver.utils.TripHistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HistoryAdapter(
    private var historyList: List<TripHistoryEntry>,
    private var itemLongClickListener: ((TripHistoryEntry, Int) -> Unit)? = null // <<< Listener para clique longo
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    private val dateTimeFormatter = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    private val placeholder = "--"

    // --- ViewHolder INTERNO com Listener ---
    inner class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val startTimeTextView: TextView = itemView.findViewById(R.id.textViewStartTime)
        val durationTextView: TextView = itemView.findViewById(R.id.textViewDuration)
        val offerValueTextView: TextView = itemView.findViewById(R.id.textViewOfferValue)
        val finalVphTextView: TextView = itemView.findViewById(R.id.textViewFinalVph)
        val initialVpkTextView: TextView = itemView.findViewById(R.id.textViewInitialVpk)
        val initialDistanceTextView: TextView = itemView.findViewById(R.id.textViewInitialDistance)
        val serviceTypeTextView: TextView = itemView.findViewById(R.id.textViewServiceType)

        init {
            // Define o listener de clique longo para o item inteiro
            itemView.setOnLongClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    // Chama o listener passado para o adapter, se existir
                    itemLongClickListener?.invoke(historyList[position], position)
                }
                true // Indica que o clique longo foi consumido
            }
        }
    }
    // ---------------------------------------

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val entry = historyList[position]
        holder.startTimeTextView.text = "Início: ${dateTimeFormatter.format(Date(entry.startTimeMillis))}"
        val durationMinutes = TimeUnit.SECONDS.toMinutes(entry.durationSeconds)
        val durationSecondsPart = entry.durationSeconds % 60
        holder.durationTextView.text = "Duração: ${durationMinutes}m ${durationSecondsPart}s"
        holder.offerValueTextView.text = "Valor: ${entry.offerValue?.let { String.format(Locale.US, "%.2f €", it) } ?: placeholder}"
        holder.finalVphTextView.text = "€/h Real: ${entry.finalVph?.let { String.format(Locale.US, "%.1f", it) } ?: placeholder}"
        holder.initialVpkTextView.text = "€/km Ini: ${entry.initialVpk?.let { String.format(Locale.US, "%.2f", it) } ?: placeholder}"
        holder.initialDistanceTextView.text = "Dist Ini: ${entry.initialDistanceKm?.let { String.format(Locale.US, "%.1f km", it) } ?: placeholder}"
        holder.serviceTypeTextView.text = entry.serviceType?.takeIf { it.isNotEmpty() } ?: placeholder
    }

    override fun getItemCount() = historyList.size

    // --- Função para definir o Listener a partir da Activity ---
    fun setOnItemLongClickListener(listener: (TripHistoryEntry, Int) -> Unit) {
        this.itemLongClickListener = listener
    }
    // -------------------------------------------------------

    // Função para atualizar os dados (pode ser útil após exclusão)
    fun updateData(newHistoryList: List<TripHistoryEntry>) {
        historyList = newHistoryList
        // Considerar usar DiffUtil para melhor performance se a lista for muito grande
        notifyDataSetChanged()
    }
}