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
        assertEquals('A', CwBayesianDecoder.morseToChar("01"))
        assertEquals('S', CwBayesianDecoder.morseToChar("000"))
        assertEquals('O', CwBayesianDecoder.morseToChar("111"))
    }

    @Test
    fun morseToChar_numbers() {
        assertEquals('1', CwBayesianDecoder.morseToChar("01111"))
        assertEquals('0', CwBayesianDecoder.morseToChar("11111"))
    }

    @Test
    fun morseToChar_unknown_returnsNull() {
        assertNull(CwBayesianDecoder.morseToChar("......."))
        assertNull(CwBayesianDecoder.morseToChar(""))
    }

    // --- FFT ---

    @Test
    fun fft_magnitudeSpectrum_detectsTone() {
        val fft = CwFFT(256)
        val sampleRate = 8000f
        val freq = 700f
        val buffer = FloatArray(256) { (sin(2.0 * PI * freq * it / sampleRate)).toFloat() }
        val mag = fft.magnitudeSpectrum(buffer)
        // Peak should be at bin around 700 * 256 / 8000 ≈ 22.4
        var maxBin = 0
        var maxVal = 0f
        for (i in mag.indices) {
            if (mag[i] > maxVal) { maxVal = mag[i]; maxBin = i }
        }
        assertTrue("Peak bin $maxBin should be near 22", maxBin in 18..26)
        assertTrue("Peak value $maxVal should be positive", maxVal > 0.01f)
    }

    @Test
    fun fft_magnitudeSpectrum_silence_isFlat() {
        val fft = CwFFT(256)
        val buffer = FloatArray(256) { 0f }
        val mag = fft.magnitudeSpectrum(buffer)
        for (v in mag) assertEquals("Silence spectrum should be 0, got $v", 0f, v, 1e-6f)
    }

    @Test
    fun fft_rejectsWrongSize() {
        assertThrows(IllegalArgumentException::class.java) { CwFFT(100) }
    }

    // --- Spectrogram ---

    @Test
    fun spectrogram_addSamples_updatesEnergy() {
        val spec = CwSpectrogram(sampleRate = 8000)
        val freq = 700f
        // Feed multiple frames to stabilize energy normalization
        for (i in 0..5) {
            val buffer = FloatArray(256) { (sin(2.0 * PI * freq * it / 8000.0)).toFloat() }
            spec.addSamples(buffer)
        }
        val col = spec.getCurrentColumn()
        val peakBin = spec.findPeakBin()
        assertTrue("Peak bin $peakBin should be >= 0", peakBin >= 0)
    }

    @Test
    fun spectrogram_findPeakBin_returnsValidBin() {
        val spec = CwSpectrogram(sampleRate = 8000)
        // Add multiple frames of 700 Hz tone
        for (i in 0..5) {
            val buffer = FloatArray(256) { (sin(2.0 * PI * 700.0 * it / 8000.0)).toFloat() }
            spec.addSamples(buffer)
        }
        val peakBin = spec.findPeakBin()
        assertTrue("Peak bin should be >= 0, got $peakBin", peakBin >= 0)
    }

    @Test
    fun spectrogram_freqToBin_roundtrip() {
        val spec = CwSpectrogram(sampleRate = 8000)
        val freq = 700f
        val bin = spec.freqToBin(freq)
        val backFreq = spec.binToFreq(bin)
        assertTrue("Freq $freq → bin $bin → freq $backFreq", backFreq > 600f && backFreq < 800f)
    }

    @Test
    fun spectrogram_getBinEnergy_returnsCorrectLength() {
        val spec = CwSpectrogram(sampleRate = 8000)
        val energy = spec.getBinEnergy(0, 10)
        assertEquals(10, energy.size)
    }

    @Test
    fun spectrogram_reset() {
        val spec = CwSpectrogram(sampleRate = 8000)
        spec.addSamples(FloatArray(256) { 1f })
        spec.reset()
        assertEquals(-1, spec.findPeakBin())
    }

    // --- Bayesian decoder ---

    @Test
    fun bayesian_processTone_dit() {
        val decoder = CwBayesianDecoder()
        // At 20 WPM, dot = 60 ms
        val result = decoder.processTone(60f)
        assertEquals('0', result.symbol)
        assertTrue("Dit probability should be positive", result.probability > 0.1f)
    }

    @Test
    fun bayesian_processTone_dash() {
        val decoder = CwBayesianDecoder()
        // Dash = 3 * dot = 180 ms
        val result = decoder.processTone(180f)
        assertEquals('1', result.symbol)
        assertTrue("Dash probability should be positive", result.probability > 0.1f)
    }

    @Test
    fun bayesian_processTone_unknown_returnsNull() {
        val decoder = CwBayesianDecoder()
        // Very long tone — low probability for both dit and dash
        val result = decoder.processTone(5000f)
        assertNull(result.symbol)
    }

    @Test
    fun bayesian_processGap_interChar_returnsChar() {
        val decoder = CwBayesianDecoder()
        decoder.processTone(60f) // dit
        decoder.processTone(60f) // dit
        decoder.processTone(60f) // dit
        // 3 dots = "000" = 'S'
        val char = decoder.processGap(180f) // 3 * dot = inter-char gap
        assertEquals('S', char)
    }

    @Test
    fun bayesian_processGap_wordGap_addsSpace() {
        val decoder = CwBayesianDecoder()
        decoder.processTone(60f) // dit = 'E'
        decoder.processGap(180f) // inter-char gap
        // Now word gap
        val space = decoder.processGap(420f) // 7 * dot
        assertEquals(' ', space)
    }

    @Test
    fun bayesian_decodedText_accumulates() {
        val decoder = CwBayesianDecoder()
        decoder.processTone(60f) // dit = 'E'
        decoder.processGap(180f) // inter-char
        assertTrue(decoder.decodedText.isNotEmpty())
    }

    @Test
    fun bayesian_reset() {
        val decoder = CwBayesianDecoder()
        decoder.processTone(60f)
        decoder.reset()
        assertEquals("", decoder.decodedText)
    }

    @Test
    fun bayesian_getSpeed() {
        val decoder = CwBayesianDecoder()
        // Send 3 dits at 20 WPM (60 ms each)
        decoder.processTone(60f)
        decoder.processTone(60f)
        decoder.processTone(60f)
        val speed = decoder.getSpeed()
        assertTrue("Speed should be ~20 WPM, got $speed", speed > 15f && speed < 30f)
    }

    // --- Channel tracker ---

    @Test
    fun channelTracker_initialState() {
        val spec = CwSpectrogram(sampleRate = 8000)
        val tracker = CwChannelTracker(spec)
        val channels = tracker.update()
        assertTrue("No channels should be active initially", channels.isEmpty())
    }

    @Test
    fun channelTracker_detectsTone() {
        val spec = CwSpectrogram(sampleRate = 8000)
        // Feed a tone
        for (i in 0..5) {
            spec.addSamples(FloatArray(256) { (sin(2.0 * PI * 700.0 * it / 8000.0)).toFloat() })
        }
        val tracker = CwChannelTracker(spec)
        val channels = tracker.update()
        assertTrue("Should detect at least 1 channel", channels.isNotEmpty())
    }

    @Test
    fun channelTracker_bestChannel() {
        val spec = CwSpectrogram(sampleRate = 8000)
        for (i in 0..5) {
            spec.addSamples(FloatArray(256) { (sin(2.0 * PI * 700.0 * it / 8000.0)).toFloat() })
        }
        val tracker = CwChannelTracker(spec)
        tracker.update()
        val best = tracker.getBestChannel()
        assertNotNull("Best channel should exist", best)
        if (best != null) assertTrue(best.frequency in 600f..800f)
    }

    @Test
    fun channelTracker_reset() {
        val spec = CwSpectrogram(sampleRate = 8000)
        for (i in 0..5) {
            spec.addSamples(FloatArray(256) { (sin(2.0 * PI * 700.0 * it / 8000.0)).toFloat() })
        }
        val tracker = CwChannelTracker(spec)
        tracker.update()
        tracker.reset()
        assertNull(tracker.getBestChannel())
    }

    // --- Full decoder ---

    @Test
    fun decoder_initialState() {
        val decoder = CwDecoder()
        assertEquals("", decoder.decodedTextFlow.value)
        assertEquals(0f, decoder.signalStrength.value, 0.001f)
    }

    @Test
    fun decoder_processSilence_doesNotCrash() {
        val decoder = CwDecoder()
        decoder.processBuffer(FloatArray(256) { 0f })
        assertEquals("", decoder.decodedTextFlow.value)
    }

    @Test
    fun decoder_processNoise_doesNotCrash() {
        val decoder = CwDecoder()
        decoder.processBuffer(FloatArray(256) { (Math.random() * 2 - 1).toFloat() * 0.1f })
        assertNotNull(decoder.decodedTextFlow.value)
    }

    @Test
    fun decoder_processTone_doesNotCrash() {
        val decoder = CwDecoder()
        for (i in 0..20) {
            decoder.processBuffer(FloatArray(256) { (sin(2.0 * PI * 700.0 * it / 8000.0)).toFloat() })
        }
        assertNotNull(decoder.decodedTextFlow.value)
    }

    @Test
    fun decoder_reset() {
        val decoder = CwDecoder()
        decoder.processBuffer(FloatArray(256) { 1f })
        decoder.resetDecoder()
        assertEquals("", decoder.decodedTextFlow.value)
        assertEquals(0f, decoder.signalStrength.value, 0.001f)
    }

    @Test
    fun decoder_withFixedPitch() {
        val decoder = CwDecoder(sampleRate = 8000, cwToneFreq = 700f)
        assertEquals(700f, decoder.estimatedPitch.value)
    }
}