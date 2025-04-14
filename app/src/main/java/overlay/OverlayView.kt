package com.example.smartdriver.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.example.smartdriver.R // Import R para acessar cores e outros recursos
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.OfferRating
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * View personalizada que implementa a interface visual do "semáforo"
 * que aparece sobre outros apps para indicar a qualidade das ofertas.
 * Esta versão não é tocável/arrastável.
 */
class OverlayView(context: Context) : View(context) {

    companion object {
        private const val TAG = "OverlayView"

        // Cores do semáforo (Podem ser definidas em colors.xml também)
        private val COLOR_EXCELLENT = Color.parseColor("#4CAF50") // Verde
        private val COLOR_GOOD = Color.parseColor("#8BC34A")      // Verde claro
        private val COLOR_MEDIUM = Color.parseColor("#FFC107")     // Amarelo
        private val COLOR_POOR = Color.parseColor("#F44336")       // Vermelho
        private val COLOR_UNKNOWN = Color.parseColor("#9E9E9E")    // Cinza
        private val TEXT_COLOR = Color.WHITE                      // Cor do texto dentro do semáforo

        // Constantes de animação
        private const val MAX_PULSE_CYCLES = 3      // Quantos pulsos ao mostrar
        private const val PULSE_DURATION_MS = 30L   // Intervalo da animação (mais lento)
        private const val PULSE_MIN_SCALE = 0.9f    // Escala mínima do pulso
        private const val PULSE_MAX_SCALE = 1.1f    // Escala máxima do pulso
        private const val PULSE_STEP = 0.03f        // Incremento/decremento da escala
    }

    // Estado atual do overlay
    private var currentRating = OfferRating.UNKNOWN
    private var currentOfferData: OfferData? = null

    // Configurações de aparência (serão atualizadas pelo OverlayService)
    private var fontSizeScale = 1.0f // Escala para texto (1.0f = 100%)
    private var viewAlpha = 0.85f    // Transparência inicial (0.0 = invisível, 1.0 = opaco)

    // Animação de Pulso
    private var isAnimating = false
    private var pulseScale = 1.0f
    private var pulseIncreasing = true
    private var pulseAnimationCycles = 0
    private val animationHandler = Handler(Looper.getMainLooper())

