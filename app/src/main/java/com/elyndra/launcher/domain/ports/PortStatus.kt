package com.elyndra.launcher.domain.ports

/**
 * Lo que dice el área de acción de una cápsula (donde una tienda pondría el
 * precio) y el botón grande de la ficha. Kotlin puro (PortStatusTest).
 */
enum class PortAction {
    /** Aún no se sabe qué release hay: gratis, como todos. */
    Free,
    Install,
    Update,
    Installed,

    /** Aún no hay APK de Android (solo PC, o solo el código): se ve el proyecto en GitHub. */
    NotOnAndroid,

    /** El APK es solo para gafas de realidad virtual y esto no lo es. */
    VrOnly,

    /** Tiene build de Android pero la última release no trae un APK para este dispositivo. */
    Unavailable,

    /**
     * Hay una release nueva pero firmada con otra clave: Android no la deja
     * instalar encima y desinstalar borraría los datos guardados. No se
     * actualiza; la ficha lo explica.
     */
    SignatureChanged,
}

/** Lo instalado de un port (tabla `installed_ports`). */
data class InstalledPort(
    val portId: String,
    val packageName: String,
    val tag: String,
    val installedAt: Long,
    /** Sistema original del juego (a qué carpeta va). */
    val systemId: String? = null,
    /** La última release que vio la comprobación diaria. */
    val latestTag: String? = null,
    val latestPublishedAt: Long = 0,
    val notifiedTag: String? = null,
    /** Una release firmada con otra clave, que no se instala. */
    val blockedTag: String? = null,
)

object PortStatus {

    /**
     * [releasesKnown]: ya se pidieron las releases (si no, [latest] null no
     * dice nada). [installed] solo cuenta si su paquete sigue instalado.
     */
    fun action(port: PortEntry, installed: InstalledPort?, latest: Installable?, releasesKnown: Boolean): PortAction = when {
        installed != null && latest != null && latest.release.tag == installed.blockedTag -> PortAction.SignatureChanged
        installed != null && latest != null &&
            PortVersions.isNewer(latest.release.tag, latest.release.publishedAt, installed.tag, installed.installedAt) -> PortAction.Update
        installed != null -> PortAction.Installed
        port.vrOnly -> PortAction.VrOnly
        !port.installable -> PortAction.NotOnAndroid
        !releasesKnown -> PortAction.Free
        latest == null -> PortAction.Unavailable
        else -> PortAction.Install
    }
}
