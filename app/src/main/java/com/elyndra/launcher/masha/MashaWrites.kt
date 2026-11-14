package com.elyndra.launcher.masha

import com.elyndra.launcher.library.NameCheck
import java.util.UUID

/* ─────────────────────────────────────────────────────────────
   Lo que Masha cambia en la biblioteca: añadir juegos, ponerles
   nombre y elegir emulador. Nunca borra juegos ni archivos.

   Si el usuario lo pidió directamente o acaba de decir que sí, se
   hace ya ([submit] con execute, ver ActionGate). Si la idea es de
   Masha, deja en el chat una tarjeta con lo que va a hacer ("Añadir
   12 juegos", "Cambiar el nombre de X a Y") y botones de Confirmar /
   Cancelar; también vale contestar "sí" o "no" con palabras, en
   cualquier idioma de la app ([answer], ConfirmationResolver). Todo
   lo hecho se puede deshacer ("deshaz lo último").

   Kotlin puro: se prueba en la JVM.
   ───────────────────────────────────────────────────────────── */

/** Qué pide confirmación (para el texto de la tarjeta). */
object ConfirmKind {
    const val ADD_GAMES = "add_games"
    const val RENAME = "rename"
    const val SET_EMULATOR = "set_emulator"

    /** Instalar un port de la tienda (items: nombre del juego, juego original del que hacen falta archivos o vacío). */
    const val INSTALL_PORT = "install_port"
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

    /** Lo que pasó con una escritura: hecha ya o a la espera de la tarjeta. */
    sealed interface Submitted {
        data class Done(val outcome: WriteOutcome) : Submitted
        data class Proposed(val write: PendingWrite) : Submitted
    }

    /**
     * Con [execute] (el usuario la pidió o la confirmó, ver ActionGate) se
     * hace ya y queda para deshacer; si no, se propone con tarjeta.
     */
    suspend fun submit(kind: String, items: List<String>, execute: Boolean, run: suspend () -> WriteOutcome): Submitted {
        if (!execute) return Submitted.Proposed(propose(kind, items, run))
        val out = run()
        out.undo?.let { record(out.label, it) }
        return Submitted.Done(out)
    }

    /** La respuesta con palabras a la última propuesta. */
    sealed interface Answered {
        val write: PendingWrite
        data class Confirmed(override val write: PendingWrite, val outcome: WriteOutcome) : Answered
        data class Cancelled(override val write: PendingWrite) : Answered
    }

    /**
     * "Sí" / "dale" / "confirmo" / "yes" / "はい"… con una propuesta a la
     * espera: se hace; "no" / "cancela": se descarta. Otra cosa (o nada
     * pendiente): null, y lo contesta el modelo.
     */
    suspend fun answer(text: String): Answered? {
        val w = latest() ?: return null
        return when (ConfirmationResolver.resolve(text)) {
            ConfirmationResolver.Answer.Affirmative -> confirm(w.id)?.let { Answered.Confirmed(w, it) }
            ConfirmationResolver.Answer.Negative -> if (cancel(w.id)) Answered.Cancelled(w) else null
            ConfirmationResolver.Answer.Unrelated -> null
        }
    }

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

    /** ¿Es un "sí" / "no" contestando a una confirmación? (en los idiomas de la app, ver [ConfirmationResolver]) */
    fun answer(text: String): Boolean? = when (ConfirmationResolver.resolve(text)) {
        ConfirmationResolver.Answer.Affirmative -> true
        ConfirmationResolver.Answer.Negative -> false
        ConfirmationResolver.Answer.Unrelated -> null
    }
}
