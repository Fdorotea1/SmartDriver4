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
import com.example.smartdriver.utils.OfferRating // Para rating de exemplo (LEGADO, PODE SER REMOVIDO DEPOIS)
import com.example.smartdriver.utils.EvaluationResult // <<< NOVO IMPORT
import com.example.smartdriver.utils.IndividualRating // <<< NOVO IMPORT
import com.example.smartdriver.utils.BorderRating // <<< NOVO IMPORT

import java.util.* // Para Locale

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        private const val PREFS_NAME = "SmartDriverSettings"

        // --- NOVAS Chaves e Defaults para Limiares ---
        private const val KEY_GOOD_KM_THRESHOLD = "good_km_threshold"
        private const val KEY_POOR_KM_THRESHOLD = "poor_km_threshold"
        private const val KEY_GOOD_HOUR_THRESHOLD = "good_hour_threshold"
        private const val KEY_POOR_HOUR_THRESHOLD = "poor_hour_threshold"

        private const val DEFAULT_GOOD_KM_THRESHOLD = 1.20 // Exemplo: 1.20 €/km ou mais é BOM
        private const val DEFAULT_POOR_KM_THRESHOLD = 0.70 // Exemplo: 0.70 €/km ou menos é MAU
        private const val DEFAULT_GOOD_HOUR_THRESHOLD = 15.0 // Exemplo: 15.0 €/h ou mais é BOM
        private const val DEFAULT_POOR_HOUR_THRESHOLD = 8.0  // Exemplo: 8.0 €/h ou menos é MAU

        // Chaves e Defaults para Aparência (inalterados)
        private const val KEY_FONT_SIZE = "font_size_percent"
        private const val KEY_TRANSPARENCY = "transparency_percent"
        private const val DEFAULT_FONT_SIZE_PERCENT = 100
        private const val DEFAULT_TRANSPARENCY_PERCENT = 15

        // --- NOVOS Métodos para ler os limiares ---
        fun getGoodKmThreshold(context: Context): Double {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Usar getFloat e converter para Double para consistência com saving
            return prefs.getFloat(KEY_GOOD_KM_THRESHOLD, DEFAULT_GOOD_KM_THRESHOLD.toFloat()).toDouble()
        }

        fun getPoorKmThreshold(context: Context): Double {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getFloat(KEY_POOR_KM_THRESHOLD, DEFAULT_POOR_KM_THRESHOLD.toFloat()).toDouble()
        }

        fun getGoodHourThreshold(context: Context): Double {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getFloat(KEY_GOOD_HOUR_THRESHOLD, DEFAULT_GOOD_HOUR_THRESHOLD.toFloat()).toDouble()
        }

        fun getPoorHourThreshold(context: Context): Double {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getFloat(KEY_POOR_HOUR_THRESHOLD, DEFAULT_POOR_HOUR_THRESHOLD.toFloat()).toDouble()
        }

        // Métodos para aparência (inalterados)
        fun getFontSize(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE_PERCENT)
        }
        fun getTransparency(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_TRANSPARENCY, DEFAULT_TRANSPARENCY_PERCENT)
        }
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
        previewContainer = binding.previewOverlayContainer
        previewOverlayView = OverlayView(this)

        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER
        )
        previewOverlayView.layoutParams = layoutParams
        previewContainer.addView(previewOverlayView)
        Log.d(TAG,"Preview OverlayView adicionada ao container.")

        loadSettings()
        setupListeners()
        setupInitialPreview() // Configura dados de exemplo
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }

    /** Configura a pré-visualização inicial com dados de exemplo */
    private fun setupInitialPreview() {
        val sampleOffer = OfferData(
            value = "10.50", pickupDistance = "2.1", tripDistance = "8.4",
            pickupDuration = "5", tripDuration = "15",
            distance = "10.5", duration = "20" // Totais calculados como exemplo
        )
        // CRIA UM RESULTADO DE EXEMPLO - A COR DA BORDA E BARRAS SERÁ BASEADA NISSO
        // (Neste exemplo: €/km = 1.0, €/h = 31.5 - assumindo defaults, km seria MÉDIO, hora seria BOM -> Borda AMARELA)
        val sampleResult = EvaluationResult(
            kmRating = IndividualRating.MEDIUM, // Exemplo
            hourRating = IndividualRating.GOOD,  // Exemplo
            combinedBorderRating = BorderRating.YELLOW // Exemplo
        )
        previewOverlayView.updateState(sampleResult, sampleOffer) // <<< Atualiza com EvaluationResult
        updatePreviewAppearance()
        Log.d(TAG,"Preview inicial configurado com dados de exemplo.")
    }

    /** Carrega os valores salvos e atualiza os campos da UI e a pré-visualização */
    private fun loadSettings() {
        Log.d(TAG, "Carregando configurações salvas...")

        // Carrega novos limiares
        binding.goodKmThresholdEditText.setText(String.format(Locale.US, "%.2f", getGoodKmThreshold(this)))
        binding.poorKmThresholdEditText.setText(String.format(Locale.US, "%.2f", getPoorKmThreshold(this)))
        binding.goodHourThresholdEditText.setText(String.format(Locale.US, "%.2f", getGoodHourThreshold(this)))
        binding.poorHourThresholdEditText.setText(String.format(Locale.US, "%.2f", getPoorHourThreshold(this)))

        // Carrega aparência (inalterado)
        val fontSize = getFontSize(this)
        binding.fontSizeSeekBar.progress = fontSize; binding.fontSizeValueTextView.text = "$fontSize%"
        val transparency = getTransparency(this)
        binding.transparencySeekBar.progress = transparency; binding.transparencyValueTextView.text = "$transparency% transp"

        updatePreviewAppearance() // Atualiza preview com valores carregados
        Log.d(TAG, "Configurações carregadas e aplicadas.")
    }

    /** Configura os listeners, incluindo atualização do preview */
    private fun setupListeners() {
        binding.saveSettingsButton.setOnClickListener { if (validateInputs()) { saveSettings(); applySettingsToServices(); finish() } }
        binding.resetToDefaultsButton.setOnClickListener { resetToDefaults(); Toast.makeText(this, "Padrões restaurados.", Toast.LENGTH_SHORT).show() }

        // Listeners de aparência (inalterados)
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

        // Listeners para limpar erros de validação ao digitar
        addThresholdValidationWatcher(binding.goodKmThresholdEditText, binding.goodKmThresholdLayout)
        addThresholdValidationWatcher(binding.poorKmThresholdEditText, binding.poorKmThresholdLayout)
        addThresholdValidationWatcher(binding.goodHourThresholdEditText, binding.goodHourThresholdLayout)
        addThresholdValidationWatcher(binding.poorHourThresholdEditText, binding.poorHourThresholdLayout)
    }

    /** Atualiza a aparência da pré-visualização (fonte e transparência) */
    private fun updatePreviewAppearance() {
        val fontSizePercent = binding.fontSizeSeekBar.progress; val transparencyPercent = binding.transparencySeekBar.progress
        val fontScale = fontSizePercent / 100f; val alpha = 1.0f - (transparencyPercent / 100f)
        previewOverlayView.updateFontSize(fontScale); previewOverlayView.updateAlpha(alpha)
        Log.d(TAG, "Preview atualizado: Fonte=$fontSizePercent%, Transp=$transparencyPercent%")
    }

    /** Valida os campos de entrada dos novos limiares */
    private fun validateInputs(): Boolean {
        var isValid = true
        // Limpa erros antigos
        binding.goodKmThresholdLayout.error = null
        binding.poorKmThresholdLayout.error = null
        binding.goodHourThresholdLayout.error = null
        binding.poorHourThresholdLayout.error = null

        // Obtem textos
        val goodKmText = binding.goodKmThresholdEditText.text?.toString() ?: ""
        val poorKmText = binding.poorKmThresholdEditText.text?.toString() ?: ""
        val goodHourText = binding.goodHourThresholdEditText.text?.toString() ?: ""
        val poorHourText = binding.poorHourThresholdEditText.text?.toString() ?: ""

        // Valida €/km
        try {
            if (goodKmText.isBlank()) { binding.goodKmThresholdLayout.error = "Obrigatório"; isValid = false }
            if (poorKmText.isBlank()) { binding.poorKmThresholdLayout.error = "Obrigatório"; isValid = false }
            if (!isValid) throw NumberFormatException("Campos km vazios.") // Força catch se algum estiver vazio

            val goodKm = goodKmText.replace(',', '.').toDouble()
            val poorKm = poorKmText.replace(',', '.').toDouble()

            if (goodKm <= poorKm) {
                binding.goodKmThresholdLayout.error = "Bom deve ser > Mau"
                binding.poorKmThresholdLayout.error = " " // Só para ocupar espaço
                isValid = false
            }
            if (goodKm < 0 || poorKm < 0) {
                if (goodKm < 0) binding.goodKmThresholdLayout.error = "Positivo"
                if (poorKm < 0) binding.poorKmThresholdLayout.error = "Positivo"
                isValid = false
            }
        } catch (e: NumberFormatException) {
            if (isValid) Toast.makeText(this, "Limiares €/km inválidos.", Toast.LENGTH_SHORT).show()
            if (binding.goodKmThresholdLayout.error == null && !isValidDouble(goodKmText)) binding.goodKmThresholdLayout.error = "Inválido"
            if (binding.poorKmThresholdLayout.error == null && !isValidDouble(poorKmText)) binding.poorKmThresholdLayout.error = "Inválido"
            isValid = false
        }

        // Valida €/h
        try {
            if (goodHourText.isBlank()) { binding.goodHourThresholdLayout.error = "Obrigatório"; isValid = false }
            if (poorHourText.isBlank()) { binding.poorHourThresholdLayout.error = "Obrigatório"; isValid = false }
            if (!isValid) throw NumberFormatException("Campos hora vazios.")

            val goodHour = goodHourText.replace(',', '.').toDouble()
            val poorHour = poorHourText.replace(',', '.').toDouble()

            if (goodHour <= poorHour) {
                binding.goodHourThresholdLayout.error = "Bom deve ser > Mau"
                binding.poorHourThresholdLayout.error = " "
                isValid = false
            }
            if (goodHour < 0 || poorHour < 0) {
                if (goodHour < 0) binding.goodHourThresholdLayout.error = "Positivo"
                if (poorHour < 0) binding.poorHourThresholdLayout.error = "Positivo"
                isValid = false
            }
        } catch (e: NumberFormatException) {
            if (isValid) Toast.makeText(this, "Limiares €/h inválidos.", Toast.LENGTH_SHORT).show()
            if (binding.goodHourThresholdLayout.error == null && !isValidDouble(goodHourText)) binding.goodHourThresholdLayout.error = "Inválido"
            if (binding.poorHourThresholdLayout.error == null && !isValidDouble(poorHourText)) binding.poorHourThresholdLayout.error = "Inválido"
            isValid = false
        }

        if (!isValid) Toast.makeText(this, "Verifique os campos marcados.", Toast.LENGTH_LONG).show()
        return isValid
    }

    // Função auxiliar para validar double
    private fun isValidDouble(str: String): Boolean {
        return try { str.replace(',', '.').toDouble(); true }
        catch (e: NumberFormatException) { false }
    }

    // Função auxiliar para adicionar listener de validação
    private fun addThresholdValidationWatcher(editText: android.widget.EditText, layout: com.google.android.material.textfield.TextInputLayout) {
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                layout.error = null // Limpa erro ao digitar
                // Limpa erro do par também se a condição Bom > Mau estava ativa
                when (layout.id) {
                    R.id.goodKmThresholdLayout -> binding.poorKmThresholdLayout.error = null
                    R.id.poorKmThresholdLayout -> binding.goodKmThresholdLayout.error = null
                    R.id.goodHourThresholdLayout -> binding.poorHourThresholdLayout.error = null
                    R.id.poorHourThresholdLayout -> binding.goodHourThresholdLayout.error = null
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /** Salva as configurações */
    private fun saveSettings() {
        Log.d(TAG, "Salvando configurações...");
        val editor = prefs.edit()
        try {
            // Salva novos limiares como Float (mais compatível com SharedPreferences)
            editor.putFloat(KEY_GOOD_KM_THRESHOLD, binding.goodKmThresholdEditText.text.toString().replace(',', '.').toFloat())
            editor.putFloat(KEY_POOR_KM_THRESHOLD, binding.poorKmThresholdEditText.text.toString().replace(',', '.').toFloat())
            editor.putFloat(KEY_GOOD_HOUR_THRESHOLD, binding.goodHourThresholdEditText.text.toString().replace(',', '.').toFloat())
            editor.putFloat(KEY_POOR_HOUR_THRESHOLD, binding.poorHourThresholdEditText.text.toString().replace(',', '.').toFloat())

            // Salva aparência (inalterado)
            editor.putInt(KEY_FONT_SIZE, binding.fontSizeSeekBar.progress)
            editor.putInt(KEY_TRANSPARENCY, binding.transparencySeekBar.progress)

            editor.apply()
            Log.i(TAG, "Configurações salvas com novos limiares.")
            Toast.makeText(this, "Configurações salvas!", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar configurações: ${e.message}", e)
            Toast.makeText(this, "Erro ao salvar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Envia as configs de APARÊNCIA para os serviços (lógica de limiares é usada internamente pelo Avaliador) */
    private fun applySettingsToServices() {
        Log.d(TAG, "Enviando configs de APARÊNCIA para OverlayService...");
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE_SETTINGS
            putExtra(OverlayService.EXTRA_FONT_SIZE, binding.fontSizeSeekBar.progress)
            putExtra(OverlayService.EXTRA_TRANSPARENCY, binding.transparencySeekBar.progress)
        }
        try { startService(intent) }
        catch (e: Exception) { Log.e(TAG, "Erro ao enviar configs para OverlayService: ${e.message}") }
        // Não é necessário enviar os limiares para o serviço, OfferEvaluator os lê diretamente
    }

    /** Restaura padrões e atualiza UI e preview */
    private fun resetToDefaults() {
        Log.d(TAG, "Restaurando padrões.");
        // Reseta novos limiares
        binding.goodKmThresholdEditText.setText(String.format(Locale.US, "%.2f", DEFAULT_GOOD_KM_THRESHOLD))
        binding.poorKmThresholdEditText.setText(String.format(Locale.US, "%.2f", DEFAULT_POOR_KM_THRESHOLD))
        binding.goodHourThresholdEditText.setText(String.format(Locale.US, "%.2f", DEFAULT_GOOD_HOUR_THRESHOLD))
        binding.poorHourThresholdEditText.setText(String.format(Locale.US, "%.2f", DEFAULT_POOR_HOUR_THRESHOLD))

        // Reseta aparência (inalterado)
        binding.fontSizeSeekBar.progress = DEFAULT_FONT_SIZE_PERCENT
        binding.transparencySeekBar.progress = DEFAULT_TRANSPARENCY_PERCENT
        binding.fontSizeValueTextView.text = "$DEFAULT_FONT_SIZE_PERCENT%"
        binding.transparencyValueTextView.text = "$DEFAULT_TRANSPARENCY_PERCENT% transp"

        // Limpa erros
        binding.goodKmThresholdLayout.error = null; binding.poorKmThresholdLayout.error = null
        binding.goodHourThresholdLayout.error = null; binding.poorHourThresholdLayout.error = null

        updatePreviewAppearance() // Atualiza o preview
    }
}