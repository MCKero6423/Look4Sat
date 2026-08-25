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

    /** Passcode value that asks for a receive-only connection. */
    const val RECEIVE_ONLY_PASSCODE = -1

    /**
     * Build the login line.
     *
     * [version] must not contain a space: the server splits `vers` into a software name and a
     * version, so an embedded space shifts every field after it. Any space becomes a hyphen
     * rather than silently corrupting the line.
     */
    fun line(
        callsign: String,
        ssid: String,
        passcode: Int,
        version: String,
        filter: String = ""
    ): String {
        val callSsid = AprsPacket.formatCallSsid(callsign, ssid)
        val safeVersion = version.trim().replace(' ', '-').ifEmpty { "Look4Sat" }
        val base = "user $callSsid pass $passcode vers $safeVersion"
        val trimmedFilter = filter.trim()
        return if (trimmedFilter.isEmpty()) base else "$base $trimmedFilter"
    }

    /**
     * Interpret one line of server output, or null when it carries no verdict.
     *
     * Returning null for keepalives and identification comments lets the caller keep reading
     * rather than treating the first comment it sees as an answer.
     *
     * aprsc answers `# logresp CALL verified, server X` or `# logresp CALL unverified, server X`.
     * Note that "unverified" contains "verified", so the negative has to be tested first - a
     * naive contains("verified") reports every rejected login as accepted.
     */
    fun parse(line: String): Outcome? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("#")) {
            // A non-comment line during login is the server objecting in plain text.
            return Outcome.Rejected(trimmed)
        }
        val lower = trimmed.lowercase()
        if (!lower.contains("logresp")) {
            // Server identification and keepalive comments carry no verdict.
            return null
        }
        val callsign = callsignFrom(trimmed)
        return when {
            lower.contains("unverified") -> Outcome.Unverified(callsign)
            lower.contains("verified") -> Outcome.Verified(callsign)
            lower.contains("invalid") || lower.contains("error") -> Outcome.Rejected(trimmed)
            else -> Outcome.Unknown(trimmed)
        }
    }

    /** The callsign token in `# logresp CALL verified, ...`, or empty when absent. */
    private fun callsignFrom(response: String): String {
        val tokens = response.removePrefix("#").trim().split(Regex("\\s+"))
        val index = tokens.indexOfFirst { it.equals("logresp", ignoreCase = true) }
        if (index < 0) return ""
        return tokens.getOrNull(index + 1)?.trimEnd(',') ?: ""
    }
}
