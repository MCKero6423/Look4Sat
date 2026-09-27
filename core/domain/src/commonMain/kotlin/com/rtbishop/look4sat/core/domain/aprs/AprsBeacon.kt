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
 * Builds the position line this station puts on APRS-IS, or refuses to.
 *
 * Separated from the reporter so the packet can be tested without a socket. Every rule here
 * comes from aprs-is.net/connecting.aspx, and each of them was being broken:
 *
 * - the path must be exactly `TCPIP*`, and was absent entirely
 * - the line must not exceed 512 bytes including CRLF, and had no cap
 * - the comment must not contain a line break, or it injects a second packet
 * - a station with no position must not transmit, where 0.0 was substituted so 0 degrees north,
 *   0 degrees east - a point in the Gulf of Guinea - went out under the operator's callsign
 */
object AprsBeacon {

    /** The only path a client-originated packet may carry. */
    const val PATH = "TCPIP*"

    /** Destination for a position report with no addressee. */
    const val DESTINATION = "APRS"

    /** Maximum line length including the CRLF the caller appends. */
    const val MAX_LINE_BYTES = 512

    /** Comment limit for this position format, per the APRS specification. */
    const val MAX_COMMENT = 43

    /** Why a beacon could not be built. */
    sealed interface Refusal {

        /** No position was available. Transmitting 0,0 would claim the Gulf of Guinea. */
        data object NoPosition : Refusal

        /** The callsign is missing, so the packet would have no valid source. */
        data object NoCallsign : Refusal

        /** Latitude or longitude outside the possible range. */
        data class ImpossiblePosition(val latitude: Double, val longitude: Double) : Refusal
    }

    /** Either a line ready to send, or the reason there is none. */
    sealed interface Result {
        data class Line(val text: String) : Result
        data class Blocked(val refusal: Refusal) : Result
    }

    /**
     * Build the position line.
     *
     * Returns [Result.Blocked] rather than a placeholder: a beacon is a claim about where the
     * operator is, and there is no honest default for "nowhere".
     */
    fun build(
        callsign: String,
        ssid: String,
        latitude: Double?,
        longitude: Double?,
        symbolTable: String,
        symbolCode: String,
        comment: String
    ): Result {
        if (callsign.isBlank()) return Result.Blocked(Refusal.NoCallsign)
        if (latitude == null || longitude == null) return Result.Blocked(Refusal.NoPosition)
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return Result.Blocked(Refusal.ImpossiblePosition(latitude, longitude))
        }
        val source = AprsPacket.formatCallSsid(callsign.trim().uppercase(), ssid.trim())
        val position = AprsPosition(
            latitude = latitude,
            longitude = longitude,
            symbolTable = tableOf(symbolTable),
            symbolCode = codeOf(symbolCode)
        )
        val header = "$source>$DESTINATION,$PATH:="
        val body = position.toUncompressedString()
        val room = MAX_LINE_BYTES - CRLF_BYTES - header.toByteArray().size - body.toByteArray().size
        return Result.Line(header + body + sanitiseComment(comment, room))
    }

    /**
     * Strip anything that would break the line, then trim to fit.
     *
     * A newline typed into the comment field used to end the packet early and start a second one
     * from the remaining text - an injection the operator could trigger by accident.
     */
    fun sanitiseComment(comment: String, room: Int = MAX_COMMENT): String {
        if (room <= 0) return ""
        val cleaned = comment.asSequence()
            // Printable ASCII only: line breaks split the packet, and control characters have no
            // meaning in a comment while being able to confuse a parser.
            .filter { it.code in 0x20..0x7E }
            .joinToString("")
            .trim()
        val limit = minOf(MAX_COMMENT, room)
        return if (cleaned.length <= limit) cleaned else cleaned.take(limit)
    }

    /**
     * The symbol table byte, defaulting to the primary table.
     *
     * Must be `/`, `\` or an overlay character. It was previously whatever the operator typed
     * first - any character at all, including one that breaks the fixed-width parse. aprs.fi
     * names symbol misconfiguration as the most common reason a station never appears on the map.
     */
    fun tableOf(entry: String): Char {
        val candidate = entry.trim().firstOrNull() ?: return TABLE_PRIMARY
        return when {
            candidate == TABLE_PRIMARY || candidate == TABLE_ALTERNATE -> candidate
            candidate.isDigit() -> candidate
            candidate in 'A'..'Z' -> candidate
            else -> TABLE_PRIMARY
        }
    }

    /** The symbol byte. Any printable character is a valid symbol; anything else is not. */
    fun codeOf(entry: String): Char {
        val candidate = entry.trim().firstOrNull() ?: return DEFAULT_SYMBOL
        return if (candidate.code in 0x21..0x7E) candidate else DEFAULT_SYMBOL
    }

    private const val TABLE_PRIMARY = '/'
    private const val TABLE_ALTERNATE = '\\'

    /** Bytes the caller adds after the line. */
    private const val CRLF_BYTES = 2

    /** Fallback symbol. `>` is a car on the primary table - a reasonable stand-in for a phone. */
    /**
     * Substituted when the stored code is unusable.
     *
     * A house, not a car. The old default was '>' (CAR) with a comment conceding it was "a
     * reasonable stand-in for a phone" - but a station beaconing from a handset showed up as a
     * vehicle for every operator who was not driving, and aprs.fi names transmit-side symbol
     * misconfiguration among the first things to check when a station looks wrong. A house is
     * correct for most users and obviously wrong rather than misleading for the rest.
     */
    private const val DEFAULT_SYMBOL = '-'
}
