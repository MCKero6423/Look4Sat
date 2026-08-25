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
package com.rtbishop.look4sat.core.domain.qrz

/**
 * Outcome of a QRZ grid lookup.
 *
 * Four outcomes rather than a nullable string, because the previous null meant any of "the
 * station has no grid on file", "the cookie expired", "the request timed out" and "QRZ changed
 * its markup" - and the operator saw the same blank either way, with no way to tell that
 * re-pasting the cookie would fix it.
 */
sealed interface QrzGrid {

    /** The station's Maidenhead locator, as QRZ has it. */
    data class Found(val locator: String) : QrzGrid

    /** The page was read and the station has no locator published. Not an error. */
    data object NotOnFile : QrzGrid

    /** The detail table was absent, which is what QRZ serves when the cookie is not valid. */
    data object SignedOut : QrzGrid

    /** The request never completed. [attempts] is how many tries were made before giving up. */
    data class Unreachable(val attempts: Int) : QrzGrid
}

/**
 * Parsing of QRZ's callsign page, separate from the fetch so it can be tested without a
 * network. Pure string work over already-downloaded markup.
 */
object QrzGridParser {

    /** The detail row QRZ renders for a station that published a locator. */
    private val gridRow = Regex("""<td class="dh">Grid Square</td>\s*<td class="di">([^<]+)</td>""")

    /**
     * QRZ's own words on a callsign page served to a visitor who is not signed in.
     *
     * Classified on this positive notice rather than on the detail table being absent: measured
     * against live responses, a callsign QRZ has never heard of also returns HTTP 200 with zero
     * detail rows, because QRZ serves its search form instead of a callsign page. Keying on
     * absence therefore reported a mistyped callsign as an expired cookie, and would have sent
     * the operator off to re-paste a cookie that was never broken.
     */
    private val signedOutNotice = Regex("""Login is required for additional detail""")

    /** The account menu on a signed-in page, used to read back whose cookie this is. */
    private val accountCallsign = Regex("""<li class="leaf last"[^>]*>\s*([A-Z0-9/]+)\s*<ul""")

    /** A cookie name=value pair inside a browser extension's JSON export. */
    private val jsonCookie = Regex(""""name"\s*:\s*"([^"]+)"\s*,\s*"value"\s*:\s*"([^"]*)"""")

    /**
     * Interpret a callsign page.
     *
     * Only QRZ explicitly saying that a login is required counts as signed out, so an absent
     * locator degrades to the harmless [QrzGrid.NotOnFile] and only a genuinely stale cookie
     * sends the operator back to settings. Getting this wrong in either direction misdirects
     * them: the old client returned null for everything, and keying on the detail table being
     * absent would have blamed the cookie for a mistyped callsign.
     */
    fun parseGrid(html: String): QrzGrid {
        val locator = gridRow.find(html)?.groupValues?.get(1)?.trim()
        if (!locator.isNullOrBlank()) return QrzGrid.Found(locator)
        if (signedOutNotice.containsMatchIn(html)) return QrzGrid.SignedOut
        return QrzGrid.NotOnFile
    }

    /** The callsign this cookie is signed in as, or null when it is not signed in. */
    fun parseOwnCallsign(html: String): String? =
        accountCallsign.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Normalise the pasted cookie into a Cookie header value.
     *
     * Accepts a raw `k=v; k=v` header or the JSON array a cookie-export extension produces,
     * since the operator pastes whatever their browser handed them. Parsed by regex rather
     * than a JSON library because org.json is compileOnly here - it is supplied by Android at
     * runtime and absent from unit tests, so a JSON path could not be tested.
     */
    fun cookieHeader(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        if (!text.startsWith("[")) return text
        val pairs = jsonCookie.findAll(text)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { it.first.isNotBlank() }
            .toList()
        return if (pairs.isEmpty()) text else pairs.joinToString("; ") { "${it.first}=${it.second}" }
    }
}
