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
package com.rtbishop.look4sat.core.domain.cw

/**
 * Morse code lookup table, ported from fldigi morse.cxx (cMorse::cw_table).
 * Each entry: [enabled, displayChar, prosignDisplay, dotDashPattern].
 */
internal object MorseTable {

    private data class Entry(
        val enabled: Boolean,
        val chr: String,
        val prt: String,
        val rpr: String
    )

    private val table = listOf(
        // Prosigns
        Entry(true, "=", "<BT>", "-...-"),
        Entry(false, "~", "<AA>", ".-.-"),
        Entry(true, "<", "<AS>", ".-..."),
        Entry(true, ">", "<AR>", ".-.-."),
        Entry(true, "%", "<SK>", "...-.-"),
        Entry(true, "+", "<KN>", "-.--."),
        Entry(true, "&", "<INT>", "..-.-"),
        Entry(true, "{", "<HM>", "....--"),
        Entry(true, "}", "<VE>", "...-."),
        // ASCII 7-bit letters
        Entry(true, "A", "A", ".-"),
        Entry(true, "B", "B", "-..."),
        Entry(true, "C", "C", "-.-."),
        Entry(true, "D", "D", "-.."),
        Entry(true, "E", "E", "."),
        Entry(true, "F", "F", "..-."),
        Entry(true, "G", "G", "--."),
        Entry(true, "H", "H", "...."),
        Entry(true, "I", "I", ".."),
        Entry(true, "J", "J", ".---"),
        Entry(true, "K", "K", "-.-"),
        Entry(true, "L", "L", ".-.."),
        Entry(true, "M", "M", "--"),
        Entry(true, "N", "N", "-."),
        Entry(true, "O", "O", "---"),
        Entry(true, "P", "P", ".--."),
        Entry(true, "Q", "Q", "--.-"),
        Entry(true, "R", "R", ".-."),
        Entry(true, "S", "S", "..."),
        Entry(true, "T", "T", "-"),
        Entry(true, "U", "U", "..-"),
        Entry(true, "V", "V", "...-"),
        Entry(true, "W", "W", ".--"),
        Entry(true, "X", "X", "-..-"),
        Entry(true, "Y", "Y", "-.--"),
        Entry(true, "Z", "Z", "--.."),
        // lowercase map to uppercase
        Entry(true, "a", "A", ".-"), Entry(true, "b", "B", "-..."),
        Entry(true, "c", "C", "-.-."), Entry(true, "d", "D", "-.."),
        Entry(true, "e", "E", "."), Entry(true, "f", "F", "..-."),
        Entry(true, "g", "G", "--."), Entry(true, "h", "H", "...."),
        Entry(true, "i", "I", ".."), Entry(true, "j", "J", ".---"),
        Entry(true, "k", "K", "-.-"), Entry(true, "l", "L", ".-.."),
        Entry(true, "m", "M", "--"), Entry(true, "n", "N", "-."),
        Entry(true, "o", "O", "---"), Entry(true, "p", "P", ".--."),
        Entry(true, "q", "Q", "--.-"), Entry(true, "r", "R", ".-."),
        Entry(true, "s", "S", "..."), Entry(true, "t", "T", "-"),
        Entry(true, "u", "U", "..-"), Entry(true, "v", "V", "...-"),
        Entry(true, "w", "W", ".--"), Entry(true, "x", "X", "-..-"),
        Entry(true, "y", "Y", "-.--"), Entry(true, "z", "Z", "--.."),
        // Numerals
        Entry(true, "0", "0", "-----"),
        Entry(true, "1", "1", ".----"),
        Entry(true, "2", "2", "..---"),
        Entry(true, "3", "3", "...--"),
        Entry(true, "4", "4", "....-"),
        Entry(true, "5", "5", "....."),
        Entry(true, "6", "6", "-...."),
        Entry(true, "7", "7", "--..."),
        Entry(true, "8", "8", "---.."),
        Entry(true, "9", "9", "----."),
        // Punctuation
        Entry(true, "\\", "\\", ".-..-."),
        Entry(true, "'", "'", ".----."),
        Entry(true, "$", "$", "...-..-"),
        Entry(true, "(", "(", "-.--."),
        Entry(true, ")", ")", "-.--.-"),
        Entry(true, ",", ",", "--..--"),
        Entry(true, "-", "-", "-....-"),
        Entry(true, ".", ".", ".-.-.-"),
        Entry(true, "/", "/", "-..-."),
        Entry(true, ":", ":", "---..."),
        Entry(true, ";", ";", "-.-.-."),
        Entry(true, "?", "?", "..--.."),
        Entry(true, "_", "_", "..--.-"),
        Entry(true, "@", "@", ".--.-."),
        Entry(true, "!", "!", "-.-.--"),
        // Accented (disabled by default in fldigi)
        Entry(false, "Ä", "Ä", ".-.-"), Entry(false, "ä", "Ä", ".-.-"),
        Entry(false, "Æ", "Æ", ".-.-"), Entry(false, "æ", "Æ", ".-.-"),
        Entry(false, "Å", "Å", ".--.-"), Entry(false, "å", "Å", ".--.-"),
        Entry(false, "Ç", "Ç", "-.-.."), Entry(false, "ç", "Ç", "-.-.."),
        Entry(false, "È", "È", ".-..-"), Entry(false, "è", "È", ".-..-"),
        Entry(false, "É", "É", "..-.."), Entry(false, "é", "É", "..-.."),
        Entry(false, "Ó", "Ó", "---."), Entry(false, "ó", "Ó", "---."),
        Entry(false, "Ö", "Ö", "---."), Entry(false, "ö", "Ö", "---."),
        Entry(false, "Ø", "Ø", "---."), Entry(false, "ø", "Ø", "---."),
        Entry(false, "Ñ", "Ñ", "--.--"), Entry(false, "ñ", "Ñ", "--.--"),
        Entry(false, "Ü", "Ü", "..--"), Entry(false, "ü", "Ü", "..--"),
        Entry(false, "Û", "Û", "..--"), Entry(false, "û", "Û", "..--")
    )

    /**
     * Look up the display representation of a dot-dash pattern.
     * Returns "" when the pattern is not in the table or is disabled.
     * Mirror of cMorse::rx_lookup.
     */
    fun rxLookup(pattern: String): String {
        for (e in table) {
            if (pattern == e.rpr) {
                if (e.enabled) return e.prt
            }
        }
        return ""
    }
}
