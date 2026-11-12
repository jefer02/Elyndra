package com.elyndra.launcher.ui.masha.live

import com.elyndra.launcher.ui.masha.live.MicLock.State
import com.elyndra.launcher.ui.masha.live.MicLock.Tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El candado del micrófono: siempre en vivo salvo bloqueo o sin permiso; el permiso se pide una vez. */
class MicLockTest {

    @Test
    fun byDefaultLucysScreenIsAlwaysLive() {
        assertEquals(State.Live, MicLock.state(userLocked = false, permission = true, asked = true))
        assertTrue(MicLock.shouldListen(State.Live))
    }

    @Test
    fun aUserWhoBlockedTheMicIsNotRecordedJustByOpeningTheScreen() {
        assertEquals(State.Locked, MicLock.state(userLocked = true, permission = true, asked = true))
        assertFalse(MicLock.shouldListen(State.Locked))
    }

    @Test
    fun thePermissionIsAskedOnlyOnTheFirstVisit() {
        assertEquals(State.AskPermission, MicLock.state(userLocked = false, permission = false, asked = false))
        // Denegado: bloqueado con la pista, sin volver a preguntar.
        assertEquals(State.NoPermission, MicLock.state(userLocked = false, permission = false, asked = true))
        assertFalse(MicLock.shouldListen(State.NoPermission))
    }

    @Test
    fun tappingTogglesTheLockOrAsksForThePermission() {
        assertEquals(Tap.Lock, MicLock.tap(State.Live))
        assertEquals(Tap.Unlock, MicLock.tap(State.Locked))
        assertEquals(Tap.RequestPermission, MicLock.tap(State.NoPermission))
    }
}
