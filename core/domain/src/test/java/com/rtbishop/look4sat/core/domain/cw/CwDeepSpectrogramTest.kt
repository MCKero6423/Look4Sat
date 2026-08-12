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
 * Verifies the DeepCW front-end against the upstream Python reference
 * implementation (deepcw-engine examples/python/decode_morse.py).
 *
 * Model metadata: sampleRate 3200, fftLength 256, hopLength 48,
 * 400-1200 Hz -> 65 bins, log1p normalization.
 */
class CwDeepSpectrogramTest {

    @Test
    fun frequencyBinRange_matchesModelMetadata() {
        // binHz = 3200/256 = 12.5; start = ceil(400/12.5) = 32; stop = floor(1200/12.5)+1 = 97
        val (start, stop) = CwDeepSpectrogram.frequencyBinRange(3200, 256, 400.0, 1200.0)
        assertEquals(32, start)
        assertEquals(97, stop)
        assertEquals("metadata declares 65 frequency bins", 65, stop - start)
    }

    @Test
    fun compute_producesTimeBy65Matrix() {
        // 1 second at 3200 Hz. Reflect padding adds fft/2 on both sides,
        // so frames = 1 + (3200 + 256 - 256)/48 = 1 + 66 = 67
        val spec = CwDeepSpectrogram.compute(FloatArray(3200))
        assertEquals(67, spec.size)
        assertEquals(65, spec[0].size)
    }

    @Test
    fun compute_toneLandsInExpectedBin() {
        // 700 Hz -> absolute bin 700/12.5 = 56 -> relative index 56 - 32 = 24
        val audio = FloatArray(3200) { (0.6 * sin(2.0 * PI * 700.0 * it / 3200.0)).toFloat() }
        val spec = CwDeepSpectrogram.compute(audio)
        val middle = spec[spec.size / 2]
        val peak = middle.indices.maxByOrNull { middle[it] } ?: -1
        assertTrue("peak at index $peak, expected near 24", abs(peak - 24) <= 1)
    }

    @Test
    fun compute_appliesLog1pSoValuesAreNonNegative() {
        val audio = FloatArray(3200) { (0.6 * sin(2.0 * PI * 700.0 * it / 3200.0)).toFloat() }
        val spec = CwDeepSpectrogram.compute(audio)
        for (frame in spec) {
            for (v in frame) {
                assertTrue("log1p of a magnitude must be >= 0, got $v", v >= 0f)
            }
        }
    }

    @Test
    fun resampleLinear_convertsRateAndLength() {
        assertEquals(3200, CwDeepSpectrogram.resampleLinear(FloatArray(8000), 8000, 3200).size)
        assertEquals(3200, CwDeepSpectrogram.resampleLinear(FloatArray(44100), 44100, 3200).size)
    }

    @Test
    fun resampleLinear_sameRateIsIdentity() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f)
        val out = CwDeepSpectrogram.resampleLinear(input, 3200, 3200)
        assertEquals(3, out.size)
        assertEquals(0.2f, out[1], 1e-6f)
    }

    @Test
    fun resampleLinear_preservesToneFrequency() {
        // A 700 Hz tone sampled at 8000 Hz must still peak at bin 24 after
        // resampling to 3200 Hz — this is the path real microphone audio takes.
        val at8k = FloatArray(8000) { (0.6 * sin(2.0 * PI * 700.0 * it / 8000.0)).toFloat() }
        val at3200 = CwDeepSpectrogram.resampleLinear(at8k, 8000, 3200)
        val spec = CwDeepSpectrogram.compute(at3200)
        val middle = spec[spec.size / 2]
        val peak = middle.indices.maxByOrNull { middle[it] } ?: -1
        assertTrue("resampled tone peak at $peak, expected near 24", abs(peak - 24) <= 1)
    }

    @Test
    fun compute_rejectsAudioShorterThanFftLength() {
        try {
            CwDeepSpectrogram.compute(FloatArray(100))
            throw AssertionError("expected an exception for audio shorter than fftLength")
        } catch (expected: IllegalArgumentException) {
            // desired path
        }
    }
}
