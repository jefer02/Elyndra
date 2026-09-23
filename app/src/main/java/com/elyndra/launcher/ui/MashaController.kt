package com.elyndra.launcher.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.elyndra.launcher.R
import com.elyndra.launcher.data.AppLocale
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.data.fmtMinutes
import com.elyndra.launcher.domain.insights.Insight
import com.elyndra.launcher.domain.lists.DynamicList
import com.elyndra.launcher.domain.lists.ListRule
import com.elyndra.launcher.domain.lists.SmartLists
import com.elyndra.launcher.domain.session.SessionMood
import com.elyndra.launcher.masha.MashaAttachment
import com.elyndra.launcher.masha.MashaBrain
import com.elyndra.launcher.masha.MashaChatRequest
import com.elyndra.launcher.masha.MashaError
import com.elyndra.launcher.masha.MashaEvent
import com.elyndra.launcher.masha.MashaTools
import com.elyndra.launcher.masha.MashaTurn
import com.elyndra.launcher.masha.StoredMessage
import com.elyndra.launcher.masha.ToolCall
import com.elyndra.launcher.masha.offline.OfflineIntent
import com.elyndra.launcher.masha.offline.OfflineIntents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Masha en la interfaz: la conversación (en streaming, con herramientas y
 * con sus adjuntos), la sugerencia ambiental sobre el carrusel y las tarjetas
 * de la cabecera del chat.
 *
 * Con IA (DeepSeek) conversa y actúa; sin ella —sin clave, sin red o con la IA
 * apagada en Ajustes— sigue actuando: entiende lo esencial por palabras clave
 * y contesta con los datos reales ([offlineReply]).
 */
class MashaController(private val vm: ElyndraViewModel, private val brain: MashaBrain) {

    var draft by mutableStateOf(""); private set

    /** Masha está contestando (y se puede parar). */
    var busy by mutableStateOf(false); private set

    /** Lo que está haciendo ahora mismo ("Buscando en tu biblioteca…"), mientras usa una herramienta. */
    var working by mutableStateOf<UiText?>(null); private set

    val messages = mutableStateListOf<ChatMessage>()

    /** La sugerencia ambiental del momento (null = nada que decir). */
    var insight by mutableStateOf<Insight?>(null); private set

    /**
     * Sube cada vez que se recalcula la sugerencia. La burbuja se recoge sola
     * al rato; con esto vuelve a asomar al volver a la app aunque la
     * sugerencia sea la misma de antes.
     */
    var insightRevision by mutableIntStateOf(0); private set

    private val actions = MashaActions(vm, brain)
    private var job: Job? = null
    private var historyLoaded = false

    /** La IA en línea está disponible (clave + permiso del usuario). La red se mira al enviar. */
    val online: Boolean get() = brain.ai.isAvailable

    val hasKey: Boolean get() = brain.config.hasKey

    fun updateDraft(v: String) {
        draft = v
    }

    /** Al abrir el chat: recupera el hilo guardado (una vez) y saluda si está vacío. */
    fun onOpen(greeting: String) {
        if (historyLoaded) {
            if (messages.isEmpty()) messages += ChatMessage(fromMasha = true, text = greeting, greeting = true)
            return
        }
        historyLoaded = true
        vm.viewModelScope.launch {
            val stored = brain.memory.history()
            if (messages.isEmpty()) {
                stored.forEach { m ->
                    messages += ChatMessage(
                        fromMasha = m.role == MashaTurn.Role.Masha,
                        text = m.text,
                        game = m.gameKey?.let { gameRef(it) },
                        attachment = m.attachment,
                        restored = true,
                    )
                }
                if (messages.isEmpty()) messages += ChatMessage(fromMasha = true, text = greeting, greeting = true)
            }
        }
    }

    /**
     * Borra el hilo (en pantalla y guardado). Los recuerdos se quedan. Sin
     * [greeting] el chat queda vacío y saluda la próxima vez que se abra.
     */
    fun clearConversation(greeting: String?) {
        stop()
        messages.clear()
        if (greeting != null) messages += ChatMessage(fromMasha = true, text = greeting, greeting = true)
        vm.viewModelScope.launch { brain.memory.clearHistory() }
    }

