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
 * Whether a typed callsign can be logged, and what to warn about if it looks odd.
 *
 * Deliberately permissive. A survey of real callsigns against a typical strict pattern rejected
 * 16 of 28 valid ones - `W1AW/4`, `2E0ABC`, `9A1CCY`, `SV2ASP/A` among them - while a pattern
 * loose enough to accept those also accepts a Maidenhead locator as a callsign. There is no
 * regex that catches typos without discarding legitimate calls, so anything plausible is
 * accepted and doubt is reported rather than enforced.
 *
 * The previous behaviour was `if (call.length < 3) return`, which discarded the entry with no
 * message: the operator pressed done during a pass and nothing happened, with no way to tell
 * that the app had decided against them. None of the logging software surveyed - N1MM+, DXLog,
 * PoLo, HAMRS - silently drops a submission.
 */
object CallsignEntry {

    /** Shortest real callsign. Two characters occur in special event calls. */
    private const val MIN_LENGTH = 2

    /** Longest plausible entry, allowing a portable suffix such as `OH/W1AW/MM`. */
    private const val MAX_LENGTH = 16

    /** Characters a callsign may contain. */
    private val allowed = Regex("^[A-Z0-9/-]+$")

    /** The outcome of checking an entry. */
    sealed interface Verdict {

        /** Log it. [warning] is non-null when the entry is unusual but still plausible. */
        data class Acceptable(val callsign: String, val warning: Warning? = null) : Verdict

        /** Do not log it, and say why. */
        data class Rejected(val reason: Reason) : Verdict
    }

    /** Why an entry cannot be logged at all. */
    enum class Reason {
        /** Nothing was typed. */
        EMPTY,

        /** Too short to be any callsign. */
        TOO_SHORT,

        /** Longer than any real callsign with a portable suffix. */
        TOO_LONG,

        /** Contains something a callsign cannot: punctuation, spaces, non-ASCII. */
        ILLEGAL_CHARACTERS,

        /** Digits only, or letters only - no callsign is either. */
        NOT_A_CALLSIGN
    }

    /** Something worth mentioning without blocking the entry. */
    enum class Warning {
        /** Looks like a Maidenhead locator rather than a callsign, e.g. `GG77DH`. */
        LOOKS_LIKE_A_GRID,

        /** Already logged in this session - fine on a later pass, likely a slip on this one. */
        ALREADY_WORKED
    }

    /**
     * Check an entry, optionally against calls already logged in this pass.
     *
     * A repeat is a warning rather than a rejection: the same station on a later pass is a
     * legitimate new contact, and contest loggers default to allowing duplicates - DXLog
     * describes refusing them as an outdated habit.
     */
    fun check(entry: String, workedThisSession: Set<String> = emptySet()): Verdict {
        val call = entry.trim().uppercase()
        if (call.isEmpty()) return Verdict.Rejected(Reason.EMPTY)
        if (call.length < MIN_LENGTH) return Verdict.Rejected(Reason.TOO_SHORT)
        if (call.length > MAX_LENGTH) return Verdict.Rejected(Reason.TOO_LONG)
        if (!allowed.matches(call)) return Verdict.Rejected(Reason.ILLEGAL_CHARACTERS)
        val core = call.substringBefore('/').substringBefore('-')
        if (core.none { it.isDigit() } || core.none { it.isLetter() }) {
            return Verdict.Rejected(Reason.NOT_A_CALLSIGN)
        }
        val warning = when {
            call in workedThisSession -> Warning.ALREADY_WORKED
            looksLikeGrid(call) -> Warning.LOOKS_LIKE_A_GRID
            else -> null
        }
        return Verdict.Acceptable(call, warning)
    }

    /**
     * Whether this looks like a Maidenhead locator typed into the wrong field.
     *
     * Six characters of letter-letter-digit-digit-letter-letter. Worth mentioning because grid
     * and callsign are exchanged together on FM satellites and the fields sit side by side.
     */
    private fun looksLikeGrid(call: String): Boolean =
        call.length == 6 &&
            call[0].isLetter() && call[1].isLetter() &&
            call[2].isDigit() && call[3].isDigit() &&
            call[4].isLetter() && call[5].isLetter()
}
