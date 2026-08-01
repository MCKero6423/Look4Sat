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
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight pitch detector using DFT at specific frequency bins.
 * Only scans [200, 1200] Hz in configurable steps — much faster than full FFT.
 * Ported from ggmorse/src/stfft.h (simplified for CW use case).
 */
internal class CwPitchDetector(
    private val sampleRate: Float,
    private val minFreq: Float = 200f,
    private val maxFreq: Float = 1200f,
    private val stepHz: Float = 10f
) {
    /**
     * Find the dominant pitch frequency in the buffer.
     * Returns null if no significant pitch found.
     */
    fun findPitch(buffer: FloatArray): Float? {
        if (buffer.isEmpty()) return null
        var bestFreq = 0f
        var bestPower = 0f
        var freq = minFreq
        while (freq <= maxFreq) {
            var real = 0.0
            var imag = 0.0
            val omega = 2.0 * kotlin.math.PI * freq / sampleRate
            for (i in buffer.indices) {
                real += buffer[i] * cos(omega * i)
                imag += buffer[i] * -sin(omega * i)
            }
            val power = (real * real + imag * imag).toFloat()
            if (power > bestPower) {
                bestPower = power
                bestFreq = freq
            }
            freq += stepHz
        }
        return if (bestPower > 0.001f) bestFreq else null
    }
}