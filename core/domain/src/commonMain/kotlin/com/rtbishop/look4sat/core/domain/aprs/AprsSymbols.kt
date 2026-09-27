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
 * One APRS symbol an operator might plausibly want.
 *
 * [table] and [code] are the two characters that go into the packet. [descriptionKey] names a
 * string resource rather than holding text, because core:domain has no access to resources and
 * hardcoding English here would put wording outside the locale files.
 */
data class AprsSymbol(val table: Char, val code: Char, val descriptionKey: String)

/**
 * A short list of symbols worth offering, instead of two free-text fields.
 *
 * The fields accepted anything and used only the first character, so typing "satellite" into the
 * table field persisted the whole word and beaconed as `/` - the field lied about what it did.
 * aprs.fi's own troubleshooting guidance puts transmit-side symbol misconfiguration among the first
 * things to check when a station does not appear as expected.
 *
 * The strongest single argument for a list: `\S` is Satellite/Pacsat but `/S` is SHUTTLE. One
 * keystroke apart, and both look correct to someone typing from memory.
 *
 * Renderings are from aprs.org/symbols/symbolsX.txt (WB4APR, 25 Nov 2015). The list is deliberately
 * short - it covers fixed, portable, vehicle and satellite postures, not all 400-odd symbols.
 */
object AprsSymbols {

    /** Fixed home station. Correct for most users, and wrong in an obvious way for the rest. */
    val HOUSE = AprsSymbol('/', '-', "aprs_symbol_house")

    val curated = listOf(
        HOUSE,
        AprsSymbol('\\', '-', "aprs_symbol_house_alt"),
        AprsSymbol('/', '[', "aprs_symbol_person"),
        AprsSymbol('/', 'y', "aprs_symbol_yagi"),
        // Alternate table. /S is SHUTTLE, which is not what anyone means here.
        AprsSymbol('\\', 'S', "aprs_symbol_satellite"),
        AprsSymbol('/', ';', "aprs_symbol_portable"),
        AprsSymbol('/', '$', "aprs_symbol_phone"),
        AprsSymbol('/', 'I', "aprs_symbol_tcpip"),
        AprsSymbol('\\', 'K', "aprs_symbol_ht"),
        AprsSymbol('/', '>', "aprs_symbol_car"),
        AprsSymbol('/', 'k', "aprs_symbol_truck"),
        AprsSymbol('/', 'v', "aprs_symbol_van"),
        AprsSymbol('/', 'R', "aprs_symbol_rv"),
        AprsSymbol('/', 'b', "aprs_symbol_bike")
    )

    /**
     * Find the curated entry matching a stored pair, or null when it is not on the list.
     *
     * Null matters: an operator may have set a symbol this list does not offer, and the picker must
     * show it as-is rather than silently substituting the nearest entry.
     */
    fun find(table: Char, code: Char): AprsSymbol? =
        curated.firstOrNull { it.table == table && it.code == code }

    /** Same, from whatever strings the settings screen holds. Blank means the shipped default. */
    fun find(table: String, code: String): AprsSymbol? {
        val t = table.firstOrNull() ?: HOUSE.table
        val c = code.firstOrNull() ?: HOUSE.code
        return find(t, c)
    }
}
