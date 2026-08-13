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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rolling audio buffer feeding DeepCW.
 *
 * DeepCW is a whole-segment CTC model, not a sample-by-sample decoder: it
 * rewrites earlier output whenever more context arrives, so incremental
 * stitching is impossible. Instead we keep a bounded window and re-decode all
 * of it periodically, replacing the displayed text.
 */
class CwDeepBufferTest {

    @Test
    fun capacityIsCappedAtMaxSeconds() {
        val buffer = CwDeepBuffer(sampleRate = 3200, maxSeconds = 20.0)
        repeat(30) { buffer.append(FloatArray(3200)) }
        assertEquals(3200 * 20, buffer.size)
    }

    @Test
    fun oldestSamplesAreDiscardedFirst() {
        val buffer = CwDeepBuffer(sampleRate = 4, maxSeconds = 1.0)
        buffer.append(floatArrayOf(1f, 2f, 3f))
        buffer.append(floatArrayOf(4f, 5f))
        assertArrayEquals(floatArrayOf(2f, 3f, 4f, 5f), buffer.snapshot(), 0f)
    }

    @Test
    fun snapshotIsChronologicalAfterWrapAround() {
        val buffer = CwDeepBuffer(sampleRate = 4, maxSeconds = 1.0)
        buffer.append(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f), buffer.snapshot(), 0f)
    }

    @Test
    fun appendLargerThanCapacityKeepsOnlyTheTail() {
        val buffer = CwDeepBuffer(sampleRate = 4, maxSeconds = 1.0)
        buffer.append(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f))
        assertEquals(4, buffer.size)
        assertArrayEquals(floatArrayOf(6f, 7f, 8f, 9f), buffer.snapshot(), 0f)
    }

    @Test
    fun redecodeIsSignalledOncePerInterval() {
        // 1.5 s at 3200 Hz is 4800 samples; 1600 samples is 0.5 s.
        val buffer = CwDeepBuffer(3200, 20.0, redecodeIntervalMs = 1500)
        assertFalse("1.0s elapsed: interval not reached", buffer.append(FloatArray(3200)))
        assertTrue("1.5s elapsed: first trigger", buffer.append(FloatArray(1600)))
        assertFalse("2.0s: only 0.5s since trigger", buffer.append(FloatArray(1600)))
        assertFalse("2.5s: only 1.0s since trigger", buffer.append(FloatArray(1600)))
        assertTrue("3.0s: 1.5s since trigger, fires again", buffer.append(FloatArray(1600)))
    }

    @Test
    fun redecodeIntervalDoesNotDriftOverManyChunks() {
        // 100 ms chunks, as AudioCapture emits them: exactly 15 chunks per
        // 1.5 s interval, so 150 chunks must fire exactly 10 times.
        val buffer = CwDeepBuffer(3200, 20.0, redecodeIntervalMs = 1500)
        var fired = 0
        repeat(150) { if (buffer.append(FloatArray(320))) fired++ }
        assertEquals(10, fired)
    }

    @Test
    fun snapshotDoesNotAliasInternalStorage() {
        val buffer = CwDeepBuffer(sampleRate = 4, maxSeconds = 1.0)
        buffer.append(floatArrayOf(1f, 2f, 3f, 4f))
        buffer.snapshot()[0] = 99f
        assertEquals("caller must not be able to mutate the buffer", 1f, buffer.snapshot()[0], 0f)
    }

    @Test
    fun resetClearsSamplesAndIntervalCounter() {
        val buffer = CwDeepBuffer(3200, 20.0, redecodeIntervalMs = 1500)
        buffer.append(FloatArray(3200))
        buffer.reset()
        assertEquals(0, buffer.size)
        assertFalse("counter restarted, 1.0s must not trigger", buffer.append(FloatArray(3200)))
    }

    @Test
    fun hasEnoughAudioTracksTheModelMinimum() {
        // compute() needs at least FFT_LENGTH samples to produce one frame.
        val buffer = CwDeepBuffer(3200, 20.0)
        buffer.append(FloatArray(100))
        assertFalse(buffer.hasEnoughAudio)
        buffer.append(FloatArray(200))
        assertTrue(buffer.hasEnoughAudio)
    }

    @Test
    fun defaultsMatchTheMeasuredOptimum() {
        // 20s / 1.5s were chosen from measurements: 20s is the smallest window
        // reaching 0.0% CER, and keeps inference well inside real time.
        val buffer = CwDeepBuffer()
        assertEquals(CwDeepSpectrogram.SAMPLE_RATE * 20, buffer.capacity)
    }

    @Test
    fun overflowCollectsEvictedSamplesInOrder() {
        val buffer = CwDeepBuffer(sampleRate = 4, maxSeconds = 1.0) // capacity 4
        buffer.append(floatArrayOf(1f, 2f, 3f, 4f))
        assertEquals("nothing evicted before the window is full", 0, buffer.overflowCount)
        buffer.append(floatArrayOf(5f, 6f)) // overwrites 1, 2
        assertArrayEquals("evicted samples, oldest first", floatArrayOf(1f, 2f), buffer.drainOverflow(), 0f)
        assertArrayEquals("live window still correct", floatArrayOf(3f, 4f, 5f, 6f), buffer.snapshot(), 0f)
    }

    @Test
    fun drainOverflowClearsItself() {
        val buffer = CwDeepBuffer(sampleRate = 4, maxSeconds = 1.0)
        buffer.append(floatArrayOf(1f, 2f, 3f, 4f))
        buffer.append(floatArrayOf(5f))
        assertEquals(1, buffer.overflowCount)
        buffer.drainOverflow()
        assertEquals(0, buffer.overflowCount)
        assertArrayEquals(FloatArray(0), buffer.drainOverflow(), 0f)
    }

    @Test
    fun resetClearsOverflow() {
        val buffer = CwDeepBuffer(sampleRate = 4, maxSeconds = 1.0)
        buffer.append(floatArrayOf(1f, 2f, 3f, 4f))
        buffer.append(floatArrayOf(5f))
        assertEquals(1, buffer.overflowCount)
        buffer.reset()
        assertEquals(0, buffer.overflowCount)
    }
}
