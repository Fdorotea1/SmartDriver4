package com.example.smartdriver

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("CustomSplashScreen") // Justificativa: Implementação customizada necessária antes da API 31 e para consistência
class SplashActivity : AppCompatActivity() {

    // Tempo que a splash screen ficará visível (em milissegundos)
    private val SPLASH_DISPLAY_LENGTH: Long = 2000 // 2 segundos

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Handler para atrasar a transição para a MainActivity
        Handler(Looper.getMainLooper()).postDelayed({
            // Cria um Intent para iniciar a MainActivity
            val mainIntent = Intent(this@SplashActivity, MainActivity::class.java)
            startActivity(mainIntent)

            // Fecha a SplashActivity para que o usuário não possa voltar a ela
            finish()
        }, SPLASH_DISPLAY_LENGTH)
    }
}