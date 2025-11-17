package com.example.smartdriver

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.smartdriver.permissions.OnboardingActivity
import com.example.smartdriver.permissions.OnboardingPrefs
import com.example.smartdriver.R

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    // DEV override: podes pôr true para forçar o wizard em QA
    private val FORCE_WIZARD = false

    // Timings
    private val SCALE_UP_MS        = 700L
    private val TYPE_DELAY_MS      = 80L
    private val HOLD_AFTER_TYPE_MS = 2000L
    private val FADE_OUT_MS        = 280L

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // ► Router ANTES de qualquer setContentView
        val done = OnboardingPrefs.isOnboardingDone(this)
        if (FORCE_WIZARD || !done) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        // Se já está concluído, segue com a animação e depois entra no Main
        setContentView(R.layout.activity_splash)

        // Blindagem extra contra “barra roxa”
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
                setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            }
        }

        val title = findViewById<TextView>(R.id.splashTitle)
        val tagline = findViewById<TextView>(R.id.splashTagline)

        // Estado inicial
        title.apply {
            text = "SmartDriver"
            scaleX = 0.25f
            scaleY = 0.25f
            alpha = 1f
        }
        tagline.apply {
            text = ""
            alpha = 1f
        }

        // 1) Zoom do título
        val sx = ObjectAnimator.ofFloat(title, View.SCALE_X, 1f).apply { duration = SCALE_UP_MS }
        val sy = ObjectAnimator.ofFloat(title, View.SCALE_Y, 1f).apply { duration = SCALE_UP_MS }
        val scale = AnimatorSet().apply {
            interpolator = AccelerateDecelerateInterpolator()
            playTogether(sx, sy)
        }

        // 2) Depois do zoom, escreve "Be better"
        scale.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                typeWriter(tagline, "Be better", TYPE_DELAY_MS) {
                    // 3) Espera 2s e faz fade-out
                    tagline.postDelayed({
                        val fadeTitle = ObjectAnimator.ofFloat(title, View.ALPHA, 1f, 0f).apply { duration = FADE_OUT_MS }
                        val fadeTag   = ObjectAnimator.ofFloat(tagline, View.ALPHA, 1f, 0f).apply { duration = FADE_OUT_MS }
                        AnimatorSet().apply {
                            playTogether(fadeTitle, fadeTag)
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) { goToMain() }
                            })
                            start()
                        }
                    }, HOLD_AFTER_TYPE_MS)
                }
            }
        })

        scale.start()
    }

    private fun typeWriter(view: TextView, fullText: String, delayMs: Long, onComplete: () -> Unit) {
        view.text = ""
        var i = 0
        val runnable = object : Runnable {
            override fun run() {
                if (i <= fullText.length) {
                    view.text = fullText.substring(0, i)
                    i++
                    view.postDelayed(this, delayMs)
                } else {
                    onComplete()
                }
            }
        }
        view.post(runnable)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
