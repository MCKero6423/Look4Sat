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
 * Key advantages over v2 (ggmorse):
 * - Frequency-agnostic: monitors all 200-1200 Hz simultaneously
 * - Multi-channel: tracks up to 3 signals in parallel
 * - Bayesian: probability-based decisions, not hard thresholds
 * - Frequency drift tolerant: energy just moves between bins
 */
class CwDecoder(
    val sampleRate: Int = 8000,
    cwToneFreq: Float = -1f // ignored in v3 (auto-detect via spectrogram)
) {
    private val spectrogram = CwSpectrogram(
        fftSize = 256,
        hopSize = 64,
        sampleRate = sampleRate,
        minBin = 6,
        maxBin = 38,
        historyCols = 40
    )
    private val channelTracker = CwChannelTracker(spectrogram, maxChannels = 3)
    private val bayesianDecoder = CwBayesianDecoder()

    // Per-channel state
    private data class ChannelTiming(
        var isSignal: Boolean = false,
        var toneSamples: Int = 0,
        var gapSamples: Int = 0
    )
    private val timingStates = Array(3) { ChannelTiming() }

    // Sample period in milliseconds (at 4 kHz effective rate for timing)
    // The spectrogram processes at native sample rate, but timing analysis
    // uses the spectrogram column rate: hopSize/sampleRate seconds per column
    private val samplePeriodMs = 1000f * hopSize / sampleRate

    // Output flows
    private val _decodedTextFlow = MutableStateFlow("")
    val decodedTextFlow: StateFlow<String> = _decodedTextFlow

    private val _signalStrength = MutableStateFlow(0f)
    val signalStrength: StateFlow<Float> = _signalStrength

    private val _estimatedPitch = MutableStateFlow<Float?>(null)
    val estimatedPitch: StateFlow<Float?> = _estimatedPitch

    private val _estimatedSpeed = MutableStateFlow<Float?>(null)
    val estimatedSpeed: StateFlow<Float?> = _estimatedSpeed

    // Frame counter for periodic updates
    private var frameCount = 0

    init {
        if (cwToneFreq > 0f) {
            _estimatedPitch.value = cwToneFreq
        }
    }

    companion object {
        private const val hopSize = 64
    }

    fun processBuffer(buffer: FloatArray) {
        // 1. Feed samples to spectrogram (generates FFT waterfall)
        spectrogram.addSamples(buffer)

        // 2. Update channel tracker (find active frequency bins)
        val activeChannels = channelTracker.update()

        // 3. For each active channel, extract timing
        for ((idx, channel) in activeChannels.withIndex()) {
            if (idx >= timingStates.size) break
            val state = timingStates[idx]
            val col = spectrogram.getCurrentColumn()
            val energy = if (channel.bin in col.indices) col[channel.bin] else 0f

            // Adaptive threshold: 30% above noise floor
            val threshold = 0.3f + (energy - 0.3f) * 0.3f

            if (energy > threshold) {
                if (!state.isSignal) {
                    // Rising edge — process gap
                    if (state.gapSamples > 0) {
                        val gapMs = state.gapSamples * samplePeriodMs
                        bayesianDecoder.processGap(gapMs)
                    }
                    state.gapSamples = 0
                    state.isSignal = true
                }
                state.toneSamples++
            } else {
                if (state.isSignal) {
                    // Falling edge — process tone
                    val toneMs = state.toneSamples * samplePeriodMs
                    bayesianDecoder.processTone(toneMs)
                    state.toneSamples = 0
                    state.isSignal = false
                }
                state.gapSamples++
            }
        }

        // 4. Update outputs from best channel
        frameCount++
        if (frameCount % 5 == 0) { // Every 5 frames
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
            state.toneSamples = 0
            state.gapSamples = 0
        }
        frameCount = 0
        _decodedTextFlow.value = ""
        _signalStrength.value = 0f
        _estimatedPitch.value = null
        _estimatedSpeed.value = null
    }
}