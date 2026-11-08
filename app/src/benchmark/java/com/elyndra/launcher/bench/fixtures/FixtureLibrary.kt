package com.elyndra.launcher.bench.fixtures

import com.elyndra.launcher.data.AppEntry
import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.LibraryRepository
import com.elyndra.launcher.data.PlaySession
import com.elyndra.launcher.data.PlayStats
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.RomFolder

/**
 * La biblioteca de prueba: siempre la misma (nada aleatorio), con la forma de
 * una de verdad para que se recorran los mismos caminos:
 *
 * - 25 juegos Android y 4 carpetas de ROMs (PSP, Switch, PS2, GBA) con 12–15
 *   juegos cada una.
 * - Metadatos (nombre, descripción en inglés y español, género, estudio,
 *   fecha, nota) en casi todos; unos pocos sin identificar, con el nombre del
 *   archivo, como los que deja un análisis sin metadatos.
 * - Tiempo de juego y sesiones en un tercio largo: salen "Sin jugar" y jugados.
 * - Arte local de todos los tamaños y proporciones (carátulas altas, cuadradas
 *   y anchas, iconos cuadrados, logos con transparencia, fondos 16:9) y huecos
 *   para el arte de sustitución. Fondos de 160–256 px (se suavizan,
 *   HeroArtQuality) y de 1280–1920 px (no).
 *
 * Los nombres son inventados. Los paquetes (`com.elyndra.fixture.*`) y las
 * carpetas no existen: ver [installedPackages] y BenchmarkHooks.
 */
object FixtureLibrary {

    /** Carpeta de las imágenes, bajo filesDir (las rutas de la biblioteca son relativas a filesDir). */
    const val ART_DIR = "benchfixtures"

    private const val PACKAGE_PREFIX = "com.elyndra.fixture."
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MINUTE_MS = 60_000L

    /** Una imagen que hay que pintar: [path] relativa a filesDir. */
    data class ArtSpec(val path: String, val kind: Kind, val width: Int, val height: Int, val hue: Float, val label: String) {
        enum class Kind { Cover, Hero, Logo, Icon }
    }

    private class FixtureSystem(val id: String, val folderName: String, val emulatorId: String, val extension: String, val coverW: Int, val coverH: Int, val count: Int)

    private val systems = listOf(
        FixtureSystem("psp", "PSP", "ppsspp", "iso", 348, 600, 14),
        FixtureSystem("switch", "Switch", "eden", "nsp", 372, 600, 12),
        FixtureSystem("ps2", "PS2", "nethersx2", "chd", 420, 600, 13),
        FixtureSystem("gba", "GBA", "pizzaboy_gba", "gba", 480, 480, 15),
    )

    private val appNames = listOf(
        "Aether Drift", "Brickfall Arena", "Cinder Knights", "Dune Couriers", "Echo Racers",
        "Frostline Tactics", "Gale Runner", "Harbor Lights", "Iron Orchard", "Jade Circuit",
        "Kite Hunters", "Lumen Depths", "Moss & Gears", "Nightjar Rally", "Oak Legends",
        "Pixel Lagoon", "Quartz Siege", "Rift Gardens", "Solar Couriers", "Tidecaller",
        "Umbra Run", "Velvet Skies", "Wisp Kingdom", "Xeno Harvest",
        "Zéphyr Chronicles: The Unusually Long Subtitle Edition",
    )

    private val adjectives = listOf(
        "Crimson", "Silent", "Hollow", "Radiant", "Sunken", "Gilded", "Feral", "Glass",
        "Lunar", "Copper", "Wandering", "Ashen", "Verdant", "Shattered", "Distant", "Brass",
    )
    private val nouns = listOf(
        "Lantern", "Citadel", "Voyage", "Harbor", "Engine", "Tempest", "Garden", "Frontier",
        "Oracle", "Circuit", "Labyrinth", "Meridian", "Comet", "Requiem", "Bastion", "Tides",
    )
    private val suffixes = listOf("", "", " II", ": Remastered", "", " Zero", "", ": Origins")

    private val genres = listOf("Action", "RPG", "Racing", "Platformer", "Puzzle", "Strategy", "Adventure", "Sports", "Shooter", "Fighting")
    private val studios = listOf("Northwind Works", "Pale Moth", "Studio Kestrel", "Lowtide Games", "Ninefold", "Amber Forge")

    /** Lo que se planea una vez: qué juegos hay y qué imágenes lleva cada uno. */
    private class AppPlan(val index: Int, val packageName: String, val name: String, val art: Map<ArtSpec.Kind, ArtSpec>)
    private class RomPlan(val system: FixtureSystem, val index: Int, val name: String, val identified: Boolean, val art: Map<ArtSpec.Kind, ArtSpec>)
    private class FolderPlan(val system: FixtureSystem, val art: Map<ArtSpec.Kind, ArtSpec>)

    private val appPlans: List<AppPlan> = appNames.mapIndexed { i, name ->
        val slug = "app%02d".format(i)
        AppPlan(i, PACKAGE_PREFIX + slug, name, appArt(slug, i, name))
    }

