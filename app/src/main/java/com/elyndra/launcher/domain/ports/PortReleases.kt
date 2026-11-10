package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.update.ReleaseAsset
import com.elyndra.launcher.update.SemVer
import com.elyndra.launcher.update.UpdateLogic

/* Releases de los ports: qué APK bajar y si hay versión nueva. Kotlin puro
   (PortReleasesTest). Los datos los trae GitHubClient. */

/** Una release del repositorio de un port. [publishedAt] en ms (0 si GitHub no la da). */
data class PortRelease(
    val tag: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val publishedAt: Long,
    val assets: List<ReleaseAsset>,
)

/** Lo que se ve de un repositorio en la ficha: estrellas, licencia… */
data class RepoInfo(
    val stars: Int,
    val license: String?,
    val description: String?,
    val pushedAt: Long,
)

/** La release que se instalaría y su APK. */
data class Installable(val release: PortRelease, val asset: ReleaseAsset)

object PortAssetPicker {

    /** Compilaciones que no se instalan nunca: de depuración o sin firmar. */
    private val REJECT = Regex("""(?i)(^|[^a-z])(debug|unsigned)([^a-z]|$)""")

    fun isDebug(name: String): Boolean = REJECT.containsMatchIn(name)

    /**
     * Un APK que se podría instalar: casa con el patrón del catálogo, es
     * `.apk` (o `.zip`, si el build lo trae dentro de uno: [PortBuild.archive])
     * y no es de depuración, salvo que el catálogo diga que el port solo
     * publica builds de depuración ([allowDebug]).
     */
    fun candidates(assets: List<ReleaseAsset>, build: PortBuild, allowDebug: Boolean = false): List<ReleaseAsset> {
        val pattern = runCatching { Regex(build.asset) }.getOrNull() ?: return emptyList()
        val ext = if (build.archive != null) ".zip" else ".apk"
        return assets.filter { a ->
            a.name.lowercase().endsWith(ext) && pattern.containsMatchIn(a.name) && (allowDebug || !isDebug(a.name))
        }
    }

    /**
     * El APK para este dispositivo.
     *
     * Sigue el orden del catálogo ([PortBuild.abis]) saltándose las ABI que el
     * dispositivo no ejecuta ([deviceAbis] = `Build.SUPPORTED_ABIS`);
     * "universal" es el APK que lo dice o el que no nombra ninguna ABI. Si
     * nada casa, el mismo criterio que el actualizador de Elyndra.
     */
    fun pick(assets: List<ReleaseAsset>, build: PortBuild, deviceAbis: List<String>, allowDebug: Boolean = false): ReleaseAsset? {
        val apks = candidates(assets, build, allowDebug)
        if (apks.isEmpty()) return null
        val device = deviceAbis.map { it.lowercase() }
        for (abi in build.abis.map { it.lowercase() }) {
            val hit = if (abi == "universal") {
                apks.firstOrNull { UpdateLogic.namesAbi(it.name, "universal") }
                    ?: apks.firstOrNull { !UpdateLogic.mentionsAnyAbi(it.name) }
            } else if (abi in device) {
                apks.firstOrNull { UpdateLogic.namesAbi(it.name, abi) }
            } else {
                null
            }
            if (hit != null) return hit
        }
        return UpdateLogic.chooseAsset(apks, deviceAbis)
    }

    /**
     * La release más reciente con un APK para este dispositivo.
     *
     * Los borradores no cuentan; las pre-releases solo si no hay ninguna
     * estable instalable (hay ports que solo publican pre-releases).
     */
    fun latest(releases: List<PortRelease>, build: PortBuild, deviceAbis: List<String>, allowDebug: Boolean = false): Installable? {
        val usable = releases
            .filter { !it.draft }
            .sortedByDescending { it.publishedAt }
            .mapNotNull { r -> pick(r.assets, build, deviceAbis, allowDebug)?.let { Installable(r, it) } }
        return usable.firstOrNull { !it.release.prerelease } ?: usable.firstOrNull()
    }

    /** El SHA-256 que se exige: el del catálogo manda sobre el `digest` de la API. */
    fun expectedSha256(asset: ReleaseAsset, build: PortBuild): String? =
        build.sha256.entries.firstOrNull { it.key.equals(asset.name, ignoreCase = true) }?.value?.lowercase()
            ?: asset.sha256
}

object PortVersions {

    /** Sufijos de pre-release de semver de verdad; "-android.3" o "-ports.2" no lo son: son revisiones del fork. */
    private val STANDARD_PRE = setOf("alpha", "beta", "rc", "pre", "preview", "dev")
    private val DIGITS = Regex("""\d+""")

    /**
     * ¿[latestTag] es más nueva que la instalada?
     *
     * Semver cuando las dos etiquetas lo son (con pre-releases normales); si
     * no, por las cifras que llevan en orden ("v9.0.2P2" > "v9.0.2P1",
     * "5.0.1-android.3" > "5.0.1-android.2"); y si las cifras empatan pero la
     * etiqueta cambió, por fecha de publicación.
     */
    fun isNewer(latestTag: String, latestPublishedAt: Long, installedTag: String, installedAt: Long): Boolean {
        if (latestTag.trim().equals(installedTag.trim(), ignoreCase = true)) return false
        val a = SemVer.parse(latestTag)
        val b = SemVer.parse(installedTag)
        if (a != null && b != null && standard(a) && standard(b)) return a > b
        val c = compareDigits(latestTag, installedTag)
        if (c != 0) return c > 0
        return latestPublishedAt > installedAt
    }

    private fun standard(v: SemVer): Boolean = v.pre.isEmpty() || v.pre.first().trimEnd { it.isDigit() } in STANDARD_PRE

    internal fun compareDigits(a: String, b: String): Int {
        val x = DIGITS.findAll(a).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()
        val y = DIGITS.findAll(b).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i) ?: return -1
            val q = y.getOrNull(i) ?: return 1
            val c = if (p.length != q.length) p.length.compareTo(q.length) else p.compareTo(q)
            if (c != 0) return c
        }
        return 0
    }
}
