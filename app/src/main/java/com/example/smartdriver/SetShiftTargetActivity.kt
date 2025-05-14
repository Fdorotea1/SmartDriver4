package com.example.smartdriver // Ou o teu package

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import com.example.smartdriver.overlay.OverlayService // Importa o teu OverlayService

class SetShiftTargetActivity : Activity() {

    companion object {
        const val TAG = "SetShiftTargetActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSetTargetDialog()
    }

    private fun showSetTargetDialog() {
        val builder = AlertDialog.Builder(this) // Usa o contexto da Activity
        builder.setTitle(getString(R.string.dialog_set_shift_target_title))

        // Inflar o layout customizado (opcional) ou criar EditText programaticamente
        // Usando o layout customizado:
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_set_target, null)
        val editTextTarget = dialogView.findViewById<EditText>(R.id.editTextShiftTarget)
        builder.setView(dialogView)

        // Se não quiseres layout customizado, podes criar o EditText programaticamente:
        // val editTextTarget = EditText(this)
        // editTextTarget.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        // editTextTarget.hint = getString(R.string.dialog_shift_target_hint)
        // builder.setView(editTextTarget)


        builder.setPositiveButton(getString(R.string.dialog_set_target_button_ok)) { dialog, _ ->
            val targetString = editTextTarget.text.toString()
            val targetValue = targetString.toDoubleOrNull()

            if (targetValue != null && targetValue > 0) {
                Log.d(TAG, "Meta definida pelo utilizador: $targetValue")
                // Enviar a meta para o OverlayService para realmente iniciar o turno
                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_TOGGLE_SHIFT_STATE // Ação para iniciar turno
                    putExtra(OverlayService.EXTRA_SHIFT_TARGET, targetValue) // Passa a meta
                }
                startService(serviceIntent) // Usa startService, não startForegroundService aqui
                Toast.makeText(this, getString(R.string.toast_target_set_to, targetValue), Toast.LENGTH_LONG).show()
                dialog.dismiss()
                finish() // Fecha esta Activity transparente
            } else {
                Toast.makeText(this, getString(R.string.toast_invalid_target_value), Toast.LENGTH_SHORT).show()
                // Não fechar o diálogo ou a activity, permitir nova tentativa
                // Para fechar e não fazer nada: dialog.dismiss(); finish();
            }
        }

        builder.setNegativeButton(getString(R.string.dialog_set_target_button_cancel)) { dialog, _ ->
            dialog.cancel()
            finish() // Fecha esta Activity transparente
        }

        builder.setOnCancelListener {
            finish() // Fecha esta Activity transparente se o diálogo for cancelado
        }

        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false) // Opcional: impede fechar ao tocar fora
        dialog.show()
    }
}