    private val romPlans: List<RomPlan> = systems.flatMapIndexed { s, system ->
        (0 until system.count).map { j ->
            val name = adjectives[(j * 3 + s * 5) % adjectives.size] + " " + nouns[(j * 5 + s * 3) % nouns.size] + suffixes[(j + s) % suffixes.size]
            val slug = "${system.id}%02d".format(j)
            // Uno de cada nueve sin identificar: se ve el nombre del archivo y no tiene arte.
            val identified = j % 9 != 8
            RomPlan(system, j, name, identified, if (identified) romArt(slug, system, s, j, name) else emptyMap())
        }
    }

    private val folderPlans: List<FolderPlan> = systems.mapIndexed { s, system ->
        val slug = "folder_${system.id}"
        val hue = (s * 83f + 20f) % 360f
        // PSP con todo, Switch solo fondo, PS2 nada (hereda el de su juego jugado), GBA carátula e icono.
        val art = when (system.id) {
            "psp" -> mapOf(
                cover(slug, 400, 600, hue, system.folderName), hero(slug, 1920, 1080, hue, system.folderName),
                logo(slug, hue, system.folderName), icon(slug, hue, system.folderName),
            )
            "switch" -> mapOf(hero(slug, 1280, 720, hue, system.folderName))
            "gba" -> mapOf(cover(slug, 480, 480, hue, system.folderName), icon(slug, hue, system.folderName))
            else -> emptyMap()
        }
        FolderPlan(system, art)
    }

    /** Todas las imágenes, en orden fijo. */
    val art: List<ArtSpec> = (folderPlans.flatMap { it.art.values } + appPlans.flatMap { it.art.values } + romPlans.flatMap { it.art.values })

    /** Lo que se da por instalado: las apps de prueba y los emuladores de sus carpetas. */
    val installedPackages: Set<String> =
        appPlans.mapTo(HashSet()) { it.packageName } + systems.flatMap { Emulators.byId(it.emulatorId)?.packages.orEmpty() }

    fun build(now: Long): Library {
        val sessions = mutableListOf<PlaySession>()
        val apps = appPlans.map { p ->
            val key = "a:${p.packageName}"
            val played = p.index % 3 != 2
            AppEntry(
                packageName = p.packageName,
                label = p.name,
                addedAt = now - (40 - p.index) * DAY_MS,
                meta = meta(p.name, p.index, now, p.art),
                stats = if (played) play(key, p.index, emulatorId = null, now, sessions) else PlayStats(),
            )
        }
        val folders = mutableListOf<RomFolder>()
        val roms = mutableListOf<RomEntry>()
        for (f in folderPlans) {
            val system = f.system
            val folderId = "bench-${system.id}"
            val root = "primary:BenchFixtures/${system.folderName}"
            folders += RomFolder(
                id = folderId,
                systemId = system.id,
                // Sin permiso SAF: los reescaneos al volver a la app no la tocan (ElyndraViewModel.rescan).
                treeUri = "content://com.android.externalstorage.documents/tree/" + root.replace(":", "%3A").replace("/", "%2F"),
                rootDocId = root,
                displayPath = "BenchFixtures/${system.folderName}",
                emulatorId = system.emulatorId,
                addedAt = now - 30 * DAY_MS,
                lastScan = now,
                cover = f.art[ArtSpec.Kind.Cover]?.path,
                hero = f.art[ArtSpec.Kind.Hero]?.path,
                logo = f.art[ArtSpec.Kind.Logo]?.path,
                icon = f.art[ArtSpec.Kind.Icon]?.path,
            )
            for (r in romPlans.filter { it.system === system }) {
                val fileName = if (r.identified) "${r.name.replace(":", " -")} (USA).${system.extension}"
                else r.name.lowercase().replace(" ", "_").replace(":", "") + "_v1.${system.extension}"
                val docId = "$root/$fileName"
                val id = LibraryRepository.romId(folderId, docId)
                val played = r.index % 3 == 0
                roms += RomEntry(
                    id = id,
                    folderId = folderId,
                    systemId = system.id,
                    docId = docId,
                    fileName = fileName,
                    relPath = fileName,
                    size = (64L + r.index * 37L) * 1024 * 1024,
                    modified = now - (200 - r.index) * DAY_MS,
                    title = fileName.substringBeforeLast('.').substringBefore(" ("),
                    meta = if (r.identified) meta(r.name, r.index + 7, now, r.art) else GameMeta(),
                    stats = if (played) play("r:$id", r.index, system.emulatorId, now, sessions) else PlayStats(),
                    addedAt = now - 30 * DAY_MS,
                )
            }
        }
        return Library(
            folders = folders,
            roms = roms,
            apps = apps,
            sessions = sessions.sortedBy { it.start },
            // Ya "analizada": el reescaneo automático al volver a la app no salta enseguida.
            lastAutoScan = now,
        )
    }

