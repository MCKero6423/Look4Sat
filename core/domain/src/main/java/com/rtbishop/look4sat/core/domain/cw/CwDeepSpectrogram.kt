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

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln1p
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Audio front-end for the DeepCW model: turns PCM samples into the
 * `[time, frequency]` log-magnitude spectrogram the network expects.
 *
 * Mirrors the upstream Python reference (deepcw-engine
 * `examples/python/decode_morse.py`) step for step:
 *
 *   resample -> 3200 Hz, reflect-pad by fft/2, periodic Hann window of 256,
 *   real FFT, keep bins [32, 97) i.e. 400-1200 Hz, then log1p.
 *
 * The model's fixed 400-1200 Hz window means pitch detection is built in —
 * no spectral peak tracking or squelch gating is needed on our side.
 */
object CwDeepSpectrogram {

    /** Model input sample rate, from `model.onnx.json`. */
    const val SAMPLE_RATE = 3200

    /** FFT window length in samples. */
    const val FFT_LENGTH = 256

    /** Hop between consecutive frames; 48/3200 = 15.0 ms per frame. */
    const val HOP_LENGTH = 48

    private const val MIN_FREQ_HZ = 400.0
    private const val MAX_FREQ_HZ = 1200.0

    /** Number of frequency bins the model expects. */
    const val FREQUENCY_BINS = 65

    /** Milliseconds of audio represented by one output frame. */
    const val MS_PER_FRAME = 1000.0 * HOP_LENGTH / SAMPLE_RATE

    private val hannWindow: FloatArray = FloatArray(FFT_LENGTH) { i ->
        // numpy: np.hanning(N + 1)[:-1] — the periodic (not symmetric) variant.
        (0.5 - 0.5 * cos(2.0 * PI * i / FFT_LENGTH)).toFloat()
    }

    /**
     * Inclusive-exclusive bin range covering [minHz, maxHz].
     * Returns `start to stop`, matching the reference's `frequency_bin_range`.
     */
    fun frequencyBinRange(
        sampleRate: Int,
        fftLength: Int,
        minHz: Double,
        maxHz: Double
    ): Pair<Int, Int> {
        val binHz = sampleRate.toDouble() / fftLength
        val start = ceil(minHz / binHz).toInt()
        val stop = floor(maxHz / binHz).toInt() + 1
        return start to stop
    }

    /**
     * Linear-interpolation resampler. Deliberately dependency-light and
     * identical to the reference implementation so spectrograms match.
     */
    fun resampleLinear(audio: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        if (sourceRate == targetRate || audio.isEmpty()) return audio
        val targetLength = (audio.size.toDouble() * targetRate / sourceRate).roundToInt()
        val out = FloatArray(targetLength)
        val ratio = sourceRate.toDouble() / targetRate
        for (i in 0 until targetLength) {
            val position = i * ratio
            val left = floor(position).toInt()
            val right = minOf(left + 1, audio.size - 1)
            val fraction = (position - left).toFloat()
            out[i] = audio[left] * (1f - fraction) + audio[right] * fraction
        }
        return out
    }

    /**
     * Build the log-magnitude spectrogram. Input must already be at
     * [SAMPLE_RATE]; use [resampleLinear] first when it is not.
     *
     * @return `[frames][FREQUENCY_BINS]` values, all non-negative.
     */
    fun compute(audio: FloatArray): Array<FloatArray> {
        require(audio.size >= FFT_LENGTH) {
            "audio is too short for fftLength=$FFT_LENGTH, got ${audio.size}"
        }

        val (startBin, stopBin) = frequencyBinRange(
            SAMPLE_RATE, FFT_LENGTH, MIN_FREQ_HZ, MAX_FREQ_HZ
        )
        val bins = stopBin - startBin
        require(bins == FREQUENCY_BINS) {
            "expected $FREQUENCY_BINS bins, computed $bins"
        }

        val padded = reflectPad(audio, FFT_LENGTH / 2)
        val frames = 1 + (padded.size - FFT_LENGTH) / HOP_LENGTH
        val result = Array(frames) { FloatArray(bins) }

        val real = FloatArray(FFT_LENGTH)
        val imag = FloatArray(FFT_LENGTH)
        for (frame in 0 until frames) {
            val offset = frame * HOP_LENGTH
            for (i in 0 until FFT_LENGTH) {
                real[i] = padded[offset + i] * hannWindow[i]
                imag[i] = 0f
            }
            fftInPlace(real, imag)
            val row = result[frame]
            for (bin in startBin until stopBin) {
                val magnitude = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin])
                row[bin - startBin] = ln1p(magnitude.toDouble()).toFloat()
            }
        }
        return result
    }

    /**
     * numpy `mode="reflect"`: mirrors around the edge samples without
     * repeating them, so [1,2,3] padded by 2 becomes [3,2,1,2,3,2,1].
     */
    private fun reflectPad(audio: FloatArray, pad: Int): FloatArray {
        if (pad == 0) return audio
        val out = FloatArray(audio.size + 2 * pad)
        for (i in 0 until pad) out[i] = audio[pad - i]
        audio.copyInto(out, pad)
        val last = audio.size - 1
        for (i in 0 until pad) out[pad + audio.size + i] = audio[last - 1 - i]
        return out
    }

    /**
     * Iterative radix-2 Cooley-Tukey FFT. [FFT_LENGTH] is a power of two, so
     * no padding case is needed. Only the first half of the output is read by
     * [compute], which is the real-input equivalent of numpy's `rfft`.
     */
    private fun fftInPlace(real: FloatArray, imag: FloatArray) {
        val n = real.size

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }

        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wReal = cos(angle).toFloat()
            val wImag = kotlin.math.sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curReal = 1f
                var curImag = 0f
                for (k in 0 until length / 2) {
                    val evenReal = real[i + k]
                    val evenImag = imag[i + k]
                    val oddReal = real[i + k + length / 2]
                    val oddImag = imag[i + k + length / 2]
                    val mulReal = oddReal * curReal - oddImag * curImag
                    val mulImag = oddReal * curImag + oddImag * curReal
                    real[i + k] = evenReal + mulReal
                    imag[i + k] = evenImag + mulImag
                    real[i + k + length / 2] = evenReal - mulReal
                    imag[i + k + length / 2] = evenImag - mulImag
                    val nextReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = nextReal
                }
                i += length
            }
            length = length shl 1
        }
    }
}
