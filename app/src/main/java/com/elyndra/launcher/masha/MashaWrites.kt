package com.elyndra.launcher.masha

import com.elyndra.launcher.library.NameCheck
import java.util.UUID

/* ─────────────────────────────────────────────────────────────
   Lo que Masha cambia en la biblioteca: añadir juegos, ponerles
   nombre y elegir emulador. Nunca borra juegos ni archivos.

   Cada escritura pasa por una confirmación: Masha deja en el chat
   una tarjeta con lo que va a hacer ("Añadir 12 juegos", "Cambiar
   el nombre de X a Y") y botones de Confirmar / Cancelar; también
   vale contestar "sí" o "no". Lo hecho se puede deshacer ("deshaz
   lo último"). La única excepción es el nombre que el usuario
   dicta él mismo: ahí no hay nada que confirmar.

   Kotlin puro: se prueba en la JVM.
   ───────────────────────────────────────────────────────────── */

/** Qué pide confirmación (para el texto de la tarjeta). */
object ConfirmKind {
    const val ADD_GAMES = "add_games"
    const val RENAME = "rename"
    const val SET_EMULATOR = "set_emulator"
}

/** Estado de una tarjeta de confirmación. */
object ConfirmState {
    const val PENDING = "pending"
    const val DONE = "done"
    const val CANCELLED = "cancelled"
    /** Se reinició la app antes de contestar: la acción ya no está. */
    const val EXPIRED = "expired"
}

/** Lo que hace una escritura confirmada y cómo se deshace. */
class PendingWrite(
    val id: String,
    val kind: String,
    val items: List<String>,
    /** Hace la escritura; devuelve la etiqueta de "hecho" y cómo deshacerla. */
    val run: suspend () -> WriteOutcome,
)

/** Resultado de una escritura: lo que se cuenta y, si se puede, cómo volver atrás. */
data class WriteOutcome(val label: String, val ok: Boolean = true, val undo: (suspend () -> String)? = null)

/**
 * Las escrituras a la espera de confirmación y la pila de deshacer.
 * Solo vive en memoria: tras reiniciar, lo pendiente caduca.
 */
class MashaWrites(private val newId: () -> String = { UUID.randomUUID().toString() }) {

    private val pending = LinkedHashMap<String, PendingWrite>()
    private val undo = ArrayDeque<Pair<String, suspend () -> String>>()

    /** Deja [run] esperando confirmación; devuelve su id (el de la tarjeta). */
    fun propose(kind: String, items: List<String>, run: suspend () -> WriteOutcome): PendingWrite {
        val w = PendingWrite(newId(), kind, items, run)
        pending[w.id] = w
        return w
    }

    fun isPending(id: String): Boolean = id in pending

    /** La última propuesta sin contestar (para "sí" / "no" escritos). */
    fun latest(): PendingWrite? = pending.values.lastOrNull()

    /** Confirmar: hace la escritura una sola vez. Null si ya no estaba (contestada o caducada). */
    suspend fun confirm(id: String): WriteOutcome? {
        val w = pending.remove(id) ?: return null
        val out = w.run()
        out.undo?.let { u ->
            undo.addLast(out.label to u)
            while (undo.size > MAX_UNDO) undo.removeFirst()
        }
        return out
    }

    fun cancel(id: String): Boolean = pending.remove(id) != null

    /** Algo hecho sin confirmación (el nombre dictado): también se puede deshacer. */
    fun record(label: String, undoAction: suspend () -> String) {
        undo.addLast(label to undoAction)
        while (undo.size > MAX_UNDO) undo.removeFirst()
    }

    val canUndo: Boolean get() = undo.isNotEmpty()

    /** Deshace lo último; devuelve qué se deshizo, o null si no había nada. */
    suspend fun undoLast(): String? {
        val (_, action) = undo.removeLastOrNull() ?: return null
        return action()
    }

    companion object {
        const val MAX_UNDO = 10
    }
}

/** Validación de los argumentos de las herramientas que escriben. */
object MashaWriteRules {

    /** Lo máximo que se añade de una vez; lo demás se dice como "omitido". */
    const val BATCH_CAP = 50

    /** Un nombre puesto por Masha (o dictado) tiene que servir como nombre de juego. */
    const val MAX_NAME = 120

    fun cleanName(name: String?): String? {
        val n = name?.trim()?.replace(Regex("\\s+"), " ")?.trim('"', '«', '»', '“', '”')?.trim().orEmpty()
        if (n.isEmpty() || n.length > MAX_NAME) return null
        return n.takeIf { NameCheck.isNameUsable(it) }
    }

    /** Parte una lista en lo que entra en el lote y lo que se queda fuera. */
    fun <T> cap(items: List<T>, max: Int = BATCH_CAP): Pair<List<T>, List<T>> =
        items.take(max) to items.drop(max)

    /** ¿Es un "sí" / "no" contestando a una confirmación? (en los idiomas de la app) */
    fun answer(text: String): Boolean? {
        val t = text.trim().lowercase().trimEnd('.', '!', '¡', '?', '¿').trim()
        return when (t) {
            in YES -> true
            in NO -> false
            else -> null
        }
    }

    private val YES = setOf(
        "sí", "si", "vale", "ok", "okay", "dale", "confirmo", "confirmar", "hazlo", "adelante", "de acuerdo",
        "yes", "yep", "sure", "confirm", "do it", "go ahead", "sim", "pode", "confirmar", "oui", "d'accord", "ja", "jawohl", "bestätigen",
        "はい", "うん", "お願い",
    )
    private val NO = setOf(
        "no", "nope", "cancela", "cancelar", "mejor no", "déjalo", "dejalo", "cancel", "don't", "dont", "não", "nao", "non", "annuler",
        "nein", "abbrechen", "いいえ", "やめて",
    )
}