    private fun meta(name: String, i: Int, now: Long, art: Map<ArtSpec.Kind, ArtSpec>): GameMeta {
        val genre = genres[i % genres.size]
        val studio = studios[i % studios.size]
        val en = "$name is a ${genre.lowercase()} game by $studio. Explore hand-made worlds, master the controls at your own pace " +
            "and come back for short sessions or long weekends. This text is a fixture used only by the benchmark build."
        val es = "$name es un juego de ${genre.lowercase()} de $studio. Explora mundos hechos a mano, domina los controles a tu ritmo " +
            "y vuelve para partidas cortas o fines de semana largos. Este texto es de prueba y solo existe en la variante de pruebas."
        return GameMeta(
            scrapedAt = now,
            matched = true,
            sources = listOf("local"),
            name = name,
            description = en,
            descriptionLang = "en",
            descriptions = mapOf("en" to en, "es" to es),
            releaseDate = "%04d-%02d-01".format(2004 + i % 20, 1 + i % 12),
            developer = studio,
            publisher = studios[(i + 2) % studios.size],
            genre = genre,
            players = if (i % 4 == 0) "1-4" else "1",
            rating = 0.55f + (i % 9) * 0.05f,
            cover = art[ArtSpec.Kind.Cover]?.path,
            hero = art[ArtSpec.Kind.Hero]?.path,
            logo = art[ArtSpec.Kind.Logo]?.path,
            icon = art[ArtSpec.Kind.Icon]?.path,
        )
    }

    /** Unas sesiones en los últimos días y lo que suman. */
    private fun play(key: String, i: Int, emulatorId: String?, now: Long, out: MutableList<PlaySession>): PlayStats {
        var total = 0
        var last = 0L
        val count = 1 + i % 3
        repeat(count) { n ->
            val minutes = 12 + (i * 17 + n * 23) % 84
            val start = now - (1 + i % 11 + n * 4) * DAY_MS - (i % 5) * 60 * MINUTE_MS
            val end = start + minutes * MINUTE_MS
            out += PlaySession(key = key, start = start, minutes = minutes, emulatorId = emulatorId, end = end, earlyExit = minutes < 15)
            total += minutes
            last = maxOf(last, end)
        }
        return PlayStats(minutes = total, lastPlayed = last, launches = count + 1)
    }

    /* ── el arte que lleva cada uno ──────────────────────────────── */

    private fun appArt(slug: String, i: Int, name: String): Map<ArtSpec.Kind, ArtSpec> {
        val hue = (i * 37f) % 360f
        val parts = mutableListOf<Pair<ArtSpec.Kind, ArtSpec>>()
        when (i % 4) {
            0 -> parts += cover(slug, 400, 600, hue, name)
            1 -> parts += cover(slug, 480, 480, hue, name)
            2 -> parts += cover(slug, 640, 360, hue, name)
            // 3: sin carátula (icono y fondo, como muchas apps de verdad)
        }
        when {
            i % 5 == 4 -> Unit // sin fondo: el de sustitución
            i % 3 == 0 -> parts += hero(slug, 192, 108, hue, name)
            i % 7 == 1 -> parts += hero(slug, 1920, 1080, hue, name)
            else -> parts += hero(slug, 1280, 720, hue, name)
        }
        if (i % 2 == 0) parts += logo(slug, hue, name)
        if (i % 8 != 7) parts += icon(slug, hue, name)
        return parts.toMap()
    }

    private fun romArt(slug: String, system: FixtureSystem, s: Int, j: Int, name: String): Map<ArtSpec.Kind, ArtSpec> {
        val hue = (s * 90f + j * 23f) % 360f
        val parts = mutableListOf<Pair<ArtSpec.Kind, ArtSpec>>()
        if (j % 6 != 5) parts += cover(slug, system.coverW, system.coverH, hue, name)
        when {
            j % 4 == 3 -> Unit
            j % 4 == 0 -> parts += if (j % 8 == 0) hero(slug, 160, 90, hue, name) else hero(slug, 256, 144, hue, name)
            j % 5 == 1 -> parts += hero(slug, 1920, 1080, hue, name)
            else -> parts += hero(slug, 1280, 720, hue, name)
        }
        if (j % 3 == 0) parts += logo(slug, hue, name)
        if (j % 7 == 2) parts += icon(slug, hue, name)
        return parts.toMap()
    }

    private fun spec(slug: String, kind: ArtSpec.Kind, w: Int, h: Int, hue: Float, label: String) =
        kind to ArtSpec("$ART_DIR/$slug/${kind.name.lowercase()}.webp", kind, w, h, hue, label)

    private fun cover(slug: String, w: Int, h: Int, hue: Float, label: String) = spec(slug, ArtSpec.Kind.Cover, w, h, hue, label)
    private fun hero(slug: String, w: Int, h: Int, hue: Float, label: String) = spec(slug, ArtSpec.Kind.Hero, w, h, hue, label)
    private fun logo(slug: String, hue: Float, label: String) = spec(slug, ArtSpec.Kind.Logo, 640, 240, hue, label)
    private fun icon(slug: String, hue: Float, label: String) = spec(slug, ArtSpec.Kind.Icon, 192, 192, hue, label)
}
