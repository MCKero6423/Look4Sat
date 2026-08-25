package com.rtbishop.look4sat.core.domain.qrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrzGridParserTest {

    /** A signed-in page for a station that published a locator. */
    private val withGrid = """
        <table class="detail">
          <tr><td class="dh">Callsign</td><td class="di">BG7NTA</td></tr>
          <tr><td class="dh">Grid Square</td><td class="di">OL72</td></tr>
          <tr><td class="dh">Country</td><td class="di">China</td></tr>
        </table>
    """.trimIndent()

    /** Signed in, but this station has no Grid Square row at all. */
    private val withoutGrid = """
        <table class="detail">
          <tr><td class="dh">Callsign</td><td class="di">W1AW</td></tr>
          <tr><td class="dh">Country</td><td class="di">United States</td></tr>
        </table>
    """.trimIndent()

    /**
     * What QRZ actually serves for a real callsign when nobody is signed in: no detail table,
     * plus its own notice saying why. Transcribed from a live response rather than invented.
     */
    private val signedOut = """
        <div class="csgn">W1AW</div>
        <div>Login is required for additional detail.</div>
        <div>Email: Login required to view</div>
    """.trimIndent()

    /**
     * A callsign QRZ has never heard of. Also HTTP 200, also zero detail rows, but no login
     * notice - QRZ serves its search form instead of a callsign page.
     */
    private val unknownCallsign = """
        <div class="search">The available search types are callsign, name, address.</div>
    """.trimIndent()

    @Test
    fun `reads the locator when the station published one`() {
        assertEquals(QrzGrid.Found("OL72"), QrzGridParser.parseGrid(withGrid))
    }

    /**
     * The distinction that matters: a station with no grid must not look like an expired
     * cookie, because the fix for one is nothing and the fix for the other is re-pasting it.
     */
    @Test
    fun `a station with no grid is not confused with being signed out`() {
        assertEquals(QrzGrid.NotOnFile, QrzGridParser.parseGrid(withoutGrid))
        assertEquals(QrzGrid.SignedOut, QrzGridParser.parseGrid(signedOut))
    }

    /**
     * The cookie prompt must fire only when the cookie is the problem. Measured against live
     * responses, a mistyped callsign returns a page with no detail rows either - so classifying
     * on their absence would blame the cookie and send the operator off to re-paste a working
     * one, mid-pass, over a satellite that is about to set.
     */
    @Test
    fun `an unknown callsign does not read as an expired cookie`() {
        assertEquals(QrzGrid.NotOnFile, QrzGridParser.parseGrid(unknownCallsign))
    }

    @Test
    fun `an empty grid cell counts as not on file`() {
        val blank = withGrid.replace(">OL72<", "><")
        assertEquals(QrzGrid.NotOnFile, QrzGridParser.parseGrid(blank))
    }

    @Test
    fun `whitespace around the locator is trimmed`() {
        val padded = withGrid.replace(">OL72<", ">  OL72  <")
        assertEquals(QrzGrid.Found("OL72"), QrzGridParser.parseGrid(padded))
    }

    /** Six-character locators are as common as four; nothing may truncate them. */
    @Test
    fun `a six character locator survives intact`() {
        val six = withGrid.replace(">OL72<", ">OL72ab<")
        assertEquals(QrzGrid.Found("OL72ab"), QrzGridParser.parseGrid(six))
    }

    /** QRZ puts the two cells adjacent, but whitespace between them must not break the match. */
    @Test
    fun `whitespace between the two table cells is tolerated`() {
        val spaced = withGrid.replace(
            "<td class=\"dh\">Grid Square</td><td class=\"di\">OL72</td>",
            "<td class=\"dh\">Grid Square</td>\n              <td class=\"di\">OL72</td>"
        )
        assertEquals(QrzGrid.Found("OL72"), QrzGridParser.parseGrid(spaced))
    }

    @Test
    fun `reads back which callsign the cookie belongs to`() {
        val menu = """<li class="leaf last" onclick="return true">BG7NTA <ul class="sub">"""
        assertEquals("BG7NTA", QrzGridParser.parseOwnCallsign(menu))
        assertNull(QrzGridParser.parseOwnCallsign(signedOut))
    }

    @Test
    fun `a raw cookie header is passed through unchanged`() {
        val raw = "qz_userid=1266043; qz_sess=abc123"
        assertEquals(raw, QrzGridParser.cookieHeader(raw))
    }

    /** The shape a cookie-export extension produces. */
    @Test
    fun `a json cookie export is flattened into a header`() {
        val json = """
            [{"domain":".qrz.com","name":"qz_userid","value":"1266043"},
             {"domain":".qrz.com","name":"qz_sess","value":"abc123"}]
        """.trimIndent()
        assertEquals("qz_userid=1266043; qz_sess=abc123", QrzGridParser.cookieHeader(json))
    }

    @Test
    fun `an empty cookie yields an empty header`() {
        assertEquals("", QrzGridParser.cookieHeader("   "))
    }

    /** Malformed JSON is handed over as-is rather than silently becoming empty. */
    @Test
    fun `unparseable json is passed through rather than dropped`() {
        val broken = """[{"nope":"nothing here"}]"""
        assertEquals(broken, QrzGridParser.cookieHeader(broken))
    }
}
