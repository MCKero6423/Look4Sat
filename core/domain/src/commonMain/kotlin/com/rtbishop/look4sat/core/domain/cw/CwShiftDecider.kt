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

import kotlin.math.abs

/**
 * Decides what shift to apply from a sequence of tone estimates.
 *
 * Kept out of the decoder so the rule can be exercised directly. The decoder needs an
 * Android Context and a loaded ONNX session, so a rule living inside it can only be
 * tested by restating it - and a restated rule cannot fail when the real one is wrong.
 * Mutation testing proved that: four defects injected into an in-decoder version of this
 * logic left the whole suite green.
 *
 * @param hysteresisHz how far the tone must move before the shift is revised.
 */
class CwShiftDecider(private val hysteresisHz: Float = DEFAULT_HYSTERESIS_HZ) {

    companion object {
        /**
         * Default margin before re-shifting, in Hz.
         *
         * Detection resolves to 12.5 Hz and a real tone wanders, so a couple of scan bins
         * of jitter must not count as a retune: revising the shift costs the whole 20 s
         * decode window, which is worth far more than perfect centring.
         */
        const val DEFAULT_HYSTERESIS_HZ = 40f
    }

    /** Shift currently applied to incoming audio; 0 when the tone needs no move. */
    var shiftHz: Float = 0f
        private set

    /**
     * Tone that produced [shiftHz]. Hysteresis compares against this rather than against
     * the previous shift, because a shift of 0 is a real state: at the window edge one
     * 12.5 Hz estimate hop flips between "inside" (shift 0) and "outside" (a large
     * shift), and a shift-space comparison lapses exactly where the jump is largest.
     */
    var anchorToneHz: Float? = null
        private set

    /** What [accept] decided, for logging. */
    enum class Outcome {
        /** No tone in the window; the existing shift was retained. */
        NO_TONE,

        /** The tone moved less than the margin; the existing shift was retained. */
        WITHIN_HYSTERESIS,

        /** The tone is inside the model window, so no shift is needed. */
        NO_SHIFT_NEEDED,

        /** The shift was updated to move an out-of-window tone into range. */
        SHIFTED
    }

    /** Result of feeding one detection to the decider. */
    data class Decision(
        val outcome: Outcome,
        /** Shift in force after the decision. */
        val shiftHz: Float,
        /** True when [shiftHz] differs from the value before this decision. */
        val changed: Boolean,
        /** Tone the decision was based on, null when none was detected. */
        val toneHz: Float?
    )

    /**
     * Feed one tone analysis and get the shift to apply.
     *
     * Silence retains the current shift rather than clearing it: CW is keyed, so a
     * detection window landing in a gap carries no information about the pitch. Treating
     * it as an authoritative "no shift" collapsed established shifts - measured over
     * 180 s of keyed audio at 1400 Hz, 11 of 90 windows saw no tone, and each one left
     * the following audio unshifted and therefore invisible to the model.
     */
    fun accept(analysis: CwToneShifter.Analysis): Decision {
        val previousShift = shiftHz
        val toneHz = analysis.toneHz
            ?: return Decision(Outcome.NO_TONE, previousShift, changed = false, toneHz = null)

        val anchor = anchorToneHz
        if (anchor != null && abs(toneHz - anchor) < hysteresisHz) {
            return Decision(Outcome.WITHIN_HYSTERESIS, previousShift, changed = false, toneHz = toneHz)
        }

        shiftHz = analysis.shiftHz
        anchorToneHz = toneHz
        val outcome = if (analysis.needsShift) Outcome.SHIFTED else Outcome.NO_SHIFT_NEEDED
        return Decision(outcome, shiftHz, changed = shiftHz != previousShift, toneHz = toneHz)
    }

    /** Forget the current shift and anchor, e.g. when the feature is toggled or reset. */
    fun reset() {
        shiftHz = 0f
        anchorToneHz = null
    }
}
