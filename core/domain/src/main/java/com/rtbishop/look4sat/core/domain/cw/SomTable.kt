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
 * Self-Organizing Map codebook for CW character matching, ported from
 * fldigi cw.cxx (cw::som_table). Each row is a dot/dash weight vector
 * (0 = element absent, 0.33 = dot, 1.0 = dash).
 */
internal object SomTable {

    data class SomEntry(val pattern: String, val weights: FloatArray)

    val table: List<SomEntry> = listOf(
        // Prosigns
        SomEntry("-...-", floatArrayOf(1.0f, 0.33f, 0.33f, 0.33f, 1.0f, 0f, 0f)),
        SomEntry(".-.-", floatArrayOf(0.33f, 1.0f, 0.33f, 1.0f, 0f, 0f, 0f)),
        SomEntry(".-...", floatArrayOf(0.33f, 1.0f, 0.33f, 0.33f, 0.33f, 0f, 0f)),
        SomEntry(".-.-.", floatArrayOf(0.33f, 1.0f, 0.33f, 1.0f, 0.33f, 0f, 0f)),
        SomEntry("...-.-", floatArrayOf(0.33f, 0.33f, 0.33f, 1.0f, 0.33f, 1.0f, 0f)),
        SomEntry("-.--.", floatArrayOf(1.0f, 0.33f, 1.0f, 1.0f, 0.33f, 0f, 0f)),
        SomEntry("..-.-", floatArrayOf(0.33f, 0.33f, 1.0f, 0.33f, 1.0f, 0f, 0f)),
        SomEntry("....--", floatArrayOf(0.33f, 0.33f, 0.33f, 0.33f, 1.0f, 1.0f, 0f)),
        SomEntry("...-.", floatArrayOf(0.33f, 0.33f, 0.33f, 1.0f, 0.33f, 0f, 0f)),
        // ASCII 7-bit letters
        SomEntry(".-", floatArrayOf(0.33f, 1.0f, 0f, 0f, 0f, 0f, 0f)),
        SomEntry("-...", floatArrayOf(1.0f, 0.33f, 0.33f, 0.33f, 0f, 0f, 0f)),
        SomEntry("-.-.", floatArrayOf(1.0f, 0.33f, 1.0f, 0.33f, 0f, 0f, 0f)),
        SomEntry("-..", floatArrayOf(1.0f, 0.33f, 0.33f, 0f, 0f, 0f, 0f)),
        SomEntry(".", floatArrayOf(0.33f, 0f, 0f, 0f, 0f, 0f, 0f)),
        SomEntry("..-.", floatArrayOf(0.33f, 0.33f, 1.0f, 0.33f, 0f, 0f, 0f)),
        SomEntry("--.", floatArrayOf(1.0f, 1.0f, 0.33f, 0f, 0f, 0f, 0f)),
        SomEntry("....", floatArrayOf(0.33f, 0.33f, 0.33f, 0.33f, 0f, 0f, 0f)),
        SomEntry("..", floatArrayOf(0.33f, 0.33f, 0f, 0f, 0f, 0f, 0f)),
        SomEntry(".---", floatArrayOf(0.33f, 1.0f, 1.0f, 1.0f, 0f, 0f, 0f)),
        SomEntry("-.-", floatArrayOf(1.0f, 0.33f, 1.0f, 0f, 0f, 0f, 0f)),
        SomEntry(".-..", floatArrayOf(0.33f, 1.0f, 0.33f, 0.33f, 0f, 0f, 0f)),
        SomEntry("--", floatArrayOf(1.0f, 1.0f, 0f, 0f, 0f, 0f, 0f)),
        SomEntry("-.", floatArrayOf(1.0f, 0.33f, 0f, 0f, 0f, 0f, 0f)),
        SomEntry("---", floatArrayOf(1.0f, 1.0f, 1.0f, 0f, 0f, 0f, 0f)),
        SomEntry(".--.", floatArrayOf(0.33f, 1.0f, 1.0f, 0.33f, 0f, 0f, 0f)),
        SomEntry("--.-", floatArrayOf(1.0f, 1.0f, 0.33f, 1.0f, 0f, 0f, 0f)),
        SomEntry(".-.", floatArrayOf(0.33f, 1.0f, 0.33f, 0f, 0f, 0f, 0f)),
        SomEntry("...", floatArrayOf(0.33f, 0.33f, 0.33f, 0f, 0f, 0f, 0f)),
        SomEntry("-", floatArrayOf(1.0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        SomEntry("..-", floatArrayOf(0.33f, 0.33f, 1.0f, 0f, 0f, 0f, 0f)),
        SomEntry("...-", floatArrayOf(0.33f, 0.33f, 0.33f, 1.0f, 0f, 0f, 0f)),
        SomEntry(".--", floatArrayOf(0.33f, 1.0f, 1.0f, 0f, 0f, 0f, 0f)),
        SomEntry("-..-", floatArrayOf(1.0f, 0.33f, 0.33f, 1.0f, 0f, 0f, 0f)),
        SomEntry("-.--", floatArrayOf(1.0f, 0.33f, 1.0f, 1.0f, 0f, 0f, 0f)),
        SomEntry("--..", floatArrayOf(1.0f, 1.0f, 0.33f, 0.33f, 0f, 0f, 0f)),
        // Numerals
        SomEntry("-----", floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0f, 0f)),
        SomEntry(".----", floatArrayOf(0.33f, 1.0f, 1.0f, 1.0f, 1.0f, 0f, 0f)),
        SomEntry("..---", floatArrayOf(0.33f, 0.33f, 1.0f, 1.0f, 1.0f, 0f, 0f)),
        SomEntry("...--", floatArrayOf(0.33f, 0.33f, 0.33f, 1.0f, 1.0f, 0f, 0f)),
        SomEntry("....-", floatArrayOf(0.33f, 0.33f, 0.33f, 0.33f, 1.0f, 0f, 0f)),
        SomEntry(".....", floatArrayOf(0.33f, 0.33f, 0.33f, 0.33f, 0.33f, 0f, 0f)),
        SomEntry("-....", floatArrayOf(1.0f, 0.33f, 0.33f, 0.33f, 0.33f, 0f, 0f)),
        SomEntry("--...", floatArrayOf(1.0f, 1.0f, 0.33f, 0.33f, 0.33f, 0f, 0f)),
        SomEntry("---..", floatArrayOf(1.0f, 1.0f, 1.0f, 0.33f, 0.33f, 0f, 0f)),
        SomEntry("----.", floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f, 0.33f, 0f, 0f)),
        // Punctuation
        SomEntry(".-..-.", floatArrayOf(0.33f, 1.0f, 0.33f, 0.33f, 1.0f, 0.33f, 0f)),
        SomEntry(".----.", floatArrayOf(0.33f, 1.0f, 1.0f, 1.0f, 1.0f, 0.33f, 0f)),
        SomEntry("...-..-", floatArrayOf(0.33f, 0.33f, 0.33f, 1.0f, 0.33f, 0.33f, 1.0f)),
        SomEntry("-.---.", floatArrayOf(1.0f, 0.33f, 1.0f, 1.0f, 0.33f, 0f, 0f)),
        SomEntry("-.--.-", floatArrayOf(1.0f, 0.33f, 1.0f, 1.0f, 0.33f, 1.0f, 0f)),
        SomEntry("--..--", floatArrayOf(1.0f, 1.0f, 0.33f, 0.33f, 1.0f, 1.0f, 0f)),
        SomEntry("-....-", floatArrayOf(1.0f, 0.33f, 0.33f, 0.33f, 0.33f, 1.0f, 0f)),
        SomEntry(".-.-.-", floatArrayOf(0.33f, 1.0f, 0.33f, 1.0f, 0.33f, 1.0f, 0f)),
        SomEntry("-..-.", floatArrayOf(1.0f, 0.33f, 0.33f, 1.0f, 0.33f, 0f, 0f)),
        SomEntry("---...", floatArrayOf(1.0f, 1.0f, 1.0f, 0.33f, 0.33f, 0.33f, 0f)),
        SomEntry("-.-.-.", floatArrayOf(1.0f, 0.33f, 1.0f, 0.33f, 1.0f, 0.33f, 0f)),
        SomEntry("..--..", floatArrayOf(0.33f, 0.33f, 1.0f, 1.0f, 0.33f, 0.33f, 0f)),
        SomEntry("..--.-", floatArrayOf(0.33f, 0.33f, 1.0f, 1.0f, 0.33f, 1.0f, 0f)),
        SomEntry(".--.-.", floatArrayOf(0.33f, 1.0f, 1.0f, 0.33f, 1.0f, 0.33f, 0f)),
        SomEntry("-.-.--", floatArrayOf(1.0f, 0.33f, 1.0f, 0.33f, 1.0f, 1.0f, 0f)),
        // Accented
        SomEntry(".-.-", floatArrayOf(0.33f, 1.0f, 0.33f, 1.0f, 0f, 0f, 0f)), // A umlaut, A aelig
        SomEntry(".--.-", floatArrayOf(0.33f, 1.0f, 1.0f, 0.33f, 1.0f, 0f, 0f)), // A ring
        SomEntry("-.-..", floatArrayOf(1.0f, 0.33f, 1.0f, 0.33f, 0.33f, 0f, 0f)), // C cedilla
        SomEntry(".-..-", floatArrayOf(0.33f, 1.0f, 0.33f, 0.33f, 1.0f, 0f, 0f)), // E grave
        SomEntry("..-..", floatArrayOf(0.33f, 0.33f, 1.0f, 0.33f, 0.33f, 0f, 0f)), // E acute
        SomEntry("---.", floatArrayOf(1.0f, 1.0f, 1.0f, 0.33f, 0f, 0f, 0f)), // O acute, O umlaut, O slash
        SomEntry("--.--", floatArrayOf(1.0f, 1.0f, 0.33f, 1.0f, 1.0f, 0f, 0f)), // N tilde
        SomEntry("..--", floatArrayOf(0.33f, 0.33f, 1.0f, 1.0f, 0f, 0f, 0f)) // U umlaut, U circ
    )
}
