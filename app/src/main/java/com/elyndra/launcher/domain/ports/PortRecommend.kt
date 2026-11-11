package com.elyndra.launcher.domain.ports

import java.text.Normalizer

/**
 * Lo que Lucy busca y recomienda de la tienda de ports (Kotlin puro,
 * PortRecommendTest): por nombre (del port o del juego original), por género
 * o por ánimo ("algo de miedo", "para jugar con amigos en la tele"), solo lo
 * que se puede instalar en este dispositivo salvo que se pida lo demás.
 *
 * [summary] es lo que va en el contexto de cada pregunta (PORTS): números y
 * nombres de juegos, nunca repositorios, enlaces ni rutas.
 */
object PortRecommend {

    /** Una búsqueda: un resultado por juego, y cuántos se quedaron fuera por no poder instalarse aquí. */
    data class Found(val groups: List<GameGroup>, val incompatible: Int)

    /** El resumen de la tienda para el contexto. */
    data class Summary(val total: Int, val compatible: Int, val installed: Int, val updates: Int, val forYourLibrary: List<String>)

    const val INSTALLED = "installed"
    const val UPDATE_AVAILABLE = "update_available"
    const val AVAILABLE = "available"
    const val NOT_ON_ANDROID = "not_on_android"

    /**
     * Ánimos que se entienden (en los idiomas de la app) y las etiquetas de
     * GitHub o géneros de la tienda que cuentan como cada uno.
     */
    private val MOODS: List<Pair<List<String>, Set<String>>> = listOf(
        listOf("scary", "horror", "creepy", "miedo", "terror", "medo", "peur", "effrayant", "grusel", "horror", "ホラー", "怖") to
            setOf("horror", "survival-horror"),
        listOf("chill", "relax", "cozy", "tranquil", "calm", "relaj", "tranqui", "calme", "detente", "entspann", "gemutlich", "のんびり", "まったり") to
            setOf("platformer", "puzzle", "adventure", "exploration"),
        listOf("friends", "co-op", "coop", "couch", "together", "multiplayer", "amigos", "cooperativ", "amis", "ensemble", "freunde", "koop", "友達", "協力") to
            setOf("co-op", "coop", "multiplayer", "local-multiplayer", "split-screen", "party"),
        listOf("fast", "adrenaline", "action", "rapido", "accion", "rapide", "schnell", "アクション") to
            setOf("racing", "shooter", "action", "fps", "kart"),
        listOf("story", "epic", "adventure", "historia", "aventura", "histoire", "aventure", "geschichte", "abenteuer", "物語", "冒険") to
            setOf("rpg", "adventure", "jrpg", "action-rpg"),
        listOf("think", "brain", "puzzle", "pensar", "logica", "reflexion", "denken", "rätsel", "パズル", "頭") to
            setOf("strategy", "puzzle", "rts"),
    )

    /** El género de la tienda para lo que diga el modelo ("RPG", "plataformas", "fps"…), o null. */
    fun genreOf(input: String?): String? {
        val w = fold(input ?: return null).trim()
        if (w.isEmpty()) return null
        return StoreRows.GENRES.entries.firstOrNull { (key, aliases) -> w == key || w in aliases || aliases.any { w.startsWith(it) } }?.key
            ?: ALIASES.entries.firstOrNull { (k, _) -> w.startsWith(k) }?.value
    }

    private val ALIASES = mapOf(
        "plataforma" to "platformer", "plateforme" to "platformer", "jump" to "platformer", "carrera" to "racing", "corrida" to "racing",
        "course" to "racing", "renn" to "racing", "rol" to "rpg", "disparo" to "shooter", "tiro" to "shooter", "tir" to "shooter",
        "estrategia" to "strategy", "strategie" to "strategy", "レース" to "racing", "シューティング" to "shooter", "ロールプレイング" to "rpg",
    )

    /** Las etiquetas que cuentan para [mood], o null si no se entiende. */
    fun moodTags(mood: String?): Set<String>? {
        val m = fold(mood ?: return null)
        if (m.isBlank()) return null
        val hits = MOODS.filter { (words, _) -> words.any { fold(it) in m } }.flatMap { it.second }
        return hits.toSet().takeIf { it.isNotEmpty() }
    }

    fun matchesMood(p: PortEntry, tags: Set<String>): Boolean {
        val own = p.tags.mapTo(HashSet()) { it.lowercase() } + StoreRows.genres(p)
        return own.any { it in tags }
    }

    /** ¿Se puede instalar algún port de este juego en este dispositivo? (El catálogo ya viene con la regla de RV aplicada.) */
    fun compatible(g: GameGroup): Boolean = g.ports.any { it.installable }

    /** El port que se recomienda de un juego: el instalable, si lo hay. */
    fun best(g: GameGroup): PortEntry = g.ports.firstOrNull { it.installable } ?: g.lead

    fun state(p: PortEntry, installedIds: Set<String>, updateIds: Set<String>): String = when {
        p.id in installedIds && p.id in updateIds -> UPDATE_AVAILABLE
        p.id in installedIds -> INSTALLED
        p.installable -> AVAILABLE
        else -> NOT_ON_ANDROID
    }

    /** El juego original del que hacen falta archivos (null si no hacen falta). */
    fun requiredGame(p: PortEntry): String? = if (p.requires.userRom) p.game.name else null

    /**
     * Busca en [catalog]. Con [query], por nombre (StoreRows.find); sin nada,
     * los juegos de la biblioteca que tienen port ([owned]). [genre] y
     * [mood] filtran. Lo incompatible solo se cuenta, salvo con [includeIncompatible].
     * Instalables primero, luego los que ya están instalados.
     */
    fun search(
        catalog: List<PortEntry>,
        query: String?,
        genre: String?,
        mood: String?,
        owned: List<RomPortMatch>,
        includeIncompatible: Boolean = false,
        installedIds: Set<String> = emptySet(),
        limit: Int = 8,
    ): Found {
        val genreKey = genreOf(genre)
        val tags = moodTags(mood)
        val filtering = genreKey != null || tags != null
        val base = when {
            !query.isNullOrBlank() -> StoreRows.find(catalog, query, owned, limit = Int.MAX_VALUE)
            filtering -> PortGrouping.group(catalog)
            else -> StoreRows.find(catalog, null, owned, limit = Int.MAX_VALUE)
        }
        val matched = base.filter { g ->
            (genreKey == null || g.ports.any { genreKey in StoreRows.genres(it) }) &&
                (tags == null || g.ports.any { matchesMood(it, tags) })
        }
        val (ok, no) = matched.partition(::compatible)
        val shown = (if (includeIncompatible) ok + no else ok)
            .sortedWith(compareByDescending<GameGroup> { compatible(it) }.thenByDescending { g -> g.ports.any { it.id in installedIds } })
        return Found(shown.take(limit), if (includeIncompatible) 0 else no.size)
    }

    fun summary(catalog: List<PortEntry>, installedIds: Set<String>, updateIds: Set<String>, owned: List<RomPortMatch>, max: Int = 3): Summary {
        val groups = PortGrouping.group(catalog)
        val ownedNotInstalled = owned
            .filter { it.port.installable && it.port.id !in installedIds }
            .map { PortDisplay.title(it.port) }
            .distinct()
            .take(max)
        return Summary(
            total = groups.size,
            compatible = groups.count(::compatible),
            installed = catalog.count { it.id in installedIds },
            updates = catalog.count { it.id in installedIds && it.id in updateIds },
            forYourLibrary = ownedNotInstalled,
        )
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
