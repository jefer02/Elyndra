package com.elyndra.launcher.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.R
import com.elyndra.launcher.sound.CustomSoundImporter
import com.elyndra.launcher.sound.CustomSoundRules
import com.elyndra.launcher.sound.SoundPack
import com.elyndra.launcher.sound.UiSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ajustes → Sonidos: lo que se ve en pantalla y lo que se guarda (en [SoundManager]). */
class SoundsController(private val vm: ElyndraViewModel) {

    private val manager get() = vm.sound
    private val store get() = vm.sound.settings
    private val importer by lazy { CustomSoundImporter(vm.app) }

    var enabled by mutableStateOf(store.enabled); private set
    var volume by mutableIntStateOf(store.volume); private set
    var navigation by mutableStateOf(store.navigation); private set
    var pack by mutableStateOf(store.pack); private set

    /** Sonido propio de cada evento (nombre del archivo); sin entrada = el del paquete. */
    val custom = mutableStateMapOf<UiSound, String>().apply {
        UiSound.entries.forEach { s -> store.custom(s)?.let { put(s, it) } }
    }

    /** Evento cuyo archivo se está importando (para el aviso "Comprobando…"). */
    var importing by mutableStateOf<UiSound?>(null); private set

    fun toggleEnabled() {
        enabled = !enabled
        store.enabled = enabled
        manager.reload()
    }

    fun updateVolume(v: Int) {
        volume = v
        store.volume = v
        manager.updateVolume()
    }

    fun toggleNavigation() {
        navigation = !navigation
        store.navigation = navigation
    }

    fun choosePack(p: SoundPack) {
        if (p == pack) return
        pack = p
        store.pack = p
        manager.reload()
        if (p != SoundPack.Off) vm.viewModelScope.launch {
            // Que se oiga el paquete nuevo en cuanto está cargado.
            kotlinx.coroutines.delay(PREVIEW_AFTER_LOAD_MS)
            manager.preview(UiSound.Select)
        }
    }

    fun preview(sound: UiSound) = manager.preview(sound)

    /** Resultado del selector de archivos (SAF): se copia, se comprueba y se carga. */
    fun onPicked(sound: UiSound, uri: Uri?) {
        if (uri == null) return
        importing = sound
        vm.viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { importer.import(sound, uri) }
            importing = null
            when (result) {
                is CustomSoundImporter.Result.Ok -> {
                    custom[sound] = result.fileName
                    store.setCustom(sound, result.fileName)
                    manager.reload()
                    vm.showToast(UiText.res(R.string.sound_custom_ok, UiText.res(sound.nameRes)))
                    kotlinx.coroutines.delay(PREVIEW_AFTER_LOAD_MS)
                    manager.preview(sound)
                }
                is CustomSoundImporter.Result.Rejected -> vm.showToast(rejection(result.problem, sound))
                CustomSoundImporter.Result.Failed -> vm.showToast(UiText.res(R.string.sound_custom_failed))
            }
        }
    }

    fun reset(sound: UiSound) {
        val name = custom.remove(sound) ?: return
        store.setCustom(sound, null)
        vm.viewModelScope.launch(Dispatchers.IO) { importer.delete(name) }
        manager.reload()
    }

    /* ── música de fondo ──────────────────────────────────────── */

    private val music get() = vm.music

    var musicEnabled by mutableStateOf(store.musicEnabled); private set
    var musicVolume by mutableIntStateOf(store.musicVolume); private set
    /** Audio propio (URI de SAF); null = el ambiente de Elyndra. */
    var musicUri by mutableStateOf(store.musicUri); private set

    /** Nombre legible del audio propio (se lee fuera del hilo principal al cambiar). */
    var musicName by mutableStateOf<String?>(null); private set

    init {
        music.onCustomFailed = {
            musicUri = null
            refreshMusicName()
            vm.showToast(UiText.res(R.string.music_custom_failed))
        }
        refreshMusicName()
        // Ajustes guardados: la música arranca en cuanto la Activity está delante.
        music.reload()
    }

    private fun refreshMusicName() {
        val u = musicUri
        if (u == null) {
            musicName = null
            return
        }
        vm.viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    vm.app.contentResolver.query(Uri.parse(u), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()
            }
            if (musicUri == u) musicName = name ?: Uri.parse(u).lastPathSegment
        }
    }

    fun toggleMusic() {
        musicEnabled = !musicEnabled
        store.musicEnabled = musicEnabled
        music.reload()
    }

    fun updateMusicVolume(v: Int) {
        musicVolume = v
        store.musicVolume = v
        music.updateVolume()
    }

    /** Audio elegido con el selector del sistema: se queda el permiso (sin copiarlo, puede ser largo). */
    fun onMusicPicked(uri: Uri?) {
        if (uri == null) return
        runCatching { vm.app.files.takePermission(uri) }
        val old = musicUri
        musicUri = uri.toString()
        store.musicUri = musicUri
        if (old != null && old != musicUri) runCatching { vm.app.files.releasePermission(old) }
        if (!musicEnabled) {
            musicEnabled = true
            store.musicEnabled = true
        }
        refreshMusicName()
        music.reload()
    }

    fun useBuiltInMusic() {
        val old = musicUri ?: return
        musicUri = null
        store.musicUri = null
        runCatching { vm.app.files.releasePermission(old) }
        refreshMusicName()
        music.reload()
    }

    private fun rejection(problem: CustomSoundRules.Problem, sound: UiSound): UiText = when (problem) {
        CustomSoundRules.Problem.TooBig -> UiText.res(R.string.sound_custom_too_big, (CustomSoundRules.MAX_BYTES / 1024).toInt())
        CustomSoundRules.Problem.TooLong -> UiText.res(R.string.sound_custom_too_long, "%.1f".format(CustomSoundRules.maxMs(sound) / 1000f))
        CustomSoundRules.Problem.TooShort -> UiText.res(R.string.sound_custom_too_short)
        CustomSoundRules.Problem.NotAudio -> UiText.res(R.string.sound_custom_not_audio)
    }

    private companion object {
        const val PREVIEW_AFTER_LOAD_MS = 250L
    }
}
