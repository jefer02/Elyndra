package com.elyndra.launcher.masha

/**
 * Lo que se ve de una respuesta con el chat oculto (puro): solo imágenes. Cada
 * adjunto se convierte en baldosas (carátula del juego, arte del port o icono
 * de la app) sin texto en pantalla; el título y su motivo van en la etiqueta
 * de accesibilidad y en un pie pequeño solo al enfocar o mantener la baldosa.
 * Las confirmaciones y las fichas de "hecho" no dan baldosas (la confirmación
 * tiene su propia cuadrícula con Confirmar / Cancelar).
 */
object CardTiles {

    enum class Kind { Game, Port }

    data class Tile(val kind: Kind, val id: String, val reason: String = "")

    /** Cuántas baldosas como mucho (se deslizan en horizontal). */
    const val MAX = 12

    fun from(attachment: MashaAttachment?): List<Tile> = when (attachment) {
        is MashaAttachment.Games -> attachment.keys.map { Tile(Kind.Game, it) }
        is MashaAttachment.Plan -> attachment.blocks.map { Tile(Kind.Game, it.key, it.reason) }
        is MashaAttachment.ArcCard -> attachment.keys.map { Tile(Kind.Game, it) }
        is MashaAttachment.Ports -> attachment.ids.map { Tile(Kind.Port, it) }
        is MashaAttachment.Picks -> attachment.items.map {
            Tile(if (it.kind == MashaAttachment.Pick.PORT) Kind.Port else Kind.Game, it.id, it.reason)
        }
        is MashaAttachment.Confirm, is MashaAttachment.Done, null -> emptyList()
    }.distinctBy { it.kind to it.id }.take(MAX)

    /** La etiqueta de accesibilidad (y el pie al enfocar): el título y, si lo hay, su motivo. */
    fun label(title: String, reason: String): String =
        if (reason.isBlank()) title else "$title. ${reason.trim()}"
}