    fun sendSuggestion(text: String, uiLanguage: String) {
        draft = text
        // Las sugerencias son preguntas de consulta: su respuesta se puede reutilizar.
        send(uiLanguage, cacheable = true)
    }

    fun send(uiLanguage: String, cacheable: Boolean = false) {
        val text = draft.trim()
        if (text.isEmpty() || busy) return
        // El hilo que ve el modelo es el de antes de este mensaje, sin el saludo de cortesía.
        val history = messages.filterNot { it.pending || it.greeting || it.text.isBlank() }
            .map { MashaTurn(if (it.fromMasha) MashaTurn.Role.Masha else MashaTurn.Role.User, it.text) }
        messages += ChatMessage(fromMasha = false, text = text)
        draft = ""
        busy = true
        persist(StoredMessage(MashaTurn.Role.User, text, null, null, System.currentTimeMillis()))

        job = vm.viewModelScope.launch {
            try {
                if (brain.canGoOnline()) online(text, history, uiLanguage, cacheable) else offlineReply(text, failure = null)
            } catch (e: CancellationException) {
                finishPending(stopped = true)
                throw e
            } finally {
                busy = false
                working = null
            }
        }
    }

    /** Corta la respuesta en curso (la petición a la IA se cancela de verdad). */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun online(text: String, history: List<MashaTurn>, language: String, cacheable: Boolean) {
        val context = brain.context(language, vm.screenName(), vm.focusName())
        val index = messages.size
        messages += ChatMessage(fromMasha = true, text = "", pending = true)
        val buffer = StringBuilder()
        var attachment: MashaAttachment? = null
        val done = ArrayList<String>()

        fun render(final: Boolean) {
            if (index !in messages.indices) return
            messages[index] = messages[index].copy(
                text = buffer.toString(),
                attachment = attachment,
                done = done.toList(),
                pending = !final,
            )
        }

        brain.ai.chat(
            MashaChatRequest(history, text, context.json, context.fingerprint, language, cacheable),
            actions,
        ).collect { event ->
            when (event) {
                is MashaEvent.Delta -> {
                    buffer.append(event.text)
                    render(final = false)
                }
                is MashaEvent.ToolStarted -> working = workingLabel(event.call)
                is MashaEvent.ToolFinished -> {
                    working = null
                    when (val a = event.result.attachment) {
                        is MashaAttachment.Done -> done += a.label
                        null -> Unit
                        else -> attachment = a
                    }
                    render(final = false)
                }
                is MashaEvent.Completed -> {
                    buffer.setLength(0)
                    buffer.append(event.text)
                    render(final = true)
                    val game = if (attachment == null) findMentionedGame(event.text) else null
                    if (game != null && index in messages.indices) messages[index] = messages[index].copy(game = game)
                    persist(StoredMessage(MashaTurn.Role.Masha, event.text, game?.key, attachment, System.currentTimeMillis()))
                }
                is MashaEvent.Failed -> {
                    if (event.partial.isBlank()) {
                        // Nada que enseñar: se quita la burbuja vacía y contesta Masha sin conexión.
                        if (index in messages.indices) messages.removeAt(index)
                        offlineReply(text, failure = event.error)
                    } else {
                        render(final = true)
                        persist(StoredMessage(MashaTurn.Role.Masha, event.partial, null, attachment, System.currentTimeMillis()))
                        vm.showToast(errorText(event.error))
                    }
                }
            }
        }
    }

    /** Si se cortó a medias, lo que quedó escrito se da por bueno (sin la marca de "escribiendo"). */
    private fun finishPending(stopped: Boolean) {
        val i = messages.indexOfLast { it.pending }
        if (i < 0) return
        val m = messages[i]
        if (m.text.isBlank() && m.attachment == null && stopped) messages.removeAt(i) else messages[i] = m.copy(pending = false)
    }

    /* ── Masha sin IA ─────────────────────────────────────────── */