    // Objetos Paint para desenho (inicializados com anti-aliasing)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_UNKNOWN // Cor inicial
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        textAlign = Paint.Align.CENTER
        textSize = 24f // Tamanho base, será ajustado pela escala e tamanho do círculo
        // Considerar usar uma fonte mais legível se necessário
        // typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        textAlign = Paint.Align.CENTER
        textSize = 20f // Tamanho base
    }

    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        textAlign = Paint.Align.CENTER
        textSize = 16f // Tamanho base
    }

    init {
        Log.d(TAG, "OverlayView inicializada")
        // A transparência inicial é definida via alpha na view
        alpha = viewAlpha
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Calcular raio do círculo (baseado no menor lado da view)
        val centerX = width / 2f
        val centerY = height / 2f
        var radius = min(width, height) / 2f

        // Aplicar escala da animação de pulso, se ativa
        if (isAnimating) {
            radius *= pulseScale
        }

        // 1. Desenhar o círculo de fundo
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)

        // 2. Ajustar tamanhos das fontes com base no raio atual e na escala definida
        val baseTextSize = radius * 0.3f * fontSizeScale // 30% do raio, ajustado pela escala
        textPaint.textSize = baseTextSize.coerceAtLeast(10f) // Mínimo de 10sp
        valuePaint.textSize = (baseTextSize * 0.8f).coerceAtLeast(9f) // 80% do principal, min 9sp
        detailPaint.textSize = (baseTextSize * 0.5f).coerceAtLeast(8f) // 50% do principal, min 8sp

        // Distância vertical entre as linhas de texto (ajustada pelo raio)
        val lineSpacing = radius * 0.15f // Espaçamento entre linhas

        // 3. Desenhar textos (centralizados verticalmente)
        val lines = mutableListOf<String>()

        // Linha 1: Classificação
        lines.add(getRatingText(currentRating))

        // Linha 2: Valor €
        currentOfferData?.value?.takeIf { it.isNotEmpty() }?.let {
            lines.add("$it€")
        }

        // Linha 3: Valor €/km
        currentOfferData?.calculateProfitability()?.let {
            lines.add(String.format(Locale.getDefault(), "%.2f€/km", it))
        }

        // Linha 4: Valor €/h
        currentOfferData?.calculateValuePerHour()?.let {
            lines.add(String.format(Locale.getDefault(), "%.0f€/h", it))
        }

        // Linha 5: Distância Total
        currentOfferData?.calculateTotalDistance()?.takeIf { it > 0 }?.let {
            lines.add(String.format(Locale.getDefault(), "%.1f km", it))
        }

        // Calcular posição Y inicial para centralizar o bloco de texto
        val totalTextHeight = (lines.size - 1) * lineSpacing + textPaint.textSize // Altura aproximada
        var currentY = centerY - totalTextHeight / 2f + textPaint.textSize / 2f // Começa um pouco acima do centro

        // Desenhar cada linha
        lines.forEachIndexed { index, text ->
            val paint = when (index) {
                0 -> textPaint    // Classificação (maior)
                1 -> valuePaint   // Valor €
                else -> detailPaint // Outros detalhes
            }
            canvas.drawText(text, centerX, currentY, paint)
            currentY += lineSpacing + paint.descent() // Mover para a próxima linha (ajustar espaçamento)
        }
    }

    /**
     * Atualiza a escala de tamanho da fonte (e redesenha).
     * @param scale Nova escala (ex: 1.0 para 100%, 1.5 para 150%).
     */
    fun updateFontSize(scale: Float) {
        Log.d(TAG, "Atualizando escala de fonte: $scale")
        fontSizeScale = scale.coerceIn(0.5f, 2.0f) // Limitar escala entre 50% e 200%
        invalidate() // Força redesenho
    }

    /**
     * Atualiza a transparência da view (e redesenha).
     * @param alpha Valor alpha (0.0 = transparente, 1.0 = opaco).
     */
    fun updateAlpha(alphaValue: Float) {
        Log.d(TAG, "Atualizando alpha (transparência): $alphaValue")
        viewAlpha = alphaValue.coerceIn(0.0f, 1.0f) // Limitar alpha entre 0 e 1
        this.alpha = viewAlpha // Aplica na view
        invalidate() // Força redesenho
    }


    /**
     * Atualiza o estado visual com base na avaliação da oferta e inicia animação.
     * @param rating Nova classificação da oferta.
     * @param offerData Dados detalhados da oferta (para exibir valores).
     */
    fun updateState(rating: OfferRating, offerData: OfferData?) {
        Log.d(TAG, "Atualizando estado para $rating com dados: $offerData")
        currentRating = rating
        currentOfferData = offerData

        // Atualizar a cor de fundo com base na classificação
        backgroundPaint.color = getRatingColor(rating)

        // Reiniciar controle de ciclos de animação e iniciar pulso
        pulseAnimationCycles = 0
        startPulseAnimation()

        // Redesenhar a view com os novos dados e cor
        invalidate()
    }

    /** Inicia a animação de pulso para chamar atenção */
    private fun startPulseAnimation() {
        // Cancelar animação anterior se estiver em andamento
        animationHandler.removeCallbacksAndMessages(null)

        isAnimating = true
        pulseScale = PULSE_MIN_SCALE // Começa pequeno
        pulseIncreasing = true
        pulseAnimationCycles = 0 // Reseta contagem de ciclos

        // Runnable para a animação
        val pulseRunnable = object : Runnable {
            override fun run() {
                if (!isAnimating) return // Para se a animação for cancelada

                // Atualizar escala
                if (pulseIncreasing) {
                    pulseScale += PULSE_STEP
                    if (pulseScale >= PULSE_MAX_SCALE) {
                        pulseScale = PULSE_MAX_SCALE
                        pulseIncreasing = false
                    }
                } else {
                    pulseScale -= PULSE_STEP
                    if (pulseScale <= PULSE_MIN_SCALE) {
                        pulseScale = PULSE_MIN_SCALE
                        pulseIncreasing = true
                        pulseAnimationCycles++ // Completou um ciclo
                    }
                }

                // Redesenhar a view
                invalidate()

                // Continuar a animação se não atingiu o limite de ciclos
                if (pulseAnimationCycles < MAX_PULSE_CYCLES) {
                    animationHandler.postDelayed(this, PULSE_DURATION_MS)
                } else {
                    // Parar animação após os ciclos
                    isAnimating = false
                    pulseScale = 1.0f // Volta ao tamanho normal
                    invalidate() // Redesenha no tamanho final
                    Log.d(TAG, "Animação de pulso concluída.")
                }
            }
        }
        // Iniciar a animação
        animationHandler.post(pulseRunnable)
    }

    /** Obtém o texto curto a ser exibido para cada classificação */
    private fun getRatingText(rating: OfferRating): String {
        return when (rating) {
            OfferRating.EXCELLENT -> "EXC" // Ou "ÓTIMA"
            OfferRating.GOOD -> "BOA"
            OfferRating.MEDIUM -> "OK"   // Ou "MÉDIA"
            OfferRating.POOR -> "RUIM" // Ou "MÁ"
            OfferRating.UNKNOWN -> "?"
        }
    }

    /** Obtém a cor de fundo correspondente a cada classificação */
    private fun getRatingColor(rating: OfferRating): Int {
        return when (rating) {
            OfferRating.EXCELLENT -> COLOR_EXCELLENT
            OfferRating.GOOD -> COLOR_GOOD
            OfferRating.MEDIUM -> COLOR_MEDIUM
            OfferRating.POOR -> COLOR_POOR
            OfferRating.UNKNOWN -> COLOR_UNKNOWN
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Parar a animação se a view for removida da janela para evitar leaks
        animationHandler.removeCallbacksAndMessages(null)
        isAnimating = false
        Log.d(TAG, "OverlayView detached from window, animação parada.")
    }
}