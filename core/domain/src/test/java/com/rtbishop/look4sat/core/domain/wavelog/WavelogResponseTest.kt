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

    /**
     * The defect that lost contacts. Wavelog's own rejection text is "Duplicate for <call>", and
     * Api_v2 surfaces that inside validation_error bodies - so matching the bare word classified a
     * hard rejection as a duplicate, which maps to success and drops the QSO from the queue.
     *
     * Bodies transcribed from the Wavelog server source, not from its documentation.
     */
    @Test
    fun `the word duplicate in a rejection does not make it a duplicate`() {
        val bodies = listOf(
            400 to """{"error":{"code":"validation_error","message":"duplicate submode is invalid"}}""",
            400 to """{"status":"failed","reason":"duplicate key violation; QSO NOT stored"}""",
            200 to """{"status":"failed","reason":"Duplicate for BG7NTA"}"""
        )
        for ((code, body) in bodies) {
            val verdict = WavelogResponse.verdict(code, body)
            assertTrue(
                "must be a rejection, got " + verdict + " for " + body,
                verdict is WavelogResponse.Verdict.Rejected
            )
        }
    }

    /** A success that mentions the word must still be a success. */
    @Test
    fun `the word duplicate in a success does not make it a failure`() {
        val verdict = WavelogResponse.verdict(
            201,
            """{"status":"created","adif_errors":0,"messages":["Removed duplicate mode entry"]}"""
        )
        assertTrue("got " + verdict, verdict is WavelogResponse.Verdict.Accepted)
    }

    /**
     * A reverse proxy or a PHP fatal answers 200 with HTML. There is no status token, so it used to
     * read as a stored QSO and a misconfigured proxy would eat contacts silently.
     */
    @Test
    fun `an html body is never an acceptance`() {
        val pages = listOf(
            "<!DOCTYPE html><html><head><title>502 Bad Gateway</title></head></html>",
            "<html><body><h1>Maintenance</h1></body></html>",
            "<br /><b>Fatal error</b>: Uncaught Error in /var/www/index.php"
        )
        for (page in pages) {
            val verdict = WavelogResponse.verdict(200, page)
            assertTrue(
                "html must not be accepted, got " + verdict,
                verdict is WavelogResponse.Verdict.Unreadable
            )
        }
    }

    /** The v2 endpoint reports errors in an envelope with no status key at all. */
    @Test
    fun `a v2 error envelope is a rejection`() {
        val verdict = WavelogResponse.verdict(
            401,
            """{"error":{"code":"invalid_token","message":"Bearer token must start with wl2_"}}"""
        )
        assertEquals(
            WavelogResponse.Verdict.Rejected("Bearer token must start with wl2_"),
            verdict
        )
    }

    /** The QSO endpoint answers `created`, which is what the v1 source actually emits. */
    @Test
    fun `the created status the qso endpoint returns is accepted`() {
        val verdict = WavelogResponse.verdict(
            201,
            """{"status":"created","adif_count":1,"adif_errors":0,"messages":[""]}"""
        )
        assertTrue("got " + verdict, verdict is WavelogResponse.Verdict.Accepted)
    }

    /** v1 uses `abort` with a 400 when any record in a batch failed. Not a success. */
    @Test
    fun `an abort status is a rejection`() {
        val verdict = WavelogResponse.verdict(
            400,
            """{"status":"abort","messages":["Bad ADIF field CALL"]}"""
        )
        assertTrue("got " + verdict, verdict is WavelogResponse.Verdict.Rejected)
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
