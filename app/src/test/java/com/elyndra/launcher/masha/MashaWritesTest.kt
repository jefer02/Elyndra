package com.elyndra.launcher.masha

import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.metadata.MergedMetadata
import com.elyndra.launcher.metadata.MetaField
import com.elyndra.launcher.metadata.ScrapeApply
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.masha.offline.OfflineIntent
import com.elyndra.launcher.masha.offline.OfflineIntents
import com.elyndra.launcher.ui.IdentifyRules
import com.elyndra.launcher.ui.MashaLibraryJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MashaWritesTest {

    private var n = 0
    private val writes = MashaWrites { "w${++n}" }

    @Test
    fun aWriteRunsOnlyAfterConfirmationAndOnlyOnce() = runBlocking {
        var runs = 0
        val w = writes.propose(ConfirmKind.ADD_GAMES, listOf("A", "B")) {
            runs++
            WriteOutcome("＋ 2") { "added 2" }
        }
        assertTrue(writes.isPending(w.id))
        assertEquals(0, runs)
        assertEquals(w, writes.latest())
        assertEquals("＋ 2", writes.confirm(w.id)!!.label)
        assertEquals(1, runs)
        // Ya contestada: ni se repite ni sigue pendiente.
        assertNull(writes.confirm(w.id))
        assertFalse(writes.isPending(w.id))
        assertEquals(1, runs)
    }

    @Test
    fun cancelledWritesNeverRun() = runBlocking {
        var runs = 0
        val w = writes.propose(ConfirmKind.SET_EMULATOR, listOf("DuckStation", "PlayStation")) { runs++; WriteOutcome("x") }
        assertTrue(writes.cancel(w.id))
        assertNull(writes.confirm(w.id))
        assertEquals(0, runs)
        assertNull(writes.latest())
    }

    @Test
    fun undoGoesBackwardsAndIsBounded() = runBlocking {
        val log = ArrayList<String>()
        for (i in 1..3) {
            val w = writes.propose(ConfirmKind.RENAME, listOf("g$i", "n$i")) { WriteOutcome("r$i") { log += "undo $i"; "rename $i" } }
            writes.confirm(w.id)
        }
        writes.record("dictated") { log += "undo dictated"; "dictated" }
        assertEquals("dictated", writes.undoLast())
        assertEquals("rename 3", writes.undoLast())
        assertEquals(listOf("undo dictated", "undo 3"), log)
        repeat(MashaWrites.MAX_UNDO + 5) { writes.record("x$it") { "x$it" } }
        var count = 0
        while (writes.undoLast() != null) count++
        assertEquals(MashaWrites.MAX_UNDO, count)
        assertFalse(writes.canUndo)
    }

    @Test
    fun renameThroughMashaIsLockedAgainstSyncs() = runBlocking {
        var meta = GameMeta(scrapedAt = 1, name = "CUSA01715")
        val w = writes.propose(ConfirmKind.RENAME, listOf("CUSA01715", "Bloodborne")) {
            val before = meta
            meta = IdentifyRules.withUserName(meta, "Bloodborne")
            WriteOutcome("✎") { meta = before; "rename" }
        }
        writes.confirm(w.id)
        assertEquals("Bloodborne", meta.lockedName)
        // Una pasada de metadatos con otro nombre no lo pisa.
        val merged = MergedMetadata(
            text = mapOf(MetaField.Name to "Other"), rating = null, art = emptyMap(), sources = listOf(Service.Steam),
            ssId = null, igdbId = null, sgdbId = null, ra = null, matchedBy = "name", confidence = 0.9f,
        )
        meta = ScrapeApply.apply(meta, merged, "es", ScrapeApply.Art(), 2)
        assertEquals("Bloodborne", meta.lockedName)
        // Y se puede deshacer.
        writes.undoLast()
        assertNull(meta.lockedName)
    }
}

class MashaWriteRulesTest {

    @Test
    fun namesAreValidated() {
        assertEquals("Bloodborne", MashaWriteRules.cleanName("  «Bloodborne»  "))
        assertEquals("God of War III", MashaWriteRules.cleanName("God   of War III"))
        assertNull(MashaWriteRules.cleanName(""))
        assertNull(MashaWriteRules.cleanName(null))
        assertNull(MashaWriteRules.cleanName("CUSA01715"))
        assertNull(MashaWriteRules.cleanName("com.studio.game"))
        assertNull(MashaWriteRules.cleanName("x".repeat(200)))
    }

    @Test
    fun batchesAreCappedAt50() {
        val (taken, skipped) = MashaWriteRules.cap((1..120).toList())
        assertEquals(50, taken.size)
        assertEquals(70, skipped.size)
        assertEquals(1, taken.first())
        assertEquals(51, skipped.first())
        assertEquals(3 to 0, MashaWriteRules.cap(listOf(1, 2, 3)).let { it.first.size to it.second.size })
    }

