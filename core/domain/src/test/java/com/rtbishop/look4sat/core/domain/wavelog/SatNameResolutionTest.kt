package com.rtbishop.look4sat.core.domain.wavelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoTW rejects a QSO whose SAT_NAME is not spelled as its accepted list has it, so these pin
 * the exact spellings against the names real TLE sources actually publish.
 */
class SatNameResolutionTest {

    /**
     * The catalogue number decides it whenever it is known, because sources disagree on the
     * name for the same object and the descriptive part of a TLE name is never the designator.
     */
    @Test
    fun `the catalogue number wins over whatever the TLE calls it`() {
        assertEquals("AO-123", WaveLogApi.normalizeSatName("ASRTU-1", 61781))
        assertEquals("AO-91", WaveLogApi.normalizeSatName("RADFXSAT (FOX-1B)", 43017))
        assertEquals("QO-100", WaveLogApi.normalizeSatName("ES'HAIL 2", 43700))
        assertEquals("TO-108", WaveLogApi.normalizeSatName("TIANYAN 01", 44881))
        assertEquals("CAS-3H", WaveLogApi.normalizeSatName("LILACSAT-2", 40908))
        assertEquals("ARISS", WaveLogApi.normalizeSatName("ISS (ZARYA)", 25544))
    }

    /**
     * Two sources naming one satellite differently must still upload identically, or which
     * source the operator fetched from would decide whether the QSO can be confirmed.
     */
    @Test
    fun `the same satellite resolves alike whichever source named it`() {
        val fromCelestrak = WaveLogApi.normalizeSatName("SAUDISAT 1C (SO-50)", 27607)
        val fromAmsat = WaveLogApi.normalizeSatName("SO-50", 27607)
        assertEquals(fromCelestrak, fromAmsat)
        assertEquals("SO-50", fromCelestrak)
    }

    /** Without a catalogue number - QSOs logged before it was recorded - the name is tried. */
    @Test
    fun `falls back to the name when no catalogue number is given`() {
        assertEquals("SO-50", WaveLogApi.normalizeSatName("SAUDISAT 1C (SO-50)"))
        assertEquals("AO-7", WaveLogApi.normalizeSatName("OSCAR 7 (AO-7)"))
        assertEquals("AO-73", WaveLogApi.normalizeSatName("FUNCUBE-1 (AO-73)"))
    }

    /**
     * Which side of the parentheses holds the designator varies, so both are tried:
     * "SAUDISAT 1C (SO-50)" has it inside, "ISS (ZARYA)" has the useful part outside.
     */
    @Test
    fun `looks on both sides of the parentheses`() {
        assertEquals("IO-117", WaveLogApi.normalizeSatName("GREENCUBE (IO-117)"))
        assertEquals("ARISS", WaveLogApi.normalizeSatName("ARISS (ZARYA)"))
    }

    /** Sources write RS15 where LoTW has RS-15; a hyphen must not decide confirmability. */
    @Test
    fun `a differing hyphen still matches`() {
        assertEquals("RS-15", WaveLogApi.normalizeSatName("RADIO ROSTO (RS15)"))
        assertEquals("UKUBE1", WaveLogApi.normalizeSatName("UKUBE-1"))
    }

    /** Formation launches are catalogued with a companion object appended. */
    @Test
    fun `a shared launch entry resolves to the satellite`() {
        assertEquals("CAS-2T", WaveLogApi.normalizeSatName("CAS-2T & KS-1Q"))
        assertEquals("RS-44", WaveLogApi.normalizeSatName("RS-44 & BREEZE-KM R/B"))
    }

    /**
     * LoTW's list is not all upper case - Arsene is written that way - and uploading ARSENE
     * would be rejected just as AO7 is.
     */
    @Test
    fun `the spelling LoTW uses is preserved rather than upper-cased`() {
        assertEquals("Arsene", WaveLogApi.normalizeSatName("ARSENE"))
        assertEquals("Arsene", WaveLogApi.normalizeSatName("arsene"))
        assertEquals("Arsene", WaveLogApi.normalizeSatName("Arsene"))
    }

    /**
     * An unknown satellite is passed through rather than dropped: the QSO is still worth
     * saving, and the caller uses [WaveLogApi.isLotwSatellite] to tell the operator that
     * this one will not be confirmed.
     */
    @Test
    fun `an unknown satellite is passed through and reported as unknown`() {
        assertEquals("CUTE-1 (CO-55)", WaveLogApi.normalizeSatName("CUTE-1 (CO-55)"))
        assertFalse(WaveLogApi.isLotwSatellite("CUTE-1 (CO-55)"))
        assertFalse(WaveLogApi.isLotwSatellite("CUBESAT XI-V"))
        assertFalse(WaveLogApi.isLotwSatellite("SWISSCUBE"))
        assertTrue(WaveLogApi.isLotwSatellite("SAUDISAT 1C (SO-50)"))
        assertTrue(WaveLogApi.isLotwSatellite("ASRTU-1", 61781))
    }

    @Test
    fun `an empty name does not crash`() {
        assertEquals("", WaveLogApi.normalizeSatName(""))
        assertEquals("", WaveLogApi.normalizeSatName("   "))
        assertFalse(WaveLogApi.isLotwSatellite(""))
    }

    /** A catalogue number nobody mapped falls through to the name rather than returning null. */
    @Test
    fun `an unmapped catalogue number falls back to the name`() {
        assertEquals("SO-50", WaveLogApi.normalizeSatName("SAUDISAT 1C (SO-50)", 99999))
    }
}
