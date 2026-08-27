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
package com.rtbishop.look4sat.core.data.cw

import com.rtbishop.look4sat.core.domain.cw.CwDeepBuffer
import com.rtbishop.look4sat.core.domain.cw.CwDeepSpectrogram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/**
 * How long audio waits before its text can reach the record pane.
 *
 * The record binds to archived text only. The live decode is rewritten from scratch every
 * cycle, so a pane that concatenated it lost characters the operator had already read - the
 * decoder appeared to delete its own output while still running. Binding to archived text
 * makes the pane monotonic, and the cost is latency, which is additive: audio must first be
 * pushed out of the live window, then accumulate into a full archive batch.
 *
 * [CwDeepDecoder] needs a Context and a loaded ONNX model, so it cannot be constructed here.
 * These tests read its real constants rather than copies, so retuning one without
 * reconsidering the user-visible delay fails here.
 */
class CwArchiveTimingTest {

    /** Sending speed for the character counts, typical for satellite CW. */
    private val wpm = 18.0

    /** PARIS standard: one word is five characters. */
    private val charsPerSecond = wpm * 5 / 60.0

    private val window = CwDeepBuffer.DEFAULT_MAX_SECONDS
    private val batch = CwDeepDecoder.ARCHIVE_SECONDS

    /** Seconds of audio that have reached the archive after listening for [elapsed]. */
    private fun archivedSeconds(elapsed: Double): Double {
        val evicted = elapsed - window
        if (evicted <= 0.0) return 0.0
        return floor(evicted / batch) * batch
    }

    @Test
    fun thresholdMatchesTheDeclaredBatchLength() {
        assertEquals(
            "threshold must be the batch length in samples",
            (CwDeepSpectrogram.SAMPLE_RATE * batch).toInt(),
            CwDeepDecoder.ARCHIVE_THRESHOLD
        )
    }

    @Test
    fun archiveBatchIsShorterThanACallSign() {
        // A seven-character call sign at 18 WPM takes about 4.7 s. A batch longer than that
        // means the record can stall for longer than the single most important thing being
        // sent, which is what made the stall read as deletion.
        val callSignSeconds = 7 / charsPerSecond
        assertTrue(
            "batch $batch s must not exceed a call sign at $wpm WPM " +
                "(${"%.1f".format(callSignSeconds)} s)",
            batch <= callSignSeconds
        )
    }

    @Test
    fun firstTextReachesTheRecordWithinHalfAMinute() {
        // At the previous 15 s batch this was 35 s, so a short exchange ended with the record
        // still completely empty: every decoded character had only ever been in the live line,
        // which shows 64 characters and overwrites them.
        val firstArchive = window + batch
        assertTrue("first archived text must appear within 30 s, got $firstArchive s", firstArchive <= 30.0)
    }

    @Test
    fun aThirtySecondSessionStillProducesARecord() {
        val archived = archivedSeconds(30.0)
        assertTrue("30 s of listening must archive something, got $archived s", archived > 0.0)
    }

    @Test
    fun theRecordNeverStallsForLongerThanOneBatch() {
        var longestStall = 0.0
        var lastGrowthAt = window
        var previous = 0.0
        var t = window
        while (t <= 600.0) {
            val archived = archivedSeconds(t)
            if (archived > previous) {
                longestStall = maxOf(longestStall, t - lastGrowthAt)
                lastGrowthAt = t
                previous = archived
            }
            t += 0.1
        }
        assertTrue(
            "record stalled ${"%.1f".format(longestStall)} s, one batch is $batch s",
            longestStall <= batch + 0.11
        )
    }

    @Test
    fun audioInFlightWhenCaptureStopsWouldLoseTheEndOfTheTransmission() {
        // Why flush() exists. Neither holding place drains on its own: the live window only
        // reaches the archive by being pushed out by newer audio, and the pending batch only
        // by filling up. Both hold the end of the transmission, where the call sign is.
        val worstCase = window + batch
        val lostCharacters = worstCase * charsPerSecond
        assertTrue(
            "flush() must exist: ${"%.0f".format(lostCharacters)} characters would be lost",
            lostCharacters > 20
        )
    }

    @Test
    fun archivingStaysRarerThanTheLiveDecode() {
        // Each batch is one inference. Shortening the batch trades CPU for latency, so it must
        // stay rarer than the live redecode or the archive path becomes the dominant cost.
        val liveIntervalSeconds = CwDeepBuffer.DEFAULT_REDECODE_INTERVAL_MS / 1000.0
        assertTrue(
            "batch $batch s must stay longer than the live cycle $liveIntervalSeconds s",
            batch > liveIntervalSeconds
        )
    }

    @Test
    fun aBatchIsLongEnoughToDecode() {
        // compute() rejects audio shorter than one FFT frame, so a batch below that would be
        // silently dropped by archiveDecode's size guard and its text lost outright.
        assertTrue(
            "batch of ${CwDeepDecoder.ARCHIVE_THRESHOLD} samples must exceed " +
                "FFT_LENGTH ${CwDeepSpectrogram.FFT_LENGTH}",
            CwDeepDecoder.ARCHIVE_THRESHOLD > CwDeepSpectrogram.FFT_LENGTH
        )
    }
}
