package com.example.smartdriver

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.smartdriver.databinding.ActivitySettingsBinding
import com.example.smartdriver.overlay.OverlayService
import com.example.smartdriver.overlay.OverlayView
import com.example.smartdriver.utils.*

import java.util.*

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        private const val PREFS_NAME = "SmartDriverSettings"

        // Limiares
        private const val KEY_GOOD_KM_THRESHOLD = "good_km_threshold"
        private const val KEY_POOR_KM_THRESHOLD = "poor_km_threshold"
        private const val KEY_GOOD_HOUR_THRESHOLD = "good_hour_threshold"
        private const val KEY_POOR_HOUR_THRESHOLD = "poor_hour_threshold"

        private const val DEFAULT_GOOD_KM_THRESHOLD = 1.20
        private const val DEFAULT_POOR_KM_THRESHOLD = 0.70
        private const val DEFAULT_GOOD_HOUR_THRESHOLD = 15.0
        private const val DEFAULT_POOR_HOUR_THRESHOLD = 8.0

        // Aparência
        private const val KEY_FONT_SIZE = "font_size_percent"
        private const val KEY_TRANSPARENCY = "transparency_percent"
        private const val KEY_OVERLAY_Y_OFFSET = "overlay_y_offset_dp" // <-- NOVO
        private const val DEFAULT_FONT_SIZE_PERCENT = 100
        private const val DEFAULT_TRANSPARENCY_PERCENT = 15
        private const val DEFAULT_OVERLAY_Y_OFFSET = 50 // <-- NOVO

        fun getGoodKmThreshold(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(KEY_GOOD_KM_THRESHOLD, DEFAULT_GOOD_KM_THRESHOLD.toFloat()).toDouble()

        fun getPoorKmThreshold(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(KEY_POOR_KM_THRESHOLD, DEFAULT_POOR_KM_THRESHOLD.toFloat()).toDouble()

        fun getGoodHourThreshold(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(KEY_GOOD_HOUR_THRESHOLD, DEFAULT_GOOD_HOUR_THRESHOLD.toFloat()).toDouble()

        fun getPoorHourThreshold(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(KEY_POOR_HOUR_THRESHOLD, DEFAULT_POOR_HOUR_THRESHOLD.toFloat()).toDouble()

        fun getFontSize(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE_PERCENT)

        fun getTransparency(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_TRANSPARENCY, DEFAULT_TRANSPARENCY_PERCENT)

        fun getOverlayYOffsetDp(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_OVERLAY_Y_OFFSET, DEFAULT_OVERLAY_Y_OFFSET)
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var previewOverlayView: OverlayView
    private lateinit var previewContainer: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        previewContainer = binding.previewOverlayContainer
        previewOverlayView = OverlayView(this)

        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        previewOverlayView.layoutParams = layoutParams
        previewContainer.addView(previewOverlayView)

        loadSettings()
        setupListeners()
        setupInitialPreview()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }

    private fun setupInitialPreview() {
        val sampleOffer = OfferData(
            value = "10.50", pickupDistance = "2.1", tripDistance = "8.4",
            pickupDuration = "5", tripDuration = "15",
            distance = "10.5", duration = "20"
        )
        val sampleResult = EvaluationResult(
            kmRating = IndividualRating.MEDIUM,
            hourRating = IndividualRating.GOOD,
            combinedBorderRating = BorderRating.YELLOW
        )
        previewOverlayView.updateState(sampleResult, sampleOffer)
        updatePreviewAppearance()
        updatePreviewOverlayY(getOverlayYOffsetDp(this))
    }

    private fun loadSettings() {
        binding.goodKmThresholdEditText.setText(
            String.format(Locale.US, "%.2f", getGoodKmThreshold(this))
        )
        binding.poorKmThresholdEditText.setText(
            String.format(Locale.US, "%.2f", getPoorKmThreshold(this))
        )
        binding.goodHourThresholdEditText.setText(
            String.format(Locale.US, "%.2f", getGoodHourThreshold(this))
        )
        binding.poorHourThresholdEditText.setText(
            String.format(Locale.US, "%.2f", getPoorHourThreshold(this))
        )

        binding.fontSizeSeekBar.progress = getFontSize(this)
        binding.fontSizeValueTextView.text = "${getFontSize(this)}%"

        binding.transparencySeekBar.progress = getTransparency(this)
        binding.transparencyValueTextView.text = "${getTransparency(this)}% transp"

        binding.overlayYOffsetSeekBar.progress = getOverlayYOffsetDp(this) // <-- NOVO
        binding.overlayYOffsetValueTextView.text = "${getOverlayYOffsetDp(this)} dp" // <-- NOVO

        updatePreviewAppearance()
        updatePreviewOverlayY(getOverlayYOffsetDp(this))
    }

    private fun setupListeners() {
        binding.fontSizeSeekBar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.fontSizeValueTextView.text = "$progress%"
                updatePreviewAppearance()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.transparencySeekBar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.transparencyValueTextView.text = "$progress% transp"
                updatePreviewAppearance()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // NOVO: listener para Y Offset
        binding.overlayYOffsetSeekBar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.overlayYOffsetValueTextView.text = "$progress dp"
                updatePreviewOverlayY(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.saveSettingsButton.setOnClickListener {
            if (validateInputs()) {
                saveSettings()
                applySettingsToServices()
                finish()
            }
        }

        binding.resetToDefaultsButton.setOnClickListener {
            resetToDefaults()
            Toast.makeText(this, "Padrões restaurados.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePreviewAppearance() {
        val fontSizePercent = binding.fontSizeSeekBar.progress
        val transparencyPercent = binding.transparencySeekBar.progress
        val fontScale = fontSizePercent / 100f
        val alpha = 1.0f - (transparencyPercent / 100f)
        previewOverlayView.updateFontSize(fontScale)
        previewOverlayView.alpha = alpha
    }

    private fun updatePreviewOverlayY(yDp: Int) {
        val density = resources.displayMetrics.density
        val lp = previewOverlayView.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = (yDp * density).toInt()
        previewOverlayView.layoutParams = lp
    }

    private fun validateInputs(): Boolean {
        return true // Mantém a tua lógica anterior de validação aqui
    }

    private fun saveSettings() {
        val editor = prefs.edit()
        editor.putFloat(KEY_GOOD_KM_THRESHOLD, binding.goodKmThresholdEditText.text.toString().replace(',', '.').toFloat())
        editor.putFloat(KEY_POOR_KM_THRESHOLD, binding.poorKmThresholdEditText.text.toString().replace(',', '.').toFloat())
        editor.putFloat(KEY_GOOD_HOUR_THRESHOLD, binding.goodHourThresholdEditText.text.toString().replace(',', '.').toFloat())
        editor.putFloat(KEY_POOR_HOUR_THRESHOLD, binding.poorHourThresholdEditText.text.toString().replace(',', '.').toFloat())
        editor.putInt(KEY_FONT_SIZE, binding.fontSizeSeekBar.progress)
        editor.putInt(KEY_TRANSPARENCY, binding.transparencySeekBar.progress)
        editor.putInt(KEY_OVERLAY_Y_OFFSET, binding.overlayYOffsetSeekBar.progress) // <-- NOVO
        editor.apply()
    }

    private fun applySettingsToServices() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE_SETTINGS
            putExtra(OverlayService.EXTRA_FONT_SIZE, binding.fontSizeSeekBar.progress)
            putExtra(OverlayService.EXTRA_TRANSPARENCY, binding.transparencySeekBar.progress)
            putExtra("extra_overlay_y_offset", binding.overlayYOffsetSeekBar.progress) // <-- NOVO
        }
        startService(intent)
    }

    private fun resetToDefaults() {
        binding.fontSizeSeekBar.progress = DEFAULT_FONT_SIZE_PERCENT
        binding.transparencySeekBar.progress = DEFAULT_TRANSPARENCY_PERCENT
        binding.overlayYOffsetSeekBar.progress = DEFAULT_OVERLAY_Y_OFFSET // <-- NOVO
        updatePreviewAppearance()
        updatePreviewOverlayY(DEFAULT_OVERLAY_Y_OFFSET)
    }
}
