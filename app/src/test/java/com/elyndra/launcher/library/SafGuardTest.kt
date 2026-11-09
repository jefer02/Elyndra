package com.elyndra.launcher.library

import android.content.ContentResolver
import com.elyndra.launcher.domain.ports.PortPlacement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Una carpeta virtual (la de los ports de un sistema sin carpeta) no llega
 * nunca a SAF: cada entrada de SafFiles la rechaza antes de tocar el
 * ContentResolver, y ninguna entrada de SafFiles ni de RomScanner puede
 * saltarse SafGuard. Si alguien añade una entrada sin la guarda, o saca el
 * árbol de una carpeta a mano fuera de SafGuard, esta prueba falla.
 */
class SafGuardTest {

    private val rejected = mutableListOf<String>()
    private val virtual = PortPlacement.virtualFolder("switch", now = 0)

    @Before fun capture() {
        SafGuard.onReject = { rejected += it }
    }

    @After fun restore() {
        SafGuard.onReject = {}
    }

    /** Un resolver que falla si se le llama: con la guarda, nunca se le llama. */
    private val resolver = object : ContentResolver(null) {}

    @Test fun `virtual folders and empty trees are refused`() {
        assertTrue(virtual.isVirtual)
        assertFalse(SafGuard.allows(virtual))
        assertNull(SafGuard.tree(virtual))
        assertFalse(SafGuard.allows(""))
        assertFalse(SafGuard.allows(null as String?))
        assertFalse(SafGuard.allows("file:///sdcard/Roms"))
        assertTrue(SafGuard.allows("content://com.android.externalstorage.documents/tree/primary%3ARoms"))
        assertEquals(5, rejected.size)
    }

    @Test fun `every SafFiles entry point refuses a virtual folder before touching the resolver`() {
        val files = SafFiles(resolver)
        val tree = virtual.treeUri
        var touched = false
        assertNull(files.docUri(tree, "x"))
        assertNull(files.openStream(tree, "x") { touched = true })
        assertNull(files.openRandom(tree, "x") { touched = true })
        assertFalse(files.hasPermission(tree))
        files.releasePermission(tree)
        assertFalse(touched)
        assertEquals(5, rejected.size)
    }

    private val main = File("src/main/java/com/elyndra/launcher")

    /** Las funciones públicas de [file] que reciben un árbol, con su cuerpo hasta la siguiente función. */
    private fun entryPoints(file: File): Map<String, String> {
        val text = file.readText()
        val fn = Regex("""\n {4}(?:suspend )?fun (?:<[^>]+> )?(\w+)\(([^)]*)\)""")
        val matches = fn.findAll(text).toList()
        return matches.mapIndexedNotNull { i, m ->
            if (!Regex("""\btreeUri: (String|Uri)\b""").containsMatchIn(m.groupValues[2])) return@mapIndexedNotNull null
            val end = matches.getOrNull(i + 1)?.range?.first ?: text.length
            m.groupValues[1] to text.substring(m.range.last, end)
        }.toMap()
    }

    @Test fun `no SAF entry point can skip the guard`() {
        val files = entryPoints(File(main, "library/SafFiles.kt"))
        val scanner = entryPoints(File(main, "library/RomScanner.kt"))
        assertTrue(files.keys.containsAll(listOf("docUri", "openStream", "openRandom", "hasPermission", "takePermission", "releasePermission")))
        assertTrue(scanner.keys.containsAll(listOf("children", "subfolders", "readSmallText", "scan", "scanPs4", "looksLikePs4", "readHead", "ps4SystemFile")))
        for ((name, body) in files + scanner) {
            // La guarda va antes que nada: en las primeras líneas del cuerpo.
            val head = body.lineSequence().take(4).joinToString("\n")
            assertTrue("$name doesn't start with SafGuard", head.contains("SafGuard.allows(treeUri"))
        }
    }

    @Test fun `nothing outside the SAF helpers parses a folder tree by hand`() {
        val offenders = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in setOf("SafGuard.kt", "SafFiles.kt", "RomScanner.kt") }
            .filter { Regex("""Uri\.parse\(\w+\.treeUri\)""").containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertEquals("use SafGuard.tree(folder) instead", emptyList<String>(), offenders)
    }
}
