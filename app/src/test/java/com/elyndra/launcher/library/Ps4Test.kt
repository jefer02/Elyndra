package com.elyndra.launcher.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * PS4: PARAM.SFO, cabecera de .pkg, qué carpeta es un juego y cómo se juntan
 * bases, actualizaciones, DLC y paquetes. Todo con bytes sintéticos.
 */
class Ps4Test {

    /* ── constructores de bytes ───────────────────────────────── */

    /** Un PARAM.SFO con estas claves: texto (0x0204) salvo los Int, que van como 0x0404. */
    private fun sfo(values: Map<String, Any>): ByteArray {
        val keys = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        val entries = ByteArrayOutputStream()
        for ((k, v) in values) {
            val keyOffset = keys.size()
            keys.write(k.toByteArray(Charsets.US_ASCII)); keys.write(0)
            val dataOffset = data.size()
            val (fmt, bytes, max) = when (v) {
                is Int -> Triple(0x0404, le32(v), 4)
                else -> {
                    val b = v.toString().toByteArray(Charsets.UTF_8) + 0
                    val max = ((b.size + 3) / 4) * 4 + 4
                    Triple(0x0204, b + ByteArray(max - b.size), max)
                }
            }
            data.write(bytes)
            entries.write(le16(keyOffset)); entries.write(le16(fmt))
            entries.write(le32(if (v is Int) 4 else v.toString().toByteArray(Charsets.UTF_8).size + 1))
            entries.write(le32(max)); entries.write(le32(dataOffset))
        }
        while (keys.size() % 4 != 0) keys.write(0)
        val keyTable = 0x14 + entries.size()
        val dataTable = keyTable + keys.size()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte()))
        out.write(le32(0x101)); out.write(le32(keyTable)); out.write(le32(dataTable)); out.write(le32(values.size))
        out.write(entries.toByteArray()); out.write(keys.toByteArray()); out.write(data.toByteArray())
        return out.toByteArray()
    }

    /** Los primeros 0x80 bytes de un .pkg: magia, Content ID, tipo y flags (big-endian). */
    private fun pkgHeader(contentId: String, type: Int = 0x1A, flags: Int = 0): ByteArray {
        val b = ByteArray(0x80)
        b[0] = 0x7F; b[1] = 'C'.code.toByte(); b[2] = 'N'.code.toByte(); b[3] = 'T'.code.toByte()
        contentId.toByteArray(Charsets.US_ASCII).copyInto(b, 0x40)
        be32(type).copyInto(b, 0x74)
        be32(flags).copyInto(b, 0x78)
        return b
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
    private fun be32(v: Int) = byteArrayOf(((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun params(id: String, category: String, title: String = "Juego $id", ver: String = "01.00") =
        Sfo.Params(mapOf("TITLE_ID" to id, "CATEGORY" to category, "TITLE" to title, "APP_VER" to ver))

    private fun dir(name: String, p: Sfo.Params, eboot: Boolean = true) =
        Ps4.GameDir("doc:$name", name, name, 10, eboot, p)

    private fun pkg(name: String, contentId: String, type: Int = 0x1A, flags: Int = 0) =
        Ps4.PkgFile("doc:$name", name, 1000, 5, PkgHeader.parse(pkgHeader(contentId, type, flags)))

    /* ── PARAM.SFO ────────────────────────────────────────────── */

    @Test
    fun parsesTitleIdVersionAndCategory() {
        val p = Sfo.parse(sfo(mapOf("APP_VER" to "01.09", "CATEGORY" to "gd", "TITLE" to "Bloodborne™", "TITLE_ID" to "CUSA00900", "PARENTAL_LEVEL" to 5)))!!
        assertEquals("Bloodborne™", p.title)
        assertEquals("CUSA00900", p.titleId)
        assertEquals("01.09", p.appVer)
        assertEquals("gd", p.category)
        assertEquals("5", p.values["PARENTAL_LEVEL"])
    }

    @Test
    fun rejectsWhatIsNotAnSfo() {
        assertNull(Sfo.parse(ByteArray(0)))
        assertNull(Sfo.parse(ByteArray(0x40)))
        val broken = sfo(mapOf("TITLE" to "X")).also { it[1] = 'Q'.code.toByte() }
        assertNull(Sfo.parse(broken))
        // Truncado: las entradas se salen del archivo.
        assertNull(Sfo.parse(sfo(mapOf("TITLE" to "X", "TITLE_ID" to "CUSA00001")).copyOf(0x20)))
    }

    /* ── cabecera del .pkg ────────────────────────────────────── */

    @Test
    fun readsTheSerialFromTheContentId() {
        val info = PkgHeader.parse(pkgHeader("UP9000-CUSA00900_00-BLOODBORNE000000"))!!
        assertEquals("CUSA00900", info.titleId)
        assertEquals("UP9000-CUSA00900_00-BLOODBORNE000000", info.contentId)
        assertEquals(PkgHeader.Kind.Game, info.kind)
    }

    @Test
    fun tellsPatchesAndDlcApart() {
        assertEquals(PkgHeader.Kind.Patch, PkgHeader.parse(pkgHeader("UP9000-CUSA00900_00-BLOODBORNE000000", flags = 0x00100000))!!.kind)
        assertEquals(PkgHeader.Kind.Patch, PkgHeader.parse(pkgHeader("UP9000-CUSA00900_00-BLOODBORNE000000", flags = 0x60000000))!!.kind)
        assertEquals(PkgHeader.Kind.Addon, PkgHeader.parse(pkgHeader("UP9000-CUSA00900_00-THEOLDHUNTERS000", type = 0x1B))!!.kind)
    }

    @Test
    fun onlyTheHeaderIsNeededAndBadOnesAreRejected() {
        assertNull(PkgHeader.parse(ByteArray(0x7F)))
        assertNull(PkgHeader.parse(ByteArray(0x80)))
        assertNull(PkgHeader.parse(pkgHeader("NOT-A-CONTENT-ID")))
        assertEquals(0x80, PkgHeader.HEADER_BYTES)
    }

    /* ── qué carpeta es un juego ──────────────────────────────── */

    @Test
    fun detectsAGameByItsContent() {
        assertTrue(Ps4.looksLikeGameDir(listOf("sce_sys" to true, "eboot.bin" to false, "sce_module" to true)))
        assertTrue(Ps4.looksLikeGameDir(listOf("SCE_SYS" to true, "EBOOT.BIN" to false)))
        assertFalse(Ps4.looksLikeGameDir(listOf("sce_sys" to true)))
        assertFalse(Ps4.looksLikeGameDir(listOf("eboot.bin" to false)))
    }

    @Test
    fun ignoresHiddenAndBachataTemporaryFolders() {
        assertTrue(Ps4.isIgnoredDir(".bachata-import-0b8e7c2a-1111-2222-3333-444455556666"))
        assertTrue(Ps4.isIgnoredDir(".staging-42"))
        assertTrue(Ps4.isIgnoredDir(".bachata-write-check-1"))
        assertTrue(Ps4.isIgnoredDir(".hidden"))
        assertFalse(Ps4.isIgnoredDir("CUSA00900"))
        assertFalse(Ps4.isIgnoredDir("Bloodborne"))
    }

    @Test
    fun titleIdsAreFourLettersAndFiveDigits() {
        assertTrue(Ps4.isTitleId("CUSA00900"))
        assertTrue(Ps4.isTitleId("PLAS10001"))
        assertFalse(Ps4.isTitleId("cusa00900"))
        assertFalse(Ps4.isTitleId("CUSA0090"))
        assertFalse(Ps4.isTitleId(null))
        assertEquals("CUSA08790", Ps4.titleIdOfContentId("EP0002-CUSA08790_00-CODBO4000000000"))
        assertNull(Ps4.titleIdOfContentId("short"))
    }

    /* ── bases, actualizaciones, DLC y paquetes ───────────────── */

    @Test
    fun anExtractedGameHidesItsPkg() {
        val r = Ps4.resolve(
            listOf(dir("CUSA00900", params("CUSA00900", "gd", "Bloodborne"))),
            listOf(pkg("Bloodborne.pkg", "UP9000-CUSA00900_00-BLOODBORNE000000")),
        )
        assertEquals(listOf("Bloodborne"), r.games.map { it.title })
        assertEquals("CUSA00900", r.games.single().titleId)
        assertTrue(r.notInstalled.isEmpty())
    }

    @Test
    fun aPkgWithoutItsGameIsNotPlayableButCounted() {
        // El nombre del archivo no importa: casa por el CUSA de la cabecera.
        val r = Ps4.resolve(
            listOf(dir("Bloodborne", params("CUSA00900", "gd"))),
            listOf(
                pkg("bloodborne-renamed.pkg", "UP9000-CUSA00900_00-BLOODBORNE000000"),
                pkg("CUSA00900.pkg", "EP0002-CUSA08790_00-OTHERGAME000000"),
                pkg("again.pkg", "EP0002-CUSA08790_00-OTHERGAME000000"),
            ),
        )
        assertEquals(1, r.games.size)
        assertEquals(listOf("CUSA08790"), r.notInstalled.map { it.info!!.titleId })
    }

    @Test
    fun separateUpdatesJoinTheirBaseAndRaiseTheVersion() {
        val r = Ps4.resolve(
            listOf(
                dir("CUSA00900", params("CUSA00900", "gd", ver = "01.00")),
                dir("CUSA00900-UPDATE", params("CUSA00900", "gp", ver = "01.09"), eboot = true),
            ),
            emptyList(),
        )
        val game = r.games.single()
        assertEquals("doc:CUSA00900", game.base.docId)
        assertEquals(listOf("doc:CUSA00900-UPDATE"), game.updates.map { it.docId })
        assertEquals("01.09", game.appVer)
    }

    @Test
    fun anUpdateWithoutBaseAndDlcAreNotGames() {
        val r = Ps4.resolve(
            listOf(
                dir("CUSA11111-UPDATE", params("CUSA11111", "gp")),
                dir("CUSA00900-DLC", params("CUSA00900", "ac"), eboot = false),
            ),
            listOf(
                pkg("patch.pkg", "UP9000-CUSA22222_00-PATCH00000000000", flags = 0x00100000),
                pkg("dlc.pkg", "UP9000-CUSA22222_00-DLC0000000000000", type = 0x1B),
            ),
        )
        assertTrue(r.games.isEmpty())
        // Un parche o un DLC suelto no se ofrece como juego sin instalar.
        assertTrue(r.notInstalled.isEmpty())
    }

    @Test
    fun anUpdateMergedByBachataIsTheGame() {
        // Lo que dejó Bachata al instalar God of War III Remastered con su 1.02:
        // la carpeta del juego, con eboot.bin, pero con el PARAM.SFO del parche.
        val r = Ps4.resolve(
            listOf(dir("CUSA01715", params("CUSA01715", "gp", "God of War® III Remastered", "01.02"))),
            listOf(pkg("hako-gow3r.v1.02.update-01715.pkg", "EP9000-CUSA01715_00-0000GODOFWAR3PS4", flags = 0x00100000)),
        )
        val game = r.games.single()
        assertEquals("God of War® III Remastered", game.title)
        assertEquals("CUSA01715", game.titleId)
        assertEquals("01.02", game.appVer)
        // Su .pkg de actualización no es un juego sin instalar.
        assertTrue(r.notInstalled.isEmpty())
    }

    @Test
    fun aLoneShadPs4UpdateFolderIsStillNotAGame() {
        val r = Ps4.resolve(listOf(dir("CUSA01715-UPDATE", params("CUSA01715", "gp"))), emptyList())
        assertTrue(r.games.isEmpty())
        assertTrue(Ps4.isSeparateUpdateFolder("CUSA00900-patch"))
        assertFalse(Ps4.isSeparateUpdateFolder("CUSA01715"))
    }

    @Test
    fun mergedUpdatesNeedNoGrouping() {
        // Bachata funde la actualización en la carpeta del juego: queda una base con la versión nueva.
        val r = Ps4.resolve(listOf(dir("Bloodborne", params("CUSA00900", "gd", ver = "01.09"))), emptyList())
        assertEquals("01.09", r.games.single().appVer)
        assertTrue(r.games.single().updates.isEmpty())
    }

    @Test
    fun aBaseWithoutEbootOrWithABadIdIsSkippedAndDuplicatesCollapse() {
        val r = Ps4.resolve(
            listOf(
                dir("A", params("CUSA00001", "gd"), eboot = false),
                dir("B", params("bad", "gd")),
                dir("C1", params("CUSA00003", "gd", title = "Primero")),
                dir("C2", params("CUSA00003", "gd", title = "Copia")),
            ),
            emptyList(),
        )
        assertEquals(listOf("Primero"), r.games.map { it.title })
    }

    @Test
    fun versionsCompareByNumber() {
        assertTrue(Ps4.compareVersions("01.10", "01.09") > 0)
        assertTrue(Ps4.compareVersions("1.2", "01.02") == 0)
        assertTrue(Ps4.compareVersions("02.00", "10.00") < 0)
    }

    @Test
    fun categories() {
        assertEquals(Ps4.Category.Base, Ps4.categoryOf("gd"))
        assertEquals(Ps4.Category.Patch, Ps4.categoryOf("gp"))
        assertEquals(Ps4.Category.Addon, Ps4.categoryOf("ac"))
        assertEquals(Ps4.Category.Base, Ps4.categoryOf(null))
        assertEquals(Ps4.Category.Other, Ps4.categoryOf("sd"))
    }
}
