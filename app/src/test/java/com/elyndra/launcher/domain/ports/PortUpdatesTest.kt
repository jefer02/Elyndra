package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.domain.ports.AutoUpdatePolicy.Route
import com.elyndra.launcher.domain.ports.SignatureCheck.Decision
import com.elyndra.launcher.domain.ports.SignatureCheck.Signers
import com.elyndra.launcher.update.ReleaseAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortUpdatesTest {

    private fun release(tag: String, at: Long = 200) = PortRelease(tag, tag, "", "", false, false, at, emptyList())
    private fun installed(id: String, tag: String, at: Long = 100, notified: String? = null, blocked: String? = null, latest: String? = null) =
        InstalledPort(id, "com.$id", tag, at, latestTag = latest, latestPublishedAt = 300, notifiedTag = notified, blockedTag = blocked)

    @Test fun `a newer release is pending and an equal or older one is not`() {
        val list = listOf(installed("a", "v1.0"), installed("b", "v2.0"), installed("c", "v1.0"))
        val pending = PortUpdates.pending(list, mapOf("a" to release("v1.1"), "b" to release("v2.0"), "c" to null))
        assertEquals(listOf(PendingUpdate("a", "com.a", "v1.1", 200)), pending)
    }

    @Test fun `a release blocked by a key change is never pending`() {
        val list = listOf(installed("a", "v1.0", blocked = "v1.1"))
        assertTrue(PortUpdates.pending(list, mapOf("a" to release("v1.1"))).isEmpty())
        // Una release posterior a la bloqueada sí se ofrece (puede volver a la clave buena).
        assertEquals(1, PortUpdates.pending(list, mapOf("a" to release("v1.2"))).size)
    }

    @Test fun `each tag is notified once`() {
        val list = listOf(installed("a", "v1.0", notified = "v1.1"), installed("b", "v1.0"))
        val pending = PortUpdates.pending(list, mapOf("a" to release("v1.1"), "b" to release("v1.1")))
        assertEquals(listOf("b"), PortUpdates.toNotify(pending, list).map { it.portId })
        // Una etiqueta nueva de "a" vuelve a avisar.
        val newer = PortUpdates.pending(list, mapOf("a" to release("v1.2")))
        assertEquals(listOf("a"), PortUpdates.toNotify(newer, list).map { it.portId })
    }

    @Test fun `the daily check result is known without asking github`() {
        val list = listOf(installed("a", "v1.0", latest = "v1.1"), installed("b", "v1.0", latest = "v1.0"), installed("c", "v1.0", latest = "v2", blocked = "v2"))
        assertEquals(listOf("a"), PortUpdates.known(list).map { it.portId })
    }

    @Test fun `silent updates need automatic mode android 12 and elyndra as installer`() {
        assertEquals(Route.Silent, AutoUpdatePolicy.route(PortUpdateMode.Automatic, 31, installedByElyndra = true, foreground = false))
        assertEquals(Route.Silent, AutoUpdatePolicy.route(PortUpdateMode.Automatic, 35, installedByElyndra = true, foreground = true))
        // Sin alguna de las tres: el diálogo si el usuario está delante; si no, un aviso.
        assertEquals(Route.Notify, AutoUpdatePolicy.route(PortUpdateMode.Automatic, 30, installedByElyndra = true, foreground = false))
        assertEquals(Route.Notify, AutoUpdatePolicy.route(PortUpdateMode.Automatic, 34, installedByElyndra = false, foreground = false))
        assertEquals(Route.Prompt, AutoUpdatePolicy.route(PortUpdateMode.Automatic, 34, installedByElyndra = false, foreground = true))
        assertEquals(Route.Notify, AutoUpdatePolicy.route(PortUpdateMode.NotifyOnly, 35, installedByElyndra = true, foreground = false))
        assertEquals(Route.Prompt, AutoUpdatePolicy.route(PortUpdateMode.NotifyOnly, 35, installedByElyndra = true, foreground = true))
    }

    @Test fun `the same key updates and a rotated key with history updates`() {
        assertEquals(Decision.Same, SignatureCheck.decide(Signers(setOf("AA")), Signers(setOf("aa"))))
        assertEquals(Decision.Rotated, SignatureCheck.decide(Signers(setOf("old")), Signers(setOf("new"), history = listOf("old", "new"))))
        assertEquals(Decision.NotInstalled, SignatureCheck.decide(null, Signers(setOf("x"))))
        assertTrue(SignatureCheck.allowsUpdate(Decision.Rotated))
    }

    @Test fun `a different key is a mismatch and never updates`() {
        val d = SignatureCheck.decide(Signers(setOf("debug-key")), Signers(setOf("release-key"), history = listOf("release-key")))
        assertEquals(Decision.Mismatch, d)
        assertFalse(SignatureCheck.allowsUpdate(d))
        // Varios firmantes: tienen que ser los mismos, todos.
        assertEquals(Decision.Mismatch, SignatureCheck.decide(Signers(setOf("a", "b")), Signers(setOf("a"))))
    }

    @Test fun `unreadable signatures are unknown and left to android`() {
        assertEquals(Decision.Unknown, SignatureCheck.decide(Signers(setOf("a")), null))
        assertTrue(SignatureCheck.allowsUpdate(Decision.Unknown))
    }

    @Test fun `a blocked tag shows as signature changed in the store`() {
        val port = PortEntry("a", "A", repo = GhRepo("o", "r"), game = PortGame("G", "n64"), builds = listOf(PortBuild("android")))
        val latest = Installable(release("v1.1"), ReleaseAsset("a.apk", "https://github.com/x", 1))
        assertEquals(PortAction.SignatureChanged, PortStatus.action(port, installed("a", "v1.0", blocked = "v1.1"), latest, releasesKnown = true))
        assertEquals(PortAction.Update, PortStatus.action(port, installed("a", "v1.0"), latest, releasesKnown = true))
    }
}
