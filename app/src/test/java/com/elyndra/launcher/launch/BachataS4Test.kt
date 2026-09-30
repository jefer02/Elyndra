package com.elyndra.launcher.launch

import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.data.Systems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El lanzamiento en Bachata S4: directo por CUSA y, si no se puede, su pantalla principal. */
class BachataS4Test {

    private val pkg = "com.bachatas4.android.github"

    @Test
    fun aValidCusaLaunchesTheGameDirectly() {
        val plan = BachataS4.plan(pkg, "CUSA00900", directAvailable = true)
        plan as BachataS4.Plan.Direct
        assertEquals(pkg, plan.spec.packageName)
        assertEquals("com.bachatas4.android.DirectLaunchActivity", plan.spec.className)
        assertEquals(mapOf("game_id" to "CUSA00900"), plan.spec.stringExtras)
        assertNull(plan.spec.action)
        assertNull(plan.spec.data)
        assertTrue(plan.spec.grantUris.isEmpty())
    }

    @Test
    fun anInvalidIdOpensBachataInstead() {
        for (id in listOf(null, "", "cusa00900", "CUSA0090", "Bloodborne")) {
            val plan = BachataS4.plan(pkg, id, directAvailable = true)
            plan as BachataS4.Plan.Fallback
            assertEquals(BachataS4.Reason.InvalidId, plan.reason)
            assertEquals("com.bachatas4.android.MainActivity", plan.spec.className)
            assertEquals(Emulators.MAIN, plan.spec.action)
            assertEquals("android.intent.category.LAUNCHER", plan.spec.category)
            assertTrue(plan.spec.stringExtras.isEmpty())
        }
    }

    @Test
    fun withoutTheDirectActivityItFallsBack() {
        val plan = BachataS4.plan("com.bachatas4.android", "CUSA00900", directAvailable = false)
        plan as BachataS4.Plan.Fallback
        assertEquals(BachataS4.Reason.NoDirectActivity, plan.reason)
        assertEquals("com.bachatas4.android", plan.spec.packageName)
    }

    @Test
    fun theProfileCoversBothPackagesAndIsNotAnAndroidGame() {
        val profile = Emulators.byId(BachataS4.PROFILE_ID)!!
        assertEquals(listOf("com.bachatas4.android.github", "com.bachatas4.android"), profile.packages)
        // Los dos paquetes quedan fuera de "Juegos Android" (AppCatalog usa esta lista).
        assertTrue("com.bachatas4.android.github" in Emulators.emulatorOnlyPackages)
        assertTrue("com.bachatas4.android" in Emulators.emulatorOnlyPackages)
        assertEquals(listOf(BachataS4.PROFILE_ID), Systems.byId("ps4")!!.emulators)
    }

    @Test
    fun thePlannerSendsTheSerialAsGameId() {
        val profile = Emulators.byId(BachataS4.PROFILE_ID)!!
        val ref = RomRef(safUri = "content://x", providerUri = "content://y", path = null, isDirectory = true, serial = "CUSA00900")
        val ok = LaunchPlanner.plan(profile, profile.components.first(), ref) as PlanResult.Ok
        assertEquals("CUSA00900", ok.spec.stringExtras["game_id"])
        assertEquals("com.bachatas4.android.DirectLaunchActivity", ok.spec.className)
        // Sin serial válido el planificador no inventa un id.
        assertEquals(PlanResult.NeedsTitleId, LaunchPlanner.plan(profile, profile.components.first(), ref.copy(serial = "bad")))
    }

    @Test
    fun ps4FoldersAreDetectedByName() {
        assertEquals("ps4", Systems.detect("PS4")?.id)
        assertEquals("ps4", Systems.detect("PlayStation 4")?.id)
        // El nombre con el que Bachata y mucha gente guardan los juegos.
        assertEquals("ps4", Systems.detect("play 4")?.id)
        assertEquals("ps4", Systems.detect("PS4 Games")?.id)
    }
}
