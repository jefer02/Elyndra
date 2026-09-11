package com.elyndra.launcher.metadata

import com.elyndra.launcher.library.Names
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/* ─────────────────────────────────────────────────────────────
   RetroAchievements — Web API (https://api-docs.retroachievements.org).

   Credenciales: nombre de usuario + Web API Key, que está en
   https://retroachievements.org/settings (sección "Keys"). La clave
   va en el parámetro `y` de cada llamada.
   ───────────────────────────────────────────────────────────── */

data class RaCredentials(val user: String, val apiKey: String) {
    val isComplete: Boolean get() = user.isNotBlank() && apiKey.isNotBlank()
}

data class RaProfile(val user: String, val points: Int, val softcorePoints: Int, val truePoints: Int, val userPic: String?)

data class RaGameEntry(
    val id: Int,
    val title: String,
    val consoleId: Int,
    val achievements: Int,
    val points: Int,
    val hashes: List<String>,
)

data class RaAchievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val badge: String,
    val earned: Boolean,
    val earnedHardcore: Boolean,
    val order: Int,
)

data class RaGameProgress(
    val id: Int,
    val title: String,
    val achievements: List<RaAchievement>,
    val earned: Int,
    val earnedHardcore: Int,
    val boxArt: String?,
    val released: String?,
    val developer: String?,
    val publisher: String?,
    val genre: String?,
) {
    val total: Int get() = achievements.size
    val points: Int get() = achievements.sumOf { it.points }
    val earnedPoints: Int get() = achievements.filter { it.earned || it.earnedHardcore }.sumOf { it.points }
}

object RaParser {

    fun profile(root: JsonElement): RaProfile? {
        val o = root.asObject() ?: return null
        val user = o.str("User") ?: return null
        return RaProfile(
            user = user,
            points = o["TotalPoints"].asInt() ?: 0,
            softcorePoints = o["TotalSoftcorePoints"].asInt() ?: 0,
            truePoints = o["TotalTruePoints"].asInt() ?: 0,
            userPic = o.str("UserPic"),
        )
    }

    fun gameList(root: JsonElement): List<RaGameEntry> = root.asArray()?.mapNotNull { e ->
        val o = e.asObject() ?: return@mapNotNull null
        RaGameEntry(
            id = o["ID"].asInt() ?: return@mapNotNull null,
            title = o.str("Title") ?: return@mapNotNull null,
            consoleId = o["ConsoleID"].asInt() ?: 0,
            achievements = o["NumAchievements"].asInt() ?: 0,
            points = o["Points"].asInt() ?: 0,
            hashes = o["Hashes"].asArray()?.mapNotNull { it.asString()?.lowercase() }.orEmpty(),
        )
    }.orEmpty()

    fun progress(root: JsonElement): RaGameProgress? {
        val o = root.asObject() ?: return null
        val id = o["ID"].asInt() ?: return null
        val list = o["Achievements"].asArray()?.mapNotNull { a -> a.asObject()?.let(::achievement) }.orEmpty()
            .sortedWith(compareBy({ it.order }, { it.id }))
        return RaGameProgress(
            id = id,
            title = o.str("Title") ?: "",
            achievements = list,
            earned = o["NumAwardedToUser"].asInt() ?: list.count { it.earned || it.earnedHardcore },
            earnedHardcore = o["NumAwardedToUserHardcore"].asInt() ?: list.count { it.earnedHardcore },
            boxArt = o.str("ImageBoxArt"),
            released = o.str("Released")?.take(10),
            developer = o.str("Developer"),
            publisher = o.str("Publisher"),
            genre = o.str("Genre"),
        )
    }

    private fun achievement(o: JsonObject): RaAchievement? {
        val id = o["ID"].asInt() ?: return null
        return RaAchievement(
            id = id,
            title = o.str("Title") ?: "",
            description = o.str("Description") ?: "",
            points = o["Points"].asInt() ?: 0,
            badge = o.str("BadgeName") ?: "",
            earned = o.str("DateEarned") != null,
            earnedHardcore = o.str("DateEarnedHardcore") != null,
            order = o["DisplayOrder"].asInt() ?: 0,
        )
    }

