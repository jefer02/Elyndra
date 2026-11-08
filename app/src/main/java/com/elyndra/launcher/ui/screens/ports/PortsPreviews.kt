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
import com.elyndra.launcher.domain.ports.OriginalGame
import com.elyndra.launcher.domain.ports.PortAction
import com.elyndra.launcher.domain.ports.PortBuild
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.domain.ports.PortRelease
import com.elyndra.launcher.domain.ports.RepoInfo
import com.elyndra.launcher.domain.ports.Requirement
import com.elyndra.launcher.domain.ports.RomHash
import com.elyndra.launcher.domain.ports.RomPortMatch
import com.elyndra.launcher.domain.ports.StoreFilter
import com.elyndra.launcher.domain.ports.StoreRows
import com.elyndra.launcher.ui.components.PreviewTheme
import com.elyndra.launcher.update.ReleaseAsset

/*
 * La tienda de ports en los aparatos de prueba: móvil apaisado (~853 × 384,
 * Tecno Spark 30C), tableta (~1280 × 800, Legion Y700) y móvil vertical
 * (412 × 892 y el estrecho de 360 × 780). La tienda es siempre oscura, así
 * que no hay variante clara. Sin red las imágenes no cargan: cada una enseña
 * su degradado y el nombre, que es lo que se ve mientras llegan.
 */

@Preview(name = "Tienda · móvil apaisado", widthDp = 853, heightDp = 384)
@Preview(name = "Tienda · tableta", widthDp = 1280, heightDp = 800)
annotation class PortsLandscapePreviews

@Preview(name = "Tienda · móvil vertical", widthDp = 412, heightDp = 892)
@Preview(name = "Tienda · móvil vertical estrecho", widthDp = 360, heightDp = 780)
@Preview(name = "Tienda · tableta vertical", widthDp = 800, heightDp = 1280)
annotation class PortsPortraitPreviews

private val SAMPLE = listOf(
    PortEntry(
        id = "soh",
        name = "Ship of Harkinian",
        developer = "HarbourMasters · Android: linkzenic",
        repo = GhRepo("linkzenic", "Shipwright-Ports"),
        upstream = GhRepo("HarbourMasters", "Shipwright"),
        original = OriginalGame("The Legend of Zelda: Ocarina of Time", "n64"),
        builds = listOf(PortBuild("android")),
        requires = Requirement(
            true,
            mapOf("en" to "Your own Ocarina of Time ROM (N64 NTSC/PAL, GameCube or Master Quest)."),
            listOf(RomHash("NTSC 1.0 (US)", "n64", sha1 = "ad69c91157f6705e8ab06c79fe08aad47bb57ba7"), RomHash("PAL 1.0", "n64")),
        ),
        tags = listOf("adventure", "widescreen", "60fps"),
        short = mapOf("en" to "Ocarina of Time rebuilt from the decompilation, running natively on Android."),
        long = mapOf("en" to "Widescreen, high frame rates, modern controls, a randomizer and mods."),
        verified = true,
        featured = true,
    ),
    PortEntry(
        id = "nsmbw",
        name = "NSMB Wii Recompiled",
        kind = "recomp",
        repo = GhRepo("WitherBlitz", "NSMB-Wii-Recompilation"),
        original = OriginalGame("New Super Mario Bros. Wii", "wii"),
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
        original = OriginalGame("Sonic Unleashed", "xbox360"),
        builds = listOf(PortBuild("android")),
        tags = listOf("action", "recomp"),
    ),
    PortEntry(
        id = "starship",
        name = "Starship",
        repo = GhRepo("HarbourMasters", "Starship"),
        original = OriginalGame("Star Fox 64", "n64"),
        builds = listOf(PortBuild("windows")),
        verified = true,
    ),
)

private val ACTIONS = mapOf("soh" to PortAction.Installed, "nsmbw" to PortAction.Install, "unleashed" to PortAction.Update, "starship" to PortAction.PcOnly)

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

private fun sampleHome(query: String = "") = StoreHome(
    rows = StoreRows.home(
        SAMPLE,
        StoreFilter(query = query),
        owned = listOf(RomPortMatch(SAMPLE[0], OOT_ROM, MatchStrength.Hash)),
        installedIds = setOf("soh"),
        publishedAt = mapOf("nsmbw" to 2L, "soh" to 1L),
    ),
    loading = false,
    offline = false,
    rateLimited = false,
    filter = StoreFilter(query = query),
    searchOpen = query.isNotEmpty(),
    platforms = StoreRows.platforms(SAMPLE),
    tags = StoreRows.tags(SAMPLE),
    action = { ACTIONS[it.id] ?: PortAction.Free },
    progress = { if (it.id == "unleashed") 0.42f else null },
    owned = { it.id == "soh" },
    heroArt = { null },
)

private val NO_ACTIONS = StoreHomeActions({}, {}, {}, {}, {}, {}, {}, {}, {})

private fun sampleDetail(action: PortAction) = PortDetailState(
    port = SAMPLE[0],
    action = action,
    progress = null,
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
    media = listOf(SAMPLE[0].image),
    cover = null,
)

@Composable
private fun StoreFrame(content: @Composable () -> Unit) = PreviewTheme {
    Box(Modifier.fillMaxSize().storeBackground()) { content() }
}

@PortsLandscapePreviews
@Composable
private fun PortsHomeLandscapePreview() = StoreFrame { PortsHome(sampleHome(), NO_ACTIONS) }

@PortsPortraitPreviews
@Composable
private fun PortsHomePortraitPreview() = StoreFrame { PortsHome(sampleHome(), NO_ACTIONS) }

@PortsPortraitPreviews
@Composable
private fun PortsSearchPortraitPreview() = StoreFrame { PortsHome(sampleHome(query = "zelda"), NO_ACTIONS) }

@PortsLandscapePreviews
@Composable
private fun PortDetailLandscapePreview() = StoreFrame { PortDetail(sampleDetail(PortAction.Update), PortDetailActions({}, {}, {}, {})) }

@PortsPortraitPreviews
@Composable
private fun PortDetailPortraitPreview() = StoreFrame { PortDetail(sampleDetail(PortAction.Install), PortDetailActions({}, {}, {}, {})) }
