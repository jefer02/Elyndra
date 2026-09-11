package com.elyndra.launcher.launch

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.elyndra.launcher.data.EmulatorProfile
import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.RomFolder
import com.elyndra.launcher.library.SafPaths

/**
 * Lanza apps Android y entrega ROMs al emulador de su carpeta.
 * Elyndra nunca emula: solo construye el intent que cada emulador entiende.
 */
class GameLauncher(private val context: Context) {

    sealed interface Outcome {
        data object Started : Outcome
        data object NotInstalled : Outcome
        data object NeedsPath : Outcome
        data object NeedsVitaTitle : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private val pm: PackageManager get() = context.packageManager

    fun isPackageInstalled(pkg: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
        true
    }.getOrDefault(false)

    /** Primer componente del perfil cuyo paquete está instalado. */
    fun installedComponent(profile: EmulatorProfile): String? =
        profile.components.firstOrNull { isPackageInstalled(it.substringBefore('/')) }

    /** ¿Está instalado el emulador con este id (perfil conocido o "custom:paquete")? */
    fun isEmulatorInstalled(emulatorId: String?): Boolean {
        if (emulatorId == null) return false
        if (emulatorId.startsWith(Emulators.CUSTOM_PREFIX)) return isPackageInstalled(emulatorId.removePrefix(Emulators.CUSTOM_PREFIX))
        return Emulators.byId(emulatorId)?.let { installedComponent(it) != null } ?: false
    }

    fun launchApp(pkg: String): Outcome {
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return Outcome.NotInstalled
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(intent, pkg)
    }

    /** Referencias a la ROM en los tres formatos que piden los emuladores. */
    fun romRef(folder: RomFolder, rom: RomEntry, vitaTitleId: String? = null): RomRef {
        val tree = Uri.parse(folder.treeUri)
        val saf = DocumentsContract.buildDocumentUriUsingTree(tree, rom.docId).toString()
        val authority = SafPaths.authorityOf(folder.treeUri)
        return RomRef(
            safUri = saf,
            providerUri = RomProvider.uriFor(context, saf, rom.fileName).toString(),
            path = SafPaths.docIdToPath(authority, rom.docId),
            isDirectory = rom.isDirectory,
            vitaTitleId = vitaTitleId,
        )
    }

    fun launchRom(emulatorId: String, ref: RomRef): Outcome {
        if (emulatorId.startsWith(Emulators.CUSTOM_PREFIX)) {
            val pkg = emulatorId.removePrefix(Emulators.CUSTOM_PREFIX)
            if (!isPackageInstalled(pkg)) return Outcome.NotInstalled
            return launchSpec(LaunchPlanner.custom(pkg, ref))
        }
        val profile = Emulators.byId(emulatorId) ?: return Outcome.NotInstalled
        val component = installedComponent(profile) ?: return Outcome.NotInstalled
        return when (val plan = LaunchPlanner.plan(profile, component, ref)) {
            is PlanResult.Ok -> launchSpec(plan.spec)
            PlanResult.NeedsPath -> Outcome.NeedsPath
            PlanResult.NeedsVitaTitle -> Outcome.NeedsVitaTitle
        }
    }

    fun launchSpec(spec: LaunchSpec): Outcome {
        val intent = Intent()
        val cls = spec.className
        if (cls != null && activityExists(spec.packageName, cls)) {
            intent.setClassName(spec.packageName, cls)
            spec.action?.let { intent.action = it }
        } else {
            // La actividad cambió de nombre en otra versión: que el sistema la busque en el paquete.
            intent.setPackage(spec.packageName)
            intent.action = spec.action ?: Emulators.VIEW
        }
        spec.category?.let { intent.addCategory(it) }
        spec.data?.let { intent.data = Uri.parse(it) }
        spec.stringExtras.forEach { (k, v) -> intent.putExtra(k, v) }
        spec.boolExtras.forEach { (k, v) -> intent.putExtra(k, v) }
        spec.arrayExtras.forEach { (k, v) -> intent.putExtra(k, v.toTypedArray()) }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (spec.clearTask) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (spec.clearTop) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

        if (spec.grantUris.isNotEmpty()) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Los permisos de un intent solo cubren data y ClipData: las URIs que viajan
            // en extras (bootPath, AutoStartFile, rom_uri…) se añaden también al ClipData.
            val uris = spec.grantUris.map(Uri::parse)
            val clip = ClipData.newRawUri("rom", uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            intent.clipData = clip
            uris.forEach { uri ->
                runCatching { context.grantUriPermission(spec.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
        return start(intent, spec.packageName)
    }

    private fun activityExists(pkg: String, cls: String): Boolean = runCatching {
        val component = ComponentName(pkg, cls)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getActivityInfo(component, 0)
        }
        true
    }.getOrDefault(false)

    private fun start(intent: Intent, pkg: String): Outcome = try {
        context.startActivity(intent)
        Outcome.Started
    } catch (e: ActivityNotFoundException) {
        if (isPackageInstalled(pkg)) Outcome.Failed(e.message ?: "ActivityNotFoundException") else Outcome.NotInstalled
    } catch (e: SecurityException) {
        Outcome.Failed(e.message ?: "SecurityException")
    } catch (e: RuntimeException) {
        Outcome.Failed(e.message ?: e.javaClass.simpleName)
    }

    /** Ficha del emulador en Google Play (o búsqueda si no está publicado ahí). */
    fun storeIntent(profile: EmulatorProfile): Intent {
        val uri = profile.storeId?.let { Uri.parse("https://play.google.com/store/apps/details?id=$it") }
            ?: Uri.parse("https://www.google.com/search?q=" + Uri.encode("${profile.name} Android emulator"))
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
