package com.elyndra.launcher.perf

import android.app.Activity

/** En release no se mide nada (ver la versión de src/debug). */
object PerfProbe {
    @Suppress("UNUSED_PARAMETER")
    fun install(activity: Activity) = Unit
}
