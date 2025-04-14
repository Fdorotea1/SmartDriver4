package com.example.smartdriver

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.smartdriver.databinding.ActivitySettingsBinding // Import ViewBinding
import com.example.smartdriver.overlay.OverlayService // Import para enviar configs
import java.util.Locale

/**
 * Atividade para configurar os parâmetros da aplicação, como limiares de rentabilidade
 * e aparência do overlay. Salva as configurações em SharedPreferences.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        // Nome do arquivo de SharedPreferences para configurações
        private const val PREFS_NAME = "SmartDriverSettings"

        // Chaves para as configurações salvas
        private const val KEY_EXCELLENT_THRESHOLD = "excellent_threshold"
        private const val KEY_GOOD_THRESHOLD = "good_threshold"
        private const val KEY_MEDIUM_THRESHOLD = "medium_threshold"
        private const val KEY_MIN_HOURLY_RATE = "min_hourly_rate"
        private const val KEY_FONT_SIZE = "font_size_percent" // Salvar como percentual
        private const val KEY_TRANSPARENCY = "transparency_percent" // Salvar como percentual

        // Valores padrão (usados se nada for salvo ainda)
        private const val DEFAULT_EXCELLENT_THRESHOLD = 1.50
        private const val DEFAULT_GOOD_THRESHOLD = 1.20
        private const val DEFAULT_MEDIUM_THRESHOLD = 0.90
        private const val DEFAULT_MIN_HOURLY_RATE = 15
        private const val DEFAULT_FONT_SIZE_PERCENT = 100
        private const val DEFAULT_TRANSPARENCY_PERCENT = 15

        // --- Métodos estáticos para ler configurações de outras partes do app ---

        fun getExcellentThreshold(context: Context): Double {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getFloat(KEY_EXCELLENT_THRESHOLD, DEFAULT_EXCELLENT_THRESHOLD.toFloat()).toDouble()
        }

        fun getGoodThreshold(context: Context): Double {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getFloat(KEY_GOOD_THRESHOLD, DEFAULT_GOOD_THRESHOLD.toFloat()).toDouble()
        }

        fun getMediumThreshold(context: Context): Double {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getFloat(KEY_MEDIUM_THRESHOLD, DEFAULT_MEDIUM_THRESHOLD.toFloat()).toDouble()
        }

        fun getMinHourlyRate(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_MIN_HOURLY_RATE, DEFAULT_MIN_HOURLY_RATE)
        }

        fun getFontSize(context: Context): Int { // Retorna percentual (ex: 100)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE_PERCENT)
        }

        fun getTransparency(context: Context): Int { // Retorna percentual (ex: 15)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_TRANSPARENCY, DEFAULT_TRANSPARENCY_PERCENT)
        }
    }

    // ViewBinding
    private lateinit var binding: ActivitySettingsBinding
    // SharedPreferences
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate chamado")

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSettings()
        setupListeners()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }


    /** Carrega os valores salvos nas SharedPreferences e atualiza os campos da UI */
    private fun loadSettings() {
        Log.d(TAG, "Carregando configurações salvas...")
        binding.excellentThresholdEditText.setText(String.format(Locale.US, "%.2f", prefs.getFloat(KEY_EXCELLENT_THRESHOLD, DEFAULT_EXCELLENT_THRESHOLD.toFloat())))
        binding.goodThresholdEditText.setText(String.format(Locale.US, "%.2f", prefs.getFloat(KEY_GOOD_THRESHOLD, DEFAULT_GOOD_THRESHOLD.toFloat())))
        binding.mediumThresholdEditText.setText(String.format(Locale.US, "%.2f", prefs.getFloat(KEY_MEDIUM_THRESHOLD, DEFAULT_MEDIUM_THRESHOLD.toFloat())))
        binding.minHourlyRateEditText.setText(prefs.getInt(KEY_MIN_HOURLY_RATE, DEFAULT_MIN_HOURLY_RATE).toString())

        val fontSize = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE_PERCENT)
        binding.fontSizeSeekBar.progress = fontSize
        binding.fontSizeValueTextView.text = "$fontSize%"

        val transparency = prefs.getInt(KEY_TRANSPARENCY, DEFAULT_TRANSPARENCY_PERCENT)
        binding.transparencySeekBar.progress = transparency
        binding.transparencyValueTextView.text = "$transparency% transp"

        Log.d(TAG, "Configurações carregadas.")
    }

    /** Configura os listeners para os botões e SeekBars */
    private fun setupListeners() {
        binding.saveSettingsButton.setOnClickListener {
            if (validateInputs()) {
                saveSettings()
                applySettingsToServices()
                Toast.makeText(this, "Configurações salvas!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        binding.resetToDefaultsButton.setOnClickListener {
            resetToDefaults()
            Toast.makeText(this, "Valores padrão restaurados.", Toast.LENGTH_SHORT).show()
        }

        binding.fontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.fontSizeValueTextView.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.transparencySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.transparencyValueTextView.text = "$progress% transp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        addThresholdValidationWatcher(binding.excellentThresholdEditText)
        addThresholdValidationWatcher(binding.goodThresholdEditText)
        addThresholdValidationWatcher(binding.mediumThresholdEditText)
        // Também adicionamos watcher para o hourly rate para limpar erro
        binding.minHourlyRateEditText.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Limpa o erro ao digitar, a validação completa ocorre no save
                if (binding.minHourlyRateLayout.error != null) {
                    binding.minHourlyRateLayout.error = null
                }
            }
        })
    }

    /** Valida os campos de entrada antes de salvar */
    private fun validateInputs(): Boolean {
        var isValid = true

        // Limpar erros anteriores
        binding.excellentThresholdLayout.error = null
        binding.goodThresholdLayout.error = null
        binding.mediumThresholdLayout.error = null
        binding.minHourlyRateLayout.error = null

        // Obter textos (usar toString() que é seguro para Editable?)
        val excellentText = binding.excellentThresholdEditText.text?.toString() ?: ""
        val goodText = binding.goodThresholdEditText.text?.toString() ?: ""
        val mediumText = binding.mediumThresholdEditText.text?.toString() ?: ""
        val hourlyText = binding.minHourlyRateEditText.text?.toString() ?: ""

        // Validar Thresholds
        try {
            // Verificar se estão vazios
            if (excellentText.isBlank() || goodText.isBlank() || mediumText.isBlank()) {
                if (excellentText.isBlank()) binding.excellentThresholdLayout.error = "Obrigatório"
                if (goodText.isBlank()) binding.goodThresholdLayout.error = "Obrigatório"
                if (mediumText.isBlank()) binding.mediumThresholdLayout.error = "Obrigatório"
                isValid = false
                throw NumberFormatException("Campos de threshold não podem estar vazios.") // Pula para catch
            }

            val excellent = excellentText.replace(',', '.').toDouble()
            val good = goodText.replace(',', '.').toDouble()
            val medium = mediumText.replace(',', '.').toDouble()

            if (excellent <= good || good <= medium) {
                binding.excellentThresholdLayout.error = "Deve ser Excelente > Bom > Médio"
                // Usar espaço para erro nos outros para não colapsar o layout
                binding.goodThresholdLayout.error = " "
                binding.mediumThresholdLayout.error = " "
                isValid = false
            }

            if (excellent < 0 || good < 0 || medium < 0) {
                // Adiciona erro específico se algum for negativo
                if (excellent < 0) binding.excellentThresholdLayout.error = "Deve ser positivo"
                if (good < 0) binding.goodThresholdLayout.error = "Deve ser positivo"
                if (medium < 0) binding.mediumThresholdLayout.error = "Deve ser positivo"
                isValid = false
            }

        } catch (e: NumberFormatException) {
            // O erro de campo obrigatório já foi tratado acima se estava vazio.
            // Se chegou aqui, o formato é inválido.
            if (isValid) { // Só mostra toast se não houve erro anterior
                Toast.makeText(this, "Valores de limiar inválidos. Use números.", Toast.LENGTH_SHORT).show()
            }
            // Marca os campos problemáticos (se não estiverem já marcados como obrigatório)
            if (binding.excellentThresholdLayout.error == null && !isValidDouble(excellentText)) binding.excellentThresholdLayout.error = "Inválido"
            if (binding.goodThresholdLayout.error == null && !isValidDouble(goodText)) binding.goodThresholdLayout.error = "Inválido"
            if (binding.mediumThresholdLayout.error == null && !isValidDouble(mediumText)) binding.mediumThresholdLayout.error = "Inválido"
            isValid = false
        } catch (e: Exception) {
            Log.e(TAG, "Erro inesperado na validação dos thresholds: ${e.message}")
            Toast.makeText(this, "Erro ao validar limiares.", Toast.LENGTH_SHORT).show()
            isValid = false
        }


        // Validar Valor por Hora (Opcional, mas se preenchido, deve ser válido)
        if (hourlyText.isNotBlank()) {
            try {
                val hourlyRate = hourlyText.toInt()
                if (hourlyRate < 0) {
                    binding.minHourlyRateLayout.error = "Valor por hora deve ser positivo."
                    isValid = false
                }
            } catch (e: NumberFormatException) {
                binding.minHourlyRateLayout.error = "Valor por hora inválido (deve ser número inteiro)."
                isValid = false
            }
        } // Se for branco, não há erro.


        if (!isValid) {
            Toast.makeText(this, "Verifique os campos marcados.", Toast.LENGTH_LONG).show()
        }

        return isValid
    }

    /** Função auxiliar para verificar se uma string é um Double válido */
    private fun isValidDouble(str: String): Boolean {
        return try {
            str.replace(',', '.').toDouble()
            true
        } catch (e: NumberFormatException) {
            false
        }
    }

    /** Adiciona um TextWatcher para limpar erros de validação enquanto o usuário digita */
    private fun addThresholdValidationWatcher(editText: android.widget.EditText) {
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Limpa o erro do layout correspondente ao digitar
                when(editText.id) {
                    binding.excellentThresholdEditText.id -> binding.excellentThresholdLayout.error = null
                    binding.goodThresholdEditText.id -> binding.goodThresholdLayout.error = null
                    binding.mediumThresholdEditText.id -> binding.mediumThresholdLayout.error = null
                }
            }
            override fun afterTextChanged(s: Editable?) {
                // Poderia chamar validateInputs() aqui para validação em tempo real,
                // mas pode ser pesado. Limpar o erro já ajuda.
            }
        })
    }


    /** Salva as configurações atuais nas SharedPreferences */
    private fun saveSettings() {
        Log.d(TAG, "Salvando configurações...")
        val editor = prefs.edit()

        try {
            // Usar os textos já validados (ou padrão se houve erro na validação - idealmente não chega aqui)
            val excellentText = binding.excellentThresholdEditText.text?.toString() ?: DEFAULT_EXCELLENT_THRESHOLD.toString()
            val goodText = binding.goodThresholdEditText.text?.toString() ?: DEFAULT_GOOD_THRESHOLD.toString()
            val mediumText = binding.mediumThresholdEditText.text?.toString() ?: DEFAULT_MEDIUM_THRESHOLD.toString()
            val hourlyText = binding.minHourlyRateEditText.text?.toString() ?: "" // Vazio se não preenchido

            editor.putFloat(KEY_EXCELLENT_THRESHOLD, excellentText.replace(',', '.').toFloat())
            editor.putFloat(KEY_GOOD_THRESHOLD, goodText.replace(',', '.').toFloat())
            editor.putFloat(KEY_MEDIUM_THRESHOLD, mediumText.replace(',', '.').toFloat())

            val hourlyRate = hourlyText.toIntOrNull() ?: DEFAULT_MIN_HOURLY_RATE
            editor.putInt(KEY_MIN_HOURLY_RATE, hourlyRate)

            editor.putInt(KEY_FONT_SIZE, binding.fontSizeSeekBar.progress)
            editor.putInt(KEY_TRANSPARENCY, binding.transparencySeekBar.progress)

            editor.apply()
            Log.i(TAG, "Configurações salvas com sucesso.")

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar configurações: ${e.message}")
            Toast.makeText(this, "Erro ao salvar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Envia as configurações atualizadas para os serviços relevantes */
    private fun applySettingsToServices() {
        Log.d(TAG, "Enviando configurações atualizadas para OverlayService...")
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE_SETTINGS
            putExtra(OverlayService.EXTRA_FONT_SIZE, binding.fontSizeSeekBar.progress)
            putExtra(OverlayService.EXTRA_TRANSPARENCY, binding.transparencySeekBar.progress)
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar configurações para OverlayService: ${e.message}")
        }
    }


    /** Restaura os campos da UI para os valores padrão definidos */
    private fun resetToDefaults() {
        Log.d(TAG, "Restaurando configurações para os valores padrão.")
        binding.excellentThresholdEditText.setText(String.format(Locale.US, "%.2f", DEFAULT_EXCELLENT_THRESHOLD))
        binding.goodThresholdEditText.setText(String.format(Locale.US, "%.2f", DEFAULT_GOOD_THRESHOLD))
        binding.mediumThresholdEditText.setText(String.format(Locale.US, "%.2f", DEFAULT_MEDIUM_THRESHOLD))
        binding.minHourlyRateEditText.setText(DEFAULT_MIN_HOURLY_RATE.toString())
        binding.fontSizeSeekBar.progress = DEFAULT_FONT_SIZE_PERCENT
        binding.fontSizeValueTextView.text = "$DEFAULT_FONT_SIZE_PERCENT%"
        binding.transparencySeekBar.progress = DEFAULT_TRANSPARENCY_PERCENT
        binding.transparencyValueTextView.text = "$DEFAULT_TRANSPARENCY_PERCENT% transp"

        // Limpar mensagens de erro
        binding.excellentThresholdLayout.error = null
        binding.goodThresholdLayout.error = null
        binding.mediumThresholdLayout.error = null
        binding.minHourlyRateLayout.error = null
    }
}