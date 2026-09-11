package com.elyndra.launcher.launch

import com.elyndra.launcher.data.EmulatorProfile
import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.data.ExtraValue
import com.elyndra.launcher.data.RomArg

/** Las tres formas en que se puede entregar una ROM a un emulador. */
data class RomRef(
    /** URI del documento dentro del árbol SAF concedido. */
    val safUri: String,
    /** URI de [RomProvider] para esta ROM. */
    val providerUri: String,
    /** Ruta absoluta, si la carpeta está en almacenamiento local. */
    val path: String?,
    val isDirectory: Boolean,
    /** TITLE ID leído del archivo .psvita (solo PS Vita). */
    val vitaTitleId: String? = null,
)

/** Intent descrito sin clases de Android, para poder probarlo en la JVM. */
data class LaunchSpec(
    val packageName: String,
    /** Actividad explícita; null = que el sistema resuelva dentro del paquete. */
    val className: String?,
    val action: String?,
    val category: String?,
    val data: String?,
    val stringExtras: Map<String, String>,
    val boolExtras: Map<String, Boolean>,
    val arrayExtras: Map<String, List<String>>,
    /** URIs content:// a las que hay que dar permiso de lectura al emulador. */
    val grantUris: List<String>,
    val clearTask: Boolean,
    val clearTop: Boolean,
)

sealed interface PlanResult {
    data class Ok(val spec: LaunchSpec) : PlanResult
    /** El emulador exige ruta de archivo y la carpeta no está en almacenamiento local. */
    data object NeedsPath : PlanResult
    /** Vita3K necesita el TITLE ID y el archivo .psvita está vacío o no se pudo leer. */
    data object NeedsVitaTitle : PlanResult
}

object LaunchPlanner {

    /** "org.ppsspp.ppsspp/.PpssppActivity" → ("org.ppsspp.ppsspp", "org.ppsspp.ppsspp.PpssppActivity"). */
    fun expandComponent(component: String): Pair<String, String?> {
        val pkg = component.substringBefore('/')
        val activity = component.substringAfter('/', "")
        val cls = when {
            activity.isEmpty() -> null
            activity.startsWith(".") -> pkg + activity
            else -> activity
        }
        return pkg to cls
    }

    fun plan(profile: EmulatorProfile, component: String, rom: RomRef): PlanResult {
        val (pkg, cls) = expandComponent(component)
        val grants = LinkedHashSet<String>()

        fun valueOf(arg: RomArg): String? = when (arg) {
            RomArg.SAF -> rom.safUri
            RomArg.PROVIDER -> rom.providerUri
            RomArg.PATH -> rom.path
        }

        var data: String? = null
        profile.data?.let { arg ->
            data = valueOf(arg) ?: return PlanResult.NeedsPath
            if (arg != RomArg.PATH) grants += data!!
        }

        val strings = LinkedHashMap<String, String>()
        val bools = LinkedHashMap<String, Boolean>()
        val arrays = LinkedHashMap<String, List<String>>()
        val extras = if (rom.isDirectory && profile.dirExtras != null) profile.dirExtras else profile.extras
        for (extra in extras) {
            when (val v = extra.value) {
                is ExtraValue.Rom -> {
                    val s = valueOf(v.arg) ?: return PlanResult.NeedsPath
                    strings[extra.key] = s
                    if (v.arg != RomArg.PATH) grants += s
                }
                is ExtraValue.Text -> strings[extra.key] = v.value
                is ExtraValue.Flag -> bools[extra.key] = v.value
                is ExtraValue.RetroArchCore -> strings[extra.key] = "/data/data/$pkg/cores/${v.file}"
                ExtraValue.RetroArchConfig -> strings[extra.key] = "/storage/emulated/0/Android/data/$pkg/files/retroarch.cfg"
                ExtraValue.VitaTitleArgs -> {
                    val id = rom.vitaTitleId?.takeIf { it.isNotBlank() } ?: return PlanResult.NeedsVitaTitle
                    arrays[extra.key] = listOf("-r", id)
                }
            }
        }

        return PlanResult.Ok(
            LaunchSpec(
                packageName = pkg,
                className = cls,
                action = profile.action,
                category = profile.category,
                data = data,
                stringExtras = strings,
                boolExtras = bools,
                arrayExtras = arrays,
                grantUris = grants.toList(),
                clearTask = profile.clearTask,
                clearTop = profile.clearTop,
            ),
        )
    }

    /** App elegida a mano como emulador: la convención más común, VIEW + URI con permiso. */
    fun custom(packageName: String, rom: RomRef): LaunchSpec = LaunchSpec(
        packageName = packageName,
        className = null,
        action = Emulators.VIEW,
        category = null,
        data = rom.safUri,
        stringExtras = emptyMap(),
        boolExtras = emptyMap(),
        arrayExtras = emptyMap(),
        grantUris = listOf(rom.safUri),
        clearTask = false,
        clearTop = false,
    )
}
