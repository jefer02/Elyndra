package com.elyndra.launcher.ui.screens.ports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.elyndra.launcher.data.FileHashes
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.domain.ports.GhRepo
import com.elyndra.launcher.domain.ports.Installable
import com.elyndra.launcher.domain.ports.MatchStrength
import com.elyndra.launcher.domain.ports.PortAction
import com.elyndra.launcher.domain.ports.PortBuild
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.domain.ports.PortGame
import com.elyndra.launcher.domain.ports.PortGrouping
import com.elyndra.launcher.domain.ports.PortReleaseInfo
import com.elyndra.launcher.domain.ports.PortTrust
import com.elyndra.launcher.domain.ports.PortRelease
import com.elyndra.launcher.domain.ports.RepoInfo
import com.elyndra.launcher.domain.ports.Requirement
import com.elyndra.launcher.domain.ports.RomHash
import com.elyndra.launcher.domain.ports.RomPortMatch
import com.elyndra.launcher.domain.ports.PlatformGroup
import com.elyndra.launcher.domain.ports.StoreFilter
import com.elyndra.launcher.domain.ports.StoreSort
import com.elyndra.launcher.domain.ports.StoreRows
import com.elyndra.launcher.ui.StoreTab
import com.elyndra.launcher.ui.TileStatus
import com.elyndra.launcher.domain.ports.GameGroup
import com.elyndra.launcher.ui.components.PreviewTheme
import com.elyndra.launcher.update.ReleaseAsset

/*
 * La tienda de ports (pestañas, rejilla y ficha) en los aparatos de prueba: móvil apaisado (~853 × 384,
 * Tecno Spark 30C), tableta (~1280 × 800, Legion Y700) y móvil vertical
 * (412 × 892 y el estrecho de 360 × 780). La tienda es siempre oscura, así
 * que no hay variante clara. Sin red las imágenes no cargan: cada una enseña
 * su degradado y el nombre, que es lo que se ve mientras llegan.
 */

@Preview(name = "Tienda · móvil apaisado", widthDp = 853, heightDp = 384)
@Preview(name = "Tienda · tableta", widthDp = 1280, heightDp = 800)
annotation class StoreLandscapePreviews

@Preview(name = "Tienda · móvil vertical", widthDp = 412, heightDp = 892)
@Preview(name = "Tienda · móvil vertical estrecho", widthDp = 360, heightDp = 780)
@Preview(name = "Tienda · tableta vertical", widthDp = 800, heightDp = 1280)
annotation class StorePortraitPreviews

