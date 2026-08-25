package com.rtbishop.look4sat.core.domain.wavelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LotwSatelliteIdsTest {

    /**
     * Every name the table produces has to exist in the LoTW list verbatim, because LoTW
     * rejects a QSO whose SAT_NAME is spelled differently - AO7 for AO-7 is refused.
     */
    @Test
    fun `every mapped name is spelled exactly as LoTW has it`() {
        val known = LotwSatellites.names
        for (catnum in catnums) {
            val name = LotwSatelliteIds.nameFor(catnum)
            assertTrue("$catnum maps to $name, which LoTW does not list", name in known)
        }
    }

    /**
     * The catalogue number is the key precisely so that the source a user happens to fetch
     * from cannot change the answer. These are the numbers whose names differ most between
     * Celestrak and AMSAT, so they are the ones worth pinning.
     */
    @Test
    fun `names that differ between sources resolve to the LoTW spelling`() {
        assertEquals("AO-91", LotwSatelliteIds.nameFor(43017))  // RADFXSAT (FOX-1B) / AO-91
        assertEquals("QO-100", LotwSatelliteIds.nameFor(43700)) // ES'HAIL 2 / QO-100
        assertEquals("TO-108", LotwSatelliteIds.nameFor(44881)) // TIANYAN 01 / CAS-6 (TO-108)
        assertEquals("SO-50", LotwSatelliteIds.nameFor(27607))  // SAUDISAT 1C (SO-50) / SO-50
        assertEquals("AO-123", LotwSatelliteIds.nameFor(61781)) // ASRTU-1 (AO-123) / AO-123
    }

    /**
     * ARISS is the station, not its modules. Celestrak's full catalogue lists ISS (UNITY),
     * (ZVEZDA), (DESTINY) and (NAUKA) as separate objects; matching on the name "ISS" pulled
     * all of them in, and a QSO cannot be worked through a module.
     */
    @Test
    fun `ARISS is the station and not one of its modules`() {
        assertEquals("ARISS", LotwSatelliteIds.nameFor(25544))
        for (module in listOf(25575, 26400, 26700, 49044)) {
            assertNull("module $module must not map to a satellite", LotwSatelliteIds.nameFor(module))
        }
    }

    /**
     * One source (R4UAB) names 53109 as ROBUSTA 1F while four others call it GREENCUBE
     * (IO-117), and 53106 appears in that one source alone. Trusting either would have put a
     * second catalogue number on the same LoTW name.
     */
    @Test
    fun `IO-117 resolves to the number the amateur sources agree on`() {
        assertEquals("IO-117", LotwSatelliteIds.nameFor(53109))
        assertNull(LotwSatelliteIds.nameFor(53106))
    }

    /** 44879 is TIANQIN 1, catalogued but not an amateur satellite carrying TO-108. */
    @Test
    fun `TO-108 does not also match TIANQIN 1`() {
        assertEquals("TO-108", LotwSatelliteIds.nameFor(44881))
        assertNull(LotwSatelliteIds.nameFor(44879))
    }

    /** No two numbers may share a name, or one of them is the wrong satellite. */
    @Test
    fun `each LoTW name is claimed by a single catalogue number`() {
        val names = catnums.mapNotNull { LotwSatelliteIds.nameFor(it) }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `isKnown agrees with nameFor`() {
        assertTrue(LotwSatelliteIds.isKnown(27607))
        assertFalse(LotwSatelliteIds.isKnown(99999))
        assertNull(LotwSatelliteIds.nameFor(99999))
    }

    /** Guards against an edit that empties or truncates the table. */
    @Test
    fun `table holds every entry`() {
        assertEquals(catnums.size, LotwSatelliteIds.size)
    }

    /**
     * The TEVEL-2 constellation needs the table more than anything else does: every source
     * writes these TEVEL2-N while LoTW has TEV2-N, and stripping separators does not bridge
     * that - TEVEL21 is not TEV21 - so without these rows their QSOs upload under a name LoTW
     * refuses. Nine satellites launched in 2025 and currently workable; they were missing from
     * the first version of this table.
     */
    @Test
    fun `the TEVEL-2 constellation maps to the LoTW spelling`() {
        assertEquals("TEV2-1", LotwSatelliteIds.nameFor(63217))
        assertEquals("TEV2-4", LotwSatelliteIds.nameFor(63213))
        assertEquals("TEV2-9", LotwSatelliteIds.nameFor(63237))
        // Nine numbers, nine distinct names. The numbering is deliberately not sequential -
        // 63217 is TEVEL2-1 while 63213 is TEVEL2-4 - so an off-by-one logs the wrong bird.
        val tevel = listOf(63213, 63214, 63215, 63217, 63218, 63219, 63237, 63238, 63239)
        val names = tevel.mapNotNull { LotwSatelliteIds.nameFor(it) }
        assertEquals(tevel.size, names.size)
        assertEquals(tevel.size, names.toSet().size)
    }

    private val catnums = listOf(
        7530, 14129, 20439, 20442, 22825, 23439, 24278, 25544, 26609, 26931,
        27607, 28650, 39444, 40025, 40074, 40908, 40931, 40967, 41847, 43017,
        43678, 43700, 43803, 44530, 44881, 44909, 50466, 53109, 61781,
        63213, 63214, 63215, 63217, 63218, 63219, 63237, 63238, 63239
    )
}
