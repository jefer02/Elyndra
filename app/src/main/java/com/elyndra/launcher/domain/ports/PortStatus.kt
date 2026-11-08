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

    /** Solo hay versión de PC (v1 no instala nada que no sea un APK). */
    PcOnly,

    /** Tiene build de Android pero la última release no trae un APK para este dispositivo. */
    Unavailable,
}

/** Lo instalado de un port (tabla `installed_ports`). */
data class InstalledPort(val portId: String, val packageName: String, val tag: String, val installedAt: Long)

object PortStatus {

    /**
     * [releasesKnown]: ya se pidieron las releases (si no, [latest] null no
     * dice nada). [installed] solo cuenta si su paquete sigue instalado.
     */
    fun action(port: PortEntry, installed: InstalledPort?, latest: Installable?, releasesKnown: Boolean): PortAction = when {
        installed != null && latest != null &&
            PortVersions.isNewer(latest.release.tag, latest.release.publishedAt, installed.tag, installed.installedAt) -> PortAction.Update
        installed != null -> PortAction.Installed
        !port.installable -> PortAction.PcOnly
        !releasesKnown -> PortAction.Free
        latest == null -> PortAction.Unavailable
        else -> PortAction.Install
    }
}
