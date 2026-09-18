package com.elyndra.launcher.ui

import android.net.Uri
import androidx.annotation.StringRes
import com.elyndra.launcher.R
import com.elyndra.launcher.data.AppEntry
import com.elyndra.launcher.data.GameSystem
import com.elyndra.launcher.data.RomFolder
import com.elyndra.launcher.library.RomScanner
import com.elyndra.launcher.metadata.ArtCandidate
import com.elyndra.launcher.metadata.ArtKind
import com.elyndra.launcher.metadata.RaGameProgress
import com.elyndra.launcher.metadata.Service

enum class Screen { Library, Folder, Add, Settings, Lucy }

enum class LibraryFilter(@StringRes val label: Int) {
    All(R.string.filter_all),
    Android(R.string.filter_android),
    Consoles(R.string.filter_consoles),
}

enum class AddTab { Android, Roms }

/**
 * Criterio de orden del carrusel. Se guarda en SettingsStore por su [id].
 *
 * "Nombre" ya es el orden A→Z, así que no hay una entrada aparte para
 * "A-Z": sería la misma lista dos veces en el menú.
 */
enum class SortMode(val id: String, @StringRes val label: Int) {
    Name("name", R.string.sort_name),
    PlayTime("playtime", R.string.sort_playtime),
    Platform("platform", R.string.sort_platform),
    DateAdded("added", R.string.sort_added),
    ;

    companion object {
        fun byId(id: String): SortMode = entries.firstOrNull { it.id == id } ?: Name
    }
}

/** Un elemento del carrusel unificado: una carpeta de emulador o una app Android. */
sealed interface LibraryItem {
    val key: String
    val name: String

    data class Folder(
        val folder: RomFolder,
        val system: GameSystem,
        val romCount: Int,
        val emulatorName: String?,
        val emulatorInstalled: Boolean,
        val minutes: Int,
        /** Fondo: el elegido a mano para la carpeta o, si no, el del último juego jugado. */
        val heroPath: String?,
        /** Carátula, logo e icono elegidos a mano (null = card de consola de siempre). */
        val coverPath: String? = null,
        val logoPath: String? = null,
        val iconPath: String? = null,
        /** Paquete del emulador instalado: su icono es el automático de la carpeta. */
        val emulatorPackage: String? = null,
    ) : LibraryItem {
        override val key: String get() = folder.key
        override val name: String get() = system.name
    }

    data class App(val app: AppEntry, val installed: Boolean) : LibraryItem {
        override val key: String get() = app.key
        override val name: String get() = app.displayTitle
    }
}

data class ChatMessage(val fromLucy: Boolean, val text: String)

/** Velo de lanzamiento: carátula (o icono de la app), título y a dónde va. */
data class Launch(
    val title: String,
    val via: UiText,
    val pairIndex: Int,
    val coverPath: String?,
    val packageName: String? = null,
    val iconPath: String? = null,
)

/** Selector de "Personalizar carátula / fondo / icono" con las imágenes de un servicio. */
data class ArtPickerState(
    val key: String,
    val title: String,
    val kind: ArtKind,
    val service: Service,
    val loading: Boolean = true,
    val candidates: List<ArtCandidate> = emptyList(),
    val failed: Boolean = false,
    /** URL que se está descargando tras elegirla. */
    val applying: String? = null,
)

@StringRes
fun ArtKind.label(): Int = when (this) {
    ArtKind.Cover -> R.string.customize_cover
    ArtKind.Background -> R.string.customize_background
    ArtKind.Logo -> R.string.customize_logo
    ArtKind.Icon -> R.string.customize_icon
}

/** Nombre corto para las filas agrupadas del menú ("Carátula", no "Personalizar carátula"). */
@StringRes
fun ArtKind.shortLabel(): Int = when (this) {
    ArtKind.Cover -> R.string.art_kind_cover
    ArtKind.Background -> R.string.art_kind_background
    ArtKind.Logo -> R.string.art_kind_logo
    ArtKind.Icon -> R.string.art_kind_icon
}

/** Glifo de cada clase de imagen. */
fun ArtKind.sheetIcon(): SheetIcon = when (this) {
    ArtKind.Cover -> SheetIcon.Cover
    ArtKind.Background -> SheetIcon.Background
    ArtKind.Logo -> SheetIcon.Logo
    ArtKind.Icon -> SheetIcon.Icon
}

