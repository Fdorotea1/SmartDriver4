package com.example.smartdriver

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Estado global da MediaProjection + controlo "single-flight" do pedido de consentimento.
 */
object MediaProjectionData {

    @Volatile var resultCode: Int = Activity.RESULT_CANCELED
    @Volatile var resultData: Intent? = null

    // Há um pedido de permissão em curso?
    private val consentInFlight = AtomicBoolean(false)

    fun hasConsent(): Boolean = resultCode != Activity.RESULT_CANCELED && resultData != null

    /** Tenta marcar que há um pedido em curso. true = conseguiu (não havia outro). */
    fun markConsentInFlight(): Boolean = consentInFlight.compareAndSet(false, true)

    /** Limpa o estado "pedido em curso". Chamar SEMPRE após receber o resultado (sucesso ou cancelado). */
    fun clearConsentInFlight() { consentInFlight.set(false) }

    fun isConsentInFlight(): Boolean = consentInFlight.get()

    /** Guarda o resultado da MediaProjection. */
    fun setConsent(code: Int, data: Intent?) {
        resultCode = code
        resultData = data
    }

    /** Limpa autorização E o in-flight (usado se o utilizador revoga/para a projeção). */
    fun clear() {
        resultCode = Activity.RESULT_CANCELED
        resultData = null
        consentInFlight.set(false)
    }

    /**
     * Apenas o serviço chama isto quando precisa de consentimento.
     * Emite um broadcast UMA vez, caso não haja autorização e não haja pedido em curso.
     * Retorna true se o broadcast foi emitido.
     */
    fun requestConsentIfNeeded(context: Context, actionBroadcast: String): Boolean {
        if (hasConsent()) return false
        if (!markConsentInFlight()) return false
        return try {
            context.sendBroadcast(Intent(actionBroadcast))
            true
        } catch (_: Exception) {
            clearConsentInFlight()
            false
        }
    }
}