    /** Coincidencia exacta de hash (la forma fiable de identificar una ROM). */
    fun matchHash(list: List<RaGameEntry>, hash: String?): RaGameEntry? {
        val h = hash?.lowercase() ?: return null
        return list.firstOrNull { h in it.hashes }
    }

    /**
     * Coincidencia por título cuando no hay hash (discos comprimidos, 7z…).
     * Estricta: mejor no enlazar que enlazar el juego equivocado. Se ignoran
     * hacks, homebrew y subsets ("~Hack~ …", "[Subset - …]").
     */
    fun matchTitle(list: List<RaGameEntry>, title: String, threshold: Double = 0.92): RaGameEntry? =
        list.asSequence()
            .filterNot { it.title.startsWith("~") || it.title.contains("[Subset", ignoreCase = true) }
            .map { entry -> entry to entry.title.split(" | ").maxOf { Names.similarity(title, it) } }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }
            ?.first
}

class RetroAchievementsClient(
    private val credentials: () -> RaCredentials,
    private val cacheDir: File,
) {
    private val gate = RateGate(350)
    private val lists = ConcurrentHashMap<Int, List<RaGameEntry>>()

    private suspend fun get(endpoint: String, params: Map<String, String>): JsonElement {
        val c = credentials()
        if (!c.isComplete) throw ApiException.Unauthorized("missing credentials")
        val url = "$SITE/API/$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("y", c.apiKey.trim())
            .addQueryParameter("z", c.user.trim())
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val r = withRateRetry(attempts = 3, baseDelayMs = 3_000) {
            gate.run { Http.text(Request.Builder().url(url).get().build()) }
                .also { if (it.code == 429) throw ApiException.RateLimited("RetroAchievements 429") }
        }
        val message = runCatching { Http.parse(r.body).asObject()?.str("message") }.getOrNull() ?: r.body.take(160)
        return when (r.code) {
            200 -> Http.parse(r.body)
            401, 403 -> throw ApiException.Unauthorized(message)
            404 -> throw ApiException.NotFound(message)
            else -> throw ApiException.Server(r.code, message)
        }
    }

    /** Comprueba usuario + clave (API_GetUserProfile.php). */
    suspend fun profile(): RaProfile {
        val user = credentials().user.trim()
        return try {
            RaParser.profile(get("API_GetUserProfile.php", mapOf("u" to user)))
        } catch (e: ApiException.NotFound) {
            null
        } ?: throw ApiException.Unauthorized("user not found")
    }

    /** Juegos con logros de un sistema, con sus hashes. Se cachea 7 días, como piden sus normas de uso. */
    suspend fun gameList(consoleId: Int): List<RaGameEntry> {
        lists[consoleId]?.let { return it }
        val file = File(cacheDir, "ra/games_$consoleId.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < LIST_TTL_MS
        if (fresh) {
            runCatching { RaParser.gameList(Http.parse(file.readText())) }.getOrNull()?.let {
                lists[consoleId] = it
                return it
            }
        }
        val root = get("API_GetGameList.php", mapOf("i" to consoleId.toString(), "h" to "1", "f" to "1"))
        val parsed = RaParser.gameList(root)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
        }
        lists[consoleId] = parsed
        return parsed
    }

    /** Logros del juego y progreso del usuario (API_GetGameInfoAndUserProgress.php). */
    suspend fun progress(gameId: Int): RaGameProgress =
        RaParser.progress(get("API_GetGameInfoAndUserProgress.php", mapOf("g" to gameId.toString(), "u" to credentials().user.trim())))
            ?: throw ApiException.BadResponse("RetroAchievements: empty game")

    fun clearCache() {
        lists.clear()
        File(cacheDir, "ra").deleteRecursively()
    }

    companion object {
        const val SITE = "https://retroachievements.org"
        const val MEDIA = "https://media.retroachievements.org"
        private const val LIST_TTL_MS = 7L * 24 * 60 * 60 * 1000

        fun badgeUrl(badge: String, locked: Boolean): String = "$MEDIA/Badge/$badge${if (locked) "_lock" else ""}.png"

        fun mediaUrl(path: String): String = if (path.startsWith("http")) path else MEDIA + path

        fun gameUrl(gameId: Int): String = "$SITE/game/$gameId"
    }
}
