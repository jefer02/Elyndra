grigitpackage com.elyndra.launcher.launch

import com.elyndra.launcher.data.EmulatorProfile
import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.data.ExtraValue
import com.elyndra.launcher.data.RomArg
import com.elyndra.launcher.library.PcGames

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
    /** Archivo lanzador de un runtime de Windows, si el juego es uno (solo PC). */
    val pcLauncher: PcGames.Launcher? = null,
    /** Id del juego dentro del runtime de Windows (ver [RomRef.idIsAssigned]). */
    val launcherId: String? = null,
    /**
     * El id no sale del archivo lanzador: lo asignó el usuario o se leyó de un
     * archivo suelto junto al juego. Vale igual, y vale aunque lo que se esté
     * entregando sea la carpeta del juego con su .exe.
     */
    val idIsAssigned: Boolean = false,
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
    val intExtras: Map<String, Int>,
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
    /**
     * El runtime de Windows solo arranca lo que hay en su biblioteca y lo que
     * se le está dando es una carpeta con un .exe. Falta el archivo que exporta
     * el propio runtime (ver PcGames.Launcher).
     */
    data object NeedsPcLauncher : PlanResult
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
        val ints = LinkedHashMap<String, Int>()
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
                ExtraValue.WinShortcut -> {
                    // El runtime lee el archivo por su cuenta: si no es un
                    // .desktop suyo, abriría la ventana y se cerraría sin más.
                    if (rom.pcLauncher != PcGames.Launcher.Desktop) return PlanResult.NeedsPcLauncher
                    strings[extra.key] = rom.path ?: return PlanResult.NeedsPath
                }
                is ExtraValue.LauncherId -> {
                    val id = launcherId(rom) ?: return PlanResult.NeedsPcLauncher
                    strings[extra.key] = id
                    v.alsoKey?.let { strings[it] = id }
                }
                ExtraValue.LauncherIdInt -> {
                    val id = launcherId(rom)?.toIntOrNull() ?: return PlanResult.NeedsPcLauncher
                    ints[extra.key] = id
                }
                ExtraValue.LauncherStore -> {
                    // Un id asignado a mano es un appid de Steam: es el que el
                    // usuario tiene a mano (la ficha de la tienda lo lleva en la URL).
                    val store = rom.pcLauncher?.store
                        ?: PcGames.Launcher.Steam.store.takeIf { rom.idIsAssigned }
                        ?: return PlanResult.NeedsPcLauncher
                    strings[extra.key] = store
                }
            }
        }

        return PlanResult.Ok(
            LaunchSpec(
                packageName = pkg,
                className = cls,
                action = profile.action?.replace(Emulators.PACKAGE_TOKEN, pkg),
                category = profile.category,
                data = data,
                stringExtras = strings,
                boolExtras = bools,
                intExtras = ints,
                arrayExtras = arrays,
                grantUris = grants.toList(),
                clearTask = profile.clearTask,
                clearTop = profile.clearTop,
            ),
        )
    }

    /**
     * Id del juego dentro del runtime.
     *
     * Vale si lo lleva dentro el archivo lanzador que exportó el runtime, o si
     * viene asignado por otra vía (a mano o de un archivo suelto): un `.exe`
     * no se puede lanzar, pero con el id sí hay algo que lanzar.
     */
    private fun launcherId(rom: RomRef): String? =
        rom.launcherId?.takeIf { it.isNotBlank() && (rom.idIsAssigned || rom.pcLauncher?.carriesId == true) }

    /** App elegida a mano como emulador: la convención más común, VIEW + URI con permiso. */
    fun custom(packageName: String, rom: RomRef): LaunchSpec = LaunchSpec(
        packageName = packageName,
        className = null,
        action = Emulators.VIEW,
        category = null,
        data = rom.safUri,
        stringExtras = emptyMap(),
        boolExtras = emptyMap(),
        intExtras = emptyMap(),
        arrayExtras = emptyMap(),
        grantUris = listOf(rom.safUri),
        clearTask = false,
        clearTop = false,
    )
}
