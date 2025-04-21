package com.example.smartdriver // <<< VERIFIQUE O PACKAGE

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smartdriver.databinding.ActivityHistoryBinding
import com.example.smartdriver.overlay.OverlayService // Para constantes
import com.example.smartdriver.utils.TripHistoryEntry
// --- Imports Verificados ---
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
// ---------------------------

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var historyPrefs: SharedPreferences
    private val gson = Gson()
    private lateinit var historyAdapter: HistoryAdapter
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
        loadHistoryData()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        historyAdapter = HistoryAdapter(historyList)
        binding.recyclerViewHistory.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = historyAdapter
        }
        Log.d(TAG, "RecyclerView configurado.")
    }

    private fun loadHistoryData() {
        Log.d(TAG, "Carregando histórico...")
        val historyJsonStringList = historyPrefs.getString(OverlayService.KEY_TRIP_HISTORY, "[]")

        if (historyJsonStringList.isNullOrEmpty() || historyJsonStringList == "[]") {
            Log.d(TAG, "Histórico vazio."); showEmptyState(true, "Nenhum histórico encontrado."); return
        }

        try {
            val listType = object : TypeToken<MutableList<String>>() {}.type
            val jsonStringList: List<String> = gson.fromJson(historyJsonStringList, listType) ?: listOf()
            Log.d(TAG, "Encontradas ${jsonStringList.size} strings JSON.")

            val loadedEntries: List<TripHistoryEntry> = jsonStringList.mapNotNull { jsonEntryString ->
                try { gson.fromJson(jsonEntryString, TripHistoryEntry::class.java) }
                catch (e: JsonSyntaxException) { Log.e(TAG, "Erro sintaxe JSON entrada: $jsonEntryString", e); null }
                catch (e: Exception) { Log.e(TAG, "Erro inesperado deserializar entrada: $jsonEntryString", e); null }
            }
            Log.d(TAG, "Carregadas ${loadedEntries.size} entradas válidas.")

            if (loadedEntries.isEmpty()) { showEmptyState(true, "Nenhum histórico válido encontrado.") }
            else {
                historyList.clear()
                historyList.addAll(loadedEntries.sortedByDescending { it.startTimeMillis })
                historyAdapter.notifyDataSetChanged()
                showEmptyState(false)
                Log.i(TAG, "Histórico carregado: ${historyList.size} itens.")
            }

        } catch (e: JsonSyntaxException) { Log.e(TAG, "Erro sintaxe JSON lista principal: $historyJsonStringList", e); showEmptyState(true, "Erro ao ler dados (formato inválido).") }
        catch (e: Exception) { Log.e(TAG, "Erro GERAL ao carregar histórico: $historyJsonStringList", e); showEmptyState(true, "Erro inesperado ao carregar histórico.") }
    }

    private fun showEmptyState(show: Boolean, message: String? = null) {
        binding.recyclerViewHistory.visibility = if (show) View.GONE else View.VISIBLE
        binding.textViewEmptyHistory.visibility = if (show) View.VISIBLE else View.GONE
        if (show) { binding.textViewEmptyHistory.text = message ?: "Nenhum histórico encontrado." }
    }
}