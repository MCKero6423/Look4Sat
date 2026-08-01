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
 * Multi-channel CW signal tracker.
 * Monitors the spectrogram for active frequency bins and extracts
 * energy envelopes for each detected signal.
 *
 * Inspired by CW Skimmer's multi-channel approach:
 * tracks all active signals in the passband simultaneously,
 * selects the best one for decoded output.
 */
internal class CwChannelTracker(
    private val spectrogram: CwSpectrogram,
    private val maxChannels: Int = 3
) {
    data class Channel(
        val bin: Int,
        val frequency: Float,
        var active: Boolean = false,
        var energy: Float = 0f,
        val history: MutableList<Float> = mutableListOf(),
        var confidence: Float = 0f
    )

    private val channels = Array(maxChannels) { Channel(0, 0f) }

    /** Scan the current spectrogram column and update channel tracking. */
    fun update(): List<Channel> {
        val col = spectrogram.getCurrentColumn()
        val peaks = findPeaks(col, threshold = 0.3f, minDistance = 2)

        // Update existing channels
        for (ch in channels) {
            if (ch.active) {
                if (peaks.contains(ch.bin)) {
                    ch.energy = col[ch.bin]
                    ch.history.add(ch.energy)
                    if (ch.history.size > 40) ch.history.removeAt(0)
                    ch.confidence = computeConfidence(ch.history)
                } else {
                    // Signal lost — decay confidence
                    ch.history.add(0f)
                    if (ch.history.size > 40) ch.history.removeAt(0)
                    ch.confidence *= 0.9f
                    if (ch.confidence < 0.1f) ch.active = false
                }
            }
        }

        // Assign new peaks to inactive channels
        var peakIdx = 0
        for (ch in channels) {
            if (!ch.active && peakIdx < peaks.size) {
                val bin = peaks[peakIdx]
                val freq = spectrogram.binToFreq(bin)
                // Re-initialize channel
                channels[peakIdx] = Channel(bin, freq, true, col[bin], mutableListOf(), 0.5f)
                peakIdx++
            }
        }

        return channels.filter { it.active }
    }

    /** Find peak bins in the spectrum. */
    private fun findPeaks(spectrum: FloatArray, threshold: Float, minDistance: Int): List<Int> {
        val peaks = mutableListOf<Int>()
        for (i in 1 until spectrum.size - 1) {
            if (spectrum[i] > spectrum[i - 1] && spectrum[i] > spectrum[i + 1] && spectrum[i] > threshold) {
                if (peaks.isEmpty() || i - peaks.last() >= minDistance) {
                    peaks.add(i)
                }
            }
        }
        return peaks.sortedByDescending { spectrum[it] }
    }

    /** Compute confidence from energy history. Lower variance = higher confidence. */
    private fun computeConfidence(history: List<Float>): Float {
        if (history.size < 10) return 0.3f
        val recent = history.takeLast(10)
        val mean = recent.average().toFloat()
        val variance = recent.map { (it - mean) * (it - mean) }.average().toFloat()
        return if (mean > 0f) (mean / (mean + variance + 0.1f)).coerceIn(0f, 1f) else 0f
    }

    /** Get the channel with highest confidence. */
    fun getBestChannel(): Channel? {
        return channels.filter { it.active }.maxByOrNull { it.confidence }
    }

    fun reset() {
        for (i in channels.indices) {
            channels[i] = Channel(0, 0f)
        }
    }
}