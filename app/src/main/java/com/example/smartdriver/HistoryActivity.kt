package com.example.smartdriver

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog // <<< Import para AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smartdriver.databinding.ActivityHistoryBinding
import com.example.smartdriver.overlay.OverlayService
import com.example.smartdriver.utils.TripHistoryEntry
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var historyPrefs: SharedPreferences
    private val gson = Gson()
    private lateinit var historyAdapter: HistoryAdapter
    // A lista do adapter precisa ser mutável para remover itens
    private var historyList: MutableList<TripHistoryEntry> = mutableListOf()

    companion object {
        private const val TAG = "HistoryActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Histórico de Viagens"

        historyPrefs = getSharedPreferences(OverlayService.HISTORY_PREFS_NAME, Context.MODE_PRIVATE)

        setupRecyclerView()
        loadHistoryData() // Carrega os dados iniciais
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // ... (código inalterado) ...
        return when (item.itemId) {
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        // Passa a lista mutável para o adapter
        historyAdapter = HistoryAdapter(historyList) { entry, position ->
            // Define o que acontece no clique longo (chama o diálogo de exclusão)
            showDeleteConfirmationDialog(entry, position)
        }
        // Define o listener no adapter
        // historyAdapter.setOnItemLongClickListener { entry, position ->
        //     showDeleteConfirmationDialog(entry, position)
        // }

        binding.recyclerViewHistory.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = historyAdapter
        }
        Log.d(TAG, "RecyclerView configurado com listener de clique longo.")
    }

    // --- Função para mostrar o diálogo de confirmação ---
    private fun showDeleteConfirmationDialog(entryToDelete: TripHistoryEntry, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Excluir Entrada?")
            .setMessage("Tem a certeza que deseja excluir esta entrada do histórico?")
            .setIcon(android.R.drawable.ic_dialog_alert) // Ícone de alerta padrão
            .setPositiveButton("Excluir") { dialog, which ->
                // Ação se confirmar a exclusão
                deleteHistoryEntry(entryToDelete, position)
            }
            .setNegativeButton("Cancelar", null) // Nenhuma ação se cancelar
            .show()
    }
    // ----------------------------------------------------

    // --- Função para excluir a entrada ---
    private fun deleteHistoryEntry(entryToDelete: TripHistoryEntry, position: Int) {
        Log.d(TAG, "Tentando excluir entrada na posição $position com startTime: ${entryToDelete.startTimeMillis}")
        try {
            // 1. Lê a lista atual de JSON strings
            val currentHistoryJson = historyPrefs.getString(OverlayService.KEY_TRIP_HISTORY, "[]")
            val listType = object : TypeToken<MutableList<String>>() {}.type
            val mutableJsonList: MutableList<String> = gson.fromJson(currentHistoryJson, listType) ?: mutableListOf()

            // 2. Encontra o índice do JSON a ser removido (comparando startTimeMillis)
            var indexToRemove = -1
            for (i in mutableJsonList.indices) {
                try {
                    val entryFromJson = gson.fromJson(mutableJsonList[i], TripHistoryEntry::class.java)
                    if (entryFromJson != null && entryFromJson.startTimeMillis == entryToDelete.startTimeMillis) {
                        indexToRemove = i
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Erro ao deserializar item durante busca para exclusão: ${mutableJsonList[i]}", e)
                    // Continua procurando, pode ser uma entrada inválida antiga
                }
            }

            // 3. Remove o JSON da lista se encontrado
            if (indexToRemove != -1) {
                mutableJsonList.removeAt(indexToRemove)
                Log.d(TAG, "Entrada JSON encontrada e removida no índice $indexToRemove.")

                // 4. Salva a lista JSON atualizada de volta nas SharedPreferences
                val updatedHistoryJson = gson.toJson(mutableJsonList)
                historyPrefs.edit().putString(OverlayService.KEY_TRIP_HISTORY, updatedHistoryJson).apply()
                Log.i(TAG, "Histórico atualizado nas SharedPreferences após exclusão.")

                // 5. Remove o item da lista local do adapter
                if (position < historyList.size && historyList[position].startTimeMillis == entryToDelete.startTimeMillis) {
                    historyList.removeAt(position)
                    // 6. Notifica o adapter sobre a remoção (com animação)
                    historyAdapter.notifyItemRemoved(position)
                    // Opcional: Notificar mudança no range para atualizar posições subsequentes
                    historyAdapter.notifyItemRangeChanged(position, historyList.size)
                    Log.i(TAG, "Item removido do adapter na posição $position.")
                    // Verifica se a lista ficou vazia
                    if (historyList.isEmpty()) {
                        showEmptyState(true, "Histórico vazio.")
                    }
                } else {
                    // Se a posição não corresponder (raro, mas pode acontecer), recarrega tudo
                    Log.w(TAG, "Inconsistência de posição ($position) ao remover do adapter. Recarregando lista.")
                    loadHistoryData()
                }

            } else {
                Log.e(TAG, "ERRO: Não foi possível encontrar a entrada JSON correspondente para exclusão nas SharedPreferences.")
                // Talvez recarregar a lista para garantir consistência
                loadHistoryData()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Erro GERAL ao excluir entrada do histórico: ${e.message}", e)
            // Recarrega a lista em caso de erro para tentar sincronizar
            loadHistoryData()
        }
    }
    // -----------------------------------

    // loadHistoryData e showEmptyState permanecem iguais às versões anteriores que corrigimos
    private fun loadHistoryData() {
        Log.d(TAG, "Carregando histórico...")
        val historyJsonStringList = historyPrefs.getString(OverlayService.KEY_TRIP_HISTORY, "[]")
        if (historyJsonStringList.isNullOrEmpty() || historyJsonStringList == "[]") {
            Log.d(TAG, "Histórico vazio."); showEmptyState(true, "Nenhum histórico encontrado."); return
        }
        try {
            val listType = object : TypeToken<MutableList<String>>() {}.type
            val jsonStringList: List<String> = gson.fromJson(historyJsonStringList, listType) ?: listOf()
            val loadedEntries: List<TripHistoryEntry> = jsonStringList.mapNotNull { jsonEntryString ->
                try { gson.fromJson(jsonEntryString, TripHistoryEntry::class.java) }
                catch (e: JsonSyntaxException) { Log.e(TAG, "Erro sintaxe JSON entrada: $jsonEntryString", e); null }
                catch (e: Exception) { Log.e(TAG, "Erro inesperado deserializar entrada: $jsonEntryString", e); null }
            }
            if (loadedEntries.isEmpty()) { showEmptyState(true, "Nenhum histórico válido.") }
            else {
                historyList.clear()
                historyList.addAll(loadedEntries.sortedByDescending { it.startTimeMillis })
                // historyAdapter.updateData(historyList) // Ou apenas notificar
                historyAdapter.notifyDataSetChanged() // Notifica o adapter após carregar/ordenar
                showEmptyState(false)
                Log.i(TAG, "Histórico carregado: ${historyList.size} itens.")
            }
        } catch (e: JsonSyntaxException) { Log.e(TAG, "Erro sintaxe JSON lista principal: $historyJsonStringList", e); showEmptyState(true, "Erro ao ler dados.") }
        catch (e: Exception) { Log.e(TAG, "Erro GERAL ao carregar histórico: $historyJsonStringList", e); showEmptyState(true, "Erro inesperado.") }
    }

    private fun showEmptyState(show: Boolean, message: String? = null) {
        binding.recyclerViewHistory.visibility = if (show) View.GONE else View.VISIBLE
        binding.textViewEmptyHistory.visibility = if (show) View.VISIBLE else View.GONE
        if (show) { binding.textViewEmptyHistory.text = message ?: "Nenhum histórico encontrado." }
    }
}