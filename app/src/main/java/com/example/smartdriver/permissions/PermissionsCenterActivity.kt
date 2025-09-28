package com.example.smartdriver.permissions

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.smartdriver.R

class PermissionsCenterActivity : AppCompatActivity() {

    data class Row(
        val name: String,
        val status: () -> Boolean,
        val open: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions_center)

        val rows = listOf(
            Row("Sobrepor ecrã",
                status = { PermissionsHelper.isOverlayGranted(this) },
                open = { PermissionsHelper.requestOverlayPermission(this) }),
            Row("Serviço de Acessibilidade",
                status = { PermissionsHelper.isAnyAccessibilityFromThisAppEnabled(this) },
                open = { PermissionsHelper.openAccessibilitySettings(this) }),
            Row("Leitor de Notificações",
                status = { PermissionsHelper.isNotificationListenerEnabled(this) },
                open = { PermissionsHelper.openNotificationListenerSettings(this) }),
            Row("Ignorar otimizações de bateria",
                status = { PermissionsHelper.isIgnoringBatteryOptimizations(this) },
                open = { PermissionsHelper.requestIgnoreBatteryOptimizations(this) })
        )

        val container = findViewById<LinearLayout>(R.id.container)
        val inflater = LayoutInflater.from(this)
        rows.forEach { r ->
            val row = inflater.inflate(R.layout.item_permission_row, container, false)
            row.findViewById<TextView>(R.id.tvName).text = r.name
            fun refresh(animated: Boolean) {
                val ok = r.status()
                val tv = row.findViewById<TextView>(R.id.tvStatus)
                tv.text = if (ok) "✓ Ativo" else "✕ Inativo"
                tv.setTextColor(if (ok) 0xFF2E7D32.toInt() else 0xFF9E9E9E.toInt())
                if (animated) { tv.alpha = 0f; tv.animate().alpha(1f).setDuration(180).start() }
            }
            refresh(animated = false)
            row.findViewById<Button>(R.id.btnOpen).setOnClickListener {
                r.open()
                // refresca ligeiramente depois de voltar das definições
                row.postDelayed({ refresh(animated = true) }, 800)
            }
            container.addView(row)
        }
    }
}
