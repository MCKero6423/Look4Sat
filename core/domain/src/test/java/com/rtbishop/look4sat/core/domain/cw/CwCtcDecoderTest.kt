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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Greedy CTC collapse, matching the reference implementation's
 * `greedy_ctc_decode`: drop blanks, then drop runs of the same label.
 *
 * Alphabet from `model.onnx.json` — 41 symbols plus blank at index 41.
 */
class CwCtcDecoderTest {

    private val chars = listOf(
        ",", ".", "/", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "?",
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N",
        "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", " "
    )
    private val blank = 41

    /** Build a `[1, T, 42]` log-prob tensor whose argmax follows [path]. */
    private fun logits(path: IntArray): Array<Array<FloatArray>> {
        val frames = Array(path.size) { t ->
            FloatArray(42) { -10f }.also { it[path[t]] = 0f }
        }
        return arrayOf(frames)
    }

    @Test
    fun alphabetSizeMatchesModelMetadata() {
        assertEquals("41 symbols + blank = 42 classes", 41, chars.size)
    }

    @Test
    fun greedy_dropsRunsOfTheSameLabel() {
        assertEquals("A", CwCtcDecoder.greedy(logits(intArrayOf(14, 14, 14)), chars, blank))
    }

    @Test
    fun greedy_keepsRepeatsSeparatedByBlank() {
        // A A <blank> A collapses to "AA": the blank breaks the run.
        assertEquals("AA", CwCtcDecoder.greedy(logits(intArrayOf(14, 14, blank, 14)), chars, blank))
    }

    @Test
    fun greedy_allBlanksYieldEmptyString() {
        assertEquals("", CwCtcDecoder.greedy(logits(intArrayOf(blank, blank, blank)), chars, blank))
    }

    @Test
    fun greedy_emptyInputYieldsEmptyString() {
        assertEquals("", CwCtcDecoder.greedy(logits(intArrayOf()), chars, blank))
    }

    @Test
    fun greedy_decodesCallsignWithSpaceAndDigits() {
        // "CQ BG7" — C=16 Q=30 space=40 B=15 G=20 7=10
        val path = intArrayOf(
            blank, 16, 16, blank, 30, blank, 40,
            15, blank, 20, blank, 10, blank
        )
        assertEquals("CQ BG7", CwCtcDecoder.greedy(logits(path), chars, blank))
    }

    @Test
    fun greedy_picksHighestScoringClassPerFrame() {
        // Frame favours S (32) over T (33); only S must survive.
        val frame = FloatArray(42) { -10f }
        frame[33] = -1f
        frame[32] = -0.1f
        assertEquals("S", CwCtcDecoder.greedy(arrayOf(arrayOf(frame)), chars, blank))
    }
}
