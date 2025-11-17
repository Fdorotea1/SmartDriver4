package com.example.smartdriver

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Estado global da MediaProjection + controlo "single-flight" do pedido de consentimento.
 *
 * Regras:
 * - Nunca apagar um token válido só porque o utilizador carregou "Cancelar".
 * - Só limpar explicitamente via clear() ou quando o sistema revoga (detectado pelo serviço).
 * - Token pode ser invalidado pelo Android ao bloquear o ecrã - verificar isValid() sempre.
 */
object MediaProjectionData {

    private const val TAG = "MediaProjectionData"

    @Volatile var resultCode: Int = Activity.RESULT_CANCELED
    @Volatile var resultData: Intent? = null

    // Pedido de permissão em curso?
    private val consentInFlight = AtomicBoolean(false)

    /**
     * Verdadeiro se há um token utilizável de MediaProjection.
     *
     * NOTA: isto SÓ olha para o que temos em memória.
     * Se o processo morrer, este estado perde-se e volta a ser inválido (comportamento esperado).
     */
    @Synchronized
    fun isValid(): Boolean {
        val valid = (resultCode == Activity.RESULT_OK && resultData != null)
        Log.d(TAG, "isValid() → $valid (code=$resultCode, hasIntent=${resultData != null})")
        return valid
    }

    /** Alias histórico para compatibilidade com código existente. */
    fun hasConsent(): Boolean = isValid()

    /**
     * Tenta marcar que há um pedido em curso.
     * @return true = conseguiu (não havia outro em curso)
     */
    fun markConsentInFlight(): Boolean {
        val marked = consentInFlight.compareAndSet(false, true)
        if (marked) {
            Log.d(TAG, "Pedido de consentimento iniciado")
        } else {
            Log.w(TAG, "Pedido de consentimento já em curso, a ignorar")
        }
        return marked
    }

    /**
     * Limpa o estado "pedido em curso".
     * Chamar SEMPRE após receber o resultado (sucesso ou cancelado).
     */
    fun clearConsentInFlight() {
        if (consentInFlight.compareAndSet(true, false)) {
            Log.d(TAG, "Pedido de consentimento concluído")
        }
    }

    fun isConsentInFlight(): Boolean = consentInFlight.get()

    /**
     * Guarda o resultado da MediaProjection — APENAS se for válido (RESULT_OK + data != null).
     * Se for cancelado, NÃO apaga o token anterior (preserva sessão ativa).
     */
    @Synchronized
    fun setConsent(code: Int, data: Intent?) {
        if (code == Activity.RESULT_OK && data != null) {
            resultCode = code
            resultData = data
            Log.d(TAG, "Token MediaProjection guardado com sucesso via setConsent")
        } else {
            Log.w(TAG, "Tentativa de guardar token inválido (code=$code, data=${data != null}), a ignorar")
        }
    }

    /**
     * Variante direta (quando tens a certeza que é válido).
     * Usa esta quando recebes RESULT_OK do ActivityResult.
     */
    @Synchronized
    fun set(code: Int, data: Intent) {
        if (code == Activity.RESULT_OK) {
            resultCode = code
            resultData = data
            Log.d(TAG, "Token MediaProjection definido diretamente via set()")
        } else {
            Log.e(TAG, "Tentativa de set() com código inválido: $code")
        }
    }

    /**
     * Limpa autorização E o in-flight.
     * Usar quando:
     * - Utilizador revoga/para a projeção
     * - Opção "Encerrar" total da app
     * - Sistema invalidou o token (detectado pelo serviço)
     */
    @Synchronized
    fun clear() {
        val wasValid = (resultCode == Activity.RESULT_OK && resultData != null)
        resultCode = Activity.RESULT_CANCELED
        resultData = null
        consentInFlight.set(false)
        if (wasValid) {
            Log.i(TAG, "Token MediaProjection limpo via clear()")
        } else {
            Log.d(TAG, "clear() chamado mas já não havia token válido")
        }
    }

    /**
     * Compat opcional (baseada em broadcast).
     *
     * ⚠️ ESTA VERSÃO ESTÁ DESATIVADA NESTA BUILD.
     * Usar SEMPRE o fluxo baseado em ActivityResultLauncher no MainActivity.
     */
    @Deprecated("Desativado: usar ActivityResultLauncher em vez de broadcasts")
    fun requestConsentIfNeeded(context: Context, actionBroadcast: String): Boolean {
        Log.w(TAG, "requestConsentIfNeeded() chamado mas está desativado nesta build. Nenhum broadcast será enviado.")
        // Não faz nada, não marca in-flight, não envia broadcast.
        return false
    }

    /**
     * Debug: Verifica o estado atual do token.
     * Útil para troubleshooting.
     */
    fun logCurrentState() {
        Log.d(
            TAG, """
            Estado MediaProjection:
            - Valid: ${resultCode == Activity.RESULT_OK && resultData != null}
            - ResultCode: $resultCode
            - HasIntent: ${resultData != null}
            - ConsentInFlight: ${isConsentInFlight()}
        """.trimIndent()
        )
    }
}
