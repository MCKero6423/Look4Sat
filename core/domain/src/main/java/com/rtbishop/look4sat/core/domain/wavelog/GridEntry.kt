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
 * Checks a typed counterpart grid before it reaches the log.
 *
 * Separate from qthToPosition's validator, which requires six characters and is private. Plenty of
 * satellite operators exchange only the four-character square, so requiring six would reject
 * perfectly good entries - and this follows the same rule the callsign field settled on: refuse only
 * what is certainly wrong, warn about the rest, never silently discard.
 *
 * The reason to check at all is that an unchecked value goes into the ADIF GRIDSQUARE field, and a
 * malformed one is stored by Wavelog as-is. It then pollutes grid statistics and VUCC award
 * tracking, where a wrong square is worse than a missing one.
 */
object GridEntry {

    /** What a typed grid amounts to. */
    sealed interface Verdict {

        /** Usable. [normalised] is what should be logged - upper case for the pair, lower for the subsquare. */
        data class Acceptable(val normalised: String, val warning: Warning? = null) : Verdict

        /** Certainly not a grid. [reason] says which rule it broke. */
        data class Unusable(val reason: Reason) : Verdict

        /** Nothing typed. The QRZ lookup should run instead. */
        data object Empty : Verdict
    }

    /** Worth mentioning but not worth refusing. */
    enum class Warning {
        /** Four characters, so the location is only accurate to about 100km. */
        SQUARE_ONLY
    }

    /** Why an entry cannot be a grid. */
    enum class Reason {
        /** Not 4, 6 or 8 characters. Maidenhead has no other lengths. */
        WRONG_LENGTH,

        /** First pair outside A-R. S-X would decode past the poles. */
        FIELD_OUT_OF_RANGE,

        /** Second pair is not two digits. */
        SQUARE_NOT_DIGITS,

        /** Third pair outside A-X. */
        SUBSQUARE_OUT_OF_RANGE
    }

    /**
     * Judge a typed entry.
     *
     * Case is normalised on the way out rather than demanded on the way in - an operator typing
     * one-handed outdoors should not have to care, and the conventional rendering is upper case for
     * the field, digits, then lower case for the subsquare.
     */
    fun check(entry: String): Verdict {
        val text = entry.trim()
        if (text.isEmpty()) return Verdict.Empty
        if (text.length !in VALID_LENGTHS) return Verdict.Unusable(Reason.WRONG_LENGTH)

        val upper = text.uppercase()
        if (upper[0] !in FIELD_RANGE || upper[1] !in FIELD_RANGE) {
            return Verdict.Unusable(Reason.FIELD_OUT_OF_RANGE)
        }
        if (!upper[2].isDigit() || !upper[3].isDigit()) {
            return Verdict.Unusable(Reason.SQUARE_NOT_DIGITS)
        }
        if (text.length >= SUBSQUARE_LENGTH) {
            if (upper[4] !in SUBSQUARE_RANGE || upper[5] !in SUBSQUARE_RANGE) {
                return Verdict.Unusable(Reason.SUBSQUARE_OUT_OF_RANGE)
            }
        }
        if (text.length == EXTENDED_LENGTH && (!upper[6].isDigit() || !upper[7].isDigit())) {
            return Verdict.Unusable(Reason.SQUARE_NOT_DIGITS)
        }

        return Verdict.Acceptable(
            normalised = normalise(upper),
            warning = if (text.length == SQUARE_LENGTH) Warning.SQUARE_ONLY else null
        )
    }

    /** `OL72ap` - upper case field, digits, lower case subsquare, as the convention renders it. */
    private fun normalise(upper: String): String = buildString {
        append(upper.take(SQUARE_LENGTH))
        if (upper.length >= SUBSQUARE_LENGTH) append(upper.substring(4, 6).lowercase())
        if (upper.length == EXTENDED_LENGTH) append(upper.substring(6, 8))
    }

    private const val SQUARE_LENGTH = 4
    private const val SUBSQUARE_LENGTH = 6
    private const val EXTENDED_LENGTH = 8
    private val VALID_LENGTHS = setOf(SQUARE_LENGTH, SUBSQUARE_LENGTH, EXTENDED_LENGTH)
    private val FIELD_RANGE = 'A'..'R'
    private val SUBSQUARE_RANGE = 'A'..'X'
}
