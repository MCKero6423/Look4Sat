package com.rtbishop.look4sat.core.domain.aprs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verified/unverified distinction is the point of these tests: an unverified client stays
 * connected and its writes keep succeeding while the server discards every packet, so getting this
 * wrong means the operator watches reports "succeed" for hours with nothing landing.
 *
 * Several are transcriptions of live aprsc 2.1.21 traffic rather than guesses, because an earlier
 * version of this file froze a wrong login format as correct and that defect survived nine commits
 * and two audits.
 */
class AprsLoginTest {

    @Test
    fun `builds the line the specification asks for`() {
        assertEquals(
            "user BG7NTA-5 pass 12345 vers Look4Sat 4.6.0",
            AprsLogin.line("BG7NTA", "5", 12345, "Look4Sat", "4.6.0")
        )
    }

    /**
     * The format a live server rejects, and the reason this file was rewritten.
     *
     *     sent: user N0CALL pass -1 vers Look4Sat-4.5.4
     *     got:  # Invalid login: software name and version are not separated by a space
     *
     * `vers` is two tokens. The previous code hyphenated the space between them to satisfy the rule
     * that the software NAME contains no space, and the test asserted that as correct.
     */
    @Test
    fun `the software name and version stay separate tokens`() {
        val line = AprsLogin.line("N0CALL", "", AprsLogin.RECEIVE_ONLY_PASSCODE, "Look4Sat", "4.5.4")
        assertEquals("user N0CALL pass -1 vers Look4Sat 4.5.4", line)
        assertEquals(7, line.split(" ").size)
        assertTrue("name and version must not be joined", !line.contains("Look4Sat-4.5.4"))
    }

    /** No SSID means no hyphen; the spec says never to write -0 explicitly. */
    @Test
    fun `omits the ssid when there is none`() {
        assertEquals(
            "user BG7NTA pass 12345 vers Look4Sat 4.6.0",
            AprsLogin.line("BG7NTA", "", 12345, "Look4Sat", "4.6.0")
        )
    }

    /** Whitespace inside either token would shift every field after it, so it is collapsed. */
    @Test
    fun `whitespace within a token is collapsed rather than passed through`() {
        val line = AprsLogin.line("BG7NTA", "5", 12345, "Look 4 Sat", "4.6.0 beta")
        assertEquals("user BG7NTA-5 pass 12345 vers Look-4-Sat 4.6.0-beta", line)
        assertEquals(7, line.split(" ").size)
    }

    @Test
    fun `appends a filter when one is given`() {
        val line = AprsLogin.line("BG7NTA", "5", 12345, "Look4Sat", "4.6.0", "r/33.25/-96.5/50")
        assertEquals("user BG7NTA-5 pass 12345 vers Look4Sat 4.6.0 r/33.25/-96.5/50", line)
    }

    @Test
    fun `receive-only logins use the documented passcode`() {
        val line = AprsLogin.line("BG7NTA", "", AprsLogin.RECEIVE_ONLY_PASSCODE, "Look4Sat", "4.6.0")
        assertEquals("user BG7NTA pass -1 vers Look4Sat 4.6.0", line)
    }

    /** Empty tokens would produce a malformed line, so each has a fallback. */
    @Test
    fun `empty name or version does not produce a malformed line`() {
        val line = AprsLogin.line("BG7NTA", "", 12345, "", "")
        assertEquals("user BG7NTA pass 12345 vers Look4Sat 0", line)
        assertEquals(7, line.split(" ").size)
    }

    /**
     * The refusal that used to be invisible. It is a comment but not a logresp, so it was skipped
     * as chatter; the login then timed out into Unknown, which is treated as "may be working", and
     * every send afterwards reported success against a server that had refused the login.
     */
    @Test
    fun `an invalid login comment is a refusal, not chatter`() {
        val outcome = AprsLogin.parse(
            "# Invalid login: software name and version are not separated by a space"
        )
        assertTrue("must be a refusal, got $outcome", outcome is AprsLogin.Outcome.Rejected)
        assertEquals(
            "Invalid login: software name and version are not separated by a space",
            (outcome as AprsLogin.Outcome.Rejected).detail
        )
    }

    @Test
    fun `a login denied comment is also a refusal`() {
        assertTrue(AprsLogin.parse("# Login denied") is AprsLogin.Outcome.Rejected)
    }

    /**
     * "unverified" contains "verified", so the negative has to be tested first. A naive
     * contains("verified") reports every rejected login as accepted.
     */
    @Test
    fun `unverified is not mistaken for verified`() {
        val outcome = AprsLogin.parse("# logresp BG7NTA unverified, server T2SYDNEY")
        assertEquals(AprsLogin.Outcome.Unverified("BG7NTA"), outcome)
    }

    @Test
    fun `a verified login is recognised with its callsign`() {
        val outcome = AprsLogin.parse("# logresp BG7NTA-5 verified, server T2SYDNEY")
        assertEquals(AprsLogin.Outcome.Verified("BG7NTA-5"), outcome)
    }

    /**
     * Identification and keepalives must return null so the caller keeps reading. The greeting here
     * is verbatim from aprsc 2.1.21.
     */
    @Test
    fun `comments without a verdict are skipped`() {
        assertNull(AprsLogin.parse("# aprsc 2.1.21-gbfc2090"))
        assertNull(AprsLogin.parse("# Tue Aug 25 08:00:00 UTC 2026"))
        assertNull(AprsLogin.parse(""))
        assertNull(AprsLogin.parse("   "))
    }

    /** A non-comment line during login is the server objecting in plain text. */
    @Test
    fun `a plain text line during login is a rejection`() {
        val outcome = AprsLogin.parse("Invalid callsign format")
        assertTrue(outcome is AprsLogin.Outcome.Rejected)
        assertEquals("Invalid callsign format", (outcome as AprsLogin.Outcome.Rejected).detail)
    }

    /** A logresp with no recognisable verdict is Unknown, not silently Verified. */
    @Test
    fun `an unrecognised logresp is not treated as success`() {
        assertTrue(
            AprsLogin.parse("# logresp BG7NTA something-new, server X")
                is AprsLogin.Outcome.Unknown
        )
    }

    @Test
    fun `case does not change the verdict`() {
        assertEquals(
            AprsLogin.Outcome.Unverified("BG7NTA"),
            AprsLogin.parse("# LOGRESP BG7NTA UNVERIFIED, server X")
        )
        assertEquals(
            AprsLogin.Outcome.Verified("BG7NTA"),
            AprsLogin.parse("# LogResp BG7NTA Verified, server X")
        )
        assertTrue(AprsLogin.parse("# INVALID LOGIN: nope") is AprsLogin.Outcome.Rejected)
    }

    /** The callsign is read back so the UI can show whose login the server acknowledged. */
    @Test
    fun `reads the callsign even when the line is oddly spaced`() {
        assertEquals(
            AprsLogin.Outcome.Verified("BG7NTA-9"),
            AprsLogin.parse("#   logresp   BG7NTA-9   verified,  server  X")
        )
    }
}
