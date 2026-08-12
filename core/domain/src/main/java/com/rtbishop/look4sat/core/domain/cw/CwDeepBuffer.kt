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
 * Bounded rolling audio buffer that drives periodic full re-decodes.
 *
 * DeepCW is a whole-segment CTC model. It rewrites earlier output as more
 * context arrives — measured on one clip, 6 of 11 progressively longer reads
 * revised the prefix (`BM` -> `BG7` -> `BG7NTI` -> `BG7NTA`). Appending only
 * the newest fragment therefore leaves those intermediate guesses on screen
 * forever; measured character error rate for sliding-window stitching ranged
 * from 67% to 294%, against 0% for decoding the whole segment at once.
 *
 * So we keep a fixed window, re-run the model over all of it every
 * [redecodeIntervalMs], and replace the displayed text outright.
 *
 * Defaults come from measurement: 20 s is the smallest window that reaches
 * 0.0% CER (16 s still errs at 5.9%), while inference cost grows
 * super-linearly — 60 s of audio needs roughly 13x longer to decode than
 * 20 s does, leaving too little real-time headroom.
 */
class CwDeepBuffer(
    sampleRate: Int = CwDeepSpectrogram.SAMPLE_RATE,
    maxSeconds: Double = DEFAULT_MAX_SECONDS,
    private val redecodeIntervalMs: Int = DEFAULT_REDECODE_INTERVAL_MS
) {
    companion object {
        const val DEFAULT_MAX_SECONDS = 20.0
        const val DEFAULT_REDECODE_INTERVAL_MS = 1500
    }

    /** Maximum number of samples retained. */
    val capacity: Int = (sampleRate * maxSeconds).toInt()

    private val samplesPerInterval: Int = sampleRate * redecodeIntervalMs / 1000
    private val ring = FloatArray(capacity)
    private var writeIndex = 0
    private var filled = 0
    private var sinceLastRedecode = 0

    /** Samples currently buffered, never above [capacity]. */
    val size: Int get() = filled

    /** True once there is enough audio for the spectrogram to yield a frame. */
    val hasEnoughAudio: Boolean get() = filled >= CwDeepSpectrogram.FFT_LENGTH

    /**
     * Append captured audio, overwriting the oldest samples when full.
     *
     * @return true when [redecodeIntervalMs] of audio has accumulated since
     *   the last time this returned true, meaning the caller should re-decode.
     */
    fun append(chunk: FloatArray): Boolean {
        if (chunk.isNotEmpty()) {
            // A chunk longer than the window can only contribute its tail.
            val start = maxOf(0, chunk.size - capacity)
            for (i in start until chunk.size) {
                ring[writeIndex] = chunk[i]
                writeIndex = (writeIndex + 1) % capacity
            }
            filled = minOf(capacity, filled + (chunk.size - start))
        }

        sinceLastRedecode += chunk.size
        if (sinceLastRedecode >= samplesPerInterval) {
            sinceLastRedecode -= samplesPerInterval
            return true
        }
        return false
    }

    /** Buffered audio in chronological order, as a copy safe to hand off. */
    fun snapshot(): FloatArray {
        val out = FloatArray(filled)
        if (filled == 0) return out
        val start = (writeIndex - filled + capacity) % capacity
        val firstRun = minOf(filled, capacity - start)
        ring.copyInto(out, 0, start, start + firstRun)
        if (firstRun < filled) {
            ring.copyInto(out, firstRun, 0, filled - firstRun)
        }
        return out
    }

    /** Drop all audio and restart the re-decode interval. */
    fun reset() {
        writeIndex = 0
        filled = 0
        sinceLastRedecode = 0
        ring.fill(0f)
    }
}