    /**
     * Respuesta local: entiende la intención por palabras clave y la resuelve
     * con las mismas capacidades que usa la IA. Si llega aquí por un fallo, lo
     * dice una vez, en una línea, y contesta igual.
     */
    private suspend fun offlineReply(text: String, failure: MashaError?) {
        val ctx = localized()
        val skills = brain.skills
        var attachment: MashaAttachment? = null
        var game: MashaGameRef? = null
        val reply: String = when (val intent = OfflineIntents.detect(text)) {
            is OfflineIntent.Launch -> {
                val target = brain.knowledge.snapshot().resolve(intent.title)
                if (target == null) {
                    ctx.getString(R.string.masha_off_not_found, intent.title)
                } else {
                    actions.launchGame(target.title)
                    game = gameRef(target.key)
                    ctx.getString(R.string.masha_off_launch, target.title)
                }
            }
            is OfflineIntent.Plan -> planText(ctx, intent.minMinutes, intent.maxMinutes, intent.mood) { attachment = it }
            OfflineIntent.Recommend -> planText(ctx, 30, 60, SessionMood.Any) { attachment = it }
            OfflineIntent.Stats -> statsText(ctx)
            is OfflineIntent.ShowList -> {
                val s = brain.knowledge.snapshot()
                val found = SmartLists.evaluate(ListRule(list = intent.list.id), s.listContext())
                attachment = MashaAttachment.Games(intent.list.id, found.take(40).map { it.key }, found.size)
                if (found.isEmpty()) ctx.getString(R.string.masha_off_list_empty)
                else ctx.resources.getQuantityString(R.plurals.masha_off_list, found.size, found.size)
            }
            OfflineIntent.UpdateMetadata -> {
                val result = actions.execute(ToolCall("local", MashaTools.UPDATE_METADATA, args("scope" to "missing")))
                if (result.ok) ctx.getString(R.string.masha_off_metadata) else ctx.getString(R.string.configure_a_service)
            }
            OfflineIntent.Cleanup -> {
                val c = brain.knowledge.snapshot().curation
                val keys = (c.duplicates.flatMap { it.keys } + c.incomplete.flatMap { it.keys } + c.naming.map { it.key }).distinct()
                if (keys.isNotEmpty()) attachment = MashaAttachment.Games(DynamicList.PoorlyNamed.id, keys.take(40), keys.size)
                ctx.getString(R.string.masha_off_cleanup, c.duplicates.size, c.incomplete.size, c.naming.size)
            }
            OfflineIntent.Arcs -> {
                val arcs = skills.arcProgress()
                val first = arcs.firstOrNull()
                val step = first?.current
                if (first == null || step == null) {
                    ctx.getString(R.string.masha_off_no_arcs)
                } else {
                    val title = brain.knowledge.snapshot().game(step.gameKey)?.title ?: "?"
                    attachment = MashaAttachment.ArcCard(first.arc.id, first.arc.title, first.arc.steps.map { it.gameKey }, first.doneSteps, first.totalSteps)
                    ctx.getString(R.string.masha_off_arc, first.arc.title, first.doneSteps + 1, first.totalSteps, title)
                }
            }
            OfflineIntent.Unknown -> ctx.getString(if (brain.config.hasKey) R.string.masha_off_unknown else R.string.masha_off_unknown_nokey)
        }
        val prefix = failure?.let { ctx.getString(failureLine(it)) + "\n\n" }.orEmpty()
        val full = prefix + reply
        messages += ChatMessage(fromMasha = true, text = full, game = game, attachment = attachment, offline = true)
        persist(StoredMessage(MashaTurn.Role.Masha, full, game?.key, attachment, System.currentTimeMillis()))
    }

    private suspend fun planText(ctx: Context, min: Int, max: Int, mood: SessionMood, attach: (MashaAttachment) -> Unit): String {
        val (plan, result) = brain.skills.planSession(min, max, mood)
        if (plan.isEmpty) return ctx.getString(R.string.masha_off_plan_empty)
        result.attachment?.let(attach)
        val parts = plan.blocks.joinToString(" + ") { "${it.game.title} (${it.minutes} min)" }
        val intro = ctx.getString(
            when (mood) {
                SessionMood.Continue -> R.string.masha_off_plan_continue
                SessionMood.Light -> R.string.masha_off_plan_light
                SessionMood.Fresh -> R.string.masha_off_plan_new
                SessionMood.Any -> R.string.masha_off_plan_any
            },
        )
        val limited = if (plan.deviceLimited) " " + ctx.getString(R.string.masha_off_plan_device) else ""
        return "$intro $parts.$limited"
    }

