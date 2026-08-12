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

import kotlinx.coroutines.flow.StateFlow

/**
 * A CW (Morse code) decoder fed with microphone PCM.
 *
 * Implementations live outside `core:domain` when they need platform APIs;
 * this contract stays pure Kotlin so the UI can depend on it directly.
 */
interface ICwDecoder {

    /**
     * Decoded text for display.
     *
     * Note this is **replace** semantics, not append: a whole-segment model
     * revises earlier characters as more audio arrives, so consumers must show
     * the current value rather than accumulating emissions.
     */
    val decodedText: StateFlow<String>

    /** Detected tone frequency in Hz, or null before a tone is found. */
    val estimatedPitch: StateFlow<Float?>

    /** Relative signal strength in 0..1 for level meters. */
    val signalStrength: StateFlow<Float>

    /** Most recent inference duration in milliseconds, for diagnostics. */
    val lastInferenceMs: StateFlow<Int>

    /** Non-null when the decoder cannot run, for example the model failed to load. */
    val errorMessage: StateFlow<String?>

    /** Feed captured mono PCM in -1..1. Safe to call from a capture thread. */
    suspend fun processBuffer(samples: FloatArray, sampleRate: Int)

    /** Clear decoded text and buffered audio. */
    fun reset()

    /** Release native resources. Must be called when the decoder goes away. */
    fun close()
}
