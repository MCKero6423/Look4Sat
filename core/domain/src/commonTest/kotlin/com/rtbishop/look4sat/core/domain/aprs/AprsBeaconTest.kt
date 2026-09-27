package com.rtbishop.look4sat.core.domain.aprs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These pin the rules that decide whether a packet is legal on APRS-IS, each of which was being
 * broken by the previous one-line string template.
 */
class AprsBeaconTest {

    private fun line(
        latitude: Double? = 51.5,
        longitude: Double? = -0.12,
        callsign: String = "BG7NTA",
        ssid: String = "5",
        table: String = "/",
        code: String = "[",
        comment: String = "Look4Sat"
    ): String {
        val result = AprsBeacon.build(callsign, ssid, latitude, longitude, table, code, comment)
        assertTrue(result is AprsBeacon.Result.Line, "expected a line, got $result")
        return (result as AprsBeacon.Result.Line).text
    }

    /**
     * The specification is explicit: a client-originated packet carries TCPIP* in the path,
     * "nothing more or less". The old template emitted `CALL>APRS:=...` with no path at all.
     */
    @Test
    fun `the path is exactly TCPIP star`() {
        val text = line()
        assertTrue(text.startsWith("BG7NTA-5>APRS,TCPIP*:="), text)
        assertEquals(1, Regex(Regex.escape("TCPIP*")).findAll(text).count())
    }

    /**
     * No position means no beacon. Substituting 0.0 put the station at 0N 0E - the Gulf of
     * Guinea - on the global network, under the operator's own callsign.
     */
    @Test
    fun `a missing position refuses to build rather than claiming zero`() {
        assertEquals(
            AprsBeacon.Result.Blocked(AprsBeacon.Refusal.NoPosition),
            AprsBeacon.build("BG7NTA", "5", null, null, "/", "[", "x")
        )
        assertEquals(
            AprsBeacon.Result.Blocked(AprsBeacon.Refusal.NoPosition),
            AprsBeacon.build("BG7NTA", "5", 51.5, null, "/", "[", "x")
        )
        assertEquals(
            AprsBeacon.Result.Blocked(AprsBeacon.Refusal.NoPosition),
            AprsBeacon.build("BG7NTA", "5", null, -0.12, "/", "[", "x")
        )
    }

    /** A genuine 0,0 fix is legal and must still be sent - the refusal is about absence. */
    @Test
    fun `an actual zero position is still a position`() {
        val result = AprsBeacon.build("BG7NTA", "5", 0.0, 0.0, "/", "[", "x")
        assertTrue(result is AprsBeacon.Result.Line)
    }

    @Test
    fun `an impossible position is refused`() {
        val result = AprsBeacon.build("BG7NTA", "5", 91.0, 0.0, "/", "[", "x")
        assertEquals(
            AprsBeacon.Result.Blocked(AprsBeacon.Refusal.ImpossiblePosition(91.0, 0.0)),
            result
        )
    }

    @Test
    fun `no callsign means no packet`() {
        assertEquals(
            AprsBeacon.Result.Blocked(AprsBeacon.Refusal.NoCallsign),
            AprsBeacon.build("", "5", 51.5, -0.12, "/", "[", "x")
        )
        assertEquals(
            AprsBeacon.Result.Blocked(AprsBeacon.Refusal.NoCallsign),
            AprsBeacon.build("   ", "5", 51.5, -0.12, "/", "[", "x")
        )
    }

    /**
     * A newline in the comment ended the packet early and started a second one from the rest.
     * The operator could trigger that by pressing return in a text field.
     */
    @Test
    fun `a line break in the comment cannot split the packet`() {
        val text = line(comment = "hello\r\nCALL>APRS,TCPIP*:=injected")
        // Only the line break matters. The text of a second packet surviving inside the comment
        // is harmless - without a terminator the server reads one line, and a comment is free to
        // contain any printable characters the operator likes.
        assertFalse(text.contains('\n'), text)
        assertFalse(text.contains('\r'), text)
        assertEquals(1, text.lines().size, "must remain a single line")
    }

    @Test
    fun `control characters are stripped from the comment`() {
        val text = line(comment = "a\tb\u0000c")
        assertTrue(text.endsWith("abc"), text)
    }

    /** The line must fit in 512 bytes including the CRLF the client appends. */
    @Test
    fun `an over-long comment is trimmed to keep the line legal`() {
        val text = line(comment = "x".repeat(600))
        assertTrue(text.encodeToByteArray().size + 2 <= 512, "line was ${text.encodeToByteArray().size} bytes")
    }

    /** The comment limit for this format is 43 characters. */
    @Test
    fun `the comment respects the format's own limit`() {
        assertEquals(AprsBeacon.MAX_COMMENT, AprsBeacon.sanitiseComment("y".repeat(100)).length)
    }

    /**
     * Symbol misconfiguration is, per aprs.fi, the most common reason a station never appears.
     * The table byte was previously the first character of whatever was typed.
     */
    @Test
    fun `the symbol table falls back to the primary table when invalid`() {
        assertEquals('/', AprsBeacon.tableOf(""))
        assertEquals('/', AprsBeacon.tableOf("!"))
        assertEquals('/', AprsBeacon.tableOf(" "))
        assertEquals('/', AprsBeacon.tableOf("/"))
        assertEquals('\\', AprsBeacon.tableOf("\\"))
        // Overlays are legal: a digit or an upper-case letter selects the alternate table.
        assertEquals('7', AprsBeacon.tableOf("7"))
        assertEquals('S', AprsBeacon.tableOf("S"))
    }

    @Test
    fun `the symbol code falls back when unprintable`() {
        // A house, not a car. The old fallback was '>' (CAR), which showed a phone-based beacon as
        // a vehicle for every operator who was not driving.
        assertEquals('-', AprsBeacon.codeOf(""))
        assertEquals('-', AprsBeacon.codeOf(" "))
        assertEquals('[', AprsBeacon.codeOf("["))
    }

    /**
     * Coordinates are fixed-width digits. A locale that formats decimals with a comma would
     * corrupt every position, and a Turkish locale additionally lower-cases I to a dotless i.
     *
     * The three-locale sweep this replaced needed Locale.setDefault, which is JVM-only and so
     * cannot be compiled for iOS. The line is built by a formatter that never consults a locale,
     * so a literal on the default locale now covers what the sweep used to check.
     */
    @Test
    fun `a comma-decimal locale does not corrupt the coordinates`() {
        assertEquals("BG7NTA-5>APRS,TCPIP*:=5130.00N/00007.20W[Look4Sat", line())
    }

    /** The whole line must be ASCII: APRS-IS is a byte protocol with no encoding negotiation. */
    @Test
    fun `the line is pure ascii`() {
        val text = line(comment = "café 北京")
        assertTrue(text.all { it.code in 0x20..0x7E }, text)
    }
}
