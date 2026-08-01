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
import kotlin.math.abs

/**
 * CW (Morse code) decoder ported from ggerganov/ggmorse.
 *
 * Key improvements over v1:
 * - Automatic pitch detection (200-1200 Hz) via DFT
 * - Automatic speed detection (5-55 WPM) via interval clustering
 * - Adaptive threshold with signal statistics
 * - Resampling to 4 kHz base rate for efficiency
 * - Running Goertzel filter for tone detection
 * - First-order IIR bandpass filter (HP + LP)
 *
 * Algorithm flow:
 *   Audio buffer → Resample to 4 kHz → High-pass filter (200 Hz) →
 *   Low-pass filter (1200 Hz) → Pitch detection (DFT, 200-1200 Hz) →
 *   Running Goertzel at detected pitch → Adaptive threshold →
 *   Signal interval timing → Speed estimation → Morse character lookup
 */
class CwDecoder(
    val sampleRate: Int = 8000,
    cwToneFreq: Float = -1f, // -1 = auto-detect
    minFreq: Float = 200f,
    maxFreq: Float = 1200f
) {
    companion object {
        private val MORSE_TABLE = mapOf(
            "01" to 'A', "1000" to 'B', "1010" to 'C', "100" to 'D', "0" to 'E',
            "0010" to 'F', "110" to 'G', "0000" to 'H', "00" to 'I', "0111" to 'J',
            "101" to 'K', "0100" to 'L', "11" to 'M', "10" to 'N', "111" to 'O',
            "0110" to 'P', "1101" to 'Q', "010" to 'R', "000" to 'S', "1" to 'T',
            "001" to 'U', "0001" to 'V', "011" to 'W', "1001" to 'X', "1011" to 'Y',
            "1100" to 'Z', "01111" to '1', "00111" to '2', "00011" to '3',
            "00001" to '4', "00000" to '5', "10000" to '6', "11000" to '7',
            "11100" to '8', "11110" to '9', "11111" to '0',
            "010101" to '.', "110011" to ',', "001100" to '?', "011110" to '\'',
            "101011" to '!', "10010" to '/', "10110" to '(', "101101" to ')',
            "01000" to '&', "111000" to ':', "101010" to ';', "10001" to '=',
            "01010" to '+', "100001" to '-', "001101" to '_', "010010" to '"',
            "0001001" to '$', "011010" to '@'
        )
        fun morseToChar(morse: String): Char? = MORSE_TABLE[morse]

        private const val BASE_SAMPLE_RATE = 4000f
        private const val PITCH_DETECT_INTERVAL = 100 // frames between pitch scans
    }

    // Output flows
    private val _decodedTextFlow = MutableStateFlow("")
    val decodedTextFlow: StateFlow<String> = _decodedTextFlow

    private val _signalStrength = MutableStateFlow(0f)
    val signalStrength: StateFlow<Float> = _signalStrength

    private val _estimatedPitch = MutableStateFlow<Float?>(null)
    val estimatedPitch: StateFlow<Float?> = _estimatedPitch

    private val _estimatedSpeed = MutableStateFlow<Float?>(null)
    val estimatedSpeed: StateFlow<Float?> = _estimatedSpeed

    // DSP components
    private val resampler = CwResampler(sampleRate.toFloat(), BASE_SAMPLE_RATE)
    private val hpFilter = CwFilter()
    private val lpFilter = CwFilter()
    private val pitchDetector = CwPitchDetector(BASE_SAMPLE_RATE, minFreq, maxFreq)
    private val goertzel = CwGoertzel()

    // Decoder state
    private var decodedText = StringBuilder()
    private var currentLetter = StringBuilder()
    private var isSignal = false
    private var signalOnSamples = 0
    private var signalOffSamples = 0
    private var pitchEstimate = if (cwToneFreq > 0f) cwToneFreq else -1f
    private var pitchConfidenceCounter = 0
    private var noiseFloor = 0.0f
    private var signalPeak = 0.0f
    private var speedEstimate = 20f // initial guess: 20 WPM
    private var isPitchLocked = cwToneFreq > 0f

    init {
        if (isPitchLocked) {
            _estimatedPitch.value = cwToneFreq
        }
    }

    // Interval history for speed estimation
    private val intervalHistory = mutableListOf<Int>() // lengths of dits (type 0 only)

    // Keep track of last processed sample for the goertzel filter
    private var goertzelSampleCount = 0

    fun processBuffer(buffer: FloatArray) {
        // 1. Resample to 4 kHz base rate
        val resampled = resampler.process(buffer)

        for (sample in resampled) {
            // 2. Bandpass filter chain: 200 Hz HP → 1200 Hz LP
            val hp = hpFilter.highPass(sample, 200f, BASE_SAMPLE_RATE)
            val filtered = lpFilter.lowPass(hp, 1200f, BASE_SAMPLE_RATE)
            val absVal = abs(filtered)

            // 3. Update noise floor and signal peak (running statistics)
            noiseFloor = 0.999f * noiseFloor + 0.001f * absVal
            if (absVal > signalPeak) {
                signalPeak = absVal
            } else {
                signalPeak = 0.999f * signalPeak
            }

            // 4. Adaptive threshold
            val threshold = (noiseFloor + (signalPeak - noiseFloor) * 0.3f)
            _signalStrength.value = if (signalPeak > 0f && threshold > 0f) {
                ((signalPeak - threshold) / signalPeak).coerceIn(0f, 1f)
            } else {
                0f
            }

            // 5. Run Goertzel filter if pitch is locked
            if (isPitchLocked && pitchEstimate > 0f) {
                goertzel.process(filtered)
                goertzelSampleCount++
            }

            // 6. Signal detection with adaptive threshold
            if (absVal > threshold) {
                if (!isSignal) {
                    // Rising edge — process the silence gap that just ended
                    if (signalOffSamples > 0) {
                        processGap(signalOffSamples)
                    }
                    signalOffSamples = 0
                    isSignal = true
                }
                signalOnSamples++
            } else {
                if (isSignal) {
                    // Falling edge — process the tone that just ended
                    processTone(signalOnSamples)
                    signalOnSamples = 0
                    isSignal = false
                }
                signalOffSamples++
            }
        }

        // 7. Periodic pitch detection (every ~100 frames)
        if (!isPitchLocked) {
            pitchConfidenceCounter++
            if (pitchConfidenceCounter >= PITCH_DETECT_INTERVAL) {
                pitchConfidenceCounter = 0
                val pitch = pitchDetector.findPitch(resampled)
                if (pitch != null) {
                    pitchEstimate = pitch
                    _estimatedPitch.value = pitch
                    isPitchLocked = true
                    goertzel.init(BASE_SAMPLE_RATE, pitch)
                }
            }
        }

        // Push latest decoded text
        _decodedTextFlow.value = decodedText.toString()
    }

    private fun processTone(samples: Int) {
        val dotDuration = samplesForDot()
        if (dotDuration <= 0) return

        val ratio = samples.toFloat() / dotDuration

        if (ratio < 1.5f) {
            currentLetter.append('0') // 0 = dot
            // Track dit lengths for speed estimation
            intervalHistory.add(samples)
            if (intervalHistory.size > 20) intervalHistory.removeAt(0)
        } else if (ratio < 5.0f) {
            currentLetter.append('1') // 1 = dash
        }
        // else: ignore very long tones (likely noise/interference)

        // Update speed estimate from recent dits
        updateSpeedEstimate()
    }

    private fun processGap(samples: Int) {
        val dotDuration = samplesForDot()
        if (dotDuration <= 0) return

        val gapRatio = samples.toFloat() / dotDuration

        if (currentLetter.isNotEmpty()) {
            // Inter-character gap (3+ dot durations)
            if (gapRatio >= 2.5f) {
                val char = morseToChar(currentLetter.toString())
                if (char != null) {
                    decodedText.append(char)
                }
                currentLetter.clear()

                // Word gap (7+ dot durations)
                if (gapRatio >= 7f) {
                    decodedText.append(' ')
                }
            }
        } else {
            // Word gap (7+ dot durations, no letter in progress)
            if (gapRatio >= 7f) {
                decodedText.append(' ')
            }
        }
    }

    private fun samplesForDot(): Int {
        // Convert WPM to samples at 4 kHz base rate
        // Using standard formula: dot = 60/(50*WPM) seconds
        return ((BASE_SAMPLE_RATE * 60.0 / (50.0 * speedEstimate)).toInt()).coerceAtLeast(1)
    }

    private fun updateSpeedEstimate() {
        if (intervalHistory.size < 3) return

        // Use median of recent dit lengths for speed estimation
        val sorted = intervalHistory.sorted()
        val median = sorted[sorted.size / 2].toFloat()

        if (median > 0f) {
            val newSpeed = 60.0f / (50.0f * median / BASE_SAMPLE_RATE)
            if (newSpeed in 5f..55f) {
                // Smooth speed update (70% old, 30% new)
                speedEstimate = speedEstimate * 0.7f + newSpeed * 0.3f
                _estimatedSpeed.value = speedEstimate
            }
        }
    }

    fun resetDecoder() {
        isSignal = false
        signalOnSamples = 0
        signalOffSamples = 0
        decodedText.clear()
        currentLetter.clear()
        intervalHistory.clear()
        noiseFloor = 0.0f
        signalPeak = 0.0f
        speedEstimate = 20f
        if (!isPitchLocked) {
            pitchEstimate = -1f
            pitchConfidenceCounter = 0
        }
        goertzelSampleCount = 0
        hpFilter.reset()
        lpFilter.reset()
        resampler.reset()
        goertzel.reset()
        _decodedTextFlow.value = ""
        _signalStrength.value = 0f
        _estimatedPitch.value = if (isPitchLocked) pitchEstimate else null
        _estimatedSpeed.value = null
    }
}