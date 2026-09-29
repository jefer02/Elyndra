package com.elyndra.launcher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.elyndra.launcher.metadata.GameDescriptions

/**
 * Descripción de un juego en el idioma de la app ([lang]), con vuelta al
 * idioma por defecto cuando no hay traducción (ver [GameDescriptions]).
 *
 * [stored] es la sinopsis guardada en los metadatos, si la hay; [short]
 * la recorta para el hero. Vuelve a null en cuanto cambia el juego o el
 * idioma, así que el texto anterior no se queda pegado un instante bajo el
 * título nuevo mientras llega la respuesta.
 */
@Composable
fun rememberGameDescription(title: String?, stored: String?, lang: String, short: Boolean): String? {
    val text by produceState<String?>(null, title, stored, lang, short) {
        value = null
        value = GameDescriptions.resolve(title, stored, lang, short)
    }
    return text
}
