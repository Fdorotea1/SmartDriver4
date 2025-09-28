package com.example.smartdriver

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smartdriver.databinding.ActivityHistoryBinding
import com.example.smartdriver.overlay.OverlayService
import com.example.smartdriver.utils.TripHistoryEntry
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var historyPrefs: SharedPreferences
    private val gson = Gson()
    private lateinit var historyAdapter: HistoryAdapter
    private var historyList: MutableList<TripHistoryEntry> = mutableListOf()

    // ---- ActionMode (seleção múltipla) ----
    private var actionMode: ActionMode? = null
    private val MENU_ID_DELETE = 1001

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

        // ----- App bar preta com título branco -----
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            title = SpannableString("Histórico de Viagens").apply {
                setSpan(ForegroundColorSpan(Color.WHITE), 0, length, 0)
            }
        }

        // Status + nav bar pretas; fundo preto
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        binding.root.setBackgroundColor(Color.BLACK)
        binding.recyclerViewHistory.setBackgroundColor(Color.BLACK)
        binding.textViewEmptyHistory.setTextColor(Color.WHITE)

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
            onItemLongClick = { _, _ -> /* obsoleto; adapter gere seleção */ },
            onSelectionChanged = { count ->
                if (count > 0) startOrUpdateActionMode(count) else finishActionModeIfNeeded()
            }
        )
        binding.recyclerViewHistory.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = historyAdapter
        }
        Log.d(TAG, "RecyclerView configurado (tap=editar; long-press=seleção).")
    }

    // ---------- ActionMode helpers ----------
    private fun startOrUpdateActionMode(selectedCount: Int) {
        if (actionMode == null) {
            actionMode = startSupportActionMode(object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    // botão Excluir (ícone do sistema)
                    menu.add(0, MENU_ID_DELETE, 0, "Excluir")
                        .setIcon(android.R.drawable.ic_menu_delete)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    // estilo fica pelo tema escuro
                    return true
                }
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    return when (item.itemId) {
                        MENU_ID_DELETE -> { deleteSelectedEntries(); true }
                        else -> false
                    }
                }
                override fun onDestroyActionMode(mode: ActionMode) {
                    // Não limpar aqui — fazemos no finishActionModeIfNeeded() com post
                    actionMode = null
                }
            })
        }
        actionMode?.title = "$selectedCount selecionadas"
    }

    // Usa post() para não mexer no adapter durante layout
    private fun finishActionModeIfNeeded() {
        val mode = actionMode ?: return
        actionMode = null
        binding.recyclerViewHistory.post {
            try { historyAdapter.clearSelection() } catch (_: Exception) {}
            try { mode.finish() } catch (_: Exception) {}
        }
    }
    // ---------------------------------------

    // ---------------- Editar valor/distância/tempo (+ moradas) ----------------

    private fun showEditDialog(entry: TripHistoryEntry, position: Int) {
        val currentEff = getEffectiveValue(entry)
        val currentKm = entry.initialDistanceKm ?: 0.0
        val currentDurSec = max(0L, entry.durationSeconds)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        // --- Valor efetivo (€)
        container.addView(makeLabel("Valor efetivo (€)" +
        currentEff.takeIf { it > 0 }?.let { "  •  atual: ${currencyPT.format(it)}" } ?: ""))

        val etValor = EditText(this).apply {
            hint = "Ex.: 6,50"
            setText(if (currentEff > 0) String.format(Locale.US, "%.2f", currentEff) else "")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(etValor)

        // --- Distância (km)
        container.addView(makeLabel("Distância (km)" +
        currentKm.takeIf { it > 0 }?.let { String.format(Locale.US, "  •  atual: %.2f km", it) } ?: ""))

        val etKm = EditText(this).apply {
            hint = "Ex.: 3,20"
            setText(if (currentKm > 0) String.format(Locale.US, "%.2f", currentKm) else "")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(etKm)

        // --- Duração (mm:ss)
        container.addView(makeLabel("Duração (mm:ss)" +
        currentDurSec.takeIf { it > 0 }?.let { "  •  atual: ${formatDurationPrefill(it)}" } ?: ""))

        val etDur = EditText(this).apply {
            hint = "Ex.: 12:30"
            setText(if (currentDurSec > 0) formatDurationPrefill(currentDurSec) else "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        container.addView(etDur)

        // --- Moradas (Origem/Destino)
        container.addView(makeLabel("Origem" +
                (entry.pickupAddress?.takeIf { it.isNotBlank() }?.let { "  •  atual: $it" } ?: "")))
        val etPickup = EditText(this).apply {
            hint = "Ex.: Rua da Junqueira 123"
            setText(entry.pickupAddress?.takeIf { it.isNotBlank() } ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        container.addView(etPickup)

        container.addView(makeLabel("Destino" +
                (entry.dropoffAddress?.takeIf { it.isNotBlank() }?.let { "  •  atual: $it" } ?: "")))
        val etDropoff = EditText(this).apply {
            hint = "Ex.: Praça do Comércio"
            setText(entry.dropoffAddress?.takeIf { it.isNotBlank() } ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        container.addView(etDropoff)

        val builder = AlertDialog.Builder(this)
            .setTitle("Editar registo (valor, kms, tempo e moradas)")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { dialog, _ ->
                val newEff = etValor.text?.toString()
                    ?.replace(",", ".")?.trim()?.takeIf { it.isNotEmpty() }
                    ?.toDoubleOrNull()?.takeIf { it >= 0.0 }

                val newKm = etKm.text?.toString()
                    ?.replace(",", ".")?.trim()?.takeIf { it.isNotEmpty() }
                    ?.toDoubleOrNull()?.takeIf { it >= 0.0 }

                val newDurSec = etDur.text?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { parseDurationFlexible(it) }

                if (etDur.text?.isNotEmpty() == true && newDurSec == null) {
                    Toast.makeText(this, "Duração inválida. Use mm:ss (ex.: 12:30).", Toast.LENGTH_LONG).show()
                    dialog.dismiss(); return@setPositiveButton
                }

                val pickupToApply = etPickup.text?.toString()?.trim()?.let { if (it.isEmpty()) null else it }
                val dropoffToApply = etDropoff.text?.toString()?.trim()?.let { if (it.isEmpty()) null else it }

                if (newEff == null && newKm == null && newDurSec == null &&
                    pickupToApply == null && dropoffToApply == null) {
                    Toast.makeText(this, "Nada para atualizar.", Toast.LENGTH_SHORT).show()
                    dialog.dismiss(); return@setPositiveButton
                }

                applyEditToHistory(entry, position, newEff, newKm, newDurSec, pickupToApply, dropoffToApply)
                dialog.dismiss()
            }

        builder.show()
    }

    private fun makeLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
            setPadding(0, dp(12), 0, dp(6))
            setTextColor(Color.WHITE)
        }

    private fun applyEditToHistory(
        entryToEdit: TripHistoryEntry,
        position: Int,
        newEffective: Double?,
        newDistanceKm: Double?,
        newDurationSec: Long?,
        newPickupAddress: String?,
        newDropoffAddress: String?
    ) {
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
            val oldDur = oldEntryObj.durationSeconds

            val updated = oldEntryObj.copy(
                effectiveValue = newEffective ?: oldEntryObj.effectiveValue,
                initialDistanceKm = newDistanceKm ?: oldEntryObj.initialDistanceKm,
                durationSeconds = newDurationSec ?: oldEntryObj.durationSeconds,
                pickupAddress = newPickupAddress ?: oldEntryObj.pickupAddress,
                dropoffAddress = newDropoffAddress ?: oldEntryObj.dropoffAddress
            )

            jsonList[indexToUpdate] = gson.toJson(updated)
            historyPrefs.edit().putString(OverlayService.KEY_TRIP_HISTORY, gson.toJson(jsonList)).apply()

            if (position < historyList.size && historyList[position].startTimeMillis == entryToEdit.startTimeMillis) {
                historyList[position] = updated
                historyAdapter.updateData(historyList)
            } else {
                loadHistoryData()
            }

            val newEffForDelta = getEffectiveValue(updated)
            val deltaValue = newEffForDelta - oldEff
            val durationChanged = oldDur != updated.durationSeconds

            // ⚠️ Enviar delta sempre que valor OU duração mudarem
            if (deltaValue != 0.0 || durationChanged) {
                val intent = Intent(this, com.example.smartdriver.overlay.OverlayService::class.java).apply {
                    action = OverlayService.ACTION_APPLY_SHIFT_DELTA
                    putExtra(OverlayService.EXTRA_TRIP_START_MS, updated.startTimeMillis)
                    putExtra(OverlayService.EXTRA_OLD_EFFECTIVE, oldEff)
                    putExtra(OverlayService.EXTRA_NEW_EFFECTIVE, newEffForDelta)
                    // NOVO: deltas de duração
                    putExtra(OverlayService.EXTRA_OLD_DURATION_SEC, oldDur)
                    putExtra(OverlayService.EXTRA_NEW_DURATION_SEC, updated.durationSeconds)
                }
                try { startService(intent) } catch (_: Exception) {}
            }

            val parts = mutableListOf<String>()
            newEffective?.let { parts.add("Valor → ${currencyPT.format(it)}") }
            newDistanceKm?.let { parts.add(String.format(Locale.US, "Distância → %.2f km", it)) }
            newDurationSec?.let { parts.add("Duração → ${formatDurationPrefill(it)}") }
            if (newPickupAddress != null) parts.add("Origem → $newPickupAddress")
            if (newDropoffAddress != null) parts.add("Destino → $newDropoffAddress")

            Toast.makeText(
                this,
                if (parts.isEmpty()) "Atualização concluída." else "Atualizado: ${parts.joinToString(" | ")}",
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

    // ---------------- Excluir múltiplas (robusto) ----------------

    private fun deleteSelectedEntries() {
        val selected = historyAdapter.getSelectedEntries()
        if (selected.isEmpty()) { finishActionModeIfNeeded(); return }

        // Fecha a ActionMode e limpa seleção ANTES (adiado por post) de mexer em dados/UI
        finishActionModeIfNeeded()

        try {
            val currentHistoryJson = historyPrefs.getString(OverlayService.KEY_TRIP_HISTORY, "[]")
            val listType = object : TypeToken<MutableList<String>>() {}.type
            val mutableJsonList: MutableList<String> = gson.fromJson(currentHistoryJson, listType) ?: mutableListOf()

            val toRemoveKeys = selected.map { it.startTimeMillis }.toSet()

            // Atualiza armazenamento: mantém itens NÃO selecionados
            val remaining = mutableListOf<String>()
            for (json in mutableJsonList) {
                try {
                    val e = gson.fromJson(json, TripHistoryEntry::class.java)
                    if (e == null || e.startTimeMillis !in toRemoveKeys) remaining.add(json)
                } catch (_: Exception) {
                    // Se não conseguiu ler, preserva para não perder dados por acidente
                    remaining.add(json)
                }
            }
            historyPrefs.edit().putString(OverlayService.KEY_TRIP_HISTORY, gson.toJson(remaining)).apply()

            // Atualiza lista em memória e UI sem recarregar tudo
            historyList = historyList.filter { it.startTimeMillis !in toRemoveKeys }.toMutableList()
            historyAdapter.updateData(historyList)
            showEmptyState(historyList.isEmpty(), if (historyList.isEmpty()) "Histórico vazio." else null)

            // Aplica deltas de turno (valor → 0 e duração → 0)
            selected.forEach { entry ->
                try {
                    val oldEff = getEffectiveValue(entry)
                    val intent = Intent(this, com.example.smartdriver.overlay.OverlayService::class.java).apply {
                        action = OverlayService.ACTION_APPLY_SHIFT_DELTA
                        putExtra(OverlayService.EXTRA_TRIP_START_MS, entry.startTimeMillis)
                        putExtra(OverlayService.EXTRA_OLD_EFFECTIVE, oldEff)
                        putExtra(OverlayService.EXTRA_NEW_EFFECTIVE, 0.0)
                        // NOVO: duração a 0
                        putExtra(OverlayService.EXTRA_OLD_DURATION_SEC, entry.durationSeconds)
                        putExtra(OverlayService.EXTRA_NEW_DURATION_SEC, 0L)
                    }
                    startService(intent)
                } catch (_: Exception) { /* ignora falhas do serviço */ }
            }

            Toast.makeText(this, "Excluídas ${selected.size} entradas.", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao excluir entradas selecionadas: ${e.message}", e)
            Toast.makeText(this, "Erro ao excluir selecionadas.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Carregar lista ----------------

    private fun loadHistoryData() {
        Log.d(TAG, "Carregando histórico...")
        val historyJsonStringList = historyPrefs.getString(OverlayService.KEY_TRIP_HISTORY, "[]")
        if (historyJsonStringList.isNullOrEmpty() || historyJsonStringList == "[]") {
            Log.d(TAG, "Histórico vazio.")
            historyList.clear()
            historyAdapter.updateData(historyList)
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
            historyList.clear()
            historyList.addAll(loadedEntries.sortedByDescending { it.startTimeMillis })
            historyAdapter.updateData(historyList)
            showEmptyState(historyList.isEmpty(), if (historyList.isEmpty()) "Nenhum histórico encontrado." else null)
            Log.i(TAG, "Histórico carregado: ${historyList.size} itens.")
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

    // ---------------- Helpers ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun formatDurationPrefill(seconds: Long): String {
        val mm = seconds / 60
        val ss = seconds % 60
        return String.format(Locale.US, "%02d:%02d", mm, ss)
    }

    /**
     * Aceita:
     *  - "mm:ss" (ex.: "12:30")
     *  - "m" (minutos) => converte para segundos
     *  - "ss" (segundos)
     *  Se for número simples, assume minutos quando <= 600 (10h), caso contrário assume segundos.
     */
    private fun parseDurationFlexible(input: String): Long? {
        val t = input.trim()
        return if (t.contains(":")) {
            val parts = t.split(":")
            if (parts.size != 2) return null
            val mm = parts[0].toLongOrNull() ?: return null
            val ss = parts[1].toLongOrNull() ?: return null
            if (mm < 0 || ss < 0 || ss >= 60) return null
            mm * 60 + ss
        } else {
            val num = t.toLongOrNull() ?: return null
            if (num < 0) return null
            if (num <= 600) num * 60 else num
        }
    }
}
