package com.elyndra.launcher.domain.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PortFirstSeenTest {

    @Test fun `the first catalog seeds every port as seen forever`() {
        assertEquals(mapOf("a" to 0L, "b" to 0L), PortFirstSeen.update(emptyMap(), listOf("a", "b"), now = 500))
    }

    @Test fun `later catalogs stamp only the new ids and drop the removed ones`() {
        val known = mapOf("a" to 0L, "b" to 100L)
        assertEquals(mapOf("b" to 100L, "c" to 900L), PortFirstSeen.update(known, listOf("b", "c"), now = 900))
    }

    @Test fun `an empty catalog changes nothing`() {
        val known = mapOf("a" to 1L)
        assertSame(known, PortFirstSeen.update(known, emptyList(), now = 9))
    }

    @Test fun `round trips through the settings text`() {
        val map = mapOf("soh" to 0L, "owner/repo=x" to 1_793_500_000_000L)
        assertEquals(map, PortFirstSeen.decode(PortFirstSeen.encode(map)))
        assertEquals(emptyMap<String, Long>(), PortFirstSeen.decode(null))
        assertEquals(mapOf("a" to 2L), PortFirstSeen.decode("a=2\nbroken\n=3\nb=x"))
    }
}
