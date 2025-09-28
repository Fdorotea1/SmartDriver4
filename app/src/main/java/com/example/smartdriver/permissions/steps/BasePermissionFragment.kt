package com.example.smartdriver.permissions.steps

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.smartdriver.R

abstract class BasePermissionFragment : Fragment() {
    abstract fun headerText(): String
    abstract fun explanationText(): String
    abstract fun openSettings()
    abstract fun isGranted(): Boolean

    private var tvStatus: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_permission_step, container, false)
        v.findViewById<TextView>(R.id.tvHeader).text = headerText()
        v.findViewById<TextView>(R.id.tvExplanation).text = explanationText()
        tvStatus = v.findViewById(R.id.tvStepStatus)

        v.findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            openSettings()
            // pequeno atraso para o utilizador voltar das definições e o onResume atualizar também
            v.postDelayed({ refreshStatus(animated = true) }, 900)
        }

        // Não usamos o botão local de “Continuar”: o wizard tem barra inferior
        v.findViewById<Button>(R.id.btnIEnabled).visibility = View.GONE

        refreshStatus(animated = false)
        return v
    }

    override fun onResume() {
        super.onResume()
        refreshStatus(animated = true)
    }

    private fun refreshStatus(animated: Boolean) {
        val ok = isGranted()
        tvStatus?.apply {
            text = if (ok) "Estado: ✓ Ativo" else "Estado: ✕ Inativo"
            setTextColor(if (ok) Color.parseColor("#2E7D32") else Color.parseColor("#9E9E9E"))
            if (animated) {
                alpha = 0f
                animate().alpha(1f).setDuration(180).start()
            }
        }
    }
}
