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
package com.rtbishop.look4sat.core.domain.aprs

/**
 * The APRS-IS login line and the verdict the server returns for it.
 *
 * Kept apart from the socket so it can be tested: whether a login was verified decides whether
 * anything this app sends reaches the network, and that distinction used to be made by a
 * substring check inside a runCatching whose result was discarded, so it could not fail loudly.
 *
 * Format per aprs-is.net/connecting.aspx:
 * `user mycall[-ss] pass passcode [vers softwarename softwarevers [filter ...]]`
 */
object AprsLogin {

    /** What the server decided about a login attempt. */
    sealed interface Outcome {

        /** The passcode matched the callsign. Packets from this client are accepted. */
        data class Verified(val callsign: String) : Outcome

        /**
         * The server accepted the connection but did not verify the login.
         *
         * Not an error at the socket level, which is exactly why it needs surfacing: writes keep
         * succeeding while the server discards every packet. A receive-only login (passcode -1)
         * lands here legitimately.
         */
        data class Unverified(val callsign: String) : Outcome

        /** The server refused the login outright. */
        data class Rejected(val detail: String) : Outcome

        /** Nothing recognisable arrived. The connection may still work; we simply do not know. */
        data class Unknown(val detail: String) : Outcome
    }

    /** Any run of whitespace, collapsed to a hyphen inside a single token. */
    private val WHITESPACE = Regex("""\s+""")

    /** Passcode value that asks for a receive-only connection. */
    const val RECEIVE_ONLY_PASSCODE = -1

    /**
     * Build the login line.
     *
     * `vers` takes TWO tokens - a software name and a version, separated by a space. An earlier
     * version of this replaced that space with a hyphen, reading the rule "softwarename must not
     * contain a space" as "the field must be a single token". Live aprsc 2.1.21 rejects the result:
     *
     *     sent: user N0CALL pass -1 vers Look4Sat-4.5.4
     *     got:  # Invalid login: software name and version are not separated by a space
     *
     * So name and version stay apart, and whitespace is collapsed WITHIN each of them instead.
     */
    fun line(
        callsign: String,
        ssid: String,
        passcode: Int,
        name: String,
        version: String,
        filter: String = ""
    ): String {
        val callSsid = AprsPacket.formatCallSsid(callsign, ssid)
        val safeName = name.trim().replace(WHITESPACE, "-").ifEmpty { "Look4Sat" }
        val safeVersion = version.trim().replace(WHITESPACE, "-").ifEmpty { "0" }
        val base = "user $callSsid pass $passcode vers $safeName $safeVersion"
        val trimmedFilter = filter.trim()
        return if (trimmedFilter.isEmpty()) base else "$base $trimmedFilter"
    }

    /**
     * Interpret one line of server output, or null when it carries no verdict.
     *
     * Classified by what is KNOWN HARMLESS rather than by a list of known refusals, because that
     * list was incomplete and the failure is silent. aprsc refuses with `# Invalid login: ...` but
     * also `# Login by user not allowed` - observed live on rotate.aprs2.net - and `# Port full`
     * and `# Server full`. Each was skipped as chatter, the login timed out into Unknown, Unknown
     * is deliberately read as "may be working", and every send afterwards reported success to an
     * operator the server had refused.
     *
     * So identification and keepalive comments return null, a logresp is parsed, and anything else
     * the server bothers to say during login counts as it objecting.
     *
     * Note that "unverified" contains "verified", so the negative is tested first - a naive
     * contains("verified") reports every refusal as acceptance.
     */
    fun parse(line: String): Outcome? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("#")) {
            // A non-comment line during login is the server objecting in plain text.
            return Outcome.Rejected(trimmed)
        }
        val lower = trimmed.lowercase()
        if (lower.contains("logresp")) {
            val callsign = callsignFrom(trimmed)
            return when {
                lower.contains("unverified") -> Outcome.Unverified(callsign)
                lower.contains("verified") -> Outcome.Verified(callsign)
                else -> Outcome.Unknown(trimmed)
            }
        }
        // Identification and keepalives are the only comments that mean "keep reading".
        if (HARMLESS.any { lower.startsWith(it) }) return null
        return Outcome.Rejected(trimmed.removePrefix("#").trim())
    }

    /**
     * Comment prefixes that carry no verdict.
     *
     * Matching a prefix rather than searching for refusal words means a refusal nobody anticipated
     * is treated as a refusal instead of being ignored.
     */
    private val HARMLESS = listOf("# aprsc", "# javaprssrvr", "# aprsis", "# filter")

    /** The callsign token in `# logresp CALL verified, ...`, or empty when absent. */
    private fun callsignFrom(response: String): String {
        val tokens = response.removePrefix("#").trim().split(Regex("\\s+"))
        val index = tokens.indexOfFirst { it.equals("logresp", ignoreCase = true) }
        if (index < 0) return ""
        return tokens.getOrNull(index + 1)?.trimEnd(',') ?: ""
    }
}
