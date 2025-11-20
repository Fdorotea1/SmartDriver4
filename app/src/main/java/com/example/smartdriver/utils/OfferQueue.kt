package com.example.smartdriver.utils

import java.util.Locale
import java.util.UUID
import kotlin.math.min

/**
 * Fila/stack de ofertas para o semáforo.
 * Mantém todas as ofertas até serem explicitamente descartadas
 * ou até se iniciar uma viagem (clearOnStart).
 *
 * Implementação com MutableList como stack (mais simples que Deque).
 */
class OfferQueue(
    private val maxSize: Int = 20
) {
    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        val offer: OfferData,
        val eval: EvaluationResult,
        val signature: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    // Stack: último elemento = topo visual
    private val stack: MutableList<Entry> = ArrayList(maxSize)
    private var lastSignature: String? = null

    fun size(): Int = stack.size
    fun isEmpty(): Boolean = stack.isEmpty()

    /** Topo (último inserido) */
    fun peek(): Entry? = stack.lastOrNull()

    /** Devolve até N do topo para baixo (para stack visual). */
    fun peekAll(limit: Int = 3): List<Entry> {
        if (stack.isEmpty()) return emptyList()
        val n = min(limit, stack.size)
        return stack.subList(stack.size - n, stack.size).toList()
    }

    /**
     * Adiciona uma nova oferta ao topo.
     * Faz dedupe simples por assinatura consecutiva (evita empilhar repetidos imediatos).
     */
    fun push(offer: OfferData, eval: EvaluationResult): Entry? {
        val sig = createSignature(offer, eval)
        if (sig == lastSignature) return null   // dedupe consecutivo
        lastSignature = sig

        val e = Entry(offer = offer, eval = eval, signature = sig)
        stack.add(e)
        if (stack.size > maxSize) {
            stack.removeAt(0) // remove o mais antigo
        }
        return e
    }

    /** Descarta explicitamente o topo (após confirmação de popup). */
    fun discardTopConfirmed(): Entry? {
        return if (stack.isEmpty()) null else stack.removeAt(stack.lastIndex)
    }

    /** Iniciar viagem: limpar fila. */
    fun clearOnStart() {
        stack.clear()
        lastSignature = null
    }

    /** Opcional: remover por id (se permitires descartar cartões antigos). */
    fun removeById(id: String): Entry? {
        val idx = stack.indexOfFirst { it.id == id }
        return if (idx >= 0) stack.removeAt(idx) else null
    }

    /**
     * Assinatura leve e determinística baseada em campos que existem no projeto:
     * valor, km total, minutos totais e rating de borda.
     */
    private fun createSignature(o: OfferData, ev: EvaluationResult): String {
        val v = o.value?.toString()?.trim()?.lowercase(Locale.US) ?: "?"
        val km = o.calculateTotalDistance()?.let { String.format(Locale.US, "%.2f", it) } ?: "?"
        val min = o.calculateTotalTimeMinutes()?.toString() ?: "?"
        val br = ev.combinedBorderRating?.name ?: "?"
        return "v=$v|km=$km|min=$min|br=$br"
    }
}
