package com.example.smartdriver.overlay

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.smartdriver.HistoryActivity
import com.example.smartdriver.MainActivity // Import MainActivity
import com.example.smartdriver.R
import com.example.smartdriver.ScreenCaptureService

class MenuView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "MenuView"
        // Ação para enviar ao serviço para desligar (pode ser a mesma do MainActivity)
        const val ACTION_REQUEST_SHUTDOWN = "com.example.smartdriver.overlay.REQUEST_SHUTDOWN"
    }

    init {
        // Infla o layout do menu
        LayoutInflater.from(context).inflate(R.layout.quick_menu_layout, this, true)
        orientation = VERTICAL

        // Encontra os itens do menu (TextViews)
        val mainItem: TextView = findViewById(R.id.menu_item_main)
        val historyItem: TextView = findViewById(R.id.menu_item_history)
        val shutdownItem: TextView = findViewById(R.id.menu_item_shutdown)

        // Define os listeners de clique
        mainItem.setOnClickListener {
            Log.d(TAG, "Menu: Tela Principal clicado")
            try {
                val intent = Intent(context, MainActivity::class.java).apply {
                    // FLAG_ACTIVITY_NEW_TASK é necessário para iniciar Activity de um Service
                    // FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP traz a instância existente se houver
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(intent)
                sendDismissMenuAction() // Pede para fechar o menu
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao iniciar MainActivity: ${e.message}", e)
                Toast.makeText(context, "Erro ao abrir tela principal", Toast.LENGTH_SHORT).show()
            }
        }

        historyItem.setOnClickListener {
            Log.d(TAG, "Menu: Histórico clicado")
            try {
                val intent = Intent(context, HistoryActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK // Necessário iniciar de Service
                }
                context.startActivity(intent)
                sendDismissMenuAction() // Pede para fechar o menu
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao iniciar HistoryActivity: ${e.message}", e)
                Toast.makeText(context, "Erro ao abrir histórico", Toast.LENGTH_SHORT).show()
            }
        }

        shutdownItem.setOnClickListener {
            Log.d(TAG, "Menu: Desligar clicado")
            // Envia uma ação para o OverlayService tratar o desligamento
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_REQUEST_SHUTDOWN
            }
            try {
                context.startService(intent)
                // O menu será fechado pelo OverlayService após processar o shutdown
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao enviar $ACTION_REQUEST_SHUTDOWN: ${e.message}", e)
            }
        }

        // Opcional: Adicionar um listener ao fundo para fechar o menu ao tocar fora
        // Isso requereria tornar a janela do menu focusable e detetar perda de foco,
        // ou adicionar um overlay de fundo transparente para capturar toques.
        // Para simplificar, vamos fechar apenas ao clicar num item ou o serviço fecha.
    }

    // Envia ação para o OverlayService fechar este menu
    private fun sendDismissMenuAction() {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_DISMISS_MENU // Ação para fechar
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar ACTION_DISMISS_MENU: ${e.message}", e)
        }
    }
}