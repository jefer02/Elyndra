package com.elyndra.launcher.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Texto que produce el ViewModel y que la UI traduce al pintar. Así el
 * ViewModel no depende de un Context con idioma, y un cambio de idioma
 * se refleja también en diálogos y avisos ya abiertos.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText
    data class Raw(val text: String) : UiText

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())
    }
}

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Res -> stringResource(id, *args.map { if (it is UiText) it.resolve() else it }.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, count, *args.map { if (it is UiText) it.resolve() else it }.toTypedArray())
    is UiText.Raw -> text
}
