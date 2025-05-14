package com.example.smartdriver.overlay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.smartdriver.HistoryActivity
import com.example.smartdriver.MainActivity
import com.example.smartdriver.R
// Importa OverlayService para aceder às suas constantes
import com.example.smartdriver.overlay.OverlayService // Garanta que esta importação existe

class MenuView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "MenuView"
        // As ações são definidas no OverlayService
    }

    // Referências às Views
    private val mainItem: TextView
    private val historyItem: TextView
    private val shutdownItem: TextView
    private val shiftStatusTextView: TextView
    private val shiftTimerTextView: TextView
    private val shiftEarningsTextView: TextView // <<< NOVA REFERÊNCIA
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
        shiftEarningsTextView = findViewById(R.id.textViewShiftEarnings) // <<< OBTER REFERÊNCIA
        shiftAverageTextView = findViewById(R.id.textViewShiftAveragePerHour)
        shiftToggleButton = findViewById(R.id.buttonShiftToggle)
        shiftEndButton = findViewById(R.id.buttonShiftEnd)

        // Listeners
        mainItem.setOnClickListener { navigateToActivity(MainActivity::class.java) }
        historyItem.setOnClickListener { navigateToActivity(HistoryActivity::class.java) }

        shutdownItem.setOnClickListener {
            showConfirmationDialog(
                title = context.getString(R.string.confirm_shutdown_title),
                message = context.getString(R.string.confirm_shutdown_message),
                positiveAction = {
                    sendServiceAction(OverlayService.ACTION_REQUEST_SHUTDOWN)
                    sendDismissMenuAction()
                },
                negativeAction = {
                    sendDismissMenuAction()
                }
            )
        }

        shiftToggleButton.setOnClickListener {
            sendServiceAction(OverlayService.ACTION_TOGGLE_SHIFT_STATE)
        }

        shiftEndButton.setOnClickListener {
            showConfirmationDialog(
                title = context.getString(R.string.confirm_end_shift_title),
                message = context.getString(R.string.confirm_end_shift_message),
                positiveAction = {
                    sendServiceAction(OverlayService.ACTION_END_SHIFT)
                }
                // negativeAction = {} // Opcional
            )
        }
    }

    private fun showConfirmationDialog(
        title: String,
        message: String,
        positiveAction: () -> Unit,
        negativeAction: (() -> Unit)? = null
    ) {
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(context.getString(R.string.confirm_dialog_yes)) { dialog, _ ->
                positiveAction.invoke()
                dialog.dismiss()
            }
            .setNegativeButton(context.getString(R.string.confirm_dialog_no)) { dialog, _ ->
                negativeAction?.invoke()
                dialog.dismiss()
            }
            .setOnCancelListener {
                negativeAction?.invoke()
            }
        try {
            val dialog = builder.create()
            val window = dialog.window
            if (window != null) {
                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                }
                window.setType(windowType)
            }
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao mostrar diálogo de confirmação: ${e.message}", e)
            Toast.makeText(context, "Erro ao mostrar diálogo", Toast.LENGTH_SHORT).show()
        }
    }

    fun updateShiftStatus(statusText: String, isActive: Boolean, isPaused: Boolean) {
        shiftStatusTextView.text = statusText
        updateShiftButtons(isActive, isPaused)
    }

    fun updateShiftTimer(timeText: String) {
        shiftTimerTextView.text = timeText
    }

    // <<< NOVO MÉTODO PARA ATUALIZAR GANHOS >>>
    fun updateShiftEarnings(earningsText: String) {
        shiftEarningsTextView.text = earningsText
    }
    // <<< FIM DO NOVO MÉTODO >>>

    fun updateShiftAverage(averageText: String) {
        shiftAverageTextView.text = averageText
    }

    private fun updateShiftButtons(isActive: Boolean, isPaused: Boolean) {
        try {
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