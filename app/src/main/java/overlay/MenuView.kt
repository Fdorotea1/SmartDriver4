package com.example.smartdriver.overlay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import androidx.core.view.ViewCompat
import com.example.smartdriver.HistoryActivity
import com.example.smartdriver.MainActivity
import com.example.smartdriver.R

class MenuView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object { private const val TAG = "MenuView" }

    // Views
    private val mainItem: TextView
    private val historyItem: TextView
    private val shutdownItem: TextView
    private val shiftStatusTextView: TextView
    private val shiftTimerTextView: TextView
    private val timeToTargetTextView: TextView
    private val shiftAverageTextView: TextView
    private val shiftToggleButton: Button
    private val shiftEndButton: Button
    private val expectedEndTimeTextView: TextView
    private val shiftTargetTextView: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.quick_menu_layout, this, true)
        orientation = VERTICAL

        mainItem = findViewById(R.id.menu_item_main)
        historyItem = findViewById(R.id.menu_item_history)
        shutdownItem = findViewById(R.id.menu_item_shutdown)
        shiftStatusTextView = findViewById(R.id.textViewShiftStatus)
        shiftTimerTextView = findViewById(R.id.textViewShiftTimer)
        timeToTargetTextView = findViewById(R.id.textViewTimeToTarget)
        shiftAverageTextView = findViewById(R.id.textViewShiftAveragePerHour)
        shiftToggleButton = findViewById(R.id.buttonShiftToggle)
        shiftEndButton = findViewById(R.id.buttonShiftEnd)
        expectedEndTimeTextView = findViewById(R.id.textViewExpectedEndTime)
        shiftTargetTextView = findViewById(R.id.textViewShiftTarget)

        // A11y básica
        ViewCompat.setAccessibilityHeading(mainItem, true)
        mainItem.contentDescription = "Abrir ecrã principal"
        historyItem.contentDescription = "Abrir histórico"
        shutdownItem.contentDescription = "Desligar serviço"
        shiftToggleButton.contentDescription = "Iniciar, pausar ou retomar turno"
        shiftEndButton.contentDescription = "Terminar turno"

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
                negativeAction?.invoke(); dialog.dismiss()
            }
            .setOnCancelListener { negativeAction?.invoke() }

        try {
            val dialog = builder.create()
            dialog.window?.let { window ->
                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                }
                try { window.setType(windowType) } catch (_: Throwable) {}
            }
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao mostrar diálogo: ${e.message}", e)
            Toast.makeText(context, "$title - $message (Use os botões)", Toast.LENGTH_LONG).show()
        }
    }

    // ----------------- API de atualização -----------------

    fun updateShiftStatus(statusText: String, isActive: Boolean, isPaused: Boolean) {
        shiftStatusTextView.text = statusText
        // Cores simples sem depender de resources novos
        val color = when {
            !isActive -> ColorInt("#455A64")      // cinza-azulado
            isPaused -> ColorInt("#F9A825")       // amarelo
            else -> ColorInt("#2E7D32")           // verde
        }
        shiftStatusTextView.setTextColor(color)
        updateShiftButtons(isActive, isPaused)
    }

    fun updateShiftTimer(timeText: String) {
        shiftTimerTextView.text = timeText
    }

    fun updateTimeToTarget(timeToTargetText: String) {
        timeToTargetTextView.text = timeToTargetText
    }

    fun updateShiftAverage(averageText: String) {
        shiftAverageTextView.text = averageText
    }

    fun updateExpectedEndTime(endTimeText: String) {
        expectedEndTimeTextView.text = endTimeText
    }

    fun updateShiftTarget(targetText: String) {
        shiftTargetTextView.text = targetText
    }

    // ----------------- Internos -----------------

    private fun updateShiftButtons(isActive: Boolean, isPaused: Boolean) {
        try {
            if (!isActive) {
                shiftToggleButton.text = context.getString(R.string.shift_action_start)
                shiftToggleButton.isEnabled = true
                shiftEndButton.visibility = View.GONE
            } else {
                shiftEndButton.visibility = View.VISIBLE
                shiftToggleButton.text = if (isPaused)
                    context.getString(R.string.shift_action_resume)
                else
                    context.getString(R.string.shift_action_pause)
                shiftToggleButton.isEnabled = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro em updateShiftButtons: ${e.message}", e)
            shiftToggleButton.text = context.getString(R.string.shift_action_start)
            shiftToggleButton.isEnabled = true
            shiftEndButton.visibility = View.GONE
        }
    }

    private fun navigateToActivity(activityClass: Class<*>) {
        Log.d(TAG, "Abrir ${activityClass.simpleName}")
        try {
            val intent = Intent(context, activityClass).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            context.startActivity(intent)
            sendDismissMenuAction()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar ${activityClass.simpleName}: ${e.message}", e)
            Toast.makeText(context, "Erro ao abrir ecrã: ${activityClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(context, OverlayService::class.java).apply { this.action = action }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Erro ao enviar ação '$action': ${e.message}", e)
            Toast.makeText(context, "Não foi possível executar a ação com a app em background.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Erro geral ao enviar ação '$action': ${e.message}", e)
        }
    }

    private fun sendDismissMenuAction() {
        sendServiceAction(OverlayService.ACTION_DISMISS_MENU)
    }

    // Pequena helper para cores via hex
    private fun ColorInt(hex: String): Int = try {
        android.graphics.Color.parseColor(hex)
    } catch (_: Throwable) { android.graphics.Color.DKGRAY }
}