/** "Quitar carátula / fondo / logo / icono" del menú de pulsación larga. */
@StringRes
fun ArtKind.removeLabel(): Int = when (this) {
    ArtKind.Cover -> R.string.remove_cover
    ArtKind.Background -> R.string.remove_background
    ArtKind.Logo -> R.string.remove_logo
    ArtKind.Icon -> R.string.remove_icon
}

data class DialogButton(val label: UiText, val action: () -> Unit)

/**
 * Campo de texto de un diálogo (el id del juego dentro de BannerHub, por ahora).
 *
 * Va aquí y no en una pantalla propia porque lo que se pide es un dato suelto:
 * se escribe, se acepta y se vuelve a lo que se estaba haciendo. Cuando el
 * diálogo lo lleva, el botón de aceptar entrega lo escrito a [onConfirm] en
 * vez de disparar la acción del botón.
 */
data class DialogInput(
    val initial: String = "",
    val placeholder: UiText? = null,
    /** Teclado numérico: los ids de los runtimes son números (268910, 2551…). */
    val numeric: Boolean = true,
    val onConfirm: (String) -> Unit,
)

data class DialogSpec(
    val title: UiText,
    val message: UiText,
    val confirm: DialogButton,
    val dismiss: DialogButton? = null,
    val extra: DialogButton? = null,
    val input: DialogInput? = null,
)

/**
 * Glifo de una fila del menú de pulsación larga.
 *
 * Es un enum y no un recurso porque los iconos de Elyndra se dibujan a mano
 * (ver `SheetGlyphs.kt`): así el menú no arrastra una librería de iconos ni
 * un PNG por acción, y cada glifo se tiñe con el acento del tema.
 */
enum class SheetIcon {
    Play,
    GameId,
    Search,
    Sort,
    Details,
    Emulator,
    Rescan,
    Refresh,
    Cover,
    Background,
    Logo,
    Icon,
    Gallery,
    Service,
    App,
    Remove,
}

/**
 * Un bloque de acciones con su rótulo. El menú agrupa por intención —jugar,
 * imágenes, gestionar, quitar— en vez de encadenar quince filas iguales.
 */
data class SheetGroup(val header: UiText? = null, val actions: List<SheetAction>)

data class SheetAction(
    val label: UiText,
    val detail: UiText? = null,
    val selected: Boolean = false,
    val dimmed: Boolean = false,
    val destructive: Boolean = false,
    val icon: SheetIcon? = null,
    /** Fila que abre otra hoja: lleva galón a la derecha en vez de nada. */
    val opensSheet: Boolean = false,
    val action: () -> Unit,
)

/** Miniatura de la cabecera del menú: la carátula o el icono de lo que se pulsó. */
data class SheetThumb(
    val coverPath: String? = null,
    val iconPath: String? = null,
    val packageName: String? = null,
    val pairIndex: Int = 0,
)

data class ActionSheetSpec(
    val title: UiText,
    val subtitle: UiText? = null,
    val groups: List<SheetGroup>,
    val thumb: SheetThumb? = null,
)

/**
 * Hoja de un solo bloque, que es lo que necesitan casi todas las llamadas
 * (elegir emulador, elegir app…). Es una función y no un constructor porque
 * `List<SheetGroup>` y `List<SheetAction>` borran al mismo tipo en la JVM.
 */
fun ActionSheetSpec(title: UiText, subtitle: UiText? = null, actions: List<SheetAction>): ActionSheetSpec =
    ActionSheetSpec(title, subtitle, listOf(SheetGroup(null, actions)))

/** Estado de conexión de un servicio de metadatos en Ajustes. */
data class ServiceState(val status: Status, val detail: UiText? = null) {
    enum class Status { Unconfigured, Unverified, Checking, Connected, Error }
}

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val scanned: Int, val found: Int, val current: String) : ScanState
    data class Done(val found: List<RomScanner.Found>) : ScanState
    data class Failed(val message: UiText) : ScanState
}

/** Carpeta elegida en "Añadir → Carpeta de ROMs". */
data class PickedFolder(
    val treeUri: Uri,
    val rootDocId: String,
    val displayPath: String,
    val name: String,
    /** Subcarpetas cuyo nombre delata un sistema ("psp", "gba"…). */
    val detected: List<DetectedSub> = emptyList(),
)

data class DetectedSub(val docId: String, val name: String, val system: GameSystem)

data class EmulatorOption(val id: String, val name: String, val installed: Boolean)

sealed interface AchievementsState {
    data object Idle : AchievementsState
    data object Loading : AchievementsState
    data class Loaded(val progress: RaGameProgress) : AchievementsState
    data object Failed : AchievementsState
}
