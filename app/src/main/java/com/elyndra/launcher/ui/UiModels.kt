package com.elyndra.launcher.ui

import android.net.Uri
import androidx.annotation.StringRes
import com.elyndra.launcher.R
import com.elyndra.launcher.data.AppEntry
import com.elyndra.launcher.data.GameSystem
import com.elyndra.launcher.data.RomFolder
import com.elyndra.launcher.library.RomScanner
import com.elyndra.launcher.metadata.RaGameProgress

enum class Screen { Library, Folder, Add, Settings, Lucy }

enum class LibraryFilter(@StringRes val label: Int) {
    All(R.string.filter_all),
    Android(R.string.filter_android),
    Consoles(R.string.filter_consoles),
}

enum class AddTab { Android, Roms }

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
        /** Imagen para el hero: la del último juego jugado de la carpeta, si tiene. */
        val heroPath: String?,
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
)

data class DialogButton(val label: UiText, val action: () -> Unit)

data class DialogSpec(
    val title: UiText,
    val message: UiText,
    val confirm: DialogButton,
    val dismiss: DialogButton? = null,
    val extra: DialogButton? = null,
)

data class SheetAction(
    val label: UiText,
    val detail: UiText? = null,
    val selected: Boolean = false,
    val dimmed: Boolean = false,
    val destructive: Boolean = false,
    val action: () -> Unit,
)

data class ActionSheetSpec(val title: UiText, val subtitle: UiText? = null, val actions: List<SheetAction>)

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