private val SAMPLE = listOf(
    PortEntry(
        id = "soh",
        name = "Ship of Harkinian",
        developer = "HarbourMasters · Android: linkzenic",
        repo = GhRepo("linkzenic", "Shipwright-Ports"),
        upstream = GhRepo("HarbourMasters", "Shipwright"),
        game = PortGame("The Legend of Zelda: Ocarina of Time", "n64", igdbId = 1029, releaseYear = 1998),
        builds = listOf(PortBuild("android")),
        requires = Requirement(
            true,
            mapOf("en" to "Your own Ocarina of Time ROM (N64 NTSC/PAL, GameCube or Master Quest)."),
            listOf(RomHash("NTSC 1.0 (US)", "n64", sha1 = "ad69c91157f6705e8ab06c79fe08aad47bb57ba7"), RomHash("PAL 1.0", "n64")),
        ),
        tags = listOf("adventure", "widescreen", "60fps"),
        short = mapOf("en" to "Ocarina of Time rebuilt from the decompilation, running natively on Android."),
        long = mapOf("en" to "Widescreen, high frame rates, modern controls, a randomizer and mods."),
        release = PortReleaseInfo("v9.3.1-ports.2", "2026-10-30T12:00:00Z"),
        trust = PortTrust(verified = false, stars = 101, flags = listOf(PortTrust.PRERELEASE)),
        featured = true,
    ),
    PortEntry(
        id = "soh-waterdish",
        name = "Shipwright Android",
        developer = "Waterdish",
        repo = GhRepo("Waterdish", "Shipwright-Android"),
        game = PortGame("The Legend of Zelda: Ocarina of Time", "n64"),
        builds = listOf(PortBuild("android")),
        release = PortReleaseInfo("v9.0.5", "2026-04-02T12:00:00Z"),
        trust = PortTrust(stars = 410),
    ),
    PortEntry(
        id = "nsmbw",
        name = "NSMB Wii Recompiled",
        kind = "recomp",
        developer = "WitherBlitz",
        repo = GhRepo("WitherBlitz", "NSMB-Wii-Recompilation"),
        game = PortGame("New Super Mario Bros. Wii", "wii", releaseYear = 2009),
        builds = listOf(PortBuild("android")),
        tags = listOf("platformer", "multiplayer"),
        short = mapOf("en" to "Static recompilation of New Super Mario Bros. Wii for Android."),
        featured = true,
    ),
    PortEntry(
        id = "unleashed",
        name = "Unleashed Recompiled",
        kind = "recomp",
        repo = GhRepo("SansNope", "UnleashedRecomp-Android"),
        game = PortGame("Sonic Unleashed", "xbox360"),
        builds = listOf(PortBuild("android")),
        tags = listOf("action", "recomp"),
    ),
    PortEntry(
        id = "devilutionx",
        name = "DevilutionX",
        kind = "reimpl",
        developer = "diasurgical",
        repo = GhRepo("diasurgical", "devilutionX"),
        game = PortGame("Diablo", "pc", steamAppId = 2_456_740, releaseYear = 1996),
        builds = listOf(PortBuild("android")),
        tags = listOf("rpg"),
        trust = PortTrust(verified = true, stars = 9_000),
    ),
)

private val ACTIONS = mapOf("soh" to PortAction.Installed, "nsmbw" to PortAction.Install, "unleashed" to PortAction.Update, "soh-waterdish" to PortAction.Install)

private val OOT_ROM = RomEntry(
    id = "1",
    folderId = "n64",
    systemId = "n64",
    docId = "x",
    fileName = "oot.z64",
    relPath = "oot.z64",
    title = "Legend of Zelda, The - Ocarina of Time (USA)",
    hashes = FileHashes(1, 1, sha1 = "ad69c91157f6705e8ab06c79fe08aad47bb57ba7"),
)

private val NOW = 1_793_500_000_000L

private val SIZES = mapOf("nsmbw" to 61_000_000L, "soh-waterdish" to 48_000_000L)

private fun status(p: PortEntry): TileStatus = TileStatus.of(
    action = ACTIONS[p.id] ?: PortAction.Free,
    progress = if (p.id == "devilutionx") 0.42f else null,
    installedTag = if (p.id == "unleashed") "v1.0.2" else null,
    latestTag = if (p.id == "unleashed") "v1.0.3" else p.release.tag,
    sizeBytes = SIZES[p.id] ?: 0,
    publishedAt = p.release.publishedAtMillis,
    now = NOW,
    // Entró en el catálogo hace tres días: NUEVO.
    addedAt = if (p.id == "nsmbw") NOW - 3 * 86_400_000L else 0L,
)

private fun groups(ids: List<String>? = null): List<GameGroup> =
    PortGrouping.group(SAMPLE.filter { ids == null || it.id in ids }, setOf("soh"))

private fun sampleHome(tab: StoreTab, filter: StoreFilter = StoreFilter(), search: Boolean = false) = StoreHome(
    tab = tab,
    tabDirection = 1,
    rows = StoreRows.home(
        SAMPLE,
        StoreFilter(),
        owned = listOf(RomPortMatch(SAMPLE[0], OOT_ROM, MatchStrength.Hash)),
        installedIds = setOf("soh"),
        publishedAt = mapOf("nsmbw" to 2L, "soh" to 1L),
    ),
    browse = StoreRows.sorted(PortGrouping.group(StoreRows.visible(SAMPLE, filter), setOf("soh")), filter.sort),
    updates = groups(listOf("unleashed")),
    installed = groups(listOf("soh", "unleashed")),
    collections = StoreRows.groupChips(SAMPLE).map { g -> g to PortGrouping.group(SAMPLE.filter { StoreRows.group(it.platform) == g }) },
    loading = false,
    offline = false,
    rateLimited = false,
    filter = filter,
    searchOpen = search,
    downloading = true,
    status = ::status,
    heroArt = { null },
)

