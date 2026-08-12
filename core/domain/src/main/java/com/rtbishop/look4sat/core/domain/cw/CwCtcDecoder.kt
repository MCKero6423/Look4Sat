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
 * Turns the model's `log_probs` output into text.
 *
 * Mirrors the reference `greedy_ctc_decode`: take the best class per frame,
 * drop blanks, and collapse runs of the same label. A blank between two
 * identical labels is what keeps a genuine double letter (for example the
 * two N's in "5NN") from collapsing into one.
 */
object CwCtcDecoder {

    /**
     * @param logProbs `[batch, time, class]`; only batch 0 is read.
     * @param chars class index to symbol, excluding the blank.
     * @param blankIndex the CTC blank class (41 for this model).
     */
    fun greedy(
        logProbs: Array<Array<FloatArray>>,
        chars: List<String>,
        blankIndex: Int
    ): String {
        if (logProbs.isEmpty()) return ""
        val frames = logProbs[0]
        val builder = StringBuilder()
        var previous = -1

        for (frame in frames) {
            var best = 0
            for (i in 1 until frame.size) {
                if (frame[i] > frame[best]) best = i
            }
            if (best == blankIndex) {
                previous = -1
                continue
            }
            if (best != previous && best < chars.size) {
                builder.append(chars[best])
            }
            previous = best
        }
        return builder.toString()
    }
}
