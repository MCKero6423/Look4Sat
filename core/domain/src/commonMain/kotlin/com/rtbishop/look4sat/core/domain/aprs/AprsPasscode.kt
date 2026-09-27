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
 * Decides what passcode to present to APRS-IS, and whether the operator's entry is usable.
 *
 * The app must not derive a transmit passcode for the operator. APRS-IS states that supplying
 * the correct passcode to a user is the software author's responsibility, and the passcode
 * exists as a licence check - deriving it in-app and shipping the algorithm defeats the point.
 * APRSdroid has the same algorithm in the same file and deliberately does not use it for this
 * reason, validating the operator's entry instead and linking out to request one.
 *
 * So this validates. [AprsPacket.passcode] stays, because checking an entry means recomputing
 * the expected value, but nothing here substitutes a derived code for a missing one.
 */
object AprsPasscode {

    /** Value that asks APRS-IS for a receive-only connection. Always legitimate. */
    const val RECEIVE_ONLY = -1

    /** What the operator's passcode entry amounts to. */
    sealed interface Entry {

        /** A passcode that matches the callsign. Reports will be forwarded. */
        data class Transmit(val passcode: Int) : Entry

        /**
         * An explicit -1, or a blank entry.
         *
         * A blank entry lands here rather than being filled in with a derived code: connecting
         * receive-only is honest about what an operator without a passcode can do, where a
         * derived code silently claims a licence check that was never performed.
         */
        data object ReceiveOnly : Entry

        /** Something was typed but it is not this callsign's passcode. */
        data class Mismatch(val expectedFor: String) : Entry

        /** Something was typed that is not a number at all. */
        data object NotANumber : Entry
    }

    /**
     * Classify what the operator typed.
     *
     * A mismatch is reported rather than corrected, so the UI can refuse to save and say why.
     * Silently swapping in a derived code is how an operator ends up believing they are
     * transmitting under a passcode they never obtained.
     */
    fun classify(callsign: String, entry: String): Entry {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) return Entry.ReceiveOnly
        val value = trimmed.toIntOrNull() ?: return Entry.NotANumber
        // Checked before the callsign comparison: -1 is the documented receive-only value and
        // is never anyone's passcode, so comparing it would report a deliberate choice as a typo.
        if (value == RECEIVE_ONLY) return Entry.ReceiveOnly
        val call = callsign.trim()
        if (call.isEmpty()) return Entry.Mismatch("")
        return if (value == AprsPacket.passcode(call)) {
            Entry.Transmit(value)
        } else {
            Entry.Mismatch(call.uppercase())
        }
    }

    /**
     * The number to send in the login line for this entry.
     *
     * Anything not usable becomes [RECEIVE_ONLY]: the connection still works, the operator is
     * told separately that their reports are not being forwarded, and no packet goes out under
     * a passcode the app invented. The previous code sent a derived transmit passcode here,
     * and `takeIf { it >= 0 }` additionally made an explicit -1 impossible to use - which also
     * blocked the one safe way to test a setup, since a receive-only login is how you confirm
     * the connection works without putting anything on the network.
     */
    fun loginValue(callsign: String, entry: String): Int =
        when (val classified = classify(callsign, entry)) {
            is Entry.Transmit -> classified.passcode
            else -> RECEIVE_ONLY
        }

    /** True when this entry lets the operator's reports reach the network. */
    fun canTransmit(callsign: String, entry: String): Boolean =
        classify(callsign, entry) is Entry.Transmit
}
