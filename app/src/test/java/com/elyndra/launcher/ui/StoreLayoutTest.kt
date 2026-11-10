package com.elyndra.launcher.ui

import com.elyndra.launcher.domain.ports.PortAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreLayoutTest {

    /** Móvil vertical, estrecho, apaisado (Tecno Spark 30C), tableta (Legion Y700) en los dos sentidos, TV. */
    private val screens = listOf(412f to 892f, 360f to 780f, 853f to 384f, 1280f to 800f, 800f to 1280f, 1920f to 1080f)

    @Test fun `landscape keeps about nine percent of air on each side`() {
        assertEquals(115.2f, StoreLayout.margin(1280f, 800f), 0.01f)
        assertEquals(16f, StoreLayout.margin(412f, 892f), 0.01f)
        assertEquals(24f, StoreLayout.margin(800f, 1280f), 0.01f)
    }

    @Test fun `columns go 5, 4, 3, 2 as the room shrinks`() {
        assertEquals(5, StoreLayout.columns(1280f, 800f))
        assertEquals(5, StoreLayout.columns(1920f, 1080f))
        assertEquals(4, StoreLayout.columns(853f, 384f))
        assertEquals(4, StoreLayout.columns(800f, 1280f))
        assertEquals(3, StoreLayout.columns(600f, 900f))
        assertEquals(2, StoreLayout.columns(412f, 892f))
        assertEquals(2, StoreLayout.columns(360f, 780f))
    }

    @Test fun `square tiles fill the row exactly`() {
        for ((w, h) in screens) {
            val cols = StoreLayout.columns(w, h)
            val side = StoreLayout.tileSide(w, h)
            val used = side * cols + StoreLayout.gap(w) * (cols - 1)
            assertEquals("$w x $h", StoreLayout.room(w, h), used, 0.01f)
            assertTrue("$w x $h: $side", side > 120f)
        }
    }

    @Test fun `the sort button sits in the margin only when it fits`() {
        assertTrue(StoreLayout.sortButtonInMargin(1280f, 800f))
        assertTrue(StoreLayout.sortButtonInMargin(853f, 384f))
        assertFalse(StoreLayout.sortButtonInMargin(412f, 892f))
        assertFalse(StoreLayout.sortButtonInMargin(800f, 1280f))
    }

    @Test fun `the store tile gives way to the back button on the narrowest phones`() {
        assertTrue(StoreLayout.headerTileVisible(412f))
        assertTrue(StoreLayout.headerTileVisible(1280f))
        assertFalse(StoreLayout.headerTileVisible(360f))
    }

    @Test fun `icons join the tab row only when wide`() {
        assertTrue(StoreLayout.iconsInTabRow(1280f))
        assertTrue(StoreLayout.iconsInTabRow(853f))
        assertFalse(StoreLayout.iconsInTabRow(412f))
    }

    @Test fun `two hero banners side by side in landscape, stacked in portrait`() {
        assertEquals(2, StoreLayout.heroColumns(1280f, 800f))
        assertEquals(2, StoreLayout.heroColumns(853f, 384f))
        assertEquals(1, StoreLayout.heroColumns(412f, 892f))
        // 4:3 unless the screen is too low.
        val w = StoreLayout.heroWidth(1280f, 800f)
        assertEquals(w * 0.75f, StoreLayout.heroHeight(1280f, 800f), 0.01f)
        assertTrue(StoreLayout.heroHeight(853f, 384f) <= 384f * 0.62f + 0.01f)
    }

    @Test fun `carousel cards are 16 by 9 and the next one peeks`() {
        for ((w, h) in screens) {
            val c = StoreLayout.cardWidth(w, h)
            assertTrue("$w: $c", c in 150f..320f)
            val shown = StoreLayout.room(w, h) / (c + StoreLayout.gap(w))
            assertTrue("$w: $shown", shown % 1f > 0.05f)
        }
        assertEquals(180f, StoreLayout.cardHeight(320f), 0.01f)
    }

    @Test fun `collections use 3, 2 or 1 columns and fill the row`() {
        assertEquals(3, StoreLayout.collectionColumns(1280f, 800f))
        assertEquals(2, StoreLayout.collectionColumns(853f, 384f))
        assertEquals(1, StoreLayout.collectionColumns(360f, 780f))
        for ((w, h) in screens) {
            val cols = StoreLayout.collectionColumns(w, h)
            assertEquals(StoreLayout.room(w, h), StoreLayout.collectionWidth(w, h) * cols + StoreLayout.gap(w) * (cols - 1), 0.01f)
        }
    }

    @Test fun `detail panel is 40 percent wide in landscape and full width in portrait`() {
        assertTrue(StoreLayout.detailWide(1280f, 800f))
        assertEquals(512f, StoreLayout.detailPanelWidth(1280f, 800f), 0.01f)
        assertFalse(StoreLayout.detailWide(412f, 892f))
        assertEquals(380f, StoreLayout.detailPanelWidth(412f, 892f), 0.01f)
        // In portrait the panel overlaps the bottom of the 16:9 art.
        assertTrue(StoreLayout.detailPanelTop(412f, 892f) < StoreLayout.detailHeroHeight(412f, 892f))
    }

    @Test fun `scroll thumb spans the track and moves with the rows`() {
        assertEquals(0f to 400f, StoreLayout.scrollThumb(400f, 0, 3, 3))
        val (top0, len) = StoreLayout.scrollThumb(400f, 0, 2, 10)
        assertEquals(0f, top0, 0.01f)
        assertEquals(80f, len, 0.01f)
        val (topEnd, _) = StoreLayout.scrollThumb(400f, 8, 2, 10)
        assertEquals(320f, topEnd, 0.01f)
    }

    @Test fun `L1 and R1 cycle the tab row and wrap around`() {
        assertEquals(StoreTab.Collections, StoreTab.cycle(StoreTab.Latest, 1))
        assertEquals(StoreTab.Browse, StoreTab.cycle(StoreTab.Latest, -1))
        assertEquals(StoreTab.Latest, StoreTab.cycle(StoreTab.Browse, 1))
        // The wishlist is not in the row: it behaves like Browse.
        assertEquals(listOf(StoreTab.Latest, StoreTab.Collections, StoreTab.Updates, StoreTab.Installed, StoreTab.Browse), StoreTab.ROW)
    }

    @Test fun `tile status reads like a console store price line`() {
        val now = 1_000_000_000_000L
        assertEquals(TileStatus(TileStatus.Text.Free), TileStatus.of(PortAction.Free))
        assertEquals(TileStatus(TileStatus.Text.Size, size = 58_000_000), TileStatus.of(PortAction.Install, sizeBytes = 58_000_000))
        assertEquals(TileStatus(TileStatus.Text.Installed), TileStatus.of(PortAction.Installed))
        val update = TileStatus.of(PortAction.Update, installedTag = "v1.2", latestTag = "v1.3-android.4")
        assertEquals(TileStatus.Text.Update, update.text)
        assertEquals(TileStatus.Badge.Version("v1.3"), update.badge)
        assertEquals("v1.2", update.struck)
        assertEquals(TileStatus(TileStatus.Text.Downloading, percent = 42), TileStatus.of(PortAction.Install, progress = 0.42f))
        val day = 86_400_000L
        // Release in the last 14 days: UPDATED; older: nothing.
        assertEquals(TileStatus.Badge.Updated, TileStatus.of(PortAction.Free, publishedAt = now - day, now = now).badge)
        assertNull(TileStatus.of(PortAction.Free, publishedAt = now - 30 * day, now = now).badge)
        // Added to the catalog in the last 14 days: NEW, and NEW wins over UPDATED.
        assertEquals(TileStatus.Badge.New, TileStatus.of(PortAction.Free, addedAt = now - 3 * day, now = now).badge)
        assertEquals(TileStatus.Badge.New, TileStatus.of(PortAction.Install, publishedAt = now - day, addedAt = now - day, now = now).badge)
        // Added long ago (or seeded at 0): not new.
        assertNull(TileStatus.of(PortAction.Free, addedAt = now - 20 * day, now = now).badge)
        assertNull(TileStatus.of(PortAction.Free, addedAt = 0, now = now).badge)
        // Installed or updatable ports never show NEW.
        assertEquals(TileStatus(TileStatus.Text.Installed), TileStatus.of(PortAction.Installed, addedAt = now - day, now = now))
        assertEquals(TileStatus.Text.NotOnAndroid, TileStatus.of(PortAction.NotOnAndroid).text)
    }

    @Test fun `short tags fit the badge box`() {
        assertEquals("v9.3.1", TileStatus.shortTag("v9.3.1-ports.2"))
        assertEquals("v2.0", TileStatus.shortTag("2.0"))
        assertEquals("nightly", TileStatus.shortTag("nightly"))
        assertTrue(TileStatus.shortTag("v2025.11.09.1234567").length <= 10)
    }
}
