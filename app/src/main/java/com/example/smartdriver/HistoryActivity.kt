package com.example.smartdriver

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smartdriver.databinding.ActivityHistoryBinding
import com.example.smartdriver.overlay.OverlayService
import com.example.smartdriver.utils.TripHistoryEntry
import com.example.smartdriver.utils.TripScreenshotIndex
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

    // ---------------- Editar valor/distância/tempo ----------------

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

        // Ver se há screenshot próxima do início da viagem
        val nearestShotPath = TripScreenshotIndex.findNearestForStart(this, entry.startTimeMillis, 15_000L)

        val builder = AlertDialog.Builder(this)
            .setTitle("Editar registo (valor, kms e tempo)")
            .setView(container)
            .setNegativeButton("Cancelar", null)

        if (nearestShotPath != null) {
            builder.setNeutralButton("Ver screenshot") { _, _ ->
                showScreenshotPreview(nearestShotPath)
            }
        }

        builder.setPositiveButton("Guardar") { dialog, _ ->
            // Valor (€)
            val newEff = etValor.text?.toString()
                ?.replace(",", ".")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.toDoubleOrNull()
                ?.takeIf { it >= 0.0 }

            // Distância (km)
            val newKm = etKm.text?.toString()
                ?.replace(",", ".")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.toDoubleOrNull()
                ?.takeIf { it >= 0.0 }

            // Duração (segundos)
            val newDurSec = etDur.text?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { parseDurationFlexible(it) }

            if (etDur.text?.isNotEmpty() == true && newDurSec == null) {
                Toast.makeText(this, "Duração inválida. Use mm:ss (ex.: 12:30).", Toast.LENGTH_LONG).show()
                dialog.dismiss()
                return@setPositiveButton
            }

            if (newEff == null && newKm == null && newDurSec == null) {
                Toast.makeText(this, "Nada para atualizar.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                return@setPositiveButton
            }

            applyEditToHistory(entry, position, newEff, newKm, newDurSec)
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
        }

    private fun applyEditToHistory(
        entryToEdit: TripHistoryEntry,
        position: Int,
        newEffective: Double?,
        newDistanceKm: Double?,
        newDurationSec: Long?
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

            val updated = oldEntryObj.copy(
                effectiveValue = newEffective ?: oldEntryObj.effectiveValue,
                initialDistanceKm = newDistanceKm ?: oldEntryObj.initialDistanceKm,
                durationSeconds = newDurationSec ?: oldEntryObj.durationSeconds
            )

            jsonList[indexToUpdate] = gson.toJson(updated)
            historyPrefs.edit().putString(OverlayService.KEY_TRIP_HISTORY, gson.toJson(jsonList)).apply()

            if (position < historyList.size && historyList[position].startTimeMillis == entryToEdit.startTimeMillis) {
                historyList[position] = updated
                historyAdapter.notifyItemChanged(position)
            } else {
                loadHistoryData()
            }

            val newEffForDelta = getEffectiveValue(updated)
            val delta = newEffForDelta - oldEff
            if (delta != 0.0) {
                val intent = Intent(this, com.example.smartdriver.overlay.OverlayService::class.java).apply {
                    action = OverlayService.ACTION_APPLY_SHIFT_DELTA
                    putExtra(OverlayService.EXTRA_TRIP_START_MS, updated.startTimeMillis)
                    putExtra(OverlayService.EXTRA_OLD_EFFECTIVE, oldEff)
                    putExtra(OverlayService.EXTRA_NEW_EFFECTIVE, newEffForDelta)
                }
                try { startService(intent) } catch (_: Exception) {}
            }

            val parts = mutableListOf<String>()
            newEffective?.let { parts.add("Valor → ${currencyPT.format(it)}") }
            newDistanceKm?.let { parts.add(String.format(Locale.US, "Distância → %.2f km", it)) }
            newDurationSec?.let { parts.add("Duração → ${formatDurationPrefill(it)}") }

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
                historyAdapter.updateData(historyList)
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

    /** Preview da screenshot com OCR congelado enquanto o diálogo estiver aberto. */
    private fun showScreenshotPreview(path: String) {
        // 1) prepara pulsos de FREEZE para o ScreenCaptureService (900 ms cada)
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_FREEZE_OCR
        }
        val handler = Handler(Looper.getMainLooper())
        var keepFreezing = true
        val freezeTick = object : Runnable {
            override fun run() {
                try { startService(serviceIntent) } catch (_: Exception) {}
                if (keepFreezing) handler.postDelayed(this, 600L) // reenviar antes dos 900 ms expirarem
            }
        }

        // 2) cria a ImageView (carregando redimensionado para não matar memória)
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            setPadding(dp(12), dp(12), dp(12), dp(12))

            // medir
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                inDither = true
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, opts)

            val maxW = resources.displayMetrics.widthPixels * 9 / 10
            val maxH = resources.displayMetrics.heightPixels * 8 / 10
            var sample = 1
            while (opts.outWidth / sample > maxW || opts.outHeight / sample > maxH) {
                sample *= 2
            }

            // carregar redimensionado
            val opts2 = BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                inDither = true
                inSampleSize = sample
            }
            val bmp = BitmapFactory.decodeFile(path, opts2)
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        // 3) mostra o diálogo e mantém FREEZE até fechar
        val dlg = AlertDialog.Builder(this)
            .setTitle("Screenshot da oferta")
            .setView(iv)
            .setPositiveButton("Fechar", null)
            .create()

        dlg.setOnShowListener {
            keepFreezing = true
            handler.post(freezeTick)           // começa a congelar já
        }
        dlg.setOnDismissListener {
            keepFreezing = false               // pára de congelar
            handler.removeCallbacks(freezeTick)
        }

        dlg.show()
    }
}
