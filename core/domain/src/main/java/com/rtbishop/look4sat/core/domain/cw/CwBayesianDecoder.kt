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
 * Bayesian Morse timing decoder.
 * Replaces hard thresholds with probability-based decision making.
 *
 * Inspired by VE3NEA's CW Skimmer approach:
 * "Instead of making a hard decision at every input sample whether the signal
 * is present or not, compute the probability that the signal is present."
 *
 * Uses Gaussian probability density centered on expected durations:
 *   P(dit | duration) = exp(-(duration - dotMs)^2 / (2 * variance^2))
 *   P(dash | duration) = exp(-(duration - 3*dotMs)^2 / (2 * variance^2))
 */
internal class CwBayesianDecoder {

    // Morse timing parameters
    private var dotDurationMs = 60f // initial 20 WPM
    private var speedWpm = 20f

    // Current symbol being accumulated
    private var currentSymbol = StringBuilder()
    private var textBuffer = StringBuilder()

    // Recent dit lengths for speed estimation
    private val recentDits = mutableListOf<Float>()

    // Output
    private var _decodedText = ""
    val decodedText: String get() = _decodedText

    /** Gaussian probability. */
    private fun gaussianProb(durationMs: Float, expectedMs: Float, varianceMs: Float): Float {
        if (varianceMs <= 0f) return 0f
        val diff = durationMs - expectedMs
        return kotlin.math.exp(-(diff * diff) / (2 * varianceMs * varianceMs))
    }

    /** Process a tone duration. Returns the symbol type with highest probability. */
    fun processTone(durationMs: Float): ToneResult {
        val ditProb = gaussianProb(durationMs, dotDurationMs, dotDurationMs * 0.4f)
        val dashProb = gaussianProb(durationMs, dotDurationMs * 3f, dotDurationMs * 0.6f)

        return if (ditProb > dashProb && ditProb > 0.05f) {
            currentSymbol.append('0')
            recentDits.add(durationMs)
            updateSpeed()
            ToneResult('0', ditProb)
        } else if (dashProb > 0.05f) {
            currentSymbol.append('1')
            ToneResult('1', dashProb)
        } else {
            ToneResult(null, 0f)
        }
    }

    /** Process a gap duration. Returns decoded character or null. */
    fun processGap(durationMs: Float): Char? {
        if (currentSymbol.isEmpty()) {
            val wordProb = gaussianProb(durationMs, dotDurationMs * 7f, dotDurationMs * 1.2f)
            if (wordProb > 0.2f) {
                textBuffer.append(' ')
                _decodedText = textBuffer.toString()
                return ' '
            }
            return null
        }

        val interCharProb = gaussianProb(durationMs, dotDurationMs * 3f, dotDurationMs * 0.6f)
        val wordProb = gaussianProb(durationMs, dotDurationMs * 7f, dotDurationMs * 1.2f)

        if (wordProb > interCharProb && wordProb > 0.2f) {
            val char = flushSymbol()
            textBuffer.append(' ')
            _decodedText = textBuffer.toString()
            return char
        }
        if (interCharProb > 0.15f) {
            val char = flushSymbol()
            _decodedText = textBuffer.toString()
            return char
        }
        return null
    }

    private fun flushSymbol(): Char? {
        if (currentSymbol.isEmpty()) return null
        val morse = currentSymbol.toString()
        currentSymbol.clear()
        val char = morseToChar(morse)
        if (char != null) textBuffer.append(char)
        return char
    }

    private fun updateSpeed() {
        if (recentDits.size < 3) return
        val sorted = recentDits.sorted()
        val median = sorted[sorted.size / 2]
        if (median > 0f) {
            dotDurationMs = dotDurationMs * 0.7f + median * 0.3f
            val wpm = 60.0f / (50.0f * dotDurationMs / 1000.0f)
            if (wpm in 5f..55f) speedWpm = wpm
        }
    }

    fun getSpeed(): Float = speedWpm

    fun reset() {
        dotDurationMs = 60f
        speedWpm = 20f
        recentDits.clear()
        currentSymbol.clear()
        textBuffer.clear()
        _decodedText = ""
    }

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
    }
}

data class ToneResult(val symbol: Char?, val probability: Float)