private fun sampleDetail(action: PortAction, port: PortEntry = SAMPLE[0], progress: Float? = null) = PortDetailState(
    port = port,
    action = action,
    progress = progress,
    installing = false,
    latest = Installable(
        PortRelease("v9.3.1-ports.2", "v9.3.1", "## New\n- Android 15 fixes\n- Faster boot", "", false, false, 1_793_330_010_000L, emptyList()),
        ReleaseAsset("soh_release_v9.3.1-android.4.apk", "https://github.com/x", 58_792_077),
    ),
    releasesLoading = false,
    releasesFailed = false,
    rateLimited = false,
    repo = RepoInfo(101, null, null, 0),
    owned = RomPortMatch(SAMPLE[0], OOT_ROM, MatchStrength.Hash),
    // Sin red en la vista previa: el arte y las capturas pintan su respaldo oscuro.
    hero = null,
    portShots = listOf("port-1", "port-2"),
    originalShots = listOf("original-1"),
    siblings = PortGrouping.siblings(port, SAMPLE),
    siblingAction = { ACTIONS[it.id] ?: PortAction.Free },
    wishlisted = port.id == "nsmbw",
)

@Composable
private fun StoreFrame(content: @Composable () -> Unit) = PreviewTheme {
    Box(Modifier.fillMaxSize().storeBackground()) { content() }
}

/** Novedades: los dos carteles y los carruseles. */
@StoreLandscapePreviews
@Composable
private fun StoreLatestLandscapePreview() = StoreFrame { PortsHome(sampleHome(StoreTab.Latest), StoreHomeActions(), padHints = true) }

@StorePortraitPreviews
@Composable
private fun StoreLatestPortraitPreview() = StoreFrame { PortsHome(sampleHome(StoreTab.Latest), StoreHomeActions()) }

/** Explorar con un filtro: "Ordenar por: … | Filtrar por: Nintendo", el botón de orden en el margen y la rejilla. */
@StoreLandscapePreviews
@Composable
private fun StoreBrowseLandscapePreview() = StoreFrame {
    PortsHome(sampleHome(StoreTab.Browse, StoreFilter(groups = setOf(PlatformGroup.Nintendo), sort = StoreSort.Stars)), StoreHomeActions(), padHints = true)
}

@StorePortraitPreviews
@Composable
private fun StoreBrowsePortraitPreview() = StoreFrame { PortsHome(sampleHome(StoreTab.Browse, StoreFilter(query = "zelda"), search = true), StoreHomeActions()) }

@StoreLandscapePreviews
@Composable
private fun StoreCollectionsPreview() = StoreFrame { PortsHome(sampleHome(StoreTab.Collections), StoreHomeActions()) }

/** Actualizaciones: la caja con la versión nueva y la vieja tachada. */
@StoreLandscapePreviews
@Composable
private fun StoreUpdatesPreview() = StoreFrame { PortsHome(sampleHome(StoreTab.Updates), StoreHomeActions()) }

@StoreLandscapePreviews
@Composable
private fun PortDetailLandscapePreview() = StoreFrame { PortDetail(sampleDetail(PortAction.Update), PortDetailActions(), padHints = true) }

@StorePortraitPreviews
@Composable
private fun PortDetailPortraitPreview() = StoreFrame { PortDetail(sampleDetail(PortAction.Install, progress = 0.42f), PortDetailActions()) }

/** Un solo port del juego: su release como única variante. */
@StoreLandscapePreviews
@Composable
private fun PortDetailSinglePreview() = StoreFrame { PortDetail(sampleDetail(PortAction.Install, SAMPLE[2]), PortDetailActions()) }
