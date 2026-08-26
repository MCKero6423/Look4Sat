package com.rtbishop.look4sat.core.domain.aprs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list exists because two free-text fields accepted anything and used only the first character,
 * so an operator could type a word, watch it persist, and beacon as something else entirely.
 */
class AprsSymbolsTest {

    /**
     * The pair that justifies the whole feature. `\S` is Satellite/Pacsat; `/S` is SHUTTLE. One
     * keystroke apart, and both look right to someone typing from memory.
     */
    @Test
    fun `the satellite symbol uses the alternate table`() {
        val satellite = AprsSymbols.curated.single { it.descriptionKey == "aprs_symbol_satellite" }
        assertEquals('\\', satellite.table)
        assertEquals('S', satellite.code)
    }

    /** Every entry must survive the sanitiser that runs at packet-build time. */
    @Test
    fun `every curated symbol passes the transmit sanitiser`() {
        for (symbol in AprsSymbols.curated) {
            assertEquals(
                symbol.descriptionKey + " table must survive tableOf",
                symbol.table,
                AprsBeacon.tableOf(symbol.table.toString())
            )
            assertEquals(
                symbol.descriptionKey + " code must survive codeOf",
                symbol.code,
                AprsBeacon.codeOf(symbol.code.toString())
            )
        }
    }

    /** Two entries rendering the same pair would be a UI ambiguity. */
    @Test
    fun `no two curated symbols are the same pair`() {
        val pairs = AprsSymbols.curated.map { it.table to it.code }
        assertEquals("pairs must be unique", pairs.size, pairs.toSet().size)
    }

    /** Each needs its own description, or the list reads as duplicates. */
    @Test
    fun `every curated symbol has a distinct description key`() {
        val keys = AprsSymbols.curated.map { it.descriptionKey }
        assertEquals("description keys must be unique", keys.size, keys.toSet().size)
        assertTrue("keys must be resource names", keys.all { it.startsWith("aprs_symbol_") })
    }

    @Test
    fun `a stored pair on the list is found`() {
        assertEquals(AprsSymbols.HOUSE, AprsSymbols.find('/', '-'))
        assertNotNull(AprsSymbols.find('\\', 'S'))
    }

    /**
     * A pair the list does not offer must report as absent rather than resolving to something near
     * it. The picker relies on this to show an existing setting untouched.
     */
    @Test
    fun `a stored pair off the list is not silently substituted`() {
        assertNull(AprsSymbols.find('/', 'S'))
        assertNull(AprsSymbols.find('/', '!'))
        assertNull(AprsSymbols.find('\\', 'y'))
    }

    /** The settings screen holds strings, and only the first character counts. */
    @Test
    fun `the string overload reads the first character`() {
        assertEquals(AprsSymbols.HOUSE, AprsSymbols.find("/", "-"))
        assertEquals(AprsSymbols.HOUSE, AprsSymbols.find("/junk", "-junk"))
    }

    /** Blank fields mean the shipped default, which is what the save path substitutes. */
    @Test
    fun `blank strings resolve to the default symbol`() {
        assertEquals(AprsSymbols.HOUSE, AprsSymbols.find("", ""))
    }

    /** The default has to be on the list, or the picker opens showing nothing selected. */
    @Test
    fun `the default symbol is on the curated list`() {
        assertTrue(AprsSymbols.HOUSE in AprsSymbols.curated)
    }

    /** Short enough to scan in a bottom sheet during setup. */
    @Test
    fun `the list stays short`() {
        assertTrue(
            "a curated list of ${AprsSymbols.curated.size} defeats the point",
            AprsSymbols.curated.size in 8..20
        )
    }
}
