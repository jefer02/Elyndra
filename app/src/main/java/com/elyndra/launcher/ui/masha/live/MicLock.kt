package com.elyndra.launcher.ui.masha.live

/**
 * El candado del micrófono de Lucy (puro). En su pantalla la conversación en
 * vivo está siempre puesta, salvo que el usuario bloquee el micrófono (se
 * recuerda entre visitas: quien lo bloqueó no vuelve a ser grabado solo por
 * abrir la pantalla) o falte el permiso. El permiso se pide una sola vez, la
 * primera vez que se abre; denegado, la pantalla abre con el micrófono
 * bloqueado y una pista, sin volver a preguntar en cada visita.
 */
object MicLock {

    enum class State {
        /** Micrófono abierto: conversación en vivo. */
        Live,

        /** Bloqueado por el usuario. */
        Locked,

        /** Sin permiso (ya se preguntó): bloqueado, con la pista. */
        NoPermission,

        /** Primera visita sin permiso: se pide. */
        AskPermission,
    }

    fun state(userLocked: Boolean, permission: Boolean, asked: Boolean): State = when {
        userLocked -> State.Locked
        permission -> State.Live
        asked -> State.NoPermission
        else -> State.AskPermission
    }

    /** Lo que hace tocar el micrófono (o Y en el mando). */
    enum class Tap { Lock, Unlock, RequestPermission }

    fun tap(state: State): Tap = when (state) {
        State.Live -> Tap.Lock
        State.Locked -> Tap.Unlock
        State.NoPermission, State.AskPermission -> Tap.RequestPermission
    }

    /** ¿Tiene que estar en vivo ahora? (en primer plano, en su pantalla). */
    fun shouldListen(state: State): Boolean = state == State.Live
}
