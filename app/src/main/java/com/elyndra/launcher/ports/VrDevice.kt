package com.elyndra.launcher.ports

import android.content.pm.PackageManager

/**
 * ¿Es esto unas gafas de realidad virtual autónomas (Meta Quest, Pico…)? Lo
 * dice si el sistema declara el modo de RV de alto rendimiento, el seguimiento
 * de cabeza de OpenXR o la función de las gafas de Oculus/Meta. Los ports
 * "solo para gafas" (PortEntry.VR_ONLY) solo se instalan aquí.
 */
object VrDevice {

    const val OCULUS_STANDALONE = "oculus.hardware.standalone_vr"
    const val HEAD_TRACKING = "android.hardware.vr.headtracking"

    /** Puro (VrDeviceTest): con las funciones que declara el sistema. */
    fun isHeadset(features: Set<String>): Boolean =
        PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE in features || OCULUS_STANDALONE in features || HEAD_TRACKING in features

    fun isHeadset(pm: PackageManager): Boolean = runCatching {
        isHeadset(
            setOf(PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE, OCULUS_STANDALONE, HEAD_TRACKING)
                .filterTo(HashSet()) { pm.hasSystemFeature(it) },
        )
    }.getOrDefault(false)
}
