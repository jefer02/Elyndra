package com.elyndra.launcher.sound

import com.elyndra.launcher.R

/**
 * Los sonidos de la interfaz. [priority] decide cuál gana cuando dos llegan a
 * la vez: pulsar A que abre un menú suena a "abrir", no a "aceptar" + "abrir".
 */
enum class UiSound(val id: String, val priority: Int, val nameRes: Int) {
    Navigate("navigate", 0, R.string.sound_event_navigate),
    Select("select", 2, R.string.sound_event_select),
    Back("back", 2, R.string.sound_event_back),
    ToggleOn("toggle_on", 3, R.string.sound_event_toggle_on),
    ToggleOff("toggle_off", 3, R.string.sound_event_toggle_off),
    Open("open", 4, R.string.sound_event_open),
    Close("close", 4, R.string.sound_event_close),
    Error("error", 5, R.string.sound_event_error),
    Launch("launch", 6, R.string.sound_event_launch),
    ;

    /** Aceptar y volver esperan un instante por si detrás llega algo que manda más. */
    val deferrable: Boolean get() = this == Select || this == Back

    companion object {
        fun byId(id: String): UiSound? = entries.firstOrNull { it.id == id }
    }
}

/** Paquetes de sonidos originales (ver tools/gen_ui_sounds.py). [Off] no suena. */
enum class SoundPack(val id: String, val nameRes: Int) {
    Console("console", R.string.sound_pack_console),
    Soft("soft", R.string.sound_pack_soft),
    Retro("retro", R.string.sound_pack_retro),
    Off("off", R.string.sound_pack_off),
    ;

    /** Nombre del recurso en res/raw: `ui_<paquete>_<evento>`. */
    fun rawName(sound: UiSound): String? = if (this == Off) null else "ui_${id}_${sound.id}"

    companion object {
        val DEFAULT = Console

        fun byId(id: String?): SoundPack = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
