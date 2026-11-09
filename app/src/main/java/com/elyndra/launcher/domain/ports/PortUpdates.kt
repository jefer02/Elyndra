package com.elyndra.launcher.domain.ports

/* Actualizaciones de ports: qué hay, cómo se instala y si la firma deja. Kotlin puro (PortUpdatesTest). */

/** Una release más nueva que la instalada de un port. */
data class PendingUpdate(val portId: String, val packageName: String, val tag: String, val publishedAt: Long)

object PortUpdates {

    /**
     * Las actualizaciones de [installed] según [latest] (la última release por
     * id de port, la que elige PortAssetPicker; null o ausente: no se sabe).
     * Una release bloqueada por cambio de firma ([InstalledPort.blockedTag])
     * no cuenta: no se ofrece ni se avisa.
     */
    fun pending(installed: List<InstalledPort>, latest: Map<String, PortRelease?>): List<PendingUpdate> =
        installed.mapNotNull { p ->
            val release = latest[p.portId] ?: return@mapNotNull null
            if (release.tag == p.blockedTag) return@mapNotNull null
            if (!PortVersions.isNewer(release.tag, release.publishedAt, p.tag, p.installedAt)) return@mapNotNull null
            PendingUpdate(p.portId, p.packageName, release.tag, release.publishedAt)
        }

    /** Las que la comprobación diaria ya vio (columnas `latest_*`), sin pedir nada a GitHub. */
    fun known(installed: List<InstalledPort>): List<PendingUpdate> =
        installed.mapNotNull { p ->
            val tag = p.latestTag ?: return@mapNotNull null
            if (tag == p.blockedTag || !PortVersions.isNewer(tag, p.latestPublishedAt, p.tag, p.installedAt)) return@mapNotNull null
            PendingUpdate(p.portId, p.packageName, tag, p.latestPublishedAt)
        }

    /** De [pending], de las que aún no se avisó (una vez por etiqueta). */
    fun toNotify(pending: List<PendingUpdate>, installed: List<InstalledPort>): List<PendingUpdate> {
        val notified = installed.associate { it.portId to it.notifiedTag }
        return pending.filter { notified[it.portId] != it.tag }
    }
}

/** El ajuste: solo avisar (por defecto) o actualizar solo. */
enum class PortUpdateMode { NotifyOnly, Automatic }

/**
 * Cómo se instala una actualización.
 *
 * Sin preguntar ([Route.Silent]) solo con "actualizar automáticamente",
 * Android 12+ (`USER_ACTION_NOT_REQUIRED`) y si la app la instaló Elyndra
 * (Android solo lo permite a quien la instaló). Si no, con el diálogo de
 * siempre ([Route.Prompt]) cuando el usuario está delante; desde la
 * comprobación en segundo plano no se puede abrir un diálogo, así que se
 * avisa ([Route.Notify]).
 */
object AutoUpdatePolicy {

    enum class Route { Silent, Prompt, Notify }

    const val SILENT_MIN_SDK = 31

    fun route(mode: PortUpdateMode, sdkInt: Int, installedByElyndra: Boolean, foreground: Boolean): Route = when {
        mode == PortUpdateMode.Automatic && sdkInt >= SILENT_MIN_SDK && installedByElyndra -> Route.Silent
        foreground -> Route.Prompt
        else -> Route.Notify
    }
}

/**
 * ¿Puede el APK nuevo sustituir al instalado? Android solo actualiza una app
 * con un APK de la misma clave (o de una clave que rota desde ella, con su
 * historial). Si no, la única salida es desinstalar, y eso borra los datos
 * guardados del port: Elyndra no lo hace nunca, lo explica.
 *
 * Las firmas van como huellas SHA-256 en hexadecimal.
 */
object SignatureCheck {

    /** [current]: los firmantes de ahora; [history]: el linaje de rotación (el más antiguo primero), si lo hay. */
    data class Signers(val current: Set<String>, val history: List<String> = emptyList())

    enum class Decision { NotInstalled, Same, Rotated, Mismatch, Unknown }

    fun decide(installed: Signers?, apk: Signers?): Decision {
        if (installed == null) return Decision.NotInstalled
        if (apk == null || apk.current.isEmpty() || installed.current.isEmpty()) return Decision.Unknown
        val have = installed.current.map { it.lowercase() }.toSet()
        val want = apk.current.map { it.lowercase() }.toSet()
        if (have == want) return Decision.Same
        // Rotación: la clave de ahora del instalado está en el linaje del nuevo (solo con un firmante).
        if (have.size == 1 && apk.history.any { it.equals(have.single(), ignoreCase = true) }) return Decision.Rotated
        return Decision.Mismatch
    }

    fun allowsUpdate(decision: Decision): Boolean = decision != Decision.Mismatch
}
