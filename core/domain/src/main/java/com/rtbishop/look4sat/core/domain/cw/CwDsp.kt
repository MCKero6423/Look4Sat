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

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DSP utilities for CW (Morse code) decoding.
 * Pure Kotlin, no NDK required.
 */
internal object CwDsp {

    /**
     * Design a simple bandpass FIR filter coefficients using windowed sinc method.
     * @param lowCutoff  lower cutoff frequency (Hz) as fraction of sampleRate
     * @param highCutoff upper cutoff frequency (Hz) as fraction of sampleRate
     * @param taps       filter length (must be odd)
     */
    fun bandpassFir(lowCutoff: Double, highCutoff: Double, taps: Int): FloatArray {
        val n = if (taps % 2 == 0) taps + 1 else taps
        val half = n / 2
        val coeffs = FloatArray(n)
        for (i in 0 until n) {
            val idx = i - half
            if (idx == 0) {
                coeffs[i] = (2.0 * (highCutoff - lowCutoff)).toFloat()
            } else {
                val x = PI * idx
                coeffs[i] = ((sin(2 * highCutoff * x) - sin(2 * lowCutoff * x)) / x).toFloat()
            }
            // Hamming window
            coeffs[i] = (coeffs[i] * (0.54 - 0.46 * cos(2 * PI * i / (n - 1)))).toFloat()
        }
        // Normalize
        val sum = coeffs.sum()
        if (sum != 0f) for (i in 0 until n) coeffs[i] /= sum
        return coeffs
    }

    /** Apply FIR filter to a buffer. */
    fun applyFir(buffer: FloatArray, coeffs: FloatArray): FloatArray {
        val out = FloatArray(buffer.size)
        for (i in buffer.indices) {
            var sum = 0f
            for (j in coeffs.indices) {
                val idx = i - j
                if (idx >= 0) sum += buffer[idx] * coeffs[j]
            }
            out[i] = sum
        }
        return out
    }

    /** Simple envelope detector: abs + low-pass smoothing. */
    fun envelope(signal: FloatArray, alpha: Float = 0.1f): FloatArray {
        val env = FloatArray(signal.size)
        var s = 0f
        for (i in signal.indices) {
            s = alpha * kotlin.math.abs(signal[i]) + (1 - alpha) * s
            env[i] = s
        }
        return env
    }

    /** Estimate noise floor from envelope for adaptive thresholding. */
    fun noiseFloor(env: FloatArray, fraction: Float = 0.3f): Float {
        val sorted = env.sortedArray()
        val median = sorted[sorted.size / 2]
        return median + (sorted[sorted.size * 9 / 10] - median) * fraction
    }

    /** Simple Goertzel to detect a specific tone frequency. */
    fun goertzel(buffer: FloatArray, targetFreq: Float, sampleRate: Int): Float {
        val omega = 2.0 * PI * targetFreq / sampleRate
        val coeff = 2.0 * cos(omega)
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0
        for (sample in buffer) {
            s0 = sample.toDouble() + coeff * s1 - s2
            s2 = s1; s1 = s0
        }
        val power = s2 * s2 + s1 * s1 - coeff * s1 * s2
        return sqrt(kotlin.math.abs(power)).toFloat()
    }
}