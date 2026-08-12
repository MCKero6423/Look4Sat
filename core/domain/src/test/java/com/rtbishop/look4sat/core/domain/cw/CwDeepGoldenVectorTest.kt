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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pins the Kotlin front-end to the upstream Python reference implementation.
 *
 * `golden_spec.txt` was produced by deepcw-engine's own preprocessing code
 * (numpy reflect padding, `np.hanning(N+1)[:-1]`, `np.fft.rfft`, `log1p`) over
 * the audio [generateTestAudio] builds. Both sides synthesise the audio from
 * the same deterministic formula, so only the spectrogram needs pinning.
 *
 * A mismatch here means the model would receive subtly wrong input and emit
 * plausible-looking garbage, which is very hard to diagnose downstream — so
 * this test guards the whole pipeline.
 *
 * Regenerate with `scripts/deepcw_gen_golden.py` from the skill library if the
 * model metadata ever changes.
 */
class CwDeepGoldenVectorTest {

    private companion object {
        const val SRC_RATE = 8000
        const val TONE_HZ = 700.0
        const val AMPLITUDE = 0.6
        const val DOT_SAMPLES = 480      // 20 WPM at 8000 Hz: 1.2/20*8000
        const val PATTERN = "-.-."       // the letter C
        const val TOLERANCE = 1e-4f
    }

    /** Square-keyed 700 Hz tone: 4 dots of silence, "C", 4 dots of silence. */
    private fun generateTestAudio(): FloatArray {
        val keying = ArrayList<Int>()
        repeat(4 * DOT_SAMPLES) { keying.add(0) }
        for ((i, element) in PATTERN.withIndex()) {
            val length = if (element == '-') 3 * DOT_SAMPLES else DOT_SAMPLES
            repeat(length) { keying.add(1) }
            if (i < PATTERN.length - 1) repeat(DOT_SAMPLES) { keying.add(0) }
        }
        repeat(4 * DOT_SAMPLES) { keying.add(0) }

        return FloatArray(keying.size) { i ->
            if (keying[i] == 1) {
                (AMPLITUDE * sin(2.0 * PI * TONE_HZ * i / SRC_RATE)).toFloat()
            } else {
                0f
            }
        }
    }

    private fun readGoldenSpectrogram(): Array<FloatArray> {
        val stream = javaClass.classLoader?.getResourceAsStream("cw/golden_spec.txt")
            ?: throw IllegalStateException("cw/golden_spec.txt missing from test resources")
        stream.bufferedReader().use { reader ->
            val (frames, bins) = reader.readLine().trim().split(" ").map(String::toInt)
            return Array(frames) {
                val row = reader.readLine().trim().split(" ")
                require(row.size == bins) { "expected $bins values, got ${row.size}" }
                FloatArray(bins) { i -> row[i].toFloat() }
            }
        }
    }

    @Test
    fun spectrogramMatchesPythonReferenceFrameByFrame() {
        val expected = readGoldenSpectrogram()
        val audio = CwDeepSpectrogram.resampleLinear(
            generateTestAudio(), SRC_RATE, CwDeepSpectrogram.SAMPLE_RATE
        )
        val actual = CwDeepSpectrogram.compute(audio)

        assertEquals("frame count", expected.size, actual.size)
        assertEquals("bin count", expected[0].size, actual[0].size)

        var worstDelta = 0f
        var worstAt = ""
        for (t in expected.indices) {
            for (f in expected[t].indices) {
                val delta = abs(expected[t][f] - actual[t][f])
                if (delta > worstDelta) {
                    worstDelta = delta
                    worstAt = "frame $t bin $f: expected ${expected[t][f]}, got ${actual[t][f]}"
                }
            }
        }
        assertTrue(
            "front-end diverges from the Python reference — worst delta $worstDelta at $worstAt",
            worstDelta <= TOLERANCE
        )
    }

    @Test
    fun resampledLengthMatchesReference() {
        val audio = generateTestAudio()
        assertEquals("source audio length", 9120, audio.size)
        val resampled = CwDeepSpectrogram.resampleLinear(
            audio, SRC_RATE, CwDeepSpectrogram.SAMPLE_RATE
        )
        assertEquals("resampled length", 3648, resampled.size)
    }

    @Test
    fun goldenVectorHasExpectedShape() {
        val golden = readGoldenSpectrogram()
        assertEquals("frames", 77, golden.size)
        assertEquals("bins", CwDeepSpectrogram.FREQUENCY_BINS, golden[0].size)
    }
}
