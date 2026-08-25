package com.rtbishop.look4sat.core.domain.aprs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verified/unverified distinction is the whole point of these tests: an unverified client
 * stays connected and its writes keep succeeding while the server discards every packet, so
 * getting this wrong means the operator watches reports "succeed" for hours with nothing landing.
 */
class AprsLoginTest {

    @Test
    fun `builds the line the specification asks for`() {
        assertEquals(
            "user BG7NTA-5 pass 12345 vers Look4Sat-4.6.0",
            AprsLogin.line("BG7NTA", "5", 12345, "Look4Sat-4.6.0")
        )
    }

    /** No SSID means no hyphen; the spec says never to write -0 explicitly. */
    @Test
    fun `omits the ssid when there is none`() {
        assertEquals(
            "user BG7NTA pass 12345 vers Look4Sat",
            AprsLogin.line("BG7NTA", "", 12345, "Look4Sat")
        )
    }

    /**
     * The server splits `vers` into name and version, so a space inside it shifts every field
     * after that point. The shipped value was "Look4Sat 4.5.4", which parsed correctly only by
     * luck - and only because nothing followed it.
     */
    @Test
    fun `a space in the version cannot shift the following fields`() {
        val line = AprsLogin.line("BG7NTA", "5", 12345, "Look4Sat 4.6.0")
        assertEquals("user BG7NTA-5 pass 12345 vers Look4Sat-4.6.0", line)
        // Six tokens: user, callsign, pass, passcode, vers, version. A space inside the version
        // would make seven and push the server's field parsing one place along.
        assertEquals(6, line.split(" ").size)
    }

    @Test
    fun `appends a filter when one is given`() {
        val line = AprsLogin.line("BG7NTA", "5", 12345, "Look4Sat", "r/33.25/-96.5/50")
        assertEquals("user BG7NTA-5 pass 12345 vers Look4Sat r/33.25/-96.5/50", line)
    }

    @Test
    fun `receive-only logins use the documented passcode`() {
        val line = AprsLogin.line("BG7NTA", "", AprsLogin.RECEIVE_ONLY_PASSCODE, "Look4Sat")
        assertEquals("user BG7NTA pass -1 vers Look4Sat", line)
    }

    /**
     * "unverified" contains "verified", so the negative has to be tested first. A naive
     * contains("verified") reports every rejected login as accepted - which is precisely the
     * failure this replaces.
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
     * Server identification and keepalive comments must return null so the caller keeps reading.
     * Treating the first comment as an answer would leave every login Unknown.
     */
    @Test
    fun `comments without a verdict are skipped`() {
        assertNull(AprsLogin.parse("# aprsc 2.1.19-g730c5c0"))
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
        val outcome = AprsLogin.parse("# logresp BG7NTA something-new, server X")
        assertTrue(outcome is AprsLogin.Outcome.Unknown)
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
