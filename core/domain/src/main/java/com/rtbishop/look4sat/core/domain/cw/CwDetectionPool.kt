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
 * Pools capture chunks until enough audio is available for tone detection.
 *
 * [CwToneShifter.detectToneHz] scans bin by bin, so it needs a few hundred
 * milliseconds to resolve a pitch. A capture chunk is only 320 samples once
 * resampled to [CwDeepSpectrogram.SAMPLE_RATE], hence the pooling: without it a
 * per-chunk size check can never be satisfied and detection silently never runs.
 *
 * A ring buffer rather than a sliding array. Detection is throttled to a couple of
 * seconds while the pool fills in a few hundred milliseconds, so most chunks arrive
 * at a full buffer; shifting the array down one slot per sample cost 320 copies of
 * 1280 floats per chunk, measured at 24320 whole-array moves per 10 s of audio on
 * the capture thread. Writing to a ring index is O(1).
 *
 * Not thread-safe: the decoder drives it from a single capture coroutine.
 *
 * @param capacity samples retained; also the size [drain] returns once full.
 */
class CwDetectionPool(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val samples = FloatArray(capacity)
    private var writeIndex = 0

    /** Samples currently pooled, never above [capacity]. */
    var size: Int = 0
        private set

    /** True once [capacity] samples are pooled and detection can run. */
    val isReady: Boolean get() = size >= capacity

    /** Add a chunk, overwriting the oldest samples once full. */
    fun add(chunk: FloatArray) {
        if (chunk.isEmpty()) return
        // A chunk longer than the pool can only contribute its tail.
        val start = maxOf(0, chunk.size - capacity)
        for (i in start until chunk.size) {
            samples[writeIndex] = chunk[i]
            writeIndex = (writeIndex + 1) % capacity
            if (size < capacity) size++
        }
    }

    /**
     * Hand over the pooled audio in chronological order and empty the pool.
     *
     * Oldest sample first: the detector measures a waveform, so returning the ring in
     * storage order would splice it at the wrap point and corrupt every estimate.
     */
    fun drain(): FloatArray {
        val out = FloatArray(size)
        // Once full the oldest sample sits at the write cursor; before that at index 0.
        val oldest = if (size == capacity) writeIndex else 0
        for (i in 0 until size) {
            out[i] = samples[(oldest + i) % capacity]
        }
        clear()
        return out
    }

    /** Discard everything pooled so far. */
    fun clear() {
        size = 0
        writeIndex = 0
    }
}
