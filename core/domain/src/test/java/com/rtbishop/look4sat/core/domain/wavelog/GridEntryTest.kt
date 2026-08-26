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

    /**
     * Two characters is legal per ADIF, so it is accepted - but a field is 20 by 10 degrees, close
     * to useless for a satellite contact, so it says so rather than refusing.
     */
    @Test
    fun `a two character field is acceptable with a warning`() {
        assertEquals(
            GridEntry.Verdict.Acceptable("OL", GridEntry.Warning.FIELD_ONLY),
            GridEntry.check("ol")
        )
    }

    /** A 2-character entry must not read past its own end. */
    @Test
    fun `a two character field does not crash`() {
        for (grid in listOf("AA", "RR", "ol", "FN")) {
            assertTrue(GridEntry.check(grid) is GridEntry.Verdict.Acceptable)
        }
    }

    /**
     * Kotlin's Char.isDigit() is Unicode-aware and accepted Arabic-Indic, Devanagari, Persian and
     * fullwidth digits, which a localised keypad produces without the operator seeing a difference.
     * ADIF 3.1.7 defines Digit as ASCII 48-57, and Wavelog stores GRIDSQUARE verbatim, so such a
     * value would never match a real grid in any statistics query.
     */
    @Test
    fun `non-ascii digits are not digits`() {
        val nonAscii = listOf(
            "OL\u0661\u0662ap",
            "OL\u0968\u0968ap",
            "OL\uff11\uff12ap",
            "OL\u06f1\u06f2ap",
            "IO91vl\u0663\u0669"
        )
        for (grid in nonAscii) {
            assertTrue(
                "must be refused: " + grid,
                GridEntry.check(grid) is GridEntry.Verdict.Unusable
            )
        }
    }

    /** Nothing typed means the QRZ lookup should run, which is a different outcome from bad input. */
    @Test
    fun `an empty entry is neither acceptable nor unusable`() {
        assertEquals(GridEntry.Verdict.Empty, GridEntry.check(""))
        assertEquals(GridEntry.Verdict.Empty, GridEntry.check("   "))
    }

    /**
     * ADIF 3.1.7 permits 2, 4, 6 and 8 characters and nothing between them. A 10 or 12 character
     * locator stores its first 8 here with the rest in GRIDSQUARE_EXT, which nothing in this app
     * carries - the field clips at 8, which produces the spec-correct value.
     */
    @Test
    fun `only 2 4 6 or 8 characters can be a grid`() {
        for (bad in listOf("O", "OL7", "OL72A", "OL72APX", "OL72AP123")) {
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