    private suspend fun statsText(ctx: Context): String {
        val s = brain.knowledge.snapshot()
        val week = 7L * 24 * 60 * 60 * 1000
        val sessions = s.library.sessions.filter { it.minutes > 0 }
        val thisWeek = sessions.filter { it.start >= s.now - week }
        val lastWeek = sessions.filter { it.start in (s.now - 2 * week) until (s.now - week) }
        val top = thisWeek.groupBy { it.key }.mapValues { (_, l) -> l.sumOf { it.minutes } }.maxByOrNull { it.value }
        val topTitle = top?.let { s.game(it.key)?.title }
        if (thisWeek.isEmpty()) return ctx.getString(R.string.masha_off_stats_empty, fmtMinutes(lastWeek.sumOf { it.minutes }))
        return ctx.getString(
            R.string.masha_off_stats,
            fmtMinutes(thisWeek.sumOf { it.minutes }),
            thisWeek.size,
            fmtMinutes(lastWeek.sumOf { it.minutes }),
            topTitle ?: "—",
        )
    }

    /* ── sugerencia ambiental ─────────────────────────────────── */

    /** Recalcula qué decir sobre el carrusel (al volver a la app, tras jugar, tras una pasada de metadatos…). */
    fun refreshInsight() {
        if (!vm.app.settings.mashaAmbient) {
            insight = null
            return
        }
        vm.viewModelScope.launch {
            insight = runCatching { brain.insights().firstOrNull() }.getOrNull()
            insightRevision++
        }
    }

    fun dismissInsight() {
        insight?.let { brain.dismiss(it) }
        insight = null
        refreshInsight()
    }

    /** Tocar la sugerencia hace lo que propone (sin lanzar nada a ciegas: los juegos abren su ficha). */
    fun actOnInsight(uiLanguage: String) {
        val current = insight ?: return
        when (current) {
            Insight.EmptyLibrary -> vm.go(Screen.Add)
            is Insight.DeviceHot, is Insight.MetadataFinished -> Unit
            is Insight.ArcNext -> vm.showDetails(current.gameKey)
            is Insight.ContinueGame -> vm.showDetails(current.gameKey)
            is Insight.Abandoned -> vm.showDetails(current.gameKey)
            is Insight.NeverOpened -> vm.showDetails(current.sampleKey)
            is Insight.EmulatorMissing -> vm.library.folders.firstOrNull { it.id == current.folderId }?.let { vm.pickFolderEmulator(it) }
            is Insight.ConfigureServices -> vm.go(Screen.Settings)
            is Insight.MissingArt -> {
                val keys = (vm.library.roms.map { it.key } + vm.library.apps.map { it.key }).filter { vm.engine.needsWork(it) }
                vm.updateMetadata(keys, force = false)
            }
            is Insight.CleanUp, is Insight.Unmatched -> {
                vm.go(Screen.Masha)
                sendSuggestion(localized().getString(R.string.masha_chip_cleanup), uiLanguage)
            }
        }
        brain.dismiss(current)
        insight = null
    }

    fun insightText(i: Insight): UiText = insightText(i, brain::emulatorName)

    /* ── cabecera del chat ────────────────────────────────────── */

    data class Stats(
        val weekMinutes: Int,
        val deltaMinutes: Int,
        val topTitle: String?,
        val topMinutes: Int,
        val weekSessions: Int,
        val averageMinutes: Int,
    )

