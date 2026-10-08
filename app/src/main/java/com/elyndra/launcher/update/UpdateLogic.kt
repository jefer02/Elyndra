package com.elyndra.launcher.update

/* Lo que decide el actualizador, sin Android ni red: comparar versiones,
   elegir la release y el APK, cuándo volver a mirar y cómo recortar las
   notas. Kotlin puro: se prueba en la JVM (UpdateLogicTest). */

/**
 * Una versión semántica `MAYOR.MENOR.PARCHE[-pre][+build]`.
 *
 * Es lo que llevan las etiquetas de las releases (`v0.3.0-beta`) y
 * `versionName`. Se acepta la `v` delante y versiones cortas ("2.0" = 2.0.0);
 * el `+build` no cuenta al comparar, como dice semver.
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val pre: List<String> = emptyList()) : Comparable<SemVer> {

    val isPrerelease: Boolean get() = pre.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // Sin sufijo va por delante de cualquier pre-release de la misma versión.
        if (pre.isEmpty() || other.pre.isEmpty()) return compareValues(other.pre.size, pre.size).coerceIn(-1, 1)
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val c = comparePre(pre[i], other.pre[i])
            if (c != 0) return c
        }
        return compareValues(pre.size, other.pre.size)
    }

    override fun toString(): String = "$major.$minor.$patch" + if (pre.isEmpty()) "" else pre.joinToString(".", prefix = "-")

    companion object {
        private val CORE = Regex("""\d{1,9}""")
        private val IDENT = Regex("""[0-9A-Za-z-]+""")

        fun parse(raw: String?): SemVer? {
            var s = raw?.trim() ?: return null
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
            s = s.substringBefore('+')
            val core = s.substringBefore('-')
            val pre = if ('-' in s) s.substringAfter('-') else null
            val parts = core.split('.')
            if (parts.isEmpty() || parts.size > 3 || parts.any { !CORE.matches(it) }) return null
            val ids = pre?.split('.') ?: emptyList()
            if (pre != null && (pre.isEmpty() || ids.any { !IDENT.matches(it) })) return null
            return SemVer(
                major = parts[0].toInt(),
                minor = parts.getOrNull(1)?.toInt() ?: 0,
                patch = parts.getOrNull(2)?.toInt() ?: 0,
                pre = ids.map { it.lowercase() },
            )
        }

        /** Los identificadores numéricos se comparan como números y van antes que los de texto. */
        private fun comparePre(a: String, b: String): Int {
            val na = a.toLongOrNull()
            val nb = b.toLongOrNull()
            return when {
                na != null && nb != null -> compareValues(na, nb)
                na != null -> -1
                nb != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}

/** Un archivo adjunto a una release. [sha256]: el resumen que da la API de GitHub (`digest`), si lo da. */
data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String? = null)

/** Una release de GitHub, ya leída (ver [UpdateRepository]). */
data class Release(
    val tag: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val assets: List<ReleaseAsset>,
) {
    val version: SemVer? get() = SemVer.parse(tag)
}

object UpdateLogic {

    /** Cada cuánto mira solo, como mucho. */
    const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Lo que se enseña de las notas de la release en el diálogo. */
    const val NOTES_MAX_CHARS = 600
    const val NOTES_MAX_LINES = 12

    /**
     * La release más nueva que la instalada ([current] = `versionName`), o null.
     *
     * Los borradores no cuentan nunca; las pre-releases, solo con
     * [includePrereleases]. Una etiqueta que no es semver se ignora, y si la
     * versión instalada tampoco lo es no se ofrece nada (mejor callar que
     * proponer una "actualización" que podría ser un paso atrás).
     */
    fun newest(releases: List<Release>, current: String, includePrereleases: Boolean): Release? {
        val installed = SemVer.parse(current) ?: return null
        return releases
            .asSequence()
            .filter { !it.draft }
            .mapNotNull { r -> r.version?.let { r to it } }
            // Sin pre-releases fuera también una etiqueta "-beta" publicada como estable por despiste.
            .filter { (r, v) -> includePrereleases || (!r.prerelease && !v.isPrerelease) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second > installed }
            ?.first
    }

    /** ¿Toca la comprobación automática? (también si el reloj ha ido hacia atrás). */
    fun shouldAutoCheck(enabled: Boolean, now: Long, lastCheckAt: Long): Boolean =
        enabled && (lastCheckAt <= 0L || now < lastCheckAt || now - lastCheckAt >= AUTO_INTERVAL_MS)

