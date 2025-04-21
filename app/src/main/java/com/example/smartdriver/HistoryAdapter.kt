package com.example.smartdriver // Ou o teu pacote .adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.smartdriver.utils.TripHistoryEntry // Ajusta o import se necessário
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HistoryAdapter(private var historyList: List<TripHistoryEntry>) : // Tornar mutável internamente se precisar atualizar
    RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    // Formatter para data e hora (cache para performance)
    private val dateTimeFormatter = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    private val placeholder = "--" // Placeholder para valores nulos ou inválidos

    // Cria novas views (invocado pelo layout manager)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_history, parent, false) // Usa o layout do item
        return HistoryViewHolder(view)
    }

    // Substitui o conteúdo de uma view (invocado pelo layout manager)
    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val entry = historyList[position] // Pega o item nesta posição

        // --- Preenche os TextViews com os dados formatados ---

        // Início (Data e Hora)
        holder.startTimeTextView.text = "Início: ${dateTimeFormatter.format(Date(entry.startTimeMillis))}"

        // Duração (Formata de segundos para Xm Ys)
        val durationMinutes = TimeUnit.SECONDS.toMinutes(entry.durationSeconds)
        val durationSecondsPart = entry.durationSeconds % 60
        holder.durationTextView.text = "Duração: ${durationMinutes}m ${durationSecondsPart}s"

        // Valor da Oferta (€)
        holder.offerValueTextView.text = "Valor: ${entry.offerValue?.let { String.format(Locale.US, "%.2f €", it) } ?: placeholder}"

        // €/h Real
        holder.finalVphTextView.text = "€/h Real: ${entry.finalVph?.let { String.format(Locale.US, "%.1f", it) } ?: placeholder}"

        // €/km Inicial
        holder.initialVpkTextView.text = "€/km Ini: ${entry.initialVpk?.let { String.format(Locale.US, "%.2f", it) } ?: placeholder}"

        // Distância Inicial
        holder.initialDistanceTextView.text = "Dist Ini: ${entry.initialDistanceKm?.let { String.format(Locale.US, "%.1f km", it) } ?: placeholder}"

        // Tipo de Serviço
        holder.serviceTypeTextView.text = entry.serviceType ?: placeholder
    }

    // Retorna o tamanho do teu dataset (invocado pelo layout manager)
    override fun getItemCount() = historyList.size

    // Fornece uma referência para as views de cada item de dados
    // (ViewHolder otimiza acessos com findViewById)
    class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val startTimeTextView: TextView = itemView.findViewById(R.id.textViewStartTime)
        val durationTextView: TextView = itemView.findViewById(R.id.textViewDuration)
        val offerValueTextView: TextView = itemView.findViewById(R.id.textViewOfferValue)
        val finalVphTextView: TextView = itemView.findViewById(R.id.textViewFinalVph)
        val initialVpkTextView: TextView = itemView.findViewById(R.id.textViewInitialVpk)
        val initialDistanceTextView: TextView = itemView.findViewById(R.id.textViewInitialDistance)
        val serviceTypeTextView: TextView = itemView.findViewById(R.id.textViewServiceType)
    }

    // Função opcional para atualizar a lista de dados no adapter
    fun updateData(newHistoryList: List<TripHistoryEntry>) {
        historyList = newHistoryList
        notifyDataSetChanged() // Notifica o RecyclerView que os dados mudaram
    }
}