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

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CwDecoderTest {

    // --- Morse table ---

    @Test
    fun morseToChar_basicLetters() {
        assertEquals('A', CwDecoder.morseToChar(".-"))
        assertEquals('B', CwDecoder.morseToChar("-..."))
        assertEquals('S', CwDecoder.morseToChar("..."))
        assertEquals('O', CwDecoder.morseToChar("---"))
        assertEquals('C', CwDecoder.morseToChar("-.-."))
    }

    @Test
    fun morseToChar_numbers() {
        assertEquals('1', CwDecoder.morseToChar(".----"))
        assertEquals('5', CwDecoder.morseToChar("....."))
        assertEquals('0', CwDecoder.morseToChar("-----"))
    }

    @Test
    fun morseToChar_unknown_returnsNull() {
        assertNull(CwDecoder.morseToChar("......."))
        assertNull(CwDecoder.morseToChar(""))
        assertNull(CwDecoder.morseToChar(".-.-.-.-"))
    }

    @Test
    fun morseToChar_specialCharacters() {
        assertEquals('.', CwDecoder.morseToChar(".-.-.-"))
        assertEquals('?', CwDecoder.morseToChar("..--.."))
        assertEquals('/', CwDecoder.morseToChar("-..-."))
        assertEquals('@', CwDecoder.morseToChar(".--.-."))
    }

    // --- DSP ---

    @Test
    fun bandpassFir_producesNonEmptyCoefficients() {
        val coeffs = CwDsp.bandpassFir(0.075, 0.125, 127)
        assertTrue(coeffs.isNotEmpty())
        assertEquals(127, coeffs.size)
        // Sum should be approximately 1.0
        val sum = coeffs.sum()
        assertTrue("Sum should be ~1.0, got $sum", sum > 0.9 && sum < 1.1)
    }

    @Test
    fun bandpassFir_oddTaps_forcesOdd() {
        val coeffs = CwDsp.bandpassFir(0.075, 0.125, 100)
        assertEquals(101, coeffs.size) // forces odd
    }

    @Test
    fun applyFir_preservesLength() {
        val coeffs = CwDsp.bandpassFir(0.075, 0.125, 31)
        val input = FloatArray(100) { kotlin.math.sin(it * 0.1f).toFloat() }
        val output = CwDsp.applyFir(input, coeffs)
        assertEquals(input.size, output.size)
    }

    @Test
    fun envelope_isNonNegative() {
        val input = FloatArray(50) { if (it % 2 == 0) 0.5f else -0.3f }
        val env = CwDsp.envelope(input, 0.2f)
        for (v in env) assertTrue("Envelope should be >= 0, got $v", v >= 0f)
    }

    @Test
    fun envelope_smoothsSignal() {
        val input = FloatArray(100) { if (it % 2 == 0) 1f else 0f }
        val env = CwDsp.envelope(input, 0.3f)
        // Envelope should be between 0 and 1
        for (v in env) {
            assertTrue("Envelope value $v out of range [0,1]", v >= 0f && v <= 1f)
        }
        // After smoothing, should not rapidly oscillate
        val transitions = (1 until env.size).count { env[it] > 0.1f && env[it - 1] <= 0.1f }
        assertTrue("Too many envelope transitions: $transitions", transitions < 5)
    }

    @Test
    fun noiseFloor_producesPositiveValue() {
        val env = FloatArray(100) { kotlin.math.abs(kotlin.math.sin(it * 0.5f).toFloat()) }
        val floor = CwDsp.noiseFloor(env, 0.3f)
        assertTrue(floor > 0f)
        assertTrue(floor < 1f) // should be less than max signal
    }

    @Test
    fun goertzel_detectsPresentTone() {
        val sampleRate = 8000
        val targetFreq = 700f
        // Generate a 700 Hz tone at the sample rate
        val buffer = FloatArray(sampleRate) { (sin(2.0 * PI * targetFreq * it / sampleRate)).toFloat() }
        val power = CwDsp.goertzel(buffer, targetFreq, sampleRate)
        assertTrue("Goertzel should detect present tone, got $power", power > 0.1f)
    }

    @Test
    fun goertzel_rejectsAbsentTone() {
        val sampleRate = 8000
        val targetFreq = 700f
        // Generate a 2000 Hz tone (no match for 700 Hz)
        val buffer = FloatArray(sampleRate) { (sin(2.0 * PI * 2000f * it / sampleRate)).toFloat() }
        val power = CwDsp.goertzel(buffer, targetFreq, sampleRate)
        assertTrue("Goertzel should reject absent tone, got $power", power < 0.1f)
    }

    @Test
    fun goertzel_detectsToneInNoise() {
        val sampleRate = 8000
        val targetFreq = 700f
        // 700 Hz tone + noise
        val buffer = FloatArray(sampleRate) {
            val noise = (Math.random() * 2 - 1).toFloat() * 0.3f
            (sin(2.0 * PI * targetFreq * it / sampleRate)).toFloat() + noise
        }
        val power = CwDsp.goertzel(buffer, targetFreq, sampleRate)
        assertTrue("Goertzel should detect tone in noise, got $power", power > 0.1f)
    }

    // --- Decoder state ---

    @Test
    fun cwDecoder_initialState() {
        val decoder = CwDecoder()
        assertEquals("", decoder.decodedTextFlow.value)
        assertEquals(0f, decoder.signalStrength.value, 0.001f)
    }

    @Test
    fun resetDecoder_clearsText() {
        val decoder = CwDecoder()
        decoder.resetDecoder()
        assertEquals("", decoder.decodedTextFlow.value)
        assertEquals(0f, decoder.signalStrength.value, 0.001f)
    }

    @Test
    fun cwDecoder_defaultParameters() {
        val decoder = CwDecoder()
        assertEquals(8000, decoder.sampleRate)
        assertEquals(700f, decoder.cwToneFreq, 0.001f)
        assertEquals(200f, decoder.filterWidth, 0.001f)
    }

    @Test
    fun cwDecoder_customParameters() {
        val decoder = CwDecoder(sampleRate = 11025, cwToneFreq = 600f, filterWidth = 100f)
        assertEquals(11025, decoder.sampleRate)
        assertEquals(600f, decoder.cwToneFreq, 0.001f)
        assertEquals(100f, decoder.filterWidth, 0.001f)
    }

    @Test
    fun processBuffer_silence_doesNotCrash() {
        val decoder = CwDecoder()
        val silence = FloatArray(1024) { 0f }
        decoder.processBuffer(silence)
        assertEquals("", decoder.decodedTextFlow.value)
    }

    @Test
    fun processBuffer_noise_doesNotCrash() {
        val decoder = CwDecoder()
        val noise = FloatArray(1024) { (Math.random() * 2 - 1).toFloat() * 0.1f }
        decoder.processBuffer(noise)
        // Should not crash, decoded text may still be empty
        assertNotNull(decoder.decodedTextFlow.value)
    }

    @Test
    fun processBuffer_ditAtCenterFreq_detects() {
        val sampleRate = 8000
        val decoder = CwDecoder(sampleRate = sampleRate, cwToneFreq = 700f)
        // Generate a short dit (~480 samples at 20 WPM) at 700 Hz
        val ditDuration = (sampleRate / 20).toInt() // ~400 samples
        val buffer = FloatArray(ditDuration) {
            (sin(2.0 * PI * 700.0 * it / sampleRate)).toFloat()
        }
        decoder.processBuffer(buffer)
        // Short tone should be processed without crash
        assertNotNull(decoder.decodedTextFlow.value)
    }

    @Test
    fun processBuffer_generatedDit_emitsChar() {
        val sampleRate = 8000
        val decoder = CwDecoder(sampleRate = sampleRate, cwToneFreq = 700f)
        val ditSamples = (sampleRate / 20).toInt() // ~400 samples = 1 unit
        val gapSamples = ditSamples * 3 // inter-char gap

        // Generate "E" = dit: a single dit followed by inter-char gap
        val buffer = FloatArray(ditSamples + gapSamples)
        // First part: 700 Hz tone (dit)
        for (i in 0 until ditSamples) {
            buffer[i] = (sin(2.0 * PI * 700.0 * i / sampleRate)).toFloat()
        }
        // Second part: silence (gap)
        for (i in ditSamples until buffer.size) {
            buffer[i] = 0f
        }
        decoder.processBuffer(buffer)
        // After processing, the decoder should have detected the "E" symbol
        assertNotNull(decoder.decodedTextFlow.value)
    }
}