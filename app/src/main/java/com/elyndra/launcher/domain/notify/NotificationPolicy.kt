package com.elyndra.launcher.domain.notify

import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Los avisos de Lucy (Masha en el código) fuera de la app. Cada uno va en su
 * propio canal de Android para que se pueda apagar por separado.
 */
enum class LucyNotice(val key: String) {
    /** El dispositivo se calienta demasiado en mitad de una partida. */
    Hot("hot"),

    /** Juegos instalados mientras Elyndra no estaba delante (un aviso con todos). */
    NewGames("new_games"),

    /** Terminó una pasada de metadatos en segundo plano. */
    MetadataDone("metadata"),

    /** La nota del día: como mucho una, y solo si no se abrió la app. */
    Daily("daily"),
}

/** Lo que [NotificationPolicy] necesita recordar. Cada lectura y escritura es O(1). */
interface NotificationPolicyStore {
    /** Cuándo salió [kind] por última vez; 0 = nunca. */
    fun lastSentAt(kind: LucyNotice): Long
    fun setLastSentAt(kind: LucyNotice, at: Long)

    /** Día local (epoch day) al que se refiere [sentToday]. */
    var sentDay: Long

    /** Avisos de Lucy de cualquier tipo enviados en [sentDay]. */
    var sentToday: Int

    /** Notas diarias seguidas sin que se abriera la app entre medias. */
    var dailyStreak: Int

    /** La última vez que se abrió Elyndra (la escribe la app al volver a primer plano). */
    val lastOpenedAt: Long
}

/** El de los tests. */
class MemoryNotificationPolicyStore(override var lastOpenedAt: Long = 0L) : NotificationPolicyStore {
    private val sent = HashMap<LucyNotice, Long>()
    override fun lastSentAt(kind: LucyNotice): Long = sent[kind] ?: 0L
    override fun setLastSentAt(kind: LucyNotice, at: Long) { sent[kind] = at }
    override var sentDay: Long = -1L
    override var sentToday: Int = 0
    override var dailyStreak: Int = 0
}

/** Lo que pasa ahora mismo fuera de la política. */
data class NoticeContext(
    /** Elyndra está en pantalla: lo cuenta el bocadillo, no una notificación. */
    val foreground: Boolean,
    /** Hay una sesión de juego abierta (lanzada desde Elyndra y aún sin cerrar). */
    val inSession: Boolean = false,
    /** Ajustes → Lucy → notificaciones. */
    val enabled: Boolean = true,
)

/** Por qué sí o por qué no. */
enum class NoticeVerdict {
    Allowed,
    Disabled,
    Foreground,
    NotInSession,
    QuietHours,
    Cooldown,
    DailyCap,
    AlreadyToday,
    RecentlyOpened,
    BackOff,
}

/**
 * Cuándo puede salir cada aviso de Lucy. Puro y con el reloj inyectado.
 *
 * - Con Elyndra en pantalla no sale ninguno (para eso está el bocadillo).
 * - Calor: solo durante una sesión de juego y como mucho uno cada [hotCooldownMs].
 *   A cualquier hora: es justo cuando importa.
 * - Juegos nuevos y metadatos: nunca de noche (de [dayStartHour] a [dayEndHour]
 *   hora local), cada uno con su enfriamiento, y entre todos no más de
 *   [maxPerDay] al día.
 * - Nota diaria: una por día natural como mucho, de día, solo si la app lleva
 *   [dailyQuietAfterOpenMs] sin abrirse y nunca un día en que ya salió otro
 *   aviso de Lucy. Tras [maxDailyStreak] notas seguidas sin abrir la app se
 *   calla hasta que se vuelva a abrir.
 */
class NotificationPolicy(
    private val store: NotificationPolicyStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val hotCooldownMs: Long = 2L * HOUR_MS,
    private val newGamesCooldownMs: Long = 6L * HOUR_MS,
    private val metadataCooldownMs: Long = 12L * HOUR_MS,
    private val dailyQuietAfterOpenMs: Long = 20L * HOUR_MS,
    private val maxPerDay: Int = 3,
    private val maxDailyStreak: Int = 3,
    private val dayStartHour: Int = 9,
    private val dayEndHour: Int = 22,
) {

    fun decide(kind: LucyNotice, ctx: NoticeContext): NoticeVerdict {
        val now = clock.millis()
        if (!ctx.enabled) return NoticeVerdict.Disabled
        if (ctx.foreground) return NoticeVerdict.Foreground
        val since = now - store.lastSentAt(kind)
        return when (kind) {
            LucyNotice.Hot -> when {
                !ctx.inSession -> NoticeVerdict.NotInSession
                store.lastSentAt(kind) > 0 && since < hotCooldownMs -> NoticeVerdict.Cooldown
                else -> NoticeVerdict.Allowed
            }
            LucyNotice.NewGames, LucyNotice.MetadataDone -> when {
                !daytime() -> NoticeVerdict.QuietHours
                store.lastSentAt(kind) > 0 && since < cooldown(kind) -> NoticeVerdict.Cooldown
                sentToday() >= maxPerDay -> NoticeVerdict.DailyCap
                else -> NoticeVerdict.Allowed
            }
            LucyNotice.Daily -> when {
                !daytime() -> NoticeVerdict.QuietHours
                now - store.lastOpenedAt < dailyQuietAfterOpenMs -> NoticeVerdict.RecentlyOpened
                sentToday() > 0 -> NoticeVerdict.AlreadyToday
                streak() >= maxDailyStreak -> NoticeVerdict.BackOff
                else -> NoticeVerdict.Allowed
            }
        }
    }

    /** Apunta que [kind] acaba de salir. */
    fun markSent(kind: LucyNotice) {
        val now = clock.millis()
        if (kind == LucyNotice.Daily) store.dailyStreak = streak() + 1
        store.setLastSentAt(kind, now)
        val day = today()
        if (store.sentDay != day) {
            store.sentDay = day
            store.sentToday = 0
        }
        store.sentToday = store.sentToday + 1
    }

    /** Qué línea del repertorio toca hoy (cambia cada día, la misma todo el día). */
    fun dailyLineIndex(poolSize: Int): Int = if (poolSize <= 0) 0 else Math.floorMod(today(), poolSize.toLong()).toInt()

    /** Abrir la app reinicia la racha: se cuenta desde la última nota que no vio. */
    private fun streak(): Int =
        if (store.lastOpenedAt >= store.lastSentAt(LucyNotice.Daily)) 0 else store.dailyStreak

    private fun sentToday(): Int = if (store.sentDay == today()) store.sentToday else 0

    private fun cooldown(kind: LucyNotice): Long =
        if (kind == LucyNotice.NewGames) newGamesCooldownMs else metadataCooldownMs

    private fun today(): Long = LocalDate.now(clock).toEpochDay()

    private fun daytime(): Boolean {
        val hour = Instant.ofEpochMilli(clock.millis()).atZone(clock.zone).hour
        return hour in dayStartHour until dayEndHour
    }

    companion object {
        const val HOUR_MS = 60L * 60 * 1000
    }
}
