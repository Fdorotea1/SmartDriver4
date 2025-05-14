package com.example.smartdriver.overlay // Ou o teu package correto

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
import com.example.smartdriver.overlay.OverlayService // Garante que esta importação existe se OverlayService estiver no mesmo package, senão ajusta

class MenuView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "MenuView"
    }

    // Referências às Views
    private val mainItem: TextView
    private val historyItem: TextView
    private val shutdownItem: TextView
    private val shiftStatusTextView: TextView
    private val shiftTimerTextView: TextView
    // private val shiftEarningsTextView: TextView // <<< REMOVIDA REFERÊNCIA
    private val timeToTargetTextView: TextView    // <<< NOVA REFERÊNCIA
    private val shiftAverageTextView: TextView
    private val shiftToggleButton: Button
    private val shiftEndButton: Button

    init {
        LayoutInflater.from(context).inflate(R.layout.quick_menu_layout, this, true)
        orientation = VERTICAL // Certifica-te que esta orientação é a desejada para o LinearLayout raiz do MenuView

        mainItem = findViewById(R.id.menu_item_main)
        historyItem = findViewById(R.id.menu_item_history)
        shutdownItem = findViewById(R.id.menu_item_shutdown)
        shiftStatusTextView = findViewById(R.id.textViewShiftStatus)
        shiftTimerTextView = findViewById(R.id.textViewShiftTimer)
        // shiftEarningsTextView = findViewById(R.id.textViewShiftEarnings) // <<< LINHA REMOVIDA
        timeToTargetTextView = findViewById(R.id.textViewTimeToTarget)       // <<< NOVA LINHA
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
                    // Se o utilizador cancelar o shutdown, talvez queira manter o menu aberto
                    // sendDismissMenuAction() // Opcional: descomentar se quiser fechar o menu ao cancelar
                }
            )
        }

        shiftToggleButton.setOnClickListener {
            // A lógica de pedir a meta será tratada no OverlayService
            // ao receber ACTION_TOGGLE_SHIFT_STATE quando o turno não está ativo.
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
        val builder = AlertDialog.Builder(context) // Aplica um tema se tiveres um (R.style.AlertDialogTheme)
            .setTitle(title)
            .setMessage(message)
            .setIcon(android.R.drawable.ic_dialog_alert) // Ícone padrão do sistema
            .setPositiveButton(context.getString(R.string.confirm_dialog_yes)) { dialog, _ ->
                positiveAction.invoke()
                dialog.dismiss()
            }
            .setNegativeButton(context.getString(R.string.confirm_dialog_no)) { dialog, _ ->
                negativeAction?.invoke()
                dialog.dismiss()
            }
            .setOnCancelListener {
                negativeAction?.invoke() // Garante que negativeAction é chamada se o diálogo for cancelado
            }
        try {
            val dialog = builder.create()
            // Tenta definir o tipo de janela para overlays
            // Isto é importante para que o diálogo apareça sobre outras apps se o menu for um overlay
            dialog.window?.let { window ->
                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT // Requer permissão SYSTEM_ALERT_WINDOW
                }
                try {
                    window.setType(windowType)
                } catch (e: IllegalAccessError) {
                    // Em alguns dispositivos/versões do Android, definir TYPE_APPLICATION_OVERLAY
                    // pode falhar se o contexto não for o correto, mesmo com a permissão.
                    // O diálogo pode ainda funcionar, mas pode não aparecer sobre tudo.
                    Log.w(TAG, "Não foi possível definir o tipo de janela para o diálogo de confirmação.", e)
                }
            }
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao mostrar diálogo de confirmação: ${e.message}", e)
            // Fallback para um Toast se o diálogo falhar completamente
            Toast.makeText(context, "$title - $message (Use botões)", Toast.LENGTH_LONG).show()
            // Considerar chamar positiveAction/negativeAction aqui se o diálogo é crítico e falhou
        }
    }

    fun updateShiftStatus(statusText: String, isActive: Boolean, isPaused: Boolean) {
        shiftStatusTextView.text = statusText
        updateShiftButtons(isActive, isPaused)
    }

    fun updateShiftTimer(timeText: String) {
        shiftTimerTextView.text = timeText
    }

    // <<< FUNÇÃO REMOVIDA >>>
    // fun updateShiftEarnings(earningsText: String) {
    //    shiftEarningsTextView.text = earningsText
    // }

    // <<< NOVA FUNÇÃO PARA ATUALIZAR O TEMPO ATÉ À META >>>
    fun updateTimeToTarget(timeToTargetText: String) {
        timeToTargetTextView.text = timeToTargetText
    }
    // <<< FIM DA NOVA FUNÇÃO >>>

    fun updateShiftAverage(averageText: String) {
        shiftAverageTextView.text = averageText
    }

    private fun updateShiftButtons(isActive: Boolean, isPaused: Boolean) {
        try {
            if (!isActive) {
                shiftToggleButton.text = context.getString(R.string.shift_action_start)
                shiftToggleButton.isEnabled = true
                shiftEndButton.visibility = View.GONE // Esconde o botão de terminar se não há turno
                // Define cores dos botões (opcional, requer que as cores existam em colors.xml)
                // try { shiftToggleButton.setBackgroundColor(ContextCompat.getColor(context, R.color.button_start_color)) }
                // catch (e: Resources.NotFoundException) { Log.w(TAG, "Cor button_start_color não encontrada", e)}
            } else {
                shiftEndButton.visibility = View.VISIBLE // Mostra o botão de terminar turno
                if (isPaused) {
                    shiftToggleButton.text = context.getString(R.string.shift_action_resume)
                    // try { shiftToggleButton.setBackgroundColor(ContextCompat.getColor(context, R.color.button_resume_color)) }
                    // catch (e: Resources.NotFoundException) { Log.w(TAG, "Cor button_resume_color não encontrada", e)}
                } else {
                    shiftToggleButton.text = context.getString(R.string.shift_action_pause)
                    // try { shiftToggleButton.setBackgroundColor(ContextCompat.getColor(context, R.color.button_pause_color)) }
                    // catch (e: Resources.NotFoundException) { Log.w(TAG, "Cor button_pause_color não encontrada", e)}
                }
                shiftToggleButton.isEnabled = true // Botão de alternar pausa/retomar está sempre ativo se o turno estiver ativo
                // try { shiftEndButton.setBackgroundColor(ContextCompat.getColor(context, R.color.button_end_color)) }
                // catch (e: Resources.NotFoundException) { Log.w(TAG, "Cor button_end_color não encontrada", e)}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro inesperado em updateShiftButtons: ${e.message}", e)
            // Em caso de erro, tenta redefinir para um estado seguro
            shiftToggleButton.text = context.getString(R.string.shift_action_start)
            shiftToggleButton.isEnabled = true
            shiftEndButton.visibility = View.GONE
        }
    }

    private fun navigateToActivity(activityClass: Class<*>) {
        Log.d(TAG, "Navegando para ${activityClass.simpleName}")
        try {
            val intent = Intent(context, activityClass).apply {
                // Usar FLAG_ACTIVITY_REORDER_TO_FRONT se a Activity já existir na pilha e quiser trazê-la para frente
                // em vez de limpar o topo. Depende do comportamento desejado.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            context.startActivity(intent)
            sendDismissMenuAction() // Fecha o menu após iniciar a navegação
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar ${activityClass.simpleName}: ${e.message}", e)
            Toast.makeText(context, "Erro ao abrir ecrã: ${activityClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(context, OverlayService::class.java).apply {
            this.action = action
        }
        try {
            // Iniciar o serviço em foreground se for Android O ou superior, caso contrário, serviço normal
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Erro ao enviar ação '$action' (App em background?): ${e.message}", e)
            Toast.makeText(context, "Não foi possível executar a ação com o app em background.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Erro geral ao enviar ação '$action' para OverlayService: ${e.message}", e)
        }
    }

    private fun sendDismissMenuAction() {
        sendServiceAction(OverlayService.ACTION_DISMISS_MENU)
    }
}