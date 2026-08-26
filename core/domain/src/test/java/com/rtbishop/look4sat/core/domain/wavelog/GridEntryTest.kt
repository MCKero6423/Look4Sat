package com.rtbishop.look4sat.core.domain.wavelog

import com.rtbishop.look4sat.core.domain.utility.qthToPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A typed grid goes into the ADIF GRIDSQUARE field and Wavelog stores whatever arrives, where a
 * wrong square is worse than a missing one - it pollutes grid statistics and VUCC tracking.
 *
 * The rule follows the callsign field: refuse only what is certainly wrong. Four characters is a
 * legitimate exchange on satellites, so requiring six would reject good entries.
 */
class GridEntryTest {

    /** Real grids, from stations and from this project's own test data. */
    private val realGrids = listOf(
        "OL72AP",   // Shenzhen, the user's own
        "FN31pr",   // W1AW, mixed case as conventionally written
        "JO31",     // four-character exchange
        "PM95uq",
        "IO91vl39", // eight-character extended
        "gf15vc",   // all lower case
        "RR73"      // the highest legal field pair
    )

    @Test
    fun `real grids are accepted`() {
        for (grid in realGrids) {
            val verdict = GridEntry.check(grid)
            assertTrue("$grid must be usable, got $verdict", verdict is GridEntry.Verdict.Acceptable)
        }
    }

    /**
     * Cross-check against the app's own converter: anything this accepts at six characters or more
     * must also decode to a real position, or the two disagree about what a grid is.
     */
    @Test
    fun `accepted grids of six or more decode to a position`() {
        for (grid in realGrids.filter { it.length >= 6 }) {
            val verdict = GridEntry.check(grid)
            assertTrue(verdict is GridEntry.Verdict.Acceptable)
            assertNotNull(
                "$grid was accepted here but qthToPosition rejects it",
                qthToPosition((verdict as GridEntry.Verdict.Acceptable).normalised)
            )
        }
    }

    /** Four characters is a real exchange, flagged rather than refused. */
    @Test
    fun `a four character square is acceptable with a warning`() {
        val verdict = GridEntry.check("JO31")
        assertEquals(
            GridEntry.Verdict.Acceptable("JO31", GridEntry.Warning.SQUARE_ONLY),
            verdict
        )
    }

    @Test
    fun `six characters carry no warning`() {
        val verdict = GridEntry.check("OL72AP") as GridEntry.Verdict.Acceptable
        assertNull(verdict.warning)
    }

    /** Upper field, lower subsquare - the conventional rendering, whatever was typed. */
    @Test
    fun `case is normalised rather than demanded`() {
        assertEquals("OL72ap", (GridEntry.check("ol72ap") as GridEntry.Verdict.Acceptable).normalised)
        assertEquals("OL72ap", (GridEntry.check("OL72AP") as GridEntry.Verdict.Acceptable).normalised)
        assertEquals("OL72ap", (GridEntry.check("oL72Ap") as GridEntry.Verdict.Acceptable).normalised)
    }

    @Test
    fun `surrounding whitespace does not matter`() {
        assertEquals("OL72ap", (GridEntry.check("  OL72AP  ") as GridEntry.Verdict.Acceptable).normalised)
    }

    /** Nothing typed means the QRZ lookup should run, which is a different outcome from bad input. */
    @Test
    fun `an empty entry is neither acceptable nor unusable`() {
        assertEquals(GridEntry.Verdict.Empty, GridEntry.check(""))
        assertEquals(GridEntry.Verdict.Empty, GridEntry.check("   "))
    }

    /** Maidenhead has no odd lengths and no two-character form in this context. */
    @Test
    fun `only 4 6 or 8 characters can be a grid`() {
        for (bad in listOf("OL", "OL7", "OL72A", "OL72APX", "OL72AP123")) {
            val verdict = GridEntry.check(bad)
            assertEquals(
                "$bad must be refused for length, got $verdict",
                GridEntry.Verdict.Unusable(GridEntry.Reason.WRONG_LENGTH),
                verdict
            )
        }
    }

    /**
     * The first pair runs A-R only. S-X decodes past the poles, which is how a plausible-looking
     * entry produces a position that cannot exist.
     */
    @Test
    fun `a field pair past R is refused`() {
        for (bad in listOf("SS12AA", "ZZ99ZZ", "TT34bb")) {
            assertEquals(
                "$bad decodes outside the world",
                GridEntry.Verdict.Unusable(GridEntry.Reason.FIELD_OUT_OF_RANGE),
                GridEntry.check(bad)
            )
        }
    }

    @Test
    fun `the square pair must be digits`() {
        for (bad in listOf("OLAAAP", "OL7AAP", "ABCDEF")) {
            assertEquals(
                "$bad has no square digits",
                GridEntry.Verdict.Unusable(GridEntry.Reason.SQUARE_NOT_DIGITS),
                GridEntry.check(bad)
            )
        }
    }

    /** The subsquare runs A-X. Y and Z do not exist. */
    @Test
    fun `a subsquare past X is refused`() {
        assertEquals(
            GridEntry.Verdict.Unusable(GridEntry.Reason.SUBSQUARE_OUT_OF_RANGE),
            GridEntry.check("OL72YZ")
        )
    }

    /** A callsign is the most likely wrong thing to be typed into this field. */
    @Test
    fun `a callsign is not a grid`() {
        for (call in listOf("BG7NTA", "W1AW", "JA1ABC", "DL1ABC")) {
            val verdict = GridEntry.check(call)
            assertTrue(
                "$call must not read as a grid, got $verdict",
                verdict is GridEntry.Verdict.Unusable
            )
        }
    }

    /** Digits alone are a frequency or a report, not a grid. */
    @Test
    fun `numbers alone are not a grid`() {
        for (bad in listOf("123456", "5959", "14550")) {
            assertTrue(GridEntry.check(bad) is GridEntry.Verdict.Unusable)
        }
    }

    /** The extended form ends in two digits. */
    @Test
    fun `an eight character grid needs digits at the end`() {
        assertTrue(GridEntry.check("IO91vl39") is GridEntry.Verdict.Acceptable)
        assertEquals(
            GridEntry.Verdict.Unusable(GridEntry.Reason.SQUARE_NOT_DIGITS),
            GridEntry.check("IO91vlab")
        )
    }
}
