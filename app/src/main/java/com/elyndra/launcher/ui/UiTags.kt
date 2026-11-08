package com.elyndra.launcher.ui

import com.elyndra.launcher.metadata.ArtKind
import com.elyndra.launcher.metadata.Service

/**
 * Los `testTag` estables de la interfaz. Salen como resource-id (ver
 * `testTagsAsResourceId` en [ElyndraApp]) para que el generador del perfil base
 * y las pruebas de arranque (módulo `:baselineprofile`) encuentren las piezas
 * con UiAutomator sin depender de textos traducidos ni de posiciones. No
 * cambian nada de lo que se ve. Si se renombran, hay que cambiarlos también en
 * `baselineprofile/…/Ui.kt`.
 */
object UiTags {
    const val INTRO = "boot_intro"
    const val SHELF = "classic_shelf"
    const val FOLDER_CARD = "library_folder"
    const val APP_CARD = "library_app"
    const val SETTINGS = "settings_button"

    /** La rueda de Meridian (biblioteca y carpeta en apaisado). */
    const val WHEEL = "meridian_wheel"

    /** El campo de búsqueda de la biblioteca (Meridian y Clásico). */
    const val SEARCH_FIELD = "library_search"

    /** La ficha de un juego abierta. */
    const val DETAILS = "details_sheet"

    /** El menú de acciones (opciones de un juego, origen del arte…) abierto. */
    const val ACTION_SHEET = "action_sheet"

    /** El selector de arte y cada una de sus miniaturas. */
    const val ART_PICKER = "art_picker"
    const val ART_CANDIDATE = "art_candidate"

    /** Ajustes → Pantalla → Rendimiento (Auto / Calidad / Rendimiento). */
    const val PERFORMANCE_MODE = "settings_performance_mode"

    /** La pantalla entera: `screen_library`, `screen_folder`, `screen_settings`… */
    fun screen(screen: Screen): String = "screen_" + screen.name.lowercase()

    /** Una categoría de Ajustes (raíl o lista): `settings_cat_display`… */
    fun settingsCategory(category: SettingsCategory): String = "settings_cat_" + category.name.lowercase()

    /** El dock de secciones, con la elegida: `library_filter_all`, `library_filter_consoles`… */
    fun libraryFilter(filter: LibraryFilter): String = "library_filter_" + filter.name.lowercase()

    /** "Cambiar carátula / fondo / logo / icono" en el menú de un juego: `art_action_cover`, `art_action_hero`… */
    fun artAction(kind: ArtKind): String = "art_action_" + kind.media

    /** Un servicio en la hoja de origen del arte: `art_source_libretro`… */
    fun artSource(service: Service): String = "art_source_" + service.id
}
