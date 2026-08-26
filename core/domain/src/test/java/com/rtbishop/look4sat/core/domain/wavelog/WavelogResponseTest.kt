package com.rtbishop.look4sat.core.domain.wavelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule these pin down: a 200 is not an acceptance.
 *
 * Wavelog validates after responding and reports the outcome in the body, so a rejected QSO comes
 * back as HTTP 200 with `{"status":"failed"}`. The uploader trusted the code alone, marked the QSO
 * uploaded and dropped it from the queue - the same failure as the APRS reporter claiming a send
 * succeeded when nothing had left the phone.
 *
 * The response shapes are transcribed from the Wavelog API reference at
 * docs.wavelog.org/developer/api, not invented.
 */
class WavelogResponseTest {

    @Test
    fun `a success body is accepted`() {
        assertTrue(
            WavelogResponse.verdict(201, """{"status":"success","message":"1 locations imported."}""")
                is WavelogResponse.Verdict.Accepted
        )
    }

    /** The reference uses both spellings; treating one as unknown would requeue a stored QSO. */
    @Test
    fun `successful is recognised as well as success`() {
        assertTrue(
            WavelogResponse.verdict(200, """{"status":"successful","members":[]}""")
                is WavelogResponse.Verdict.Accepted
        )
    }

    /** The defect this class exists for. */
    @Test
    fun `a failure body beats a success code`() {
        val verdict = WavelogResponse.verdict(200, """{"status":"failed","reason":"No club members found"}""")
        assertEquals(
            WavelogResponse.Verdict.Rejected("No club members found"),
            verdict
        )
    }

    @Test
    fun `an error status with a message is rejected with that message`() {
        val verdict = WavelogResponse.verdict(401, """{"status":"error","message":"Auth Error, invalid key"}""")
        assertEquals(
            WavelogResponse.Verdict.Rejected("Auth Error, invalid key"),
            verdict
        )
    }

    /** Wavelog's own duplicate wording, which arrives with a 200 rather than a 409. */
    @Test
    fun `a dupe status is a duplicate not a failure`() {
        assertEquals(
            WavelogResponse.Verdict.Duplicate,
            WavelogResponse.verdict(200, """{"status":"dupe","message":"0 locations imported."}""")
        )
    }

    @Test
    fun `a 409 is a duplicate whatever the body says`() {
        assertEquals(
            WavelogResponse.Verdict.Duplicate,
            WavelogResponse.verdict(409, "")
        )
    }

    /** A duplicate is safe to drop from the queue: the log holds the QSO either way. */
    @Test
    fun `a duplicate is not treated as an error`() {
        val verdict = WavelogResponse.verdict(200, """{"status":"dupe"}""")
        assertTrue(verdict !is WavelogResponse.Verdict.Rejected)
    }

    /** The v1 endpoint answers an accepted ADIF record with a success code and nothing else. */
    @Test
    fun `an empty body with a success code is accepted`() {
        assertTrue(
            WavelogResponse.verdict(200, "") is WavelogResponse.Verdict.Accepted
        )
        assertTrue(
            WavelogResponse.verdict(200, "   ") is WavelogResponse.Verdict.Accepted
        )
    }

    /**
     * An unrecognised status must not read as success. Keeping the QSO queued costs a retry;
     * dropping it loses the contact.
     */
    @Test
    fun `an unrecognised status keeps the QSO queued`() {
        val verdict = WavelogResponse.verdict(200, """{"status":"something-new"}""")
        assertTrue("got $verdict", verdict is WavelogResponse.Verdict.Unreadable)
    }

    @Test
    fun `a non-success code without a body reports the code`() {
        assertEquals(
            WavelogResponse.Verdict.Rejected("HTTP 500"),
            WavelogResponse.verdict(500, "")
        )
    }

    /** Whitespace in the JSON must not change the verdict - servers format differently. */
    @Test
    fun `spacing in the json does not change the verdict`() {
        assertEquals(
            WavelogResponse.Verdict.Rejected("nope"),
            WavelogResponse.verdict(200, """{ "status" : "failed" , "reason" : "nope" }""")
        )
    }

    /** A messages array, which the ADIF endpoint returns for a malformed record. */
    @Test
    fun `a messages array supplies the reason`() {
        val verdict = WavelogResponse.verdict(200, """{"status":"failed","messages":["Bad ADIF field"]}""")
        assertEquals(
            WavelogResponse.Verdict.Rejected("Bad ADIF field"),
            verdict
        )
    }

    /** Case must not matter: the marker comparison lower-cases the body. */
    @Test
    fun `an upper case status is still understood`() {
        assertTrue(
            WavelogResponse.verdict(200, """{"STATUS":"FAILED","reason":"x"}""")
                is WavelogResponse.Verdict.Rejected
        )
    }

    /** A failure with no explanation still has to say something usable. */
    @Test
    fun `a failure without a reason still reports one`() {
        val verdict = WavelogResponse.verdict(200, """{"status":"failed"}""")
        assertEquals(
            WavelogResponse.Verdict.Rejected("server reported failure"),
            verdict
        )
    }
}
