package com.rtbishop.look4sat.core.domain.wavelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule under test: never discard a plausible callsign, and never discard anything silently.
 *
 * A strict pattern rejected 16 of these 28 real callsigns, so the check is deliberately loose and
 * reports doubt as a warning instead of refusing.
 */
class CallsignEntryTest {

    /** Real callsigns, including the awkward shapes a strict pattern throws away. */
    private val realCallsigns = listOf(
        "W1AW", "BG7NTA", "G0ABC", "VK2XYZ", "JA1ABC", "PY2ABC",
        "W1AW/4", "K1ABC/P", "DL1ABC/M", "SV2ASP/A", "OH2ABC/MM",
        "2E0ABC", "2M0XYZ", "9A1CCY", "4X4ABC", "3DA0ABC",
        "VP2MDD", "ZS6ABC", "5B4ABC", "HB9ABC", "OE1ABC",
        "LU1ABC", "CT1ABC", "EA8ABC", "TF3ABC", "VU2ABC",
        "R1ABC", "UA9ABC"
    )

    @Test
    fun `every real callsign is accepted`() {
        val rejected = realCallsigns.filter { CallsignEntry.check(it) !is CallsignEntry.Verdict.Acceptable }
        assertEquals("these real callsigns were rejected: $rejected", emptyList<String>(), rejected)
    }

    /** The entry is upper-cased for logging, since ADIF and LoTW expect that. */
    @Test
    fun `entries are normalised to upper case and trimmed`() {
        val verdict = CallsignEntry.check("  bg7nta  ")
        assertEquals(CallsignEntry.Verdict.Acceptable("BG7NTA"), verdict)
    }

    /**
     * The defect this replaces: `if (call.length < 3) return` discarded the entry with no message,
     * so the operator pressed done mid-pass and nothing happened. Two characters is a real
     * callsign length, and anything shorter now says so instead of vanishing.
     */
    @Test
    fun `a short entry is rejected with a reason rather than dropped`() {
        assertEquals(
            CallsignEntry.Verdict.Rejected(CallsignEntry.Reason.TOO_SHORT),
            CallsignEntry.check("W")
        )
        assertEquals(
            CallsignEntry.Verdict.Rejected(CallsignEntry.Reason.EMPTY),
            CallsignEntry.check("   ")
        )
    }

    @Test
    fun `illegal characters are named as such`() {
        for (bad in listOf("W1AW!", "W1 AW", "W1AW.", "БГ7НТА", "W1AW@")) {
            assertEquals(
                bad,
                CallsignEntry.Verdict.Rejected(CallsignEntry.Reason.ILLEGAL_CHARACTERS),
                CallsignEntry.check(bad)
            )
        }
    }

    /** No callsign is all digits or all letters. */
    @Test
    fun `something with no digit or no letter is not a callsign`() {
        assertEquals(
            CallsignEntry.Verdict.Rejected(CallsignEntry.Reason.NOT_A_CALLSIGN),
            CallsignEntry.check("ABCDE")
        )
        assertEquals(
            CallsignEntry.Verdict.Rejected(CallsignEntry.Reason.NOT_A_CALLSIGN),
            CallsignEntry.check("12345")
        )
    }

    @Test
    fun `an absurdly long entry is rejected`() {
        assertEquals(
            CallsignEntry.Verdict.Rejected(CallsignEntry.Reason.TOO_LONG),
            CallsignEntry.check("W1AW/QRP/PORTABLE/EXTRA")
        )
    }

    /**
     * A grid typed into the callsign field is warned about, not refused: DXLog's own loose
     * pattern accepts GG77DH as a callsign, and the two are exchanged together on FM satellites.
     */
    @Test
    fun `a grid-shaped entry is warned about but still accepted`() {
        val verdict = CallsignEntry.check("GG77DH")
        assertEquals(
            CallsignEntry.Verdict.Acceptable("GG77DH", CallsignEntry.Warning.LOOKS_LIKE_A_GRID),
            verdict
        )
    }

    /** A six-character callsign of the same shape as a grid is rare but real. The warning is advisory. */
    @Test
    fun `a normal six character callsign carries no warning`() {
        val verdict = CallsignEntry.check("BG7NTA") as CallsignEntry.Verdict.Acceptable
        assertNull(verdict.warning)
    }

    /**
     * A repeat within the pass is flagged, never blocked: the same station on a later pass is a
     * valid new contact, and contest loggers default to working duplicates.
     */
    @Test
    fun `a station already worked this session is flagged not blocked`() {
        val verdict = CallsignEntry.check("W1AW", setOf("W1AW"))
        assertEquals(
            CallsignEntry.Verdict.Acceptable("W1AW", CallsignEntry.Warning.ALREADY_WORKED),
            verdict
        )
    }

    @Test
    fun `a station worked on a previous pass carries no warning`() {
        val verdict = CallsignEntry.check("W1AW", setOf("K1ABC")) as CallsignEntry.Verdict.Acceptable
        assertNull(verdict.warning)
    }

    /** The repeat check compares normalised entries, so case cannot smuggle a duplicate past it. */
    @Test
    fun `the repeat check is case insensitive`() {
        val verdict = CallsignEntry.check("w1aw", setOf("W1AW"))
        assertTrue(verdict is CallsignEntry.Verdict.Acceptable)
        assertEquals(
            CallsignEntry.Warning.ALREADY_WORKED,
            (verdict as CallsignEntry.Verdict.Acceptable).warning
        )
    }
}