    @Test
    fun writtenYesAndNoAreUnderstood() {
        for (y in listOf("sí", "Si.", "vale", "OK", "yes!", "confirmo", "sim", "oui", "ja", "はい")) assertEquals(y, true, MashaWriteRules.answer(y))
        for (n in listOf("no", "Cancela", "mejor no", "cancel", "não", "non", "nein")) assertEquals(n, false, MashaWriteRules.answer(n))
        // Una frase cualquiera no es una respuesta: va a la IA.
        assertNull(MashaWriteRules.answer("sí, pero añade también Zelda"))
        assertNull(MashaWriteRules.answer("¿qué juegos tengo?"))
    }
}

class MashaNewToolsTest {

    private val names = MashaTools.specs.map { it.name }.toSet()

    @Test
    fun newToolsAreDeclaredWithTheirArguments() {
        for (t in listOf(
            MashaTools.LIST_UNNAMED_GAMES, MashaTools.RENAME_GAME, MashaTools.LIST_ADDABLE_GAMES, MashaTools.ADD_GAMES,
            MashaTools.OPEN_ADD_ROMS, MashaTools.LIST_EMULATORS, MashaTools.SET_PREFERRED_EMULATOR, MashaTools.UNDO_LAST_ACTION,
        )) assertTrue(t, t in names)
        val rename = MashaTools.specs.first { it.name == MashaTools.RENAME_GAME }.parameters
        assertEquals(setOf("game", "name"), rename["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertTrue("user_dictated" in rename["properties"]!!.jsonObject)
        val emu = MashaTools.specs.first { it.name == MashaTools.SET_PREFERRED_EMULATOR }.parameters
        assertEquals(setOf("system", "emulator"), emu["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun writeToolsAreMutatingAndReadToolsAreNot() {
        assertTrue(MashaTools.MUTATING.containsAll(listOf(MashaTools.RENAME_GAME, MashaTools.ADD_GAMES, MashaTools.SET_PREFERRED_EMULATOR, MashaTools.UNDO_LAST_ACTION)))
        assertFalse(MashaTools.LIST_UNNAMED_GAMES in MashaTools.MUTATING)
        assertFalse(MashaTools.LIST_ADDABLE_GAMES in MashaTools.MUTATING)
        assertFalse(MashaTools.LIST_EMULATORS in MashaTools.MUTATING)
        // Ninguna herramienta nueva borra: no hay "delete" ni "remove" entre ellas.
        val newOnes = MashaTools.specs.filter { it.name in setOf(MashaTools.RENAME_GAME, MashaTools.ADD_GAMES, MashaTools.SET_PREFERRED_EMULATOR) }
        assertTrue(newOnes.none { "delete" in it.name || "remove" in it.name })
    }

    @Test
    fun theNewToolResultsCarryOnlyNames() {
        val json = listOf(
            MashaLibraryJson.unnamed(listOf(MashaLibraryJson.Unnamed("SLUS 012.34", "PlayStation", "Slus 012.34"))),
            MashaLibraryJson.apps(listOf(MashaLibraryJson.App("8 Ball Pool", "com.miniclip.eightballpool", true))),
            MashaLibraryJson.roms(listOf(MashaLibraryJson.Rom("Crash Bandicoot", "PlayStation", removedBefore = true))),
            MashaLibraryJson.emulators(listOf(MashaLibraryJson.Emulator("DuckStation", listOf("PlayStation"), listOf("PlayStation")))),
        ).joinToString { it.toString() }
        assertFalse("content://" in json)
        assertFalse("primary:" in json)
        assertFalse("file://" in json)
        assertFalse(".iso" in json || ".cue" in json || ".chd" in json)
        assertFalse("/storage" in json || "/sdcard" in json)
        assertFalse("password" in json.lowercase() || "key" in json.lowercase().replace("is_game", ""))
    }

    @Test
    fun emulatorLookalikesAreSpotted() {
        assertTrue(MashaLibraryJson.looksLikeEmulator("NetherSX2", "xyz.aethersx2.android"))
        assertTrue(MashaLibraryJson.looksLikeEmulator("Eden", "dev.eden.eden_emulator"))
        assertFalse(MashaLibraryJson.looksLikeEmulator("8 Ball Pool", "com.miniclip.eightballpool"))
    }
}

class MashaOfflineWritesTest {

    @Test
    fun addInstalledAndRenameAreUnderstoodOffline() {
        assertEquals(OfflineIntent.AddInstalled, OfflineIntents.detect("Añade los juegos que tengo instalados"))
        assertEquals(OfflineIntent.AddInstalled, OfflineIntents.detect("add the games I have installed"))
        assertEquals(OfflineIntent.AddInstalled, OfflineIntents.detect("agrega los juegos descargados"))
        assertEquals(OfflineIntent.Rename("Bloodborne"), OfflineIntents.detect("llama a este juego Bloodborne"))
        assertEquals(OfflineIntent.Rename("God of War III"), OfflineIntents.detect("ponle de nombre «God of War III»"))
        assertEquals(OfflineIntent.Rename("Halo 3"), OfflineIntents.detect("rename this game to Halo 3"))
        // Lanzar sigue siendo lanzar.
        assertEquals(OfflineIntent.Launch("Crash Bandicoot"), OfflineIntents.detect("juega Crash Bandicoot"))
    }
}
