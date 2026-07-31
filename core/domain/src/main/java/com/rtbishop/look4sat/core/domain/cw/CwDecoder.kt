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
 * Real-time CW (Morse code) decoder.
 *
 * Processes audio buffers and emits decoded text characters.
 * Uses a bandpass filter centered on the CW tone, envelope detection,
 * and timing analysis to distinguish dits, dashes, and gaps.
 *
 * Morse timing (paris method):
 *   Dit = 1 unit
 *   Dash = 3 units
 *   Intra-char gap = 1 unit
 *   Inter-char gap = 3 units
 *   Word gap = 7 units
 */
class CwDecoder(
    val sampleRate: Int = 8000,
    val cwToneFreq: Float = 700f,
    val filterWidth: Float = 200f
) {
    // Filter coefficients (pre-computed)
    private val firCoeffs = CwDsp.bandpassFir(
        lowCutoff = ((cwToneFreq - filterWidth / 2) / sampleRate).toDouble(),
        highCutoff = ((cwToneFreq + filterWidth / 2) / sampleRate).toDouble(),
        taps = 127
    )

    // Decoder state
    private var isSignalPresent = false
    private var signalOnTime = 0  // samples since signal started
    private var signalOffTime = 0 // samples since signal ended
    private var avgDitDuration = 0f // running average of dit duration in samples
    private var decodedText = StringBuilder()
    private var currentSymbol = StringBuilder()

    private val _decodedTextFlow = MutableStateFlow("")
    val decodedTextFlow: StateFlow<String> = _decodedTextFlow

    private val _signalStrength = MutableStateFlow(0f)
    val signalStrength: StateFlow<Float> = _signalStrength

    /** Process a buffer of audio samples. */
    fun processBuffer(buffer: FloatArray) {
        // 1. Bandpass filter around CW tone
        val filtered = CwDsp.applyFir(buffer, firCoeffs)

        // 2. Envelope detection
        val env = CwDsp.envelope(filtered, 0.1f)

        // 3. Adaptive threshold
        val floor = CwDsp.noiseFloor(env)
        val threshold = floor * 1.8f

        // Track max envelope for signal strength display
        val maxEnv = env.maxOrNull() ?: 0f
        _signalStrength.value = if (threshold > 0f && maxEnv > threshold) {
            ((maxEnv - threshold) / maxEnv).coerceIn(0f, 1f)
        } else 0f

        // 4. Timing analysis
        for (sample in env) {
            if (sample > threshold) {
                // Signal ON
                if (!isSignalPresent) {
                    // Rising edge — end of silence
                    if (signalOffTime > 0) {
                        processSilence(signalOffTime)
                    }
                    signalOffTime = 0
                    isSignalPresent = true
                }
                signalOnTime++
            } else {
                // Signal OFF
                if (isSignalPresent) {
                    // Falling edge — end of tone
                    processTone(signalOnTime)
                    signalOnTime = 0
                    isSignalPresent = false
                }
                signalOffTime++
            }
        }

        // Push latest decoded text
        _decodedTextFlow.value = decodedText.toString()
    }

    private fun processTone(duration: Int) {
        // Update average dit duration based on this tone
        if (avgDitDuration == 0f) {
            // Initial estimate: assume shortest tone is a dit
            // Typical 20 WPM dit = 60ms = 480 samples at 8kHz
            avgDitDuration = minOf(duration.toFloat(), (sampleRate / 20).toFloat())
        }

        val ratio = duration.toFloat() / avgDitDuration
        if (ratio < 1.8f) {
            currentSymbol.append('.') // Dit
            // Update running average with this dit
            avgDitDuration = (avgDitDuration * 0.7f + duration * 0.3f)
        } else if (ratio < 5f) {
            currentSymbol.append('-') // Dash
        }
        // else: ignore very long tones (likely noise)
    }

    private fun processSilence(duration: Int) {
        if (currentSymbol.isNotEmpty()) {
            // Inter-character gap (3+ units) — decode accumulated symbol
            val gapRatio = duration.toFloat() / (avgDitDuration.coerceAtLeast(1f))
            if (gapRatio >= 2.5f) {
                val char = morseToChar(currentSymbol.toString())
                if (char != null) {
                    decodedText.append(char)
                }
                currentSymbol.clear()

                // Word gap (7+ units)
                if (gapRatio >= 7f) {
                    decodedText.append(' ')
                }
            }
        }
    }

    fun resetDecoder() {
        isSignalPresent = false
        signalOnTime = 0
        signalOffTime = 0
        avgDitDuration = 0f
        decodedText.clear()
        currentSymbol.clear()
        _decodedTextFlow.value = ""
        _signalStrength.value = 0f
    }

    companion object {
        private val MORSE_TABLE = mapOf(
            ".-" to 'A', "-..." to 'B', "-.-." to 'C', "-.." to 'D', "." to 'E',
            "..-." to 'F', "--." to 'G', "...." to 'H', ".." to 'I', ".---" to 'J',
            "-.-" to 'K', ".-.." to 'L', "--" to 'M', "-." to 'N', "---" to 'O',
            ".--." to 'P', "--.-" to 'Q', ".-." to 'R', "..." to 'S', "-" to 'T',
            "..-" to 'U', "...-" to 'V', ".--" to 'W', "-..-" to 'X', "-.--" to 'Y',
            "--.." to 'Z', ".----" to '1', "..---" to '2', "...--" to '3',
            "....-" to '4', "....." to '5', "-...." to '6', "--..." to '7',
            "---.." to '8', "----." to '9', "-----" to '0',
            ".-.-.-" to '.', "--..--" to ',', "..--.." to '?', ".----." to '\'',
            "-.-.--" to '!', "-..-." to '/', "-.--." to '(', "-.--.-" to ')',
            ".-..." to '&', "---..." to ':', "-.-.-." to ';', "-...-" to '=',
            ".-.-." to '+', "-....-" to '-', "..--.-" to '_', ".-..-." to '"',
            "...-..-" to '$', ".--.-." to '@'
        )

        fun morseToChar(morse: String): Char? = MORSE_TABLE[morse]
    }
}