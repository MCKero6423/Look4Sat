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
        assertEquals('A', CwDecoder.morseToChar("01"))
        assertEquals('B', CwDecoder.morseToChar("1000"))
        assertEquals('S', CwDecoder.morseToChar("000"))
        assertEquals('O', CwDecoder.morseToChar("111"))
    }

    @Test
    fun morseToChar_numbers() {
        assertEquals('1', CwDecoder.morseToChar("01111"))
        assertEquals('5', CwDecoder.morseToChar("00000"))
        assertEquals('0', CwDecoder.morseToChar("11111"))
    }

    @Test
    fun morseToChar_unknown_returnsNull() {
        assertNull(CwDecoder.morseToChar("......."))
        assertNull(CwDecoder.morseToChar(""))
        assertNull(CwDecoder.morseToChar("01-01"))
    }

    // --- Resampler ---

    @Test
    fun resampler_downsampleReducesSize() {
        val resampler = CwResampler(8000f, 4000f)
        val input = FloatArray(8000) { (sin(2.0 * PI * 700.0 * it / 8000.0)).toFloat() }
        val output = resampler.process(input)
        assertTrue("Output size ${output.size} should be ~4000", output.size in 3800..4200)
    }

    @Test
    fun resampler_emptyInput_returnsEmpty() {
        val resampler = CwResampler(8000f, 4000f)
        val output = resampler.process(FloatArray(0))
        assertTrue(output.isEmpty())
    }

    @Test
    fun resampler_sameRate_returnsSameSize() {
        val resampler = CwResampler(4000f, 4000f)
        val input = FloatArray(100) { it.toFloat() }
        val output = resampler.process(input)
        assertTrue("Output size should be ~100", output.size in 95..105)
    }

    @Test
    fun resampler_resetClearsState() {
        val resampler = CwResampler(8000f, 4000f)
        val input = FloatArray(100) { 1f }
        resampler.process(input)
        resampler.reset()
        // Should not crash
        resampler.process(FloatArray(100) { 0f })
    }

    // --- Filter ---

    @Test
    fun filter_highPass_doesNotCrash() {
        val filter = CwFilter()
        val sampleRate = 4000f
        // Test that the filter runs without crashing and produces finite values
        val output = FloatArray(100) { filter.highPass(1.0f, 200f, sampleRate) }
        output.forEach { assertFalse("Output should be finite: $it", it.isNaN() || it.isInfinite()) }
    }

    @Test
    fun filter_lowPass_smoothsSignal() {
        val filter = CwFilter()
        val sampleRate = 4000f
        // High frequency noise
        val output = FloatArray(100) { filter.lowPass((sin(2.0 * PI * 1000.0 * it / sampleRate)).toFloat(), 500f, sampleRate) }
        val maxVal = output.maxOrNull() ?: 1f
        assertTrue("High freq should be attenuated, max=$maxVal", maxVal < 0.8f)
    }

    @Test
    fun filter_reset() {
        val filter = CwFilter()
        filter.highPass(1f, 200f, 4000f)
        filter.reset()
        // Should not crash
        assertEquals(0f, filter.highPass(0f, 200f, 4000f), 0.001f)
    }

    // --- Goertzel ---

    @Test
    fun goertzel_detectsPresentTone() {
        val sampleRate = 4000f
        val targetFreq = 700f
        val goertzel = CwGoertzel()
        goertzel.init(sampleRate, targetFreq)
        // Generate 700 Hz tone
        for (i in 0 until sampleRate.toInt()) {
            goertzel.process((sin(2.0 * PI * targetFreq * i / sampleRate)).toFloat())
        }
        val power = goertzel.getPower()
        assertTrue("Goertzel should detect present tone, got $power", power > 0.1f)
    }

    @Test
    fun goertzel_rejectsAbsentTone() {
        val sampleRate = 4000f
        val targetFreq = 700f
        val goertzel = CwGoertzel()
        goertzel.init(sampleRate, targetFreq)
        // Generate 2000 Hz tone (no match)
        for (i in 0 until sampleRate.toInt()) {
            goertzel.process((sin(2.0 * PI * 2000f * i / sampleRate)).toFloat())
        }
        val power = goertzel.getPower()
        assertTrue("Goertzel should reject absent tone, got $power", power < 0.1f)
    }

    @Test
    fun goertzel_reset() {
        val goertzel = CwGoertzel()
        goertzel.init(4000f, 700f)
        goertzel.process(1f)
        goertzel.reset()
        assertEquals(0f, goertzel.getPower(), 0.001f)
    }

    // --- Pitch detector ---

    @Test
    fun pitchDetector_findsCorrectFrequency() {
        val sampleRate = 4000f
        val detector = CwPitchDetector(sampleRate, 200f, 1200f, 10f)
        val targetFreq = 700f
        val buffer = FloatArray(sampleRate.toInt()) { (sin(2.0 * PI * targetFreq * it / sampleRate)).toFloat() }
        val pitch = detector.findPitch(buffer)
        assertNotNull("Pitch should be detected", pitch)
        if (pitch != null) {
            assertTrue("Detected pitch $pitch should be close to 700 Hz", pitch in 680f..720f)
        }
    }

    @Test
    fun pitchDetector_findsDifferentFrequency() {
        val sampleRate = 4000f
        val detector = CwPitchDetector(sampleRate, 200f, 1200f, 10f)
        val targetFreq = 500f
        val buffer = FloatArray(sampleRate.toInt()) { (sin(2.0 * PI * targetFreq * it / sampleRate)).toFloat() }
        val pitch = detector.findPitch(buffer)
        assertNotNull("Pitch should be detected", pitch)
        if (pitch != null) {
            assertTrue("Detected pitch $pitch should be close to 500 Hz", pitch in 480f..520f)
        }
    }

    @Test
    fun pitchDetector_returnsNullForSilence() {
        val detector = CwPitchDetector(4000f)
        val buffer = FloatArray(4000) { 0f }
        val pitch = detector.findPitch(buffer)
        assertNull("Pitch should be null for silence", pitch)
    }

    @Test
    fun pitchDetector_emptyBuffer() {
        val detector = CwPitchDetector(4000f)
        assertNull(detector.findPitch(FloatArray(0)))
    }

    // --- Decoder state ---

    @Test
    fun cwDecoder_initialState() {
        val decoder = CwDecoder()
        assertEquals("", decoder.decodedTextFlow.value)
        assertEquals(0f, decoder.signalStrength.value, 0.001f)
        assertNull(decoder.estimatedPitch.value)
        assertNull(decoder.estimatedSpeed.value)
    }

    @Test
    fun cwDecoder_defaultParameters() {
        val decoder = CwDecoder()
        assertEquals(8000, decoder.sampleRate)
    }

    @Test
    fun cwDecoder_customParameters() {
        val decoder = CwDecoder(sampleRate = 11025, cwToneFreq = 600f)
        assertEquals(11025, decoder.sampleRate)
    }

    @Test
    fun resetDecoder_clearsState() {
        val decoder = CwDecoder()
        decoder.processBuffer(FloatArray(128) { (sin(2.0 * PI * 700.0 * it / 8000.0)).toFloat() })
        decoder.resetDecoder()
        assertEquals("", decoder.decodedTextFlow.value)
        assertEquals(0f, decoder.signalStrength.value, 0.001f)
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
        assertNotNull(decoder.decodedTextFlow.value)
    }

    @Test
    fun processBuffer_withFixedPitch_doesNotCrash() {
        val decoder = CwDecoder(sampleRate = 8000, cwToneFreq = 700f)
        val buf = FloatArray(512) { (sin(2.0 * PI * 700.0 * it / 8000.0)).toFloat() }
        decoder.processBuffer(buf)
        assertNotNull(decoder.decodedTextFlow.value)
    }

    @Test
    fun cwDecoder_withFixedPitchBypassesAutoDetect() {
        val decoder = CwDecoder(sampleRate = 8000, cwToneFreq = 600f)
        assertEquals(600f, decoder.estimatedPitch.value)
    }

    @Test
    fun resetDecoder_afterFixedPitch() {
        val decoder = CwDecoder(sampleRate = 8000, cwToneFreq = 700f)
        decoder.resetDecoder()
        assertEquals("", decoder.decodedTextFlow.value)
        // Pitch should still be locked at 700
        assertEquals(700f, decoder.estimatedPitch.value)
    }
}