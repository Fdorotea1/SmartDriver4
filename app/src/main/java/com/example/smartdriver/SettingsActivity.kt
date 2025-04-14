package com.example.smartdriver

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.ViewGroup // Import para LayoutParams
import android.widget.FrameLayout // Import FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.smartdriver.databinding.ActivitySettingsBinding // Import ViewBinding
import com.example.smartdriver.overlay.OverlayService
import com.example.smartdriver.overlay.OverlayView // Importa a OverlayView
import com.example.smartdriver.utils.OfferData // Para dados de exemplo
import com.example.smartdriver.utils.OfferRating // Para rating de exemplo
import java.util.* // Para Locale

class SettingsActivity : AppCompatActivity() {

    companion object {
        // ... (Constantes e métodos estáticos inalterados) ...
        private const val TAG = "SettingsActivity"; private const val PREFS_NAME = "SmartDriverSettings"; private const val KEY_EXCELLENT_THRESHOLD = "excellent_threshold"; private const val KEY_GOOD_THRESHOLD = "good_threshold"; private const val KEY_MEDIUM_THRESHOLD = "medium_threshold"; private const val KEY_MIN_HOURLY_RATE = "min_hourly_rate"; private const val KEY_FONT_SIZE = "font_size_percent"; private const val KEY_TRANSPARENCY = "transparency_percent"; private const val DEFAULT_EXCELLENT_THRESHOLD = 1.50; private const val DEFAULT_GOOD_THRESHOLD = 1.20; private const val DEFAULT_MEDIUM_THRESHOLD = 0.90; private const val DEFAULT_MIN_HOURLY_RATE = 15; private const val DEFAULT_FONT_SIZE_PERCENT = 100; private const val DEFAULT_TRANSPARENCY_PERCENT = 15
        fun getExcellentThreshold(context: Context): Double { val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); return prefs.getFloat(KEY_EXCELLENT_THRESHOLD, DEFAULT_EXCELLENT_THRESHOLD.toFloat()).toDouble() }
        fun getGoodThreshold(context: Context): Double { val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); return prefs.getFloat(KEY_GOOD_THRESHOLD, DEFAULT_GOOD_THRESHOLD.toFloat()).toDouble() }
        fun getMediumThreshold(context: Context): Double { val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); return prefs.getFloat(KEY_MEDIUM_THRESHOLD, DEFAULT_MEDIUM_THRESHOLD.toFloat()).toDouble() }
        fun getMinHourlyRate(context: Context): Int { val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); return prefs.getInt(KEY_MIN_HOURLY_RATE, DEFAULT_MIN_HOURLY_RATE) }
        fun getFontSize(context: Context): Int { val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); return prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE_PERCENT) }
        fun getTransparency(context: Context): Int { val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); return prefs.getInt(KEY_TRANSPARENCY, DEFAULT_TRANSPARENCY_PERCENT) }
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var previewOverlayView: OverlayView // Mantém a referência
    private lateinit var previewContainer: FrameLayout // Referência para o container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate chamado")

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Obtém referência ao container
        previewContainer = binding.previewOverlayContainer

        // Cria a OverlayView programaticamente
        previewOverlayView = OverlayView(this)
        // Define LayoutParams para a preview (WRAP_CONTENT dentro do FrameLayout)
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER // Centraliza dentro do FrameLayout
        )
        previewOverlayView.layoutParams = layoutParams

        // Adiciona a preview ao container
        previewContainer.addView(previewOverlayView)
        Log.d(TAG,"Preview OverlayView adicionada ao container.")

        loadSettings() // Carrega e aplica aos campos E ao preview inicial
        setupListeners() // Configura listeners
        setupInitialPreview() // Configura dados de exemplo
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }

    /** Configura a pré-visualização inicial com dados de exemplo */
    private fun setupInitialPreview() {
        val sampleOffer = OfferData(
            value = "8.50", pickupDistance = "2.1", tripDistance = "10.5",
            pickupDuration = "5", tripDuration = "20",
            distance = "12.6", duration = "25"
        )
        previewOverlayView.updateState(OfferRating.GOOD, sampleOffer) // Define dados e cor inicial
        updatePreviewAppearance() // Aplica fonte e transparência
        Log.d(TAG,"Preview inicial configurado.")
    }

    /** Carrega os valores salvos e atualiza os campos da UI e a pré-visualização */
    private fun loadSettings() {
        Log.d(TAG, "Carregando configurações salvas...")
        // ... (código para carregar thresholds e hourly rate inalterado) ...
        binding.excellentThresholdEditText.setText(String.format(Locale.US, "%.2f", prefs.getFloat(KEY_EXCELLENT_THRESHOLD, DEFAULT_EXCELLENT_THRESHOLD.toFloat())))
        binding.goodThresholdEditText.setText(String.format(Locale.US, "%.2f", prefs.getFloat(KEY_GOOD_THRESHOLD, DEFAULT_GOOD_THRESHOLD.toFloat())))
        binding.mediumThresholdEditText.setText(String.format(Locale.US, "%.2f", prefs.getFloat(KEY_MEDIUM_THRESHOLD, DEFAULT_MEDIUM_THRESHOLD.toFloat())))
        binding.minHourlyRateEditText.setText(prefs.getInt(KEY_MIN_HOURLY_RATE, DEFAULT_MIN_HOURLY_RATE).toString())

        val fontSize = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE_PERCENT)
        binding.fontSizeSeekBar.progress = fontSize; binding.fontSizeValueTextView.text = "$fontSize%"
        val transparency = prefs.getInt(KEY_TRANSPARENCY, DEFAULT_TRANSPARENCY_PERCENT)
        binding.transparencySeekBar.progress = transparency; binding.transparencyValueTextView.text = "$transparency% transp"
        updatePreviewAppearance() // Atualiza preview com valores carregados
        Log.d(TAG, "Configurações carregadas e aplicadas ao preview.")
    }

    /** Configura os listeners, incluindo atualização do preview */
    private fun setupListeners() {
        binding.saveSettingsButton.setOnClickListener { if (validateInputs()) { saveSettings(); applySettingsToServices(); finish() } }
        binding.resetToDefaultsButton.setOnClickListener { resetToDefaults(); Toast.makeText(this, "Padrões restaurados.", Toast.LENGTH_SHORT).show() }
        binding.fontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { binding.fontSizeValueTextView.text = "$progress%"; updatePreviewAppearance() }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.transparencySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { binding.transparencyValueTextView.text = "$progress% transp"; updatePreviewAppearance() }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        addThresholdValidationWatcher(binding.excellentThresholdEditText); addThresholdValidationWatcher(binding.goodThresholdEditText); addThresholdValidationWatcher(binding.mediumThresholdEditText)
        binding.minHourlyRateEditText.addTextChangedListener(object: TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}; override fun afterTextChanged(s: Editable?) { if (binding.minHourlyRateLayout.error != null) binding.minHourlyRateLayout.error = null } })
    }

    /** Atualiza a aparência da pré-visualização */
    private fun updatePreviewAppearance() {
        val fontSizePercent = binding.fontSizeSeekBar.progress; val transparencyPercent = binding.transparencySeekBar.progress
        val fontScale = fontSizePercent / 100f; val alpha = 1.0f - (transparencyPercent / 100f)
        previewOverlayView.updateFontSize(fontScale); previewOverlayView.updateAlpha(alpha)
        Log.d(TAG, "Preview atualizado: Fonte=$fontSizePercent%, Transp=$transparencyPercent%")
    }

    /** Valida os campos de entrada */
    private fun validateInputs(): Boolean { /* ... (código de validação inalterado) ... */
        var isValid = true; binding.excellentThresholdLayout.error = null; binding.goodThresholdLayout.error = null; binding.mediumThresholdLayout.error = null; binding.minHourlyRateLayout.error = null
        val excellentText = binding.excellentThresholdEditText.text?.toString() ?: ""; val goodText = binding.goodThresholdEditText.text?.toString() ?: ""; val mediumText = binding.mediumThresholdEditText.text?.toString() ?: ""; val hourlyText = binding.minHourlyRateEditText.text?.toString() ?: ""
        try { if (excellentText.isBlank() || goodText.isBlank() || mediumText.isBlank()) { if (excellentText.isBlank()) binding.excellentThresholdLayout.error = "Obrigatório"; if (goodText.isBlank()) binding.goodThresholdLayout.error = "Obrigatório"; if (mediumText.isBlank()) binding.mediumThresholdLayout.error = "Obrigatório"; isValid = false; throw NumberFormatException("Thresholds vazios.") }; val excellent = excellentText.replace(',', '.').toDouble(); val good = goodText.replace(',', '.').toDouble(); val medium = mediumText.replace(',', '.').toDouble(); if (excellent <= good || good <= medium) { binding.excellentThresholdLayout.error = "Deve ser Exc > Bom > Méd"; binding.goodThresholdLayout.error = " "; binding.mediumThresholdLayout.error = " "; isValid = false }; if (excellent < 0 || good < 0 || medium < 0) { if (excellent < 0) binding.excellentThresholdLayout.error = "Positivo"; if (good < 0) binding.goodThresholdLayout.error = "Positivo"; if (medium < 0) binding.mediumThresholdLayout.error = "Positivo"; isValid = false } } catch (e: NumberFormatException) { if (isValid) Toast.makeText(this, "Limiares inválidos.", Toast.LENGTH_SHORT).show(); if (binding.excellentThresholdLayout.error == null && !isValidDouble(excellentText)) binding.excellentThresholdLayout.error = "Inválido"; if (binding.goodThresholdLayout.error == null && !isValidDouble(goodText)) binding.goodThresholdLayout.error = "Inválido"; if (binding.mediumThresholdLayout.error == null && !isValidDouble(mediumText)) binding.mediumThresholdLayout.error = "Inválido"; isValid = false } catch (e: Exception) { Log.e(TAG, "Erro validação thresholds: ${e.message}"); Toast.makeText(this, "Erro validação.", Toast.LENGTH_SHORT).show(); isValid = false }
        if (hourlyText.isNotBlank()) { try { val hourlyRate = hourlyText.toInt(); if (hourlyRate < 0) { binding.minHourlyRateLayout.error = "Positivo"; isValid = false } } catch (e: NumberFormatException) { binding.minHourlyRateLayout.error = "Inválido (inteiro)"; isValid = false } }; if (!isValid) Toast.makeText(this, "Verifique os campos.", Toast.LENGTH_LONG).show(); return isValid
    }
    private fun isValidDouble(str: String): Boolean { try { str.replace(',', '.').toDouble(); return true } catch (e: NumberFormatException) { return false } }
    private fun addThresholdValidationWatcher(editText: android.widget.EditText) { editText.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { when(editText.id) { binding.excellentThresholdEditText.id -> binding.excellentThresholdLayout.error = null; binding.goodThresholdEditText.id -> binding.goodThresholdLayout.error = null; binding.mediumThresholdEditText.id -> binding.mediumThresholdLayout.error = null } }; override fun afterTextChanged(s: Editable?) { } }) }

    /** Salva as configurações */
    private fun saveSettings() { /* ... (código de salvamento inalterado) ... */
        Log.d(TAG, "Salvando configurações..."); val editor = prefs.edit()
        try { val excellentText = binding.excellentThresholdEditText.text?.toString() ?: DEFAULT_EXCELLENT_THRESHOLD.toString(); val goodText = binding.goodThresholdEditText.text?.toString() ?: DEFAULT_GOOD_THRESHOLD.toString(); val mediumText = binding.mediumThresholdEditText.text?.toString() ?: DEFAULT_MEDIUM_THRESHOLD.toString(); val hourlyText = binding.minHourlyRateEditText.text?.toString() ?: ""; editor.putFloat(KEY_EXCELLENT_THRESHOLD, excellentText.replace(',', '.').toFloat()); editor.putFloat(KEY_GOOD_THRESHOLD, goodText.replace(',', '.').toFloat()); editor.putFloat(KEY_MEDIUM_THRESHOLD, mediumText.replace(',', '.').toFloat()); val hourlyRate = hourlyText.toIntOrNull() ?: DEFAULT_MIN_HOURLY_RATE; editor.putInt(KEY_MIN_HOURLY_RATE, hourlyRate); editor.putInt(KEY_FONT_SIZE, binding.fontSizeSeekBar.progress); editor.putInt(KEY_TRANSPARENCY, binding.transparencySeekBar.progress); editor.apply(); Log.i(TAG, "Configurações salvas.") } catch (e: Exception) { Log.e(TAG, "Erro ao salvar: ${e.message}"); Toast.makeText(this, "Erro ao salvar: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    /** Envia as configs para os serviços */
    private fun applySettingsToServices() { /* ... (código de envio inalterado) ... */
        Log.d(TAG, "Enviando configs para OverlayService..."); val intent = Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_UPDATE_SETTINGS; putExtra(OverlayService.EXTRA_FONT_SIZE, binding.fontSizeSeekBar.progress); putExtra(OverlayService.EXTRA_TRANSPARENCY, binding.transparencySeekBar.progress) }
        try { startService(intent) } catch (e: Exception) { Log.e(TAG, "Erro ao enviar configs para OverlayService: ${e.message}") }
    }

    /** Restaura padrões e atualiza UI e preview */
    private fun resetToDefaults() {
        Log.d(TAG, "Restaurando padrões.");
        binding.excellentThresholdEditText.setText(String.format(Locale.US, "%.2f", DEFAULT_EXCELLENT_THRESHOLD)); binding.goodThresholdEditText.setText(String.format(Locale.US, "%.2f", DEFAULT_GOOD_THRESHOLD)); binding.mediumThresholdEditText.setText(String.format(Locale.US, "%.2f", DEFAULT_MEDIUM_THRESHOLD))
        binding.minHourlyRateEditText.setText(DEFAULT_MIN_HOURLY_RATE.toString()); binding.fontSizeSeekBar.progress = DEFAULT_FONT_SIZE_PERCENT; binding.transparencySeekBar.progress = DEFAULT_TRANSPARENCY_PERCENT
        binding.fontSizeValueTextView.text = "$DEFAULT_FONT_SIZE_PERCENT%"; binding.transparencyValueTextView.text = "$DEFAULT_TRANSPARENCY_PERCENT% transp"
        binding.excellentThresholdLayout.error = null; binding.goodThresholdLayout.error = null; binding.mediumThresholdLayout.error = null; binding.minHourlyRateLayout.error = null
        updatePreviewAppearance() // Atualiza o preview com os valores padrão
    }
}