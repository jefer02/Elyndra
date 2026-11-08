package com.elyndra.launcher.ui

import com.elyndra.launcher.ui.DockNav.Stop
import org.junit.Assert.assertEquals
import org.junit.Test

class DockNavTest {

    private val filters = listOf(LibraryFilter.All, LibraryFilter.Android, LibraryFilter.Consoles)

    @Test fun `the store comes after the last section`() {
        assertEquals(
            listOf(Stop.Filter(LibraryFilter.All), Stop.Filter(LibraryFilter.Android), Stop.Filter(LibraryFilter.Consoles), Stop.Ports),
            DockNav.stops(filters),
        )
        assertEquals(Stop.Ports, DockNav.next(filters, Stop.Filter(LibraryFilter.Consoles), 1))
    }

    @Test fun `from the store r1 wraps to the first section and l1 goes back to the last`() {
        assertEquals(Stop.Filter(LibraryFilter.All), DockNav.next(filters, Stop.Ports, 1))
        assertEquals(Stop.Filter(LibraryFilter.Consoles), DockNav.next(filters, Stop.Ports, -1))
    }

    @Test fun `l1 on the first section reaches the store`() {
        assertEquals(Stop.Ports, DockNav.next(filters, Stop.Filter(LibraryFilter.All), -1))
        assertEquals(Stop.Filter(LibraryFilter.Android), DockNav.next(filters, Stop.Filter(LibraryFilter.All), 1))
    }

    @Test fun `an unknown section starts from the first`() {
        assertEquals(Stop.Filter(LibraryFilter.Android), DockNav.next(filters, Stop.Filter(LibraryFilter.Unnamed), 1))
    }

    @Test fun `with the unnamed section shown the store is still last`() {
        val withUnnamed = filters + LibraryFilter.Unnamed
        assertEquals(Stop.Ports, DockNav.next(withUnnamed, Stop.Filter(LibraryFilter.Unnamed), 1))
        assertEquals(5, DockNav.stops(withUnnamed).size)
    }
}
