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
 * Works out what time a contact should carry.
 *
 * The logging screen used to stamp System.currentTimeMillis() and offer no way to change it. That
 * assumes contacts are typed as they happen, and serious satellite operators do not work that way:
 * the documented practice is to record the pass and transcribe it afterwards, because during eight
 * minutes of a linear transponder there is no spare attention for a keyboard. A fixed clock makes
 * every transcribed contact wrong by however long the transcription took.
 *
 * Two ways to say when: an absolute UTC time of day, or an offset from now. Both are typed into the
 * same field, because a separate widget for each is more to reach for than an operator wants while
 * holding an antenna.
 */
object PassClock {

    /** What a typed token meant. */
    sealed interface Command {

        /** Log at this UTC time of day. [minuteOfDay] is minutes since 00:00 UTC. */
        data class At(val minuteOfDay: Int) : Command

        /** Log this many minutes from now. Negative counts backwards. */
        data class Shift(val minutes: Int) : Command

        /** Back to the current time. */
        data object Live : Command

        /** Not a time instruction. The caller should leave the clock alone. */
        data object Unrecognised : Command
    }

    /**
     * Interpret a typed token.
     *
     * Deliberately narrow. Anything that is not clearly a time is [Command.Unrecognised] rather than
     * a guess, because a mis-parsed time silently backdates a contact and nothing downstream would
     * catch it.
     *
     * Accepted: `14:55` or `1455` for a UTC time of day; `+3` or `-2` for a shift in minutes, with
     * an optional `m`; empty or `now` to return to live time.
     */
    fun parse(entry: String): Command {
        val text = entry.trim().lowercase()
        if (text.isEmpty() || text == "now") return Command.Live
        if (text.startsWith("+") || text.startsWith("-")) return parseShift(text)
        return parseTimeOfDay(text)
    }

    /** `+3`, `-2m`, `+15`. */
    private fun parseShift(text: String): Command {
        val negative = text.startsWith("-")
        val digits = text.drop(1).removeSuffix("m")
        val minutes = digits.toIntOrNull() ?: return Command.Unrecognised
        if (minutes > MAX_SHIFT_MINUTES) return Command.Unrecognised
        return Command.Shift(if (negative) -minutes else minutes)
    }

    /** `14:55` or `1455`. */
    private fun parseTimeOfDay(text: String): Command {
        val digits = text.replace(":", "")
        if (digits.length != TIME_DIGITS || digits.any { !it.isDigit() }) return Command.Unrecognised
        val hours = digits.take(2).toInt()
        val minutes = digits.drop(2).toInt()
        if (hours > MAX_HOUR || minutes > MAX_MINUTE) return Command.Unrecognised
        return Command.At(hours * MINUTES_PER_HOUR + minutes)
    }

    /**
     * Apply a command, returning the timestamp a contact should carry.
     *
     * [now] is the current UTC time in milliseconds and [dayStart] is midnight UTC of the day [now]
     * falls in - passed in rather than computed, because core:domain holds no calendar and the
     * caller already knows which day it is working with.
     *
     * An absolute time later than [now] is read as belonging to the previous day: transcription
     * happens after the pass, so a pass that ran across midnight UTC is the common case, not an
     * error. Without this a contact logged at 23:58 while transcribing at 00:05 would land a full
     * day in the future.
     */
    fun resolve(command: Command, now: Long, dayStart: Long): Long = when (command) {
        is Command.At -> {
            val candidate = dayStart + command.minuteOfDay * MILLIS_PER_MINUTE
            if (candidate > now) candidate - MILLIS_PER_DAY else candidate
        }
        is Command.Shift -> now + command.minutes * MILLIS_PER_MINUTE
        Command.Live, Command.Unrecognised -> now
    }

    /** Whether a command moves the clock off live time, so the UI can show that it has. */
    fun isHolding(command: Command): Boolean =
        command is Command.At || (command is Command.Shift && command.minutes != 0)

    private const val TIME_DIGITS = 4
    private const val MAX_HOUR = 23
    private const val MAX_MINUTE = 59
    private const val MINUTES_PER_HOUR = 60

    /** A pass lasts minutes. Anything larger is a typo, not an intention. */
    private const val MAX_SHIFT_MINUTES = 720

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_DAY = 86_400_000L
}
