package com.example.smartdriver.permissions.steps

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.smartdriver.R

class StepPostNotificationsPermissionFragment : Fragment() {

    private var tvStatus: TextView? = null

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(animated: Boolean) {
        val ok = hasPermission()
        tvStatus?.apply {
            text = if (ok) "Estado: ✓ Ativo" else "Estado: ✕ Inativo"
            setTextColor(if (ok) 0xFF2E7D32.toInt() else 0xFF9E9E9E.toInt())
            if (animated) { alpha = 0f; animate().alpha(1f).setDuration(180).start() }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_permission_step, container, false)
        v.findViewById<TextView>(R.id.tvHeader).text = "Permitir notificações"
        v.findViewById<TextView>(R.id.tvExplanation).text =
            "Em Android 13+ precisamos da sua permissão para enviar notificações úteis (ex.: avisos de estado)."

        tvStatus = v.findViewById(R.id.tvStepStatus)
        setStatus(animated = false)

        val btnOpen = v.findViewById<Button>(R.id.btnOpenSettings)
        btnOpen.text = "Permitir notificações"
        btnOpen.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    if (!hasPermission()) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
                    }
                } catch (_: Exception) { /* ignora */ }
            }
        }

        // Sem botão “Continuar” local
        v.findViewById<Button>(R.id.btnIEnabled).visibility = View.GONE
        return v
    }

    override fun onResume() {
        super.onResume()
        setStatus(animated = true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        setStatus(animated = true)
    }
}
