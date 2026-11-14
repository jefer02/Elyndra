package com.elyndra.launcher.ui.masha

import com.elyndra.launcher.masha.ReplyShape
import com.elyndra.launcher.ui.ChatMessage

/** Qué lee en voz alta del último mensaje del chat (pura). */
object SpokenReply {

    /**
     * El texto que su voz tiene que ir leyendo de [last], o null si no toca:
     * no es suyo, venía del hilo guardado o ya estaba en pantalla al entrar
     * ([alreadyShown]), o la voz está apagada. Con tarjetas, solo la frase corta.
     *
     * Cortada por el usuario (conversación en vivo), su texto pasa a ser lo que
     * llegó a decir: no se lee otra vez (la voz se acababa de vaciar y volvía a
     * empezar desde el principio mientras el usuario hablaba).
     */
    fun of(last: ChatMessage?, alreadyShown: Long?, speakReplies: Boolean): String? {
        if (last == null || !last.fromMasha || last.restored || last.id == alreadyShown || !speakReplies) return null
        if (last.interrupted) return null
        return if (ReplyShape.isCardReply(last.attachment, last.done)) ReplyShape.spokenLine(last.text, ellipsis = false) else last.text
    }
}
