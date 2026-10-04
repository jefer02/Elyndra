package com.elyndra.launcher.metadata

import com.elyndra.launcher.data.GameMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/* ─────────────────────────────────────────────────────────────
   Paquetes de idioma para traducir descripciones (ML Kit).

   Qué paquetes hacen falta lo decide [TranslationNeeds] (Kotlin
   puro): el del idioma de la app y los de las descripciones que
   solo existen en otro idioma, más el inglés cuando hace de
   pivote. [TranslationPacks] los baja —solo cuando el usuario
   lo pide— y lleva su estado: instalado, descargando, esperando
   Wi-Fi o error. Nada se baja en silencio.
   ───────────────────────────────────────────────────────────── */

/** Estado de un paquete que se está bajando (o que falló). Los instalados no llevan estado. */
enum class PackState { Downloading, WaitingForWifi, Error }

object TranslationNeeds {

    /**
     * Los idiomas de origen de las descripciones que, en [target], se
     * enseñarían en otro idioma: lo que habría que traducir.
     */
    fun foreignSources(metas: Iterable<GameMeta>, target: String): Set<String> =
        metas.mapNotNullTo(LinkedHashSet()) { DescriptionPick.translatable(it, target)?.first }

    /**
     * Los paquetes necesarios para traducir de [sources] a [target]. ML Kit
     * traduce entre dos idiomas que no son el inglés pasando por él, así que
     * entonces también hace falta el inglés.
     */
    fun required(sources: Set<String>, target: String, supported: (String) -> Boolean = { true }): Set<String> {
        val from = sources.filter { it != target && supported(it) }
        if (from.isEmpty() || !supported(target)) return emptySet()
        val out = LinkedHashSet<String>()
        out += target
        out += from
        if (target != DescriptionLangs.FALLBACK && from.any { it != DescriptionLangs.FALLBACK }) out += DescriptionLangs.FALLBACK
        return out
    }

    /**
     * Al cambiar el idioma de la app, ¿qué paquetes se ofrecen? Null = ningún
     * aviso: la primera vez (no hay cambio, solo se anota), mismo idioma,
     * nada que bajar, o ya se está bajando algo.
     */
    fun notice(lastLang: String?, lang: String, missing: Set<String>, downloading: Boolean): Set<String>? = when {
        lastLang == null || lastLang == lang -> null
        missing.isEmpty() || downloading -> null
        else -> missing
    }

    /** Lo que falta por bajar; vacío = no hay que avisar de nada. */
    fun missing(sources: Set<String>, target: String, installed: Set<String>, supported: (String) -> Boolean = { true }): Set<String> =
        required(sources, target, supported) - installed
}

/**
 * Los paquetes de idioma del dispositivo y su descarga. Vive en el ámbito de
 * la aplicación: una descarga que espera Wi-Fi sigue aunque se cierre la
 * pantalla. [unmetered] dice si la red de ahora es Wi-Fi (o similar).
 */
class TranslationPacks(
    private val translator: DescriptionTranslator,
    private val scope: CoroutineScope,
    private val unmetered: () -> Boolean,
    private val pollMs: Long = 3_000,
) {
    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    /** Idiomas con el paquete ya en el dispositivo. */
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val _states = MutableStateFlow<Map<String, PackState>>(emptyMap())
    /** Los que se están bajando, esperan Wi-Fi o fallaron. */
    val states: StateFlow<Map<String, PackState>> = _states.asStateFlow()

    private var job: Job? = null

    val busy: Boolean get() = job?.isActive == true

    fun supports(lang: String): Boolean = translator.supports(lang, DescriptionLangs.FALLBACK)

    suspend fun refresh(): Set<String> {
        val now = try {
            translator.downloadedModels()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _installed.value
        }
        _installed.value = now
        return now
    }

    /**
     * Baja [langs] (los que falten). Solo se llama cuando el usuario lo ha
     * pedido. Sin [allowMobileData] y sin Wi-Fi, queda "esperando Wi-Fi" hasta
     * que la haya (ML Kit espera solo). [onDone] recibe si todo quedó instalado.
     */
    fun download(langs: Set<String>, allowMobileData: Boolean, onDone: (Boolean) -> Unit = {}) {
        val todo = langs - _installed.value
        if (todo.isEmpty()) {
            onDone(true)
            return
        }
        if (busy) return
        fun mark() = if (!allowMobileData && !unmetered()) PackState.WaitingForWifi else PackState.Downloading
        _states.update { it + todo.associateWith { mark() } }
        job = scope.launch {
            // La red puede cambiar mientras tanto: el estado la sigue.
            val watcher = launch {
                while (isActive) {
                    delay(pollMs)
                    val state = mark()
                    _states.update { m -> m.mapValues { (l, s) -> if (s != PackState.Error && l in todo) state else s } }
                }
            }
            var ok = true
            for (lang in todo) {
                try {
                    translator.download(setOf(lang), wifiOnly = !allowMobileData)
                    _states.update { it - lang }
                } catch (e: CancellationException) {
                    watcher.cancel()
                    throw e
                } catch (e: Exception) {
                    ok = false
                    _states.update { it + (lang to PackState.Error) }
                }
            }
            watcher.cancel()
            // El watcher pudo pisar un estado al final: lo instalado no lleva estado.
            val now = refresh()
            _states.update { m -> m.filterKeys { it !in now } }
            onDone(ok && todo.all { it in now })
        }
    }

    suspend fun delete(lang: String) {
        try {
            translator.deleteModel(lang)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        _states.update { it - lang }
        refresh()
    }
}
