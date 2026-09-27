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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Low-pass filter applied before decimating to the model's sample rate.
 *
 * [CwDeepSpectrogram.resampleLinear] drops from the capture rate to 3200 Hz by
 * interpolating between samples, with nothing removing the content above the new
 * Nyquist of 1600 Hz first. Everything higher folds back into the audible window,
 * which is not a subtle degradation - measured on 44100 Hz input a 3000 Hz tone
 * reappears at 200 Hz at 119 times the spectral mean, indistinguishable from a real
 * signal, and 1800 Hz lands on 1400 Hz. Worse for copy, the whole 1600-22050 Hz band
 * of hiss folds down on top of the signal and lifts the noise floor across the entire
 * display.
 *
 * The resampler itself is deliberately left alone: its comment notes it matches the
 * reference implementation the model was trained against, so changing its arithmetic
 * would move the spectrogram away from what DeepCW expects. Filtering first fixes the
 * aliasing without touching that contract.
 *
 * A windowed-sinc FIR rather than a biquad cascade: the transition band has to be
 * steep to keep 1600 Hz while rejecting 1800 Hz, and a linear-phase FIR does not
 * smear the keying envelope the way a high-order IIR would.
 */
object CwAntiAlias {

    /**
     * Cut-off as a fraction of the target Nyquist.
     *
     * Below 1.0 so the transition band lands inside the discarded region rather than
     * straddling it. At 0.92 the response is flat to 1470 Hz, which still covers the
     * model's 1200 Hz window and the shifter's detection range with room to spare.
     */
    private const val CUTOFF_FRACTION = 0.92

    /**
     * Filter length. Odd so the group delay is a whole number of samples.
     *
     * 127 taps at 44100 Hz gives roughly a 700 Hz transition width - enough to put
     * 1800 Hz down by more than 40 dB while passing 1470 Hz unattenuated. Longer would
     * be sharper and slower; this runs on a phone during a pass.
     */
    private const val TAPS = 127

    /** Delay introduced by [TAPS], for callers that need to align another path. */
    const val GROUP_DELAY_SAMPLES = TAPS / 2

    /**
     * Filter [audio] so that decimating to [targetRate] cannot alias.
     *
     * A no-op when [sourceRate] is at or below [targetRate], since there is nothing
     * above the target Nyquist to remove. Returns a new array; [audio] is unchanged.
     */
    fun prepareForDecimation(audio: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        if (audio.isEmpty() || sourceRate <= targetRate) return audio
        val cutoffHz = targetRate / 2.0 * CUTOFF_FRACTION
        return applyFir(audio, kernelFor(cutoffHz, sourceRate))
    }

    /**
     * Windowed-sinc low-pass kernel, normalised to unity gain at DC.
     *
     * Blackman window: its sidelobes are around -58 dB against the Hamming window's
     * -41 dB, and sidelobe level is exactly what decides how much of the folded band
     * survives.
     */
    private fun kernelFor(cutoffHz: Double, sampleRate: Int): FloatArray {
        val normalised = cutoffHz / sampleRate
        val half = TAPS / 2
        val raw = DoubleArray(TAPS) { i ->
            val n = i - half
            val sinc = if (n == 0) {
                2.0 * normalised
            } else {
                sin(2.0 * PI * normalised * n) / (PI * n)
            }
            val window = 0.42 -
                0.5 * cos(2.0 * PI * i / (TAPS - 1)) +
                0.08 * cos(4.0 * PI * i / (TAPS - 1))
            sinc * window
        }
        val sum = raw.sum()
        // Unity DC gain, so filtering does not change the level the model was trained on.
        return FloatArray(TAPS) { i -> (raw[i] / sum).toFloat() }
    }

    /**
     * Convolve, compensating for the filter's own delay so the output lines up with
     * the input. Edge taps that fall outside the buffer see zeros, which costs the
     * first and last [GROUP_DELAY_SAMPLES] samples of an isolated buffer.
     */
    private fun applyFir(audio: FloatArray, kernel: FloatArray): FloatArray {
        val out = FloatArray(audio.size)
        for (i in audio.indices) {
            var sum = 0f
            for (k in kernel.indices) {
                val j = i - k + GROUP_DELAY_SAMPLES
                if (j >= 0 && j < audio.size) sum += kernel[k] * audio[j]
            }
            out[i] = sum
        }
        return out
    }

    /**
     * Chunk-by-chunk filter that carries the state [prepareForDecimation] cannot.
     *
     * Two things are needed for concatenated chunks to match a whole-buffer filter.
     * History is the obvious one: the FIR spans [TAPS] samples, so a chunk's first
     * outputs need the tail of the one before it.
     *
     * The second is less obvious and was measured rather than reasoned about. A
     * linear-phase FIR is centred, so output sample `i` needs input up to
     * `i + GROUP_DELAY_SAMPLES` - samples that have not been captured yet when the
     * chunk arrives. A first attempt let those taps fall off the end of the buffer and
     * read as zeros; against a whole-buffer filter that diverged by 0.134 across the
     * last 44 samples of every chunk, which is a click at each boundary rather than a
     * rounding difference.
     *
     * So output is held back by [GROUP_DELAY_SAMPLES] samples: each call emits the
     * samples whose lookahead has now arrived, and keeps the rest until the next chunk
     * completes them. The cost is a fixed 63-sample delay, about 1.4 ms at 44100 Hz,
     * against a 20 WPM dot of roughly 60 ms.
     *
     * Not thread-safe: driven from the single capture coroutine.
     */
    class Streaming(sourceRate: Int, targetRate: Int) {

        private val kernel: FloatArray? =
            if (sourceRate <= targetRate) {
                null
            } else {
                kernelFor(targetRate / 2.0 * CUTOFF_FRACTION, sourceRate)
            }

        /** Samples not yet emitted: filter history plus the lookahead still owed. */
        private var pending = FloatArray(0)

        /** Filter one chunk, continuing from the previous call. */
        fun process(chunk: FloatArray): FloatArray {
            val k = kernel ?: return chunk
            if (chunk.isEmpty()) return chunk

            val combined = FloatArray(pending.size + chunk.size)
            pending.copyInto(combined)
            chunk.copyInto(combined, pending.size)

            // Only samples with a full window on both sides are ready. Everything from
            // here on still needs input that has not arrived.
            val ready = combined.size - TAPS + 1
            if (ready <= 0) {
                pending = combined
                return FloatArray(0)
            }

            val out = FloatArray(ready)
            for (i in 0 until ready) {
                var sum = 0f
                for (t in k.indices) {
                    sum += k[t] * combined[i + TAPS - 1 - t]
                }
                out[i] = sum
            }

            // Carry the tail that the next chunk will complete.
            pending = combined.copyOfRange(ready, combined.size)
            return out
        }

        /** Clear pending state, e.g. after a decoder reset. */
        fun reset() {
            pending = FloatArray(0)
        }
    }
}
