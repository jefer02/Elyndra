package com.elyndra.launcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.R
import com.elyndra.launcher.lucy.LucyClient
import kotlinx.coroutines.launch

/**
 * Lucy: la conversación contra Google AI Studio (Gemini), con el hilo entero y
 * el tiempo de juego real de la biblioteca como contexto. Sin clave configurada
 * responde en modo demo con los textos del prototipo.
 */
class LucyController(private val vm: ElyndraViewModel) {

    var draft by mutableStateOf(""); private set
    var typing by mutableStateOf(false); private set
    val messages = mutableStateListOf<ChatMessage>()

    val online: Boolean get() = LucyClient.hasApiKey

    fun ensureGreeting(text: String) {
        if (messages.isEmpty()) messages.add(ChatMessage(fromLucy = true, text = text))
    }

    fun updateDraft(v: String) { draft = v }

    fun send(uiLanguage: String) {
        val text = draft.trim()
        if (text.isEmpty() || typing) return
        // El hilo que ve el modelo es el de antes de este mensaje.
        val history = messages.map { LucyClient.Turn(it.fromLucy, it.text) }
        messages.add(ChatMessage(fromLucy = false, text = text))
        draft = ""
        typing = true
        vm.viewModelScope.launch {
            val reply = LucyClient.ask(text, uiLanguage, playtime(), history)
            typing = false
            messages.add(ChatMessage(fromLucy = true, text = reply.text))
            // Con clave puesta, una respuesta que no viene de la API es un fallo
            // de red o de cuota: se dice, en vez de colar el texto de demo a secas.
            if (!reply.ok && online) vm.showToast(UiText.res(R.string.lucy_error))
        }
    }

    fun sendSuggestion(text: String, uiLanguage: String) {
        draft = text
        send(uiLanguage)
    }

    private fun playtime(): Map<String, Int> {
        val lib = vm.library
        return (lib.roms.map { it.displayTitle to it.stats.minutes } + lib.apps.map { it.displayTitle to it.stats.minutes })
            .filter { it.second > 0 }
            .toMap()
    }

    data class Stats(
        val weekMinutes: Int,
        val deltaMinutes: Int,
        val topTitle: String?,
        val topMinutes: Int,
        val weekSessions: Int,
        val averageMinutes: Int,
    )

    /** Tarjetas de la cabecera: esta semana, el más jugado y las sesiones. */
    fun stats(now: Long = System.currentTimeMillis()): Stats {
        val lib = vm.library
        val week = 7L * 24 * 60 * 60 * 1000
        val thisWeek = lib.sessions.filter { it.start >= now - week }
        val lastWeek = lib.sessions.filter { it.start in (now - 2 * week) until (now - week) }
        val minutes = thisWeek.sumOf { it.minutes }
        val top = (lib.roms.map { it.displayTitle to it.stats.minutes } + lib.apps.map { it.displayTitle to it.stats.minutes })
            .maxByOrNull { it.second }
            ?.takeIf { it.second > 0 }
        return Stats(
            weekMinutes = minutes,
            deltaMinutes = minutes - lastWeek.sumOf { it.minutes },
            topTitle = top?.first,
            topMinutes = top?.second ?: 0,
            weekSessions = thisWeek.size,
            averageMinutes = if (thisWeek.isEmpty()) 0 else minutes / thisWeek.size,
        )
    }
}
