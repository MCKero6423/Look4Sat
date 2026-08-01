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
 * Running Goertzel filter for CW tone detection.
 * Tracks a specific frequency over time with a sliding window.
 * Ported from ggmorse/src/goertzel.h
 */
internal class CwGoertzel {
    private var s1 = 0.0
    private var s2 = 0.0
    private var coeff = 0.0

    fun init(sampleRate: Float, targetFreq: Float) {
        val omega = 2.0 * kotlin.math.PI * targetFreq / sampleRate
        coeff = 2.0 * cos(omega)
        s1 = 0.0
        s2 = 0.0
    }

    fun process(sample: Float) {
        val s0 = sample.toDouble() + coeff * s1 - s2
        s2 = s1
        s1 = s0
    }

    fun getPower(): Float {
        return sqrt(s2 * s2 + s1 * s1 - coeff * s1 * s2).toFloat()
    }

    fun reset() {
        s1 = 0.0
        s2 = 0.0
    }
}