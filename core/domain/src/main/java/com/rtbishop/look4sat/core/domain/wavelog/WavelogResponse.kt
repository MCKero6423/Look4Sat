/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.domain.wavelog

/**
 * Decides whether Wavelog actually accepted a QSO.
 *
 * The status code is not the answer. Wavelog validates after responding 200 and reports the
 * outcome in the body, so a rejected QSO arrives as HTTP 200 with `{"status":"failed"}`. Trusting
 * the code alone marked it uploaded and dropped it from the queue - the same class of defect as
 * the APRS reporter claiming a send succeeded when nothing had left the phone.
 *
 * Parsed as text rather than with JSONObject on purpose: org.json is compileOnly in core:domain,
 * so a JVM unit test gets the stub and every assertion against it would be vacuous.
 */
object WavelogResponse {

    /** What the server said about one QSO. */
    sealed interface Verdict {

        /** Stored. Safe to drop from the queue. */
        data class Accepted(val detail: String) : Verdict

        /** Wavelog already has it. Also safe to drop - the log is correct either way. */
        data object Duplicate : Verdict

        /** Rejected. [reason] carries the server's own wording when it gave one. */
        data class Rejected(val reason: String) : Verdict

        /** Unreadable, so keep the QSO queued rather than guess in either direction. */
        data class Unreadable(val detail: String) : Verdict
    }

    /**
     * Interpret a response.
     *
     * A body that reports failure beats a success code, because that is exactly the case the code
     * alone gets wrong. An empty body with a success code counts as accepted, since the v1
     * endpoint answers that way.
     */
    fun verdict(statusCode: Int, body: String): Verdict {
        val text = body.trim()
        // Whitespace around separators is collapsed before matching, rather than listing every
        // spacing a server might use: `{ "status" : "failed" }` is as valid as
        // `{"status":"failed"}` and enumerating the combinations is endless.
        val lower = text.lowercase().replace(AROUND_SEPARATORS, "")
        // Duplicate reported three ways: a 409, the word in a message, or Wavelog's own
        // `{"status":"dupe"}` - which arrives with HTTP 200 and is documented, not guessed.
        if (statusCode == DUPLICATE_CODE ||
            DUPLICATE_MARKERS.any { lower.contains(it) }
        ) {
            return Verdict.Duplicate
        }
        if (statusCode !in SUCCESS_CODES) {
            return Verdict.Rejected(reasonFrom(text).ifBlank { "HTTP $statusCode" })
        }
        if (FAILURE_MARKERS.any { lower.contains(it) }) {
            return Verdict.Rejected(reasonFrom(text).ifBlank { "server reported failure" })
        }
        // A status field we cannot read is not a success. Saying so keeps the QSO queued.
        if (lower.contains("\"status\"") && SUCCESS_MARKERS.none { lower.contains(it) }) {
            return Verdict.Unreadable(text.take(MAX_DETAIL))
        }
        return Verdict.Accepted(text.take(MAX_DETAIL))
    }

    /**
     * The server's own explanation, when it gave one.
     *
     * Reads `reason`, `message` or `error` out of the JSON by hand. Crude, but it only has to work
     * well enough to show the operator something more useful than a status code.
     */
    private fun reasonFrom(body: String): String {
        for (key in REASON_KEYS) {
            Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(body)
                ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        // An array of messages, which the v1 endpoint returns for a malformed ADIF record.
        Regex("\"messages\"\\s*:\\s*\\[\\s*\"([^\"]*)\"").find(body)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return ""
    }

    private val SUCCESS_CODES = 200..299
    private const val DUPLICATE_CODE = 409
    private const val MAX_DETAIL = 200

    /** Matched against the whitespace-collapsed body, so one spelling of each suffices. */
    private val FAILURE_MARKERS = listOf(
        "\"status\":\"failed\"",
        "\"status\":\"error\"",
        "\"result\":\"failed\""
    )

    /**
     * Wavelog's documented success wordings, plus the ones its other endpoints use.
     *
     * `successful` matters as much as `success`: the API reference uses both, and treating one as
     * unrecognised would leave a stored QSO queued forever.
     */
    private val SUCCESS_MARKERS = listOf(
        "\"status\":\"success\"",
        "\"status\":\"successful\"",
        "\"status\":\"created\"",
        "\"status\":\"ok\""
    )

    /** `dupe` is Wavelog's own wording and arrives with a 200. */
    private val DUPLICATE_MARKERS = listOf("\"status\":\"dupe\"", "duplicate")

    /** Whitespace next to a colon or comma, which JSON allows and servers use inconsistently. */
    private val AROUND_SEPARATORS = Regex("""\s*(?=[:,])|(?<=[:,])\s*""")

    private val REASON_KEYS = listOf("reason", "message", "error")
}
