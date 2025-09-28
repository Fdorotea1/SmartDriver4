package com.example.smartdriver.permissions

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.smartdriver.MainActivity
import com.example.smartdriver.R
import com.example.smartdriver.permissions.steps.*

class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val STATE_IDX = "state_idx"
    }

    private val steps = buildList {
        add(StepIntroFragment())
        add(StepOverlayPermissionFragment())
        add(StepAccessibilityPermissionFragment())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(StepPostNotificationsPermissionFragment()) // Pedido de notificações (Android 13+)
        }
        add(StepBatteryOptimizationFragment())
        add(StepFinishFragment())
    }

    private var idx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        // Restaura o passo atual se o sistema recriar a activity
        idx = savedInstanceState?.getInt(STATE_IDX) ?: 0
        if (idx !in steps.indices) idx = 0

        findViewById<TextView>(R.id.tvTitle).text = "Configuração inicial"
        findViewById<Button>(R.id.btnBack).setOnClickListener { goBack() }
        findViewById<Button>(R.id.btnNext).setOnClickListener { goNext() }

        showStep()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_IDX, idx)
    }

    // Back físico: volta um passo, só fecha no primeiro ecrã
    override fun onBackPressed() {
        if (idx > 0) goBack() else super.onBackPressed()
    }

    private fun showStep() {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.fragmentContainer, steps[idx])
            .commit()

        findViewById<Button>(R.id.btnBack).isEnabled = idx > 0
        findViewById<Button>(R.id.btnNext).text =
            if (idx == steps.lastIndex) "Concluir" else "Continuar"
    }

    fun goNext() {
        if (idx < steps.lastIndex) {
            idx++
            showStep()
        } else {
            // Concluir: marcar onboarding e ir para o Main diretamente
            OnboardingPrefs.setOnboardingDone(this, true)
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        }
    }

    private fun goBack() {
        if (idx > 0) {
            idx--
            showStep()
        } else finish()
    }
}
