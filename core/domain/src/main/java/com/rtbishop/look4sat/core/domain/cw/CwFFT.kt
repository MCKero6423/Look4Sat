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

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Radix-2 FFT for real-valued input.
 * Produces magnitude spectrum for the first N/2+1 bins.
 * Used by CwSpectrogram for time-frequency analysis.
 */
internal class CwFFT(private val n: Int) {
    init {
        require(n > 0 && n and (n - 1) == 0) { "FFT size must be power of 2, got $n" }
    }

    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)

    init {
        for (i in 0 until n / 2) {
            val angle = -2.0 * kotlin.math.PI * i / n
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = kotlin.math.sin(angle).toFloat()
        }
    }

    /** Compute magnitude spectrum for real input. Returns array of size n/2+1. */
    fun magnitudeSpectrum(input: FloatArray): FloatArray {
        require(input.size == n) { "Input size must be $n, got ${input.size}" }

        val real = input.copyOf()
        val imag = FloatArray(n)

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
            }
        }

        // Radix-2 Cooley-Tukey FFT
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            for (i in 0 until n step len) {
                for (k in 0 until half) {
                    val tReal = real[i + k + half] * cosTable[k * step] - imag[i + k + half] * sinTable[k * step]
                    val tImag = real[i + k + half] * sinTable[k * step] + imag[i + k + half] * cosTable[k * step]
                    real[i + k + half] = real[i + k] - tReal
                    imag[i + k + half] = imag[i + k] - tImag
                    real[i + k] += tReal
                    imag[i + k] += tImag
                }
            }
            len = len shl 1
        }

        // Magnitude spectrum (first N/2+1 bins)
        val mag = FloatArray(n / 2 + 1)
        for (i in 0..n / 2) {
            mag[i] = sqrt(real[i] * real[i] + imag[i] * imag[i]) / n
        }
        return mag
    }
}