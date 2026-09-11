package com.elyndra.launcher.library

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import com.elyndra.launcher.data.Emulators

/** Apps instaladas que se pueden abrir desde un launcher. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val isGame: Boolean,
    val installedAt: Long,
)

/**
 * Acceso al PackageManager: lista de apps lanzables, detección de juegos
 * (categoría GAME del manifiesto o el antiguo isGame) y comprobaciones de
 * instalación. La visibilidad la da el bloque <queries> del manifiesto.
 */
class AppCatalog(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    fun launchable(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return resolved.asSequence()
            .mapNotNull { ri ->
                val ai = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
                if (ai.packageName == context.packageName) return@mapNotNull null
                InstalledApp(
                    packageName = ai.packageName,
                    label = ri.loadLabel(pm).toString().ifBlank { ai.packageName },
                    isGame = isGame(ai),
                    installedAt = firstInstall(ai.packageName),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun isGame(ai: ApplicationInfo): Boolean {
        if (ai.packageName in Emulators.allPackages) return false
        return ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
    }

    private fun firstInstall(pkg: String): Long = runCatching { packageInfo(pkg)?.firstInstallTime ?: 0L }.getOrDefault(0L)

    private fun packageInfo(pkg: String) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
    }.getOrNull()

    fun isInstalled(pkg: String): Boolean = packageInfo(pkg) != null

    fun label(pkg: String): String? = runCatching {
        val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }
        pm.getApplicationLabel(ai).toString()
    }.getOrNull()

    fun icon(pkg: String): Drawable? = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()

    fun launchIntent(pkg: String): Intent? = pm.getLaunchIntentForPackage(pkg)
}
