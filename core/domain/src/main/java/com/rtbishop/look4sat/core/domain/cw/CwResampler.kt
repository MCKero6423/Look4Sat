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
 * Simple linear resampler.
 * Downsamples from input sample rate to output sample rate.
 * Ported from ggmorse/src/resampler.h
 */
internal class CwResampler(private val inputRate: Float, private val outputRate: Float) {
    private val ratio = inputRate / outputRate
    private var lastSample = 0f

    fun process(input: FloatArray): FloatArray {
        if (ratio <= 0f || input.isEmpty()) return input
        val outputLen = (input.size / ratio).toInt() + 1
        val output = FloatArray(outputLen)
        var idx = 0f
        for (i in output.indices) {
            val intIdx = idx.toInt()
            val frac = idx - intIdx
            if (intIdx + 1 < input.size) {
                output[i] = input[intIdx] * (1 - frac) + input[intIdx + 1] * frac
            } else if (intIdx < input.size) {
                output[i] = input[intIdx] * (1 - frac) + lastSample * frac
            } else {
                output[i] = lastSample
            }
            idx += ratio
        }
        lastSample = input.lastOrNull() ?: lastSample
        return output
    }

    fun reset() {
        lastSample = 0f
    }
}