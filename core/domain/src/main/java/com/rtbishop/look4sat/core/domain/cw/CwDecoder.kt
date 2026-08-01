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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * CW (Morse code) decoder v3 — Spectrogram-based multi-channel Bayesian decoder.
 *
 * Architecture inspired by Morse Expert / CW Skimmer (VE3NEA):
 *   1. FFT spectrogram creates a frequency×time matrix
 *   2. Multi-channel peak detector finds all active signals
 *   3. Per-channel energy envelope extraction
 *   4. Bayesian probability for symbol timing (Gaussian likelihood)
 *   5. Best channel selected for output
 *
 * Timing analysis is performed per spectrogram column (hop).
 * Each column represents hopSize/sampleRate seconds of audio.
 */
class CwDecoder(
    val sampleRate: Int = 8000,
    cwToneFreq: Float = -1f // ignored in v3 (auto-detect via spectrogram)
) {
    companion object {
        private const val FFT_SIZE = 256
        private const val HOP_SIZE = 64
    }

    private val spectrogram = CwSpectrogram(
        fftSize = FFT_SIZE,
        hopSize = HOP_SIZE,
        sampleRate = sampleRate,
        minBin = 6,
        maxBin = 38,
        historyCols = 40
    )
    private val channelTracker = CwChannelTracker(spectrogram, maxChannels = 3)
    private val bayesianDecoder = CwBayesianDecoder()

    // Timing state per channel
    private data class ChannelTiming(
        var isSignal: Boolean = false,
        var toneTicks: Int = 0,
        var gapTicks: Int = 0
    )
    private val timingStates = Array(3) { ChannelTiming() }

    // Time per spectrogram column in milliseconds
    private val tickMs = 1000f * HOP_SIZE / sampleRate

    // Output flows
    private val _decodedTextFlow = MutableStateFlow("")
    val decodedTextFlow: StateFlow<String> = _decodedTextFlow

    private val _signalStrength = MutableStateFlow(0f)
    val signalStrength: StateFlow<Float> = _signalStrength

    private val _estimatedPitch = MutableStateFlow<Float?>(null)
    val estimatedPitch: StateFlow<Float?> = _estimatedPitch

    private val _estimatedSpeed = MutableStateFlow<Float?>(null)
    val estimatedSpeed: StateFlow<Float?> = _estimatedSpeed

    private var frameCount = 0

    init {
        if (cwToneFreq > 0f) {
            _estimatedPitch.value = cwToneFreq
        }
    }

    fun processBuffer(buffer: FloatArray) {
        // 1. Feed samples to spectrogram
        spectrogram.addSamples(buffer)

        // 2. Get number of new columns generated
        val newCols = spectrogram.getNewColumns()
        if (newCols == 0) return

        // 3. Update channel tracker (uses latest column for peak detection)
        val activeChannels = channelTracker.update()

        // 4. Process each new column for timing analysis
        //    Columns are indexed 0..historyCols-1, where historyCols-1 is the newest
        val baseIdx = (spectrogram.historyCols - newCols).coerceAtLeast(0)
        for (colOffset in 0 until newCols) {
            val col = spectrogram.getColumn(baseIdx + colOffset)

            for ((idx, channel) in activeChannels.withIndex()) {
                if (idx >= timingStates.size) break
                val state = timingStates[idx]
                val energy = if (channel.bin in col.indices) col[channel.bin] else 0f

                // Adaptive threshold
                val threshold = 0.3f + (energy - 0.3f) * 0.3f

                if (energy > threshold) {
                    if (!state.isSignal) {
                        if (state.gapTicks > 0) {
                            val gapMs = state.gapTicks * tickMs
                            bayesianDecoder.processGap(gapMs)
                        }
                        state.gapTicks = 0
                        state.isSignal = true
                    }
                    state.toneTicks++
                } else {
                    if (state.isSignal) {
                        if (state.toneTicks > 0) {
                            val toneMs = state.toneTicks * tickMs
                            bayesianDecoder.processTone(toneMs)
                        }
                        state.toneTicks = 0
                        state.isSignal = false
                    }
                    state.gapTicks++
                }
            }
        }

        // 5. Update outputs
        frameCount++
        if (frameCount % 5 == 0) {
            val bestChannel = channelTracker.getBestChannel()
            if (bestChannel != null) {
                _estimatedPitch.value = bestChannel.frequency
                _signalStrength.value = bestChannel.confidence
                _estimatedSpeed.value = bayesianDecoder.getSpeed()
            }
            _decodedTextFlow.value = bayesianDecoder.decodedText
        }
    }

    fun resetDecoder() {
        spectrogram.reset()
        channelTracker.reset()
        bayesianDecoder.reset()
        for (state in timingStates) {
            state.isSignal = false
            state.toneTicks = 0
            state.gapTicks = 0
        }
        frameCount = 0
        _decodedTextFlow.value = ""
        _signalStrength.value = 0f
        _estimatedPitch.value = null
        _estimatedSpeed.value = null
    }
}