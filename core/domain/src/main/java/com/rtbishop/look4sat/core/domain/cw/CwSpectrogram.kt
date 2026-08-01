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
 * Sliding-window spectrogram for CW decoding.
 * Maintains a time-frequency matrix updated with each audio frame.
 *
 * FFT size: 256, hop size: 64, sample rate: 4000 (or native)
 * Frequency bins: 6..38 (187-1187 Hz, covers typical CW range)
 * History: 40 columns (320 ms window)
 * Time resolution: 64/4000 = 16 ms, Frequency resolution: 4000/256 = 15.625 Hz
 */
internal class CwSpectrogram(
    private val fftSize: Int = 256,
    private val hopSize: Int = 64,
    private val sampleRate: Int = 4000,
    private val minBin: Int = 6,
    private val maxBin: Int = 38,
    val historyCols: Int = 40
) {
    private val fft = CwFFT(fftSize)
    val numBins: Int get() = maxBin - minBin + 1

    // Hanning window
    private val hanning = FloatArray(fftSize) {
        (0.5 - 0.5 * kotlin.math.cos(2.0 * kotlin.math.PI * it / (fftSize - 1))).toFloat()
    }

    // Spectrogram data: [timeCol][freqBin]
    private val spectrogram = Array(historyCols) { FloatArray(numBins) }
    private var currentCol = 0
    private var samplesBuffered = 0
    private val buffer = FloatArray(fftSize)

    // Per-bin running energy for normalization
    private val binEnergy = FloatArray(numBins) { 1f }
    private val alpha = 0.95f

    // Counter for new columns generated since last check
    private var newColumnCount = 0

    /** Add audio samples, compute FFTs for each complete hop. */
    fun addSamples(samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val needed = fftSize - samplesBuffered
            val copyLen = minOf(needed, samples.size - offset)
            System.arraycopy(samples, offset, buffer, samplesBuffered, copyLen)
            samplesBuffered += copyLen
            offset += copyLen

            if (samplesBuffered >= fftSize) {
                processFrame()
                newColumnCount++
                // Shift buffer: keep last (fftSize - hopSize) samples
                System.arraycopy(buffer, hopSize, buffer, 0, fftSize - hopSize)
                samplesBuffered = fftSize - hopSize
            }
        }
    }

    /** Get number of new columns generated since the last call to this method. */
    fun getNewColumns(): Int {
        val count = newColumnCount
        newColumnCount = 0
        return count
    }

    private fun processFrame() {
        // Apply Hanning window
        val windowed = FloatArray(fftSize) { buffer[it] * hanning[it] }

        // Compute FFT magnitude spectrum
        val mag = fft.magnitudeSpectrum(windowed)

        // Update spectrogram column
        val col = spectrogram[currentCol]
        for (b in 0 until numBins) {
            val binIdx = minBin + b
            val rawMag = mag[binIdx]
            // Running energy normalization
            binEnergy[b] = alpha * binEnergy[b] + (1 - alpha) * rawMag
            col[b] = if (binEnergy[b] > 1e-6f) rawMag / binEnergy[b] else 0f
        }

        currentCol = (currentCol + 1) % historyCols
    }

    /** Get the current spectrogram as a 2D array in chronological order. */
    fun getSpectrogram(): Array<FloatArray> {
        val result = Array(historyCols) { i ->
            val srcIdx = (currentCol + i) % historyCols
            spectrogram[srcIdx].copyOf()
        }
        return result
    }

    /** Get the most recent column (current energy across all frequencies). */
    fun getCurrentColumn(): FloatArray {
        val prevCol = (currentCol - 1 + historyCols) % historyCols
        return spectrogram[prevCol].copyOf()
    }

    /** Get a column by index from the history (0 = oldest, historyCols-1 = newest). */
    fun getColumn(index: Int): FloatArray {
        val clamped = index.coerceIn(0, historyCols - 1)
        val srcIdx = (currentCol - historyCols + clamped + historyCols) % historyCols
        return spectrogram[srcIdx].copyOf()
    }

    /** Find the frequency bin with peak energy. Returns -1 if no significant signal. */
    fun findPeakBin(): Int {
        val col = getCurrentColumn()
        var maxBin = -1
        var maxVal = 0f
        for (i in col.indices) {
            if (col[i] > maxVal) {
                maxVal = col[i]
                maxBin = i
            }
        }
        return if (maxVal > 0.3f) maxBin else -1
    }

    /** Get energy at a specific bin over the last N columns in chronological order. */
    fun getBinEnergy(bin: Int, numCols: Int): FloatArray {
        val clamped = minOf(numCols, historyCols)
        val result = FloatArray(clamped)
        for (i in 0 until clamped) {
            val colIdx = (currentCol - clamped + i + historyCols) % historyCols
            result[i] = spectrogram[colIdx][bin]
        }
        return result
    }

    /** Get the bin index for a frequency in Hz. */
    fun freqToBin(freqHz: Float): Int {
        val bin = (freqHz * fftSize / sampleRate).toInt()
        return (bin - minBin).coerceIn(0, numBins - 1)
    }

    /** Get the center frequency for a bin. */
    fun binToFreq(bin: Int): Float {
        return (minBin + bin).toFloat() * sampleRate / fftSize
    }

    fun reset() {
        for (col in spectrogram) col.fill(0f)
        currentCol = 0
        samplesBuffered = 0
        buffer.fill(0f)
        binEnergy.fill(1f)
    }
}