package com.example.smartdriver // <<< VERIFIQUE O PACKAGE

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.smartdriver.overlay.OverlayService // Para interagir com o serviço de overlay
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.OfferEvaluator
import com.example.smartdriver.utils.EvaluationResult
// Import ScreenCaptureService para usar suas constantes de ação
import com.example.smartdriver.ScreenCaptureService

import java.util.Locale // Para Locale na assinatura
import kotlin.math.abs // Para cálculo de similaridade numérica (se necessário no futuro)

/**
 * Gerencia o fluxo de processamento de ofertas:
 * - Recebe dados de oferta potencialmente válidos do ScreenCaptureService.
 * - Filtra ofertas duplicadas ou muito similares a uma oferta recentemente exibida.
 * - Chama o OfferEvaluator para obter a classificação.
 * - Envia o resultado para o OverlayService para exibição.
 * - Solicita o salvamento do screenshot ao ScreenCaptureService para ofertas válidas exibidas.
 */
class OfferManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "OfferManager"
        @Volatile private var instance: OfferManager? = null

        // Singleton pattern para obter a instância
        fun getInstance(context: Context): OfferManager {
            return instance ?: synchronized(this) {
                instance ?: OfferManager(context.applicationContext).also { instance = it }
            }
        }

        // Timeout para considerar uma oferta "ativa" no ecrã. Se uma nova oferta
        // chegar dentro deste tempo e for considerada similar, será ignorada.
        private const val ACTIVE_OFFER_TIMEOUT_MS = 25000L // 25 segundos

    }

    private val offerEvaluator = OfferEvaluator(context) // Instância do avaliador
    @Volatile private var useOverlay = true // Flag para controlar se o overlay deve ser usado

    // Estado da última oferta exibida
    private var currentDisplayedOfferData: OfferData? = null // Dados da última oferta mostrada
    private var currentDisplayedOfferTimestamp = 0L // Timestamp de quando a última oferta foi mostrada
    private var currentDisplayedOfferSignature: String? = null // Assinatura da última oferta mostrada

    /** Ativa ou desativa a exibição de overlays */
    fun setUseOverlay(use: Boolean) {
        useOverlay = use
        if (!use) {
            hideOverlay() // Esconde o overlay se a opção for desativada
            clearLastOfferState() // Limpa o estado da oferta ativa
        }
        Log.i(TAG, "Uso de Overlay definido para: $use")
    }

    /** Verifica se a aplicação tem permissão para desenhar sobre outras apps */
    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // Permissão não necessária em versões anteriores
        }
    }

    /**
     * Processa uma nova oferta recebida do ScreenCaptureService.
     * Aplica filtros, avalia e decide se deve mostrar no overlay e salvar screenshot.
     */
    fun processOffer(offerData: OfferData) {
        val timeProcessStart = System.currentTimeMillis()
        val offerSignature = createOfferSignature(offerData) // Cria assinatura para comparação
        Log.d(TAG, "Processando oferta recebida: Assinatura=[$offerSignature], Valor=${offerData.value}€")

        // --- Lógica de Filtro de Duplicatas/Recentes ---
        val timeSinceLastDisplay = timeProcessStart - currentDisplayedOfferTimestamp
        val isActiveOfferPresent = currentDisplayedOfferData != null && currentDisplayedOfferSignature != null

        if (isActiveOfferPresent && timeSinceLastDisplay < ACTIVE_OFFER_TIMEOUT_MS) {
            // Compara a assinatura da nova oferta com a assinatura da última exibida
            if (isEssentiallyTheSameOffer(currentDisplayedOfferSignature!!, offerSignature)) {
                Log.d(TAG, ">>> Ignorando oferta: Similar (mesma assinatura '$offerSignature') e dentro do timeout (${timeSinceLastDisplay}ms < ${ACTIVE_OFFER_TIMEOUT_MS}ms).")
                currentDisplayedOfferTimestamp = timeProcessStart
                return // IGNORA esta nova oferta
            } else {
                Log.d(TAG, "Nova oferta ($offerSignature) detectada, diferente da ativa ($currentDisplayedOfferSignature). Substituindo a ativa.")
            }
        } else if (isActiveOfferPresent) {
            Log.d(TAG, "Timeout da oferta anterior ($currentDisplayedOfferSignature) atingido (${timeSinceLastDisplay}ms >= ${ACTIVE_OFFER_TIMEOUT_MS}ms). Processando nova ($offerSignature).")
        }
        // --- Fim do Filtro ---

        currentDisplayedOfferData = offerData.copy() // Guarda uma cópia dos dados
        currentDisplayedOfferTimestamp = timeProcessStart
        currentDisplayedOfferSignature = offerSignature

        val evaluationResult = offerEvaluator.evaluateOffer(offerData)
        Log.i(TAG, "Resultado Avaliação: Borda=${evaluationResult.combinedBorderRating} (Km=${evaluationResult.kmRating}, Hora=${evaluationResult.hourRating})")

        if (useOverlay) {
            if (hasOverlayPermission()) {
                // 1. SOLICITAR SALVAMENTO DO SCREENSHOT PRIMEIRO
                Log.i(TAG, ">>> Solicitando salvamento do screenshot para oferta VÁLIDA: $offerSignature")
                val saveIntent = Intent(context, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_SAVE_LAST_VALID_OFFER_SCREENSHOT
                    // putExtra("offer_signature", offerSignature) // Opcional
                }
                try { context.startService(saveIntent) }
                catch (e: Exception) { Log.e(TAG, "Erro ao enviar ACTION_SAVE_LAST_VALID_OFFER_SCREENSHOT: ${e.message}", e) }

                // 2. MOSTRAR O OVERLAY
                Log.i(TAG, ">>> Mostrando overlay para oferta: $offerSignature")
                showOverlay(evaluationResult, offerData)

            } else {
                Log.w(TAG, "Overlay ativado, mas sem permissão. Não é possível mostrar ou solicitar salvamento.")
            }
        } else {
            Log.d(TAG, "Overlay desativado nas configurações. Oferta processada mas não exibida/salva.")
        }
    }

    /**
     * Verifica se duas assinaturas de oferta são idênticas.
     */
    private fun isEssentiallyTheSameOffer(currentSignature: String, newSignature: String): Boolean {
        return currentSignature == newSignature
    }

    /**
     * Cria uma assinatura de string única para uma oferta com base nos seus dados numéricos.
     */
    private fun createOfferSignature(offerData: OfferData): String {
        val v = offerData.value.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0.00"
        val pd = offerData.pickupDistance.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0.00"
        val td = offerData.tripDistance.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0.00"
        val pt = offerData.pickupDuration.toIntOrNull()?.toString() ?: "0"
        val tt = offerData.tripDuration.toIntOrNull()?.toString() ?: "0"
        return "v:$v|pd:$pd|td:$td|pt:$pt|tt:$tt"
    }

    /**
     * Envia um Intent para o OverlayService para exibir o overlay com os dados da oferta.
     */
    private fun showOverlay(evaluationResult: EvaluationResult, offerData: OfferData) {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_OVERLAY
            putExtra(OverlayService.EXTRA_EVALUATION_RESULT, evaluationResult)
            putExtra(OverlayService.EXTRA_OFFER_DATA, offerData)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar/enviar comando SHOW_OVERLAY para OverlayService: ${e.message}", e)
        }
    }

    /**
     * Envia um Intent para o OverlayService para esconder o overlay principal.
     */
    private fun hideOverlay() {
        Log.d(TAG, "Enviando comando HIDE_OVERLAY para OverlayService.")
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_HIDE_OVERLAY
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar comando HIDE_OVERLAY para OverlayService: ${e.message}", e)
        }
    }

    /**
     * Limpa o estado da última oferta exibida.
     */
    fun clearLastOfferState() {
        Log.d(TAG,"Limpando estado da última oferta exibida.")
        currentDisplayedOfferData = null
        currentDisplayedOfferTimestamp = 0L
        currentDisplayedOfferSignature = null
    }

} // Fim da classe OfferManager