package com.example.smartdriver

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smartdriver.databinding.ActivityHistoryBinding
import com.example.smartdriver.overlay.OverlayService
import com.example.smartdriver.utils.TripHistoryEntry
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.text.NumberFormat
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var historyPrefs: SharedPreferences
    private val gson = Gson()
    private lateinit var historyAdapter: HistoryAdapter
    private var historyList: MutableList<TripHistoryEntry> = mutableListOf()

    companion object {
        private const val TAG = "HistoryActivity"
    }

    private val currencyPT: NumberFormat =
        NumberFormat.getCurrencyInstance(Locale("pt", "PT")).apply {
            minimumFractionDigits = 2; maximumFractionDigits = 2
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
        historyAdapter = HistoryAdapter(
            historyList,
            onItemClick = { entry, position -> showEditDialog(entry, position) },
            onItemLongClick = { entry, position -> showDeleteConfirmationDialog(entry, position) }
        )
        binding.recyclerViewHistory.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = historyAdapter
        }
        Log.d(TAG, "RecyclerView configurado (click=editar, longClick=apagar).")
    }

    // ---------------- Editar valor efetivo ----------------

    private fun showEditDialog(entry: TripHistoryEntry, position: Int) {
        val currentEff = getEffectiveValue(entry)
        val edit = EditText(this).apply {
            hint = "Valor efetivo (€)"
            setText(if (currentEff > 0) String.format(Locale.US, "%.2f", currentEff) else "")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        AlertDialog.Builder(this)
            .setTitle("Editar valor efetivo")
            .setView(edit)
            .setPositiveButton("Guardar") { dialog, _ ->
                val txt = edit.text?.toString()?.replace(",", ".")?.trim()
                val newEff = txt?.toDoubleOrNull()
                if (newEff == null || newEff <= 0.0) {
                    Toast.makeText(this, "Valor inválido", Toast.LENGTH_SHORT).show()
                } else {
                    applyEditToHistory(entry, position, newEff)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun applyEditToHistory(entryToEdit: TripHistoryEntry, position: Int, newEffective: Double) {
        try {
            val currentHistoryJson = historyPrefs.getString(OverlayService.KEY_TRIP_HISTORY, "[]")
            val listType = object : TypeToken<MutableList<String>>() {}.type
            val jsonList: MutableList<String> = gson.fromJson(currentHistoryJson, listType) ?: mutableListOf()

            var indexToUpdate = -1
            var oldEntryObj: TripHistoryEntry? = null
            for (i in jsonList.indices) {
                try {
                    val e = gson.fromJson(jsonList[i], TripHistoryEntry::class.java)
                    if (e != null && e.startTimeMillis == entryToEdit.startTimeMillis) {
                        indexToUpdate = i
                        oldEntryObj = e
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Erro ao deserializar item durante busca para edição: ${jsonList[i]}", e)
                }
            }
            if (indexToUpdate == -1 || oldEntryObj == null) {
                Log.e(TAG, "Não foi possível localizar a entrada no armazenamento para editar.")
                Toast.makeText(this, "Falha ao editar (não encontrado).", Toast.LENGTH_SHORT).show()
                return
            }

            val oldEff = getEffectiveValue(oldEntryObj)
            val updated = oldEntryObj.copy(effectiveValue = newEffective)

            jsonList[indexToUpdate] = gson.toJson(updated)
            historyPrefs.edit().putString(OverlayService.KEY_TRIP_HISTORY, gson.toJson(jsonList)).apply()

            if (position < historyList.size && historyList[position].startTimeMillis == entryToEdit.startTimeMillis) {
                historyList[position] = updated
                historyAdapter.notifyItemChanged(position)
            } else {
                loadHistoryData()
            }

            val delta = newEffective - oldEff
            if (delta != 0.0) {
                val intent = Intent(this, com.example.smartdriver.overlay.OverlayService::class.java).apply {
                    action = OverlayService.ACTION_APPLY_SHIFT_DELTA
                    putExtra(OverlayService.EXTRA_TRIP_START_MS, updated.startTimeMillis)
                    putExtra(OverlayService.EXTRA_OLD_EFFECTIVE, oldEff)
                    putExtra(OverlayService.EXTRA_NEW_EFFECTIVE, newEffective)
                }
                try { startService(intent) } catch (_: Exception) {}
            }

            Toast.makeText(
                this,
                "Atualizado para ${currencyPT.format(newEffective)} (Δ ${currencyPT.format(delta)})",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao editar entrada: ${e.message}", e)
            Toast.makeText(this, "Erro ao editar.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getEffectiveValue(e: TripHistoryEntry): Double {
        return (e.effectiveValue ?: e.offerValue ?: 0.0).coerceAtLeast(0.0)
    }

    // ---------------- Apagar entrada ----------------

    private fun showDeleteConfirmationDialog(entryToDelete: TripHistoryEntry, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Excluir Entrada?")
            .setMessage("Tem a certeza que deseja excluir esta entrada do histórico?")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("Excluir") { dialog, _ ->
                deleteHistoryEntry(entryToDelete, position)
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteHistoryEntry(entryToDelete: TripHistoryEntry, position: Int) {
        Log.d(TAG, "Excluir entrada na posição $position com startTime: ${entryToDelete.startTimeMillis}")
        try {
            val currentHistoryJson = historyPrefs.getString(OverlayService.KEY_TRIP_HISTORY, "[]")
            val listType = object : TypeToken<MutableList<String>>() {}.type
            val mutableJsonList: MutableList<String> = gson.fromJson(currentHistoryJson, listType) ?: mutableListOf()

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
                }
            }

            if (indexToRemove != -1) {
                val oldEff = getEffectiveValue(entryToDelete)
                val intent = Intent(this, com.example.smartdriver.overlay.OverlayService::class.java).apply {
                    action = OverlayService.ACTION_APPLY_SHIFT_DELTA
                    putExtra(OverlayService.EXTRA_TRIP_START_MS, entryToDelete.startTimeMillis)
                    putExtra(OverlayService.EXTRA_OLD_EFFECTIVE, oldEff)
                    putExtra(OverlayService.EXTRA_NEW_EFFECTIVE, 0.0)
                }
                try { startService(intent) } catch (_: Exception) {}

                mutableJsonList.removeAt(indexToRemove)
                historyPrefs.edit().putString(OverlayService.KEY_TRIP_HISTORY, gson.toJson(mutableJsonList)).apply()

                if (position < historyList.size && historyList[position].startTimeMillis == entryToDelete.startTimeMillis) {
                    historyList.removeAt(position)
                    historyAdapter.notifyItemRemoved(position)
                    historyAdapter.notifyItemRangeChanged(position, historyList.size)
                    if (historyList.isEmpty()) showEmptyState(true, "Histórico vazio.")
                } else {
                    loadHistoryData()
                }
            } else {
                Log.e(TAG, "Não foi possível encontrar a entrada para exclusão.")
                loadHistoryData()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao excluir entrada do histórico: ${e.message}", e)
            loadHistoryData()
        }
    }

    // ---------------- Carregar lista ----------------

    private fun loadHistoryData() {
        Log.d(TAG, "Carregando histórico...")
        val historyJsonStringList = historyPrefs.getString(OverlayService.KEY_TRIP_HISTORY, "[]")
        if (historyJsonStringList.isNullOrEmpty() || historyJsonStringList == "[]") {
            Log.d(TAG, "Histórico vazio.")
            showEmptyState(true, "Nenhum histórico encontrado.")
            return
        }
        try {
            val listType = object : TypeToken<MutableList<String>>() {}.type
            val jsonStringList: List<String> = gson.fromJson(historyJsonStringList, listType) ?: listOf()
            val loadedEntries: List<TripHistoryEntry> = jsonStringList.mapNotNull { jsonEntryString ->
                try { gson.fromJson(jsonEntryString, TripHistoryEntry::class.java) }
                catch (e: JsonSyntaxException) { Log.e(TAG, "Erro sintaxe JSON entrada: $jsonEntryString", e); null }
                catch (e: Exception) { Log.e(TAG, "Erro inesperado deserializar entrada: $jsonEntryString", e); null }
            }
            if (loadedEntries.isEmpty()) {
                showEmptyState(true, "Nenhum histórico válido.")
            } else {
                historyList.clear()
                historyList.addAll(loadedEntries.sortedByDescending { it.startTimeMillis })
                historyAdapter.notifyDataSetChanged()
                showEmptyState(false)
                Log.i(TAG, "Histórico carregado: ${historyList.size} itens.")
            }
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Erro sintaxe JSON lista principal: $historyJsonStringList", e)
            showEmptyState(true, "Erro ao ler dados.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro GERAL ao carregar histórico: $historyJsonStringList", e)
            showEmptyState(true, "Erro inesperado.")
        }
    }

    private fun showEmptyState(show: Boolean, message: String? = null) {
        binding.recyclerViewHistory.visibility = if (show) View.GONE else View.VISIBLE
        binding.textViewEmptyHistory.visibility = if (show) View.VISIBLE else View.GONE
        if (show) { binding.textViewEmptyHistory.text = message ?: "Nenhum histórico encontrado." }
    }
}
