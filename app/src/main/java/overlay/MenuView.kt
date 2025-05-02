package com.example.smartdriver.overlay

import android.content.Context
import android.content.Intent
import android.content.res.Resources // <<< ADICIONAR IMPORT
import android.os.Build // <<< ADICIONAR IMPORT
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.smartdriver.HistoryActivity
import com.example.smartdriver.MainActivity
import com.example.smartdriver.R

class MenuView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "MenuView"
        const val ACTION_REQUEST_SHUTDOWN = "com.example.smartdriver.overlay.REQUEST_SHUTDOWN"
        const val ACTION_TOGGLE_SHIFT_STATE = "com.example.smartdriver.overlay.TOGGLE_SHIFT_STATE"
        const val ACTION_END_SHIFT = "com.example.smartdriver.overlay.END_SHIFT"
    }

    // Referências às Views
    private val mainItem: TextView
    private val historyItem: TextView
    private val shutdownItem: TextView
    private val shiftStatusTextView: TextView
    private val shiftTimerTextView: TextView
    private val shiftAverageTextView: TextView
    private val shiftToggleButton: Button
    private val shiftEndButton: Button

    init {
        LayoutInflater.from(context).inflate(R.layout.quick_menu_layout, this, true)
        orientation = VERTICAL

        mainItem = findViewById(R.id.menu_item_main)
        historyItem = findViewById(R.id.menu_item_history)
        shutdownItem = findViewById(R.id.menu_item_shutdown)
        shiftStatusTextView = findViewById(R.id.textViewShiftStatus)
        shiftTimerTextView = findViewById(R.id.textViewShiftTimer)
        shiftAverageTextView = findViewById(R.id.textViewShiftAveragePerHour)
        shiftToggleButton = findViewById(R.id.buttonShiftToggle)
        shiftEndButton = findViewById(R.id.buttonShiftEnd)

        // Listeners
        mainItem.setOnClickListener { navigateToActivity(MainActivity::class.java) }
        historyItem.setOnClickListener { navigateToActivity(HistoryActivity::class.java) }
        shutdownItem.setOnClickListener { sendServiceAction(ACTION_REQUEST_SHUTDOWN) }
        shiftToggleButton.setOnClickListener { sendServiceAction(ACTION_TOGGLE_SHIFT_STATE) }
        shiftEndButton.setOnClickListener { sendServiceAction(ACTION_END_SHIFT) }
    }

    // --- Funções Públicas para Atualizar a UI ---

    fun updateShiftStatus(statusText: String, isActive: Boolean, isPaused: Boolean) {
        shiftStatusTextView.text = statusText
        updateShiftButtons(isActive, isPaused)
    }

    fun updateShiftTimer(timeText: String) {
        shiftTimerTextView.text = timeText
    }

    fun updateShiftAverage(averageText: String) {
        shiftAverageTextView.text = averageText
    }

    // --- Funções Auxiliares ---

    private fun updateShiftButtons(isActive: Boolean, isPaused: Boolean) {
        try { // Adiciona try-catch geral para robustez na UI
            if (!isActive) {
                shiftToggleButton.text = context.getString(R.string.shift_action_start)
                shiftToggleButton.isEnabled = true
                shiftEndButton.visibility = View.GONE
                try { shiftToggleButton.setBackgroundColor(ContextCompat.getColor(context, R.color.button_start)) }
                catch (e: Resources.NotFoundException) { Log.e(TAG, "Cor button_start não encontrada", e)}
            } else {
                shiftEndButton.visibility = View.VISIBLE
                if (isPaused) {
                    shiftToggleButton.text = context.getString(R.string.shift_action_resume)
                    shiftToggleButton.isEnabled = true
                    try { shiftToggleButton.setBackgroundColor(ContextCompat.getColor(context, R.color.button_resume)) }
                    catch (e: Resources.NotFoundException) { Log.e(TAG, "Cor button_resume não encontrada", e)}
                } else {
                    shiftToggleButton.text = context.getString(R.string.shift_action_pause)
                    shiftToggleButton.isEnabled = true
                    try { shiftToggleButton.setBackgroundColor(ContextCompat.getColor(context, R.color.button_pause)) }
                    catch (e: Resources.NotFoundException) { Log.e(TAG, "Cor button_pause não encontrada", e)}
                }
                try { shiftEndButton.setBackgroundColor(ContextCompat.getColor(context, R.color.button_end)) }
                catch (e: Resources.NotFoundException) { Log.e(TAG, "Cor button_end não encontrada", e)}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro inesperado em updateShiftButtons: ${e.message}", e)
        }
    }

    private fun navigateToActivity(activityClass: Class<*>) {
        Log.d(TAG, "Navegando para ${activityClass.simpleName}")
        try {
            val intent = Intent(context, activityClass).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
            sendDismissMenuAction()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar ${activityClass.simpleName}: ${e.message}", e)
            Toast.makeText(context, "Erro ao abrir ecrã", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(context, OverlayService::class.java).apply {
            this.action = action
        }
        try {
            // *** USA Build para verificar versão ***
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar ação '$action' para OverlayService: ${e.message}", e)
        }
    }

    private fun sendDismissMenuAction() {
        sendServiceAction(OverlayService.ACTION_DISMISS_MENU)
    }
}