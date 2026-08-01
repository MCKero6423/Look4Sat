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
 * First-order IIR filters.
 * Ported from ggmorse/src/filter.h
 */
internal class CwFilter {
    private var z1 = 0f

    companion object {
        private const val PI_F = 3.141592653589793f
    }

    fun highPass(sample: Float, cutoffHz: Float, sampleRate: Float): Float {
        val rc = 1.0f / (2f * PI_F * cutoffHz)
        val dt = 1.0f / sampleRate
        val alpha = dt / (rc + dt)
        z1 = alpha * (z1 + sample - z1)
        return sample - z1
    }

    fun lowPass(sample: Float, cutoffHz: Float, sampleRate: Float): Float {
        val rc = 1.0f / (2f * PI_F * cutoffHz)
        val dt = 1.0f / sampleRate
        val alpha = dt / (rc + dt)
        z1 += alpha * (sample - z1)
        return z1
    }

    fun reset() { z1 = 0f }
}