    /** Tarjetas de la cabecera: esta semana, el más jugado y las sesiones (las de verdad, no las salidas al minuto). */
    fun stats(now: Long = System.currentTimeMillis()): Stats {
        val lib = vm.library
        val week = 7L * 24 * 60 * 60 * 1000
        val sessions = lib.sessions.filter { it.minutes > 0 }
        val thisWeek = sessions.filter { it.start >= now - week }
        val lastWeek = sessions.filter { it.start in (now - 2 * week) until (now - week) }
        val minutes = thisWeek.sumOf { it.minutes }
        val top = (lib.roms.map { it.displayTitle to it.stats.minutes } + lib.apps.map { it.displayTitle to it.stats.minutes })
            .maxByOrNull { it.second }
            ?.takeIf { it.second > 0 }
        return Stats(
            weekMinutes = minutes,
            deltaMinutes = minutes - lastWeek.sumOf { it.minutes },
            topTitle = top?.first,
            topMinutes = top?.second ?: 0,
            weekSessions = thisWeek.size,
            averageMinutes = if (thisWeek.isEmpty()) 0 else minutes / thisWeek.size,
        )
    }

    /* ── utilidades ───────────────────────────────────────────── */

    /**
     * Cuando Masha nombra un juego de la biblioteca se engancha su carátula (o
     * icono) al mensaje. Se queda con la coincidencia más larga: entre "Mario"
     * y "Super Mario Bros." en el mismo texto, la segunda es la que se nombra.
     */
    private fun findMentionedGame(text: String): MashaGameRef? {
        val lib = vm.library
        val apps = lib.apps.map { a -> a.key to a.displayTitle }
        val roms = lib.roms.map { r -> r.key to r.displayTitle }
        return (apps + roms)
            .filter { (_, title) -> title.length >= 3 && text.contains(title, ignoreCase = true) }
            .maxByOrNull { it.second.length }
            ?.let { gameRef(it.first) }
    }

    fun gameRef(key: String): MashaGameRef? {
        val lib = vm.library
        lib.apps.firstOrNull { it.key == key }?.let { a ->
            return MashaGameRef(a.key, a.displayTitle, "Android", a.meta.cover ?: a.meta.icon, a.packageName)
        }
        val r = lib.roms.firstOrNull { it.key == key } ?: return null
        return MashaGameRef(r.key, r.displayTitle, Systems.byId(r.systemId)?.name ?: r.systemId, r.meta.cover ?: r.meta.icon, null)
    }

    private fun workingLabel(call: ToolCall): UiText = UiText.res(
        when (call.name) {
            MashaTools.FIND_GAMES, MashaTools.OPEN_LIST -> R.string.masha_working_search
            MashaTools.GET_GAME_PROFILE, MashaTools.SUGGEST_EMULATOR -> R.string.masha_working_profile
            MashaTools.PLAN_SESSION -> R.string.masha_working_plan
            MashaTools.CREATE_ARC, MashaTools.GET_ARCS -> R.string.masha_working_arc
            MashaTools.CURATION_REPORT -> R.string.masha_working_cleanup
            MashaTools.GET_STATS -> R.string.masha_working_stats
            MashaTools.LAUNCH_GAME -> R.string.masha_working_launch
            MashaTools.UPDATE_METADATA, MashaTools.SET_ART -> R.string.masha_working_metadata
            else -> R.string.masha_working_generic
        },
    )

    fun errorText(e: MashaError): UiText = UiText.res(failureLine(e))

    private fun failureLine(e: MashaError): Int = when (e) {
        MashaError.NotConfigured -> R.string.masha_err_no_key
        MashaError.Offline, is MashaError.Network -> R.string.masha_err_offline
        MashaError.Unauthorized -> R.string.masha_err_key
        MashaError.InsufficientBalance -> R.string.masha_err_balance
        MashaError.RateLimited -> R.string.masha_err_rate
        is MashaError.Unavailable -> R.string.masha_err_unavailable
        MashaError.TooManyRounds, is MashaError.BadRequest, is MashaError.Protocol -> R.string.masha_err_generic
    }

    private fun persist(message: StoredMessage) {
        vm.viewModelScope.launch { brain.memory.saveMessage(message) }
    }

    /** Contexto con el idioma de la app, para componer respuestas fuera de Compose. */
    private fun localized(): Context = AppLocale.wrap(vm.app)

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
}
