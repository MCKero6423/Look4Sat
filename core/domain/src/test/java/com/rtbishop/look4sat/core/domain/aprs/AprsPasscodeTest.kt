package com.rtbishop.look4sat.core.domain.aprs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule these pin down: the app never invents a transmit passcode.
 *
 * APRS-IS treats the passcode as a licence check and says supplying it to a user is the software
 * author's job. The previous code derived one from the callsign whenever the entry was blank or
 * unparseable, so an operator who had never obtained a passcode transmitted anyway - under a
 * check that was never performed.
 */
class AprsPasscodeTest {

    /** Recomputed rather than hardcoded: the point is agreement with the shipped algorithm. */
    private val callsign = "BG7NTA"
    private val correct = AprsPacket.passcode(callsign)

    @Test
    fun `the callsign's own passcode allows transmitting`() {
        assertEquals(
            AprsPasscode.Entry.Transmit(correct),
            AprsPasscode.classify(callsign, correct.toString())
        )
        assertTrue(AprsPasscode.canTransmit(callsign, correct.toString()))
        assertEquals(correct, AprsPasscode.loginValue(callsign, correct.toString()))
    }

    /**
     * A blank entry becomes receive-only rather than a derived code. This is the defect: the old
     * line was `cfg.passcode.toIntOrNull()?.takeIf { it >= 0 } ?: AprsPacket.passcode(callsign)`,
     * so leaving the field empty transmitted under a computed passcode.
     */
    @Test
    fun `a blank entry connects receive-only instead of deriving one`() {
        assertEquals(AprsPasscode.Entry.ReceiveOnly, AprsPasscode.classify(callsign, ""))
        assertEquals(AprsPasscode.Entry.ReceiveOnly, AprsPasscode.classify(callsign, "   "))
        assertEquals(AprsPasscode.RECEIVE_ONLY, AprsPasscode.loginValue(callsign, ""))
        assertFalse(AprsPasscode.canTransmit(callsign, ""))
    }

    /**
     * -1 is the documented receive-only value and has to survive. `takeIf { it >= 0 }` used to
     * discard it and substitute a transmit passcode, which removed the only safe way to test a
     * setup - connecting receive-only puts nothing on the network.
     */
    @Test
    fun `an explicit -1 stays receive-only`() {
        assertEquals(AprsPasscode.Entry.ReceiveOnly, AprsPasscode.classify(callsign, "-1"))
        assertEquals(AprsPasscode.RECEIVE_ONLY, AprsPasscode.loginValue(callsign, "-1"))
        assertEquals(AprsPasscode.RECEIVE_ONLY, AprsPasscode.loginValue(callsign, " -1 "))
    }

    /** A wrong passcode is reported, not quietly corrected to the right one. */
    @Test
    fun `a mismatched passcode is reported rather than fixed`() {
        val wrong = (correct + 1).toString()
        assertEquals(AprsPasscode.Entry.Mismatch("BG7NTA"), AprsPasscode.classify(callsign, wrong))
        assertFalse(AprsPasscode.canTransmit(callsign, wrong))
        // And it must not be sent: a mismatch logs in receive-only, never as the derived value.
        assertEquals(AprsPasscode.RECEIVE_ONLY, AprsPasscode.loginValue(callsign, wrong))
    }

    /**
     * The three-way distinction the reporter depends on, and why it must not use canTransmit for
     * it: that collapses a deliberate receive-only choice and a typo into one boolean. Telling an
     * operator who mistyped their passcode that they are in receive-only mode is the same
     * mis-diagnosis as telling a deliberate receive-only user their passcode is wrong, pointing
     * the other way.
     */
    @Test
    fun `a deliberate receive-only entry is distinguishable from a typo`() {
        val deliberate = AprsPasscode.classify(callsign, "-1")
        val blank = AprsPasscode.classify(callsign, "")
        val typo = AprsPasscode.classify(callsign, (correct + 1).toString())
        val garbage = AprsPasscode.classify(callsign, "abcde")

        assertTrue(deliberate is AprsPasscode.Entry.ReceiveOnly)
        assertTrue(blank is AprsPasscode.Entry.ReceiveOnly)
        assertFalse("a typo must not read as receive-only", typo is AprsPasscode.Entry.ReceiveOnly)
        assertFalse("garbage must not read as receive-only", garbage is AprsPasscode.Entry.ReceiveOnly)

        // All four are equally unable to transmit, which is why the boolean is not enough.
        for (entry in listOf("-1", "", (correct + 1).toString(), "abcde")) {
            assertFalse(AprsPasscode.canTransmit(callsign, entry))
        }
    }

    @Test
    fun `something that is not a number is its own case`() {
        assertEquals(AprsPasscode.Entry.NotANumber, AprsPasscode.classify(callsign, "abcde"))
        assertEquals(AprsPasscode.Entry.NotANumber, AprsPasscode.classify(callsign, "12a45"))
        assertEquals(AprsPasscode.RECEIVE_ONLY, AprsPasscode.loginValue(callsign, "abcde"))
    }

    /** Surrounding whitespace from a paste must not turn a valid passcode into a mismatch. */
    @Test
    fun `whitespace around a valid passcode is tolerated`() {
        assertTrue(AprsPasscode.canTransmit(callsign, "  $correct  "))
    }

    /** The passcode depends on the callsign, so it must not validate against a different one. */
    @Test
    fun `a passcode for one callsign does not validate another`() {
        assertFalse(AprsPasscode.canTransmit("W1AW", correct.toString()))
        assertEquals(
            AprsPasscode.Entry.Mismatch("W1AW"),
            AprsPasscode.classify("W1AW", correct.toString())
        )
    }

    /** Case must not matter: the algorithm upper-cases before hashing. */
    @Test
    fun `a lower case callsign validates the same passcode`() {
        assertTrue(AprsPasscode.canTransmit("bg7nta", correct.toString()))
    }

    /** The SSID is not part of the hash, so the base callsign's code is the right one. */
    @Test
    fun `no callsign cannot transmit`() {
        assertEquals(AprsPasscode.Entry.Mismatch(""), AprsPasscode.classify("", "12345"))
        assertEquals(AprsPasscode.RECEIVE_ONLY, AprsPasscode.loginValue("", "12345"))
        // A blank entry with a blank callsign is still just receive-only, not a mismatch.
        assertEquals(AprsPasscode.Entry.ReceiveOnly, AprsPasscode.classify("", ""))
    }
}