    /** La comprobación automática no vuelve a preguntar por la versión que ya se canceló; la manual, sí. */
    fun shouldPrompt(release: Release, skippedTag: String?, manual: Boolean): Boolean =
        manual || skippedTag == null || !release.tag.equals(skippedTag, ignoreCase = true)

    /** Sinónimos con los que puede venir cada ABI en el nombre del APK. */
    private val ABI_ALIASES = mapOf(
        "arm64-v8a" to listOf("arm64-v8a", "arm64", "aarch64"),
        "armeabi-v7a" to listOf("armeabi-v7a", "armeabi", "armv7"),
        "x86_64" to listOf("x86_64", "x64"),
        "x86" to listOf("x86"),
    )

    /** Un token suelto en el nombre: "x86" no casa dentro de "x86_64". */
    private fun hasToken(name: String, token: String): Boolean =
        Regex("(?<![a-z0-9_])" + Regex.escape(token) + "(?![a-z0-9_])").containsMatchIn(name)

    private fun mentionsAnyAbi(name: String): Boolean = ABI_ALIASES.values.flatten().any { hasToken(name, it) }

    /**
     * El APK para este dispositivo.
     *
     * Por orden: el de la primera ABI de [supportedAbis] (`Build.SUPPORTED_ABIS`,
     * de la preferida a la menos) que tenga APK propio; si no, el "universal";
     * si no, el único APK sin ABI en el nombre. Null = no hay uno seguro, y se
     * abre la página de la release.
     */
    fun chooseAsset(assets: List<ReleaseAsset>, supportedAbis: List<String>): ReleaseAsset? {
        val apks = assets.filter { it.name.lowercase().endsWith(".apk") }
        if (apks.isEmpty()) return null
        for (abi in supportedAbis) {
            val tokens = ABI_ALIASES[abi.lowercase()] ?: listOf(abi.lowercase())
            apks.firstOrNull { a -> val n = a.name.lowercase(); tokens.any { hasToken(n, it) } }?.let { return it }
        }
        apks.firstOrNull { hasToken(it.name.lowercase(), "universal") }?.let { return it }
        return apks.filter { !mentionsAnyAbi(it.name.lowercase()) }.singleOrNull()
    }

    /** El `.sha256` publicado junto al APK (`<nombre>.sha256`), si lo hay. */
    fun checksumAsset(apk: ReleaseAsset, assets: List<ReleaseAsset>): ReleaseAsset? =
        assets.firstOrNull { it.name.equals(apk.name + ".sha256", ignoreCase = true) }

    private val HEX64 = Regex("""[0-9a-fA-F]{64}""")

    /** `sha256:abcd…` (la API de GitHub) → `abcd…`; cualquier otro resumen, null. */
    fun parseDigest(digest: String?): String? {
        val d = digest?.trim() ?: return null
        if (!d.startsWith("sha256:", ignoreCase = true)) return null
        return d.substring(7).takeIf { HEX64.matches(it) }?.lowercase()
    }

    /** Primer token de un `.sha256` (`sha256sum` escribe "hash  nombre"). */
    fun parseChecksumFile(text: String): String? =
        text.trim().split(Regex("""\s+""")).firstOrNull()?.takeIf { HEX64.matches(it) }?.lowercase()

    /**
     * Las notas de la release, para leer en un diálogo: sin la sintaxis de
     * Markdown que se vería tal cual (#, **, enlaces, comentarios HTML), sin
     * líneas en blanco repetidas y cortadas a [maxLines] líneas y [maxChars]
     * caracteres, con "…" si se cortó algo.
     */
    fun trimNotes(body: String?, maxChars: Int = NOTES_MAX_CHARS, maxLines: Int = NOTES_MAX_LINES): String {
        if (body.isNullOrBlank()) return ""
        val cleaned = body
            .replace("\r\n", "\n")
            .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .map { line ->
                line.trim()
                    .replace(Regex("""^#{1,6}\s*"""), "")
                    .replace(Regex("""^[-*+]\s+"""), "• ")
                    .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
                    .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
                    .replace("**", "")
                    .replace("__", "")
                    .replace("`", "")
            }
            .fold(mutableListOf<String>()) { acc, line ->
                if (line.isNotEmpty() || (acc.isNotEmpty() && acc.last().isNotEmpty())) acc += line
                acc
            }
            .dropLastWhile { it.isEmpty() }
        var cut = cleaned.size > maxLines
        var text = cleaned.take(maxLines).joinToString("\n").trimEnd()
        if (text.length > maxChars) {
            val end = text.lastIndexOf(' ', maxChars).takeIf { it > maxChars / 2 } ?: maxChars
            text = text.substring(0, end).trimEnd()
            cut = true
        }
        return if (cut) "$text…" else text
    }
}
