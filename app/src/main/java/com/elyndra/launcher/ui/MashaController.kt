package com.elyndra.launcher.ui

import com.elyndra.launcher.masha.ConfirmState
import com.elyndra.launcher.masha.MashaWriteRules
import com.elyndra.launcher.masha.MashaWrites
import com.elyndra.launcher.masha.ReplyShape
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
import com.elyndra.launcher.domain.device.DeviceAdvice
import com.elyndra.launcher.domain.insights.Insight
import com.elyndra.launcher.domain.lists.DynamicList
import com.elyndra.launcher.domain.lists.ListRule
import com.elyndra.launcher.domain.lists.SmartLists
import com.elyndra.launcher.domain.ports.PortRecommend
import com.elyndra.launcher.domain.relationship.AffinityLevel
import com.elyndra.launcher.domain.relationship.AffinityScorer
import com.elyndra.launcher.domain.relationship.GainReason
import com.elyndra.launcher.domain.session.SessionMood
import com.elyndra.launcher.masha.ConversationRecall
import com.elyndra.launcher.masha.MashaAttachment
import com.elyndra.launcher.masha.MashaContextBuilder
import com.elyndra.launcher.masha.MashaPrompt
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
import com.elyndra.launcher.ui.masha.MashaMood
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lucy (Masha en el código) en la interfaz: la conversación (en streaming, con herramientas y
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

    /**
     * El ánimo de la última respuesta (frío al analizar, lavanda al bromear o
     * al consolar). Lo pinta el holograma; se deduce en local, sin tocar la IA.
     */
    var mood by mutableStateOf(MashaMood.Neutral); private set

    /** La sugerencia ambiental del momento (null = nada que decir). */
    var insight by mutableStateOf<Insight?>(null); private set

    /**
     * Sube cada vez que asoma una sugerencia nueva. La burbuja se recoge sola
     * al rato; la misma sugerencia no vuelve a asomar hasta que [pacing] lo deja.
     */
    var insightRevision by mutableIntStateOf(0); private set

    private val pacing get() = brain.pacing

    /** El nivel de la relación con Lucy (el corazón de su pantalla). */
    var affinityLevel by mutableStateOf(brain.affinity.level); private set

    /** Sube cada vez que la relación sube de nivel: Lucy lo celebra con un gesto (nunca hay aviso al bajar). */
    var levelUps by mutableIntStateOf(0); private set

    /** Cómo va hacia el siguiente nivel (barra del panel "Lucy y tú" y de Ajustes). */
    var affinityProgress by mutableStateOf(brain.affinity.progress); private set

    /** Las últimas subidas, la más nueva primero (las bajadas no se enseñan). */
    var recentGains by mutableStateOf(brain.affinity.recentGains); private set

    /** Cada subida de puntos: [gainSeq] cambia y [lastGain] dice cuántos (el "+N" flotante). */
    var gainSeq by mutableIntStateOf(0); private set
    var lastGain by mutableIntStateOf(0); private set

    /** Un nivel alcanzado por primera vez, para la tarjeta de celebración (null = ninguna). */
    var celebration by mutableStateOf<AffinityLevel?>(null); private set

    fun dismissCelebration() {
        celebration = null
    }

    /** Ya avisó de la batería o del calor en esta sesión de su pantalla (una vez como mucho). */
    private var deviceWarned = false

    /** El último mensaje del usuario: repetirlo tal cual no suma. */
    private var lastUserText: String? = null

    /** Lo que Masha propone cambiar y espera confirmación, y la pila de deshacer. */
    val writes = MashaWrites()
    private val actions = MashaActions(vm, brain, writes) { id, state -> markConfirm(id, state) }
    private var job: Job? = null
    private var persistJob: Job? = null

    /** La respuesta de la IA que ya se guardó entera (para marcarla si luego la cortan hablando). */
    private var savedReplyId: Long? = null
    private var historyLoaded = false

    val hasKey: Boolean get() = brain.config.hasKey

    fun updateDraft(v: String) {
        draft = v
    }

    /** Al abrir el chat: recupera el hilo guardado (una vez) y saluda si está vacío. */
    /**
     * Al abrir el chat: recupera el hilo guardado (una vez). Si pasó más de
     * media hora desde el último mensaje y hay un tema guardado, Lucy saluda
     * retomándolo; si no, el hilo sigue tal cual (y con el hilo vacío, el saludo de siempre).
     */
    fun onOpen(greeting: String, uiLanguage: String) {
        val welcome = ConversationRecall.shouldWelcomeBack(System.currentTimeMillis(), recall)
        if (historyLoaded) {
            if (welcome) welcomeBack(uiLanguage)
            else if (messages.isEmpty()) messages += ChatMessage(fromMasha = true, text = greeting, greeting = true)
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
                        interrupted = m.interrupted,
                    )
                }
            }
            if (welcome) welcomeBack(uiLanguage)
            else if (messages.isEmpty()) messages += ChatMessage(fromMasha = true, text = greeting, greeting = true)
        }
    }

    /* ── la última conversación ───────────────────────────────── */

    private val recall get() = brain.conversation
    private var idleJob: Job? = null
    private var welcomeJob: Job? = null

    /** Al salir de la pantalla: la conversación termina y se resume (sigue aunque la pantalla ya no esté). */
    fun onClose() {
        idleJob?.cancel()
        deviceWarned = false
        vm.viewModelScope.launch { summarize() }
    }

    /** Hubo un mensaje: se apunta y, tras [ConversationRecall.IDLE_END_MS] de silencio, se da por terminada. */
    private fun noteActivity(at: Long) {
        recall.lastActivityAt = at
        idleJob?.cancel()
        idleJob = vm.viewModelScope.launch {
            delay(ConversationRecall.IDLE_END_MS)
            summarize()
        }
    }

    /**
     * Guarda una frase con el tema de la conversación: con IA, una llamada
     * barata; sin ella (o si falla), los juegos y las intenciones que salieron.
     */
    private suspend fun summarize() {
        if (!ConversationRecall.needsSummary(recall)) return
        val turns = messages.filterNot { it.pending || it.greeting || it.text.isBlank() }
            .map { MashaTurn(if (it.fromMasha) MashaTurn.Role.Masha else MashaTurn.Role.User, it.text, it.interrupted) }
        if (turns.isEmpty()) return
        val upTo = recall.lastActivityAt
        val titles = vm.library.roms.map { it.displayTitle } + vm.library.apps.map { it.displayTitle }
        val local = ConversationRecall.fallback(turns, titles)
        val online = if (brain.canGoOnline()) {
            brain.ai.complete(ConversationRecall.SUMMARY_SYSTEM, ConversationRecall.transcript(turns), maxTokens = 60, temperature = 0.3)
                .getOrNull()
                ?.let(ConversationRecall::sanitize)
        } else {
            null
        }
        val summary = online ?: local?.summary ?: return
        recall.summary = summary
        recall.topicGame = local?.game
        recall.summarizedUpTo = upTo
    }

    /**
     * El saludo al volver, retomando el tema: con IA, en su voz y a su nivel
     * (una llamada con su prompt y el contexto); sin ella, una frase fija con
     * el juego del que se habló, si lo hubo.
     */
    private fun welcomeBack(language: String) {
        val summary = recall.summary ?: return
        if (busy || welcomeJob?.isActive == true) return
        val away = System.currentTimeMillis() - recall.lastActivityAt
        val index = messages.size
        messages += ChatMessage(fromMasha = true, text = "", pending = true)
        welcomeJob = vm.viewModelScope.launch {
            val online = if (brain.canGoOnline()) {
                val context = brain.context(language, vm.screenName(), vm.focusName())
                val prompt = "CONTEXT (JSON):\n${context.json}\nUI_LANGUAGE: $language\n\n" +
                    ConversationRecall.welcomeInstruction(summary, MashaContextBuilder.ago(away).removeSuffix(" ago"))
                brain.ai.complete(MashaPrompt.SYSTEM, prompt, maxTokens = 160).getOrNull()?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            val ctx = localized()
            val text = online ?: recall.topicGame?.let { ctx.getString(R.string.lucy_welcome_back_game, it) }
                ?: ctx.getString(R.string.lucy_welcome_back)
            if (index in messages.indices && messages[index].pending) {
                messages[index] = messages[index].copy(text = text, pending = false, offline = online == null)
            } else {
                messages += ChatMessage(fromMasha = true, text = text, offline = online == null)
            }
            mood = MashaMood.Warm
            persist(StoredMessage(MashaTurn.Role.Masha, text, null, null, System.currentTimeMillis()))
        }
    }

    /** "Olvidar conversaciones": el hilo y el último tema (los recuerdos y la relación se quedan). */
    fun forgetConversations() {
        welcomeJob?.cancel()
        idleJob?.cancel()
        recall.clear()
        clearConversation(null)
    }

    /**
     * Borra el hilo (en pantalla y guardado). Los recuerdos se quedan. Sin
     * [greeting] el chat queda vacío y saluda la próxima vez que se abra.
     */
    fun clearConversation(greeting: String?) {
        stop()
        messages.clear()
        lastUserText = null
        if (greeting != null) messages += ChatMessage(fromMasha = true, text = greeting, greeting = true)
        vm.viewModelScope.launch { brain.memory.clearHistory() }
    }

    fun sendSuggestion(text: String, uiLanguage: String) {
        draft = text
        // Las sugerencias son preguntas de consulta: su respuesta se puede reutilizar.
        send(uiLanguage, cacheable = true)
    }

    /** Lo escrito en el campo de texto. */
    fun send(uiLanguage: String, cacheable: Boolean = false) {
        if (submit(draft.trim(), uiLanguage, cacheable)) draft = ""
    }

    /**
     * Lo dicho en la conversación en vivo: va directo, sin pasar por el campo
     * de texto, por el mismo camino que lo escrito (contexto, idioma y
     * herramientas). Con [force], si aún contesta otra cosa, esa respuesta se
     * corta y se suelta su "ocupada". false si no se mandó.
     */
    fun say(text: String, uiLanguage: String, force: Boolean = false): Boolean {
        if (busy && force) release()
        return submit(text.trim(), uiLanguage, cacheable = false)
    }

    /** Sube con cada respuesta (o confirmación) que se queda el "ocupada": solo la última lo suelta. */
    private var replySeq = 0

    /** Corta ya la respuesta en curso y suelta "ocupada", sin esperar a que su corrutina termine. */
    private fun release() {
        replySeq++
        job?.cancel()
        job = null
        finishPending(stopped = true)
        busy = false
        working = null
    }

    private fun submit(text: String, uiLanguage: String, cacheable: Boolean): Boolean {
        if (text.isEmpty() || busy) return false
        // "Sí" / "no" con una propuesta a la espera: se contesta aquí, sin IA.
        val pendingWrite = writes.latest()
        val answer = pendingWrite?.let { MashaWriteRules.answer(text) }
        if (pendingWrite != null && answer != null) {
            messages += ChatMessage(fromMasha = false, text = text)
            persist(StoredMessage(MashaTurn.Role.User, text, null, null, System.currentTimeMillis()))
            if (answer) confirmWrite(pendingWrite.id) else cancelWrite(pendingWrite.id)
            return true
        }
        // El hilo que ve el modelo es el de antes de este mensaje, sin el saludo de cortesía.
        val history = messages.filterNot { it.pending || it.greeting || it.text.isBlank() }
            .map { MashaTurn(if (it.fromMasha) MashaTurn.Role.Masha else MashaTurn.Role.User, it.text, it.interrupted) }
        messages += ChatMessage(fromMasha = false, text = text)
        busy = true
        persist(StoredMessage(MashaTurn.Role.User, text, null, null, System.currentTimeMillis()))
        val previous = lastUserText
        lastUserText = text
        // Lo que dijo decide si una acción se hace ya o se propone (ActionGate).
        actions.lastUserText = text

        val seq = ++replySeq
        job = vm.viewModelScope.launch {
            try {
                if (brain.canGoOnline()) online(text, previous, history, uiLanguage, cacheable) else offlineReply(text, previous, failure = null)
            } catch (e: CancellationException) {
                // Si ya la soltó release(), lo pendiente es de la respuesta nueva.
                if (seq == replySeq) finishPending(stopped = true)
                throw e
            } finally {
                if (seq == replySeq) {
                    busy = false
                    working = null
                }
            }
        }
        return true
    }

    /* ── relación ─────────────────────────────────────────────── */

    /**
     * Puntúa el mensaje del usuario para la relación: lo que dijo la IA (con la
     * red de seguridad local) o, sin ella, las reglas locales. Pedir una acción
     * no cuenta. Si sube de nivel, Lucy se pone cálida y lo celebra.
     */
    private fun score(message: String, previous: String?, modelDelta: Int?) {
        val level = brain.affinity.level
        val delta = modelDelta?.let { AffinityScorer.guard(message, it) } ?: run {
            val intent = OfflineIntents.detect(message)
            val isAction = intent !is OfflineIntent.Hello && intent !is OfflineIntent.Unknown
            AffinityScorer.score(message, level, isAction, previous)
        }
        // La IA no dice por qué: su puntuación va como "charla"; la local sí sabe el motivo.
        val reason = if (modelDelta != null) GainReason.Chat else AffinityScorer.reason(message, level)
        val change = brain.affinity.apply(delta, reason)
        refreshAffinity()
        if (change.gained > 0) {
            lastGain = change.gained
            gainSeq++
        }
        if (change.levelUp) {
            levelUps++
            mood = MashaMood.Warm
        }
        if (change.celebrate) celebration = change.newLevel
    }

    /** Los celos sin conexión: nada hasta [AffinityLevel.Friendly]; de ahí, más intensos cuanto más cerca. */
    private fun jealousLine(message: String): Int? {
        if (!AffinityScorer.isJealousyTrigger(message)) return null
        return when (brain.affinity.level) {
            AffinityLevel.Distant, AffinityLevel.Curious -> null
            AffinityLevel.Friendly -> R.string.lucy_off_jealous_friendly
            AffinityLevel.Close -> R.string.lucy_off_jealous_close
            AffinityLevel.Devoted -> R.string.lucy_off_jealous_devoted
        }
    }

    /** "Olvidar todo": la relación vuelve al principio (y el corazón con ella). */
    fun refreshAffinity() {
        affinityLevel = brain.affinity.level
        affinityProgress = brain.affinity.progress
        recentGains = brain.affinity.recentGains
    }

    /* ── confirmaciones ───────────────────────────────────────── */

    /** ¿Sigue a la espera esta tarjeta? (tras reiniciar, ya no). */
    fun isWritePending(id: String): Boolean = writes.isPending(id)

    /**
     * "Confirmar" en la tarjeta, o un sí con palabras ("sí", "dale", "confirmo",
     * "yes", "はい"… ver ConfirmationResolver), por escrito o en vivo: se hace y
     * se cuenta en el chat. Mientras, cuenta como ocupada (la conversación en
     * vivo espera a que lo diga).
     */
    fun confirmWrite(id: String) {
        if (!writes.isPending(id)) return
        // Tocada mientras contesta otra cosa: esa respuesta ya lleva su "ocupada".
        val own = !busy
        if (own) busy = true
        val seq = if (own) ++replySeq else replySeq
        vm.viewModelScope.launch {
            try {
                val out = writes.confirm(id) ?: return@launch
                markConfirm(id, if (out.ok) ConfirmState.DONE else ConfirmState.CANCELLED)
                val ctx = localized()
                val text = ctx.getString(if (out.ok) R.string.masha_write_done else R.string.masha_write_failed, out.label)
                messages += ChatMessage(fromMasha = true, text = text, done = listOf(out.label))
                persist(StoredMessage(MashaTurn.Role.Masha, text, null, MashaAttachment.Done(out.label, out.ok), System.currentTimeMillis()))
            } finally {
                if (own && seq == replySeq) busy = false
            }
        }
    }

    /** "Cancelar": no se toca nada. */
    fun cancelWrite(id: String) {
        if (!writes.cancel(id)) return
        markConfirm(id, ConfirmState.CANCELLED)
        val text = localized().getString(R.string.masha_write_cancelled)
        messages += ChatMessage(fromMasha = true, text = text)
        persist(StoredMessage(MashaTurn.Role.Masha, text, null, null, System.currentTimeMillis()))
    }

    private fun markConfirm(id: String, state: String) {
        val i = messages.indexOfLast { (it.attachment as? MashaAttachment.Confirm)?.id == id }
        if (i < 0) return
        val a = messages[i].attachment as MashaAttachment.Confirm
        messages[i] = messages[i].copy(attachment = a.copy(state = state))
    }

    /** Corta la respuesta en curso (la petición a la IA se cancela de verdad). */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * El usuario le cortó hablando (conversación en vivo): se para la
     * respuesta y en el hilo queda solo lo que llegó a decir ([spoken], las
     * frases que sonaron), marcado como interrumpido, para que la próxima vez
     * no lo repita. Sin nada dicho y sin tarjeta, la burbuja se quita.
     */
    fun interrupt(spoken: String) {
        val lastUser = messages.indexOfLast { !it.fromMasha }
        val i = messages.indexOfLast { it.fromMasha && !it.greeting }
        stop()
        if (i < 0 || i < lastUser) return
        val m = messages[i]
        val text = spoken.trim().takeIf { it.isNotEmpty() }?.let { it.trimEnd('.', '…', ' ') + "…" } ?: "…"
        val saved = savedReplyId == m.id
        if (!saved && spoken.isBlank() && m.attachment == null) {
            messages.removeAt(i)
            return
        }
        messages[i] = m.copy(text = text, pending = false, interrupted = true)
        if (saved) {
            val previous = persistJob
            persistJob = vm.viewModelScope.launch {
                previous?.join()
                brain.memory.markLastInterrupted(text)
            }
        } else {
            persist(StoredMessage(MashaTurn.Role.Masha, text, m.game?.key, m.attachment, System.currentTimeMillis(), interrupted = true))
        }
    }

    private suspend fun online(text: String, previous: String?, history: List<MashaTurn>, language: String, cacheable: Boolean) {
        val warning = DeviceAdvice.forContext(brain.device.snapshot(), deviceWarned)
        val context = brain.context(language, vm.screenName(), vm.focusName(), portsSummary(), deviceWarned)
        val index = messages.size
        messages += ChatMessage(fromMasha = true, text = "", pending = true)
        val buffer = StringBuilder()
        var attachment: MashaAttachment? = null
        val done = ArrayList<String>()
        val tools = ArrayList<String>()

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
                is MashaEvent.ToolStarted -> {
                    tools += event.call.name
                    working = workingLabel(event.call)
                }
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
                    // El aviso de batería o calor iba en el contexto y se lanzó o instaló algo: ya está dado.
                    if (warning != null && tools.any { it in DeviceAdvice.HEAVY_TOOLS }) deviceWarned = true
                    // Con tarjeta, confirmación o algo hecho: como mucho dos frases, la herramienta que sea (v4).
                    val shown = ReplyShape.finalText(event.text, attachment, done)
                    buffer.setLength(0)
                    buffer.append(shown)
                    render(final = true)
                    mood = MashaMood.read(event.text, tools, failed = false)
                    // De caché no hay puntuación de la IA (era la de otro mensaje): la pone la regla local.
                    score(text, previous, if (event.fromCache) null else event.affinityDelta)
                    val game = if (attachment == null) findMentionedGame(event.text) else null
                    if (game != null && index in messages.indices) messages[index] = messages[index].copy(game = game)
                    persist(StoredMessage(MashaTurn.Role.Masha, shown, game?.key, attachment, System.currentTimeMillis()))
                    if (index in messages.indices) savedReplyId = messages[index].id
                }
                is MashaEvent.Failed -> {
                    if (event.partial.isBlank()) {
                        // Nada que enseñar: se quita la burbuja vacía y contesta Masha sin conexión.
                        if (index in messages.indices) messages.removeAt(index)
                        offlineReply(text, previous, failure = event.error)
                    } else {
                        render(final = true)
                        mood = MashaMood.Concerned
                        score(text, previous, modelDelta = null)
                        persist(StoredMessage(MashaTurn.Role.Masha, event.partial, null, attachment, System.currentTimeMillis()))
                        vm.showToast(errorText(event.error))
                    }
                }
            }
        }
    }

    /**
     * La tienda resumida para el contexto (PORTS). Sin catálogo aún, se pide
     * en segundo plano (red, caché o la copia del APK) y esta vez no va.
     */
    private fun portsSummary(): PortRecommend.Summary? {
        val ports = vm.ports
        if (ports.catalog.isEmpty()) {
            ports.onOpen()
            return null
        }
        return PortRecommend.summary(ports.catalog, ports.installed.keys, ports.updateIds(), ports.owned())
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
    private suspend fun offlineReply(text: String, previous: String?, failure: MashaError?) {
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
            OfflineIntent.AddInstalled -> {
                val result = actions.execute(ToolCall("local", MashaTools.ADD_GAMES, buildJsonObject { put("all", true) }))
                (result.attachment as? MashaAttachment.Confirm)?.let { attachment = it }
                when {
                    !result.ok -> ctx.getString(R.string.masha_off_nothing_to_add)
                    attachment != null -> ctx.getString(R.string.masha_off_add_confirm)
                    else -> ctx.getString(R.string.masha_write_done, (result.attachment as? MashaAttachment.Done)?.label.orEmpty())
                }
            }
            is OfflineIntent.Rename -> {
                val focused = vm.focusName() ?: vm.selected()?.name
                if (focused == null) {
                    ctx.getString(R.string.masha_off_rename_which)
                } else {
                    val result = actions.execute(
                        ToolCall("local", MashaTools.RENAME_GAME, buildJsonObject {
                            put("game", focused)
                            put("name", intent.name)
                            put("user_dictated", true)
                        }),
                    )
                    if (result.ok) ctx.getString(R.string.masha_off_renamed, focused, intent.name) else ctx.getString(R.string.masha_off_rename_bad, intent.name)
                }
            }
            is OfflineIntent.FindPort -> {
                // Del catálogo cargado o, sin red, del guardado (o la copia del APK).
                val result = actions.execute(ToolCall("local", MashaTools.FIND_PORTS, buildJsonObject { intent.query?.let { put("query", it) } }))
                val found = result.attachment as? MashaAttachment.Ports
                if (found != null) {
                    attachment = found
                    ctx.getString(R.string.masha_off_ports)
                } else if (intent.query != null) {
                    ctx.getString(R.string.lucy_off_ports_query_none, intent.query)
                } else {
                    // Nada de la biblioteca tiene port: la tienda, para mirar.
                    vm.openPorts()
                    ctx.getString(R.string.masha_off_ports_none)
                }
            }
            OfflineIntent.Hello -> ctx.getString(R.string.masha_off_hello)
            OfflineIntent.Unknown -> ctx.getString(if (brain.config.hasKey) R.string.masha_off_unknown else R.string.masha_off_unknown_nokey)
        }
        val prefix = failure?.let { ctx.getString(failureLine(it)) + "\n\n" }.orEmpty()
        // Si nombra a otra chica, otro asistente u otro launcher, un puchero según el nivel; lo pedido se hace igual.
        val jealous = jealousLine(text)?.let { ctx.getString(it) + " " }.orEmpty()
        // Con tarjeta, solo la frase corta (las tarjetas cuentan el resto).
        val full = ReplyShape.finalText(prefix + jealous + reply, attachment)
        // Sin IA no hay herramientas que mirar: una tarjeta de datos cuenta como análisis.
        mood = MashaMood.read(reply, if (attachment != null) listOf(MashaTools.GET_STATS) else emptyList(), failed = failure != null)
        score(text, previous, modelDelta = null)
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
            val all = runCatching { brain.insights() }.getOrDefault(emptyList())
            // La que ya está en pantalla se queda tal cual (no vuelve a "asomar").
            val current = insight
            if (current != null && all.any { it.id == current.id }) return@launch
            // Abrir la app no basta: solo sale un hecho nuevo o la sugerencia del día (ver InsightPacing).
            val next = pacing.pick(all, System.currentTimeMillis())
            if (next == null && current == null) return@launch
            insight = next
            insightRevision++
        }
    }

    /** "Ahora no": se descarta para hoy y Masha se calla un rato (no salta la siguiente). */
    fun dismissInsight() {
        insight?.let { brain.dismiss(it) }
        insight = null
        pacing.dismissed(System.currentTimeMillis())
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
            is Insight.NewGames -> vm.showDetails(current.sampleKey)
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
    ) {
        /** Las tres cifras vacías ("sin datos todavía"): la fila no se enseña. */
        val isEmpty: Boolean get() = weekMinutes == 0 && weekSessions == 0 && topTitle == null
    }

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
            MashaTools.FIND_PORTS, MashaTools.OPEN_PORT, MashaTools.INSTALL_PORT -> R.string.masha_working_ports
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
        noteActivity(message.createdAt)
        // En orden: marcar una respuesta como interrumpida tiene que llegar después de guardarla.
        val previous = persistJob
        persistJob = vm.viewModelScope.launch {
            previous?.join()
            brain.memory.saveMessage(message)
        }
    }

    /** Contexto con el idioma de la app, para componer respuestas fuera de Compose. */
    private fun localized(): Context = AppLocale.wrap(vm.app)

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
}
