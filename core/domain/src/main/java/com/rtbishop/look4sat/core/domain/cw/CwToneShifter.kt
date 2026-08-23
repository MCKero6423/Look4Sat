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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Moves an out-of-range CW tone into the model's analysis window.
 *
 * The DeepCW model only sees [CwDeepSpectrogram.MIN_FREQ_HZ]..[CwDeepSpectrogram.MAX_FREQ_HZ];
 * its input tensor width is fixed, so the window itself cannot be widened without
 * retraining. Instead a tone that sits outside the window is frequency-shifted to
 * [TARGET_HZ] before the spectrogram is built, which extends the usable pitch range
 * to roughly 100 Hz..Nyquist without touching the model.
 *
 * ### Why single-sideband mixing
 * Plain real mixing (`x * cos(2*pi*delta*t)`) produces both `tone+delta` and
 * `tone-delta`. Measured on a 1500 Hz tone shifted to 800 Hz, the unwanted image
 * folded back to 1000 Hz at 0.999 of the wanted amplitude — inside the window and
 * as loud as the signal. Upsampling first only moves the problem: shifting a 300 Hz
 * tone up produced a 200 Hz image at 0.996.
 *
 * A Hilbert transformer removes the negative-frequency half first, so mixing the
 * resulting analytic signal yields one sideband only. Across nine probe tones
 * (150..1550 Hz) that leaves a single spectral peak at the target with no component
 * above 0.3 relative amplitude.
 *
 * All functions are pure; the caller decides whether shifting is wanted.
 */
object CwToneShifter {

    /**
     * Where an out-of-window tone is moved to: the centre of the analysis window,
     * so the keying sidebands have equal headroom on both sides.
     */
    const val TARGET_HZ = 800.0

    /**
     * Tones below this are treated as absent rather than shifted. Mains hum and DC
     * drift live down here, and a real CW note that low is unusable anyway.
     */
    const val MIN_DETECTABLE_HZ = 100.0

    /**
     * A detected peak must exceed the spectrum mean by this factor to count as a tone.
     *
     * Chosen from measurements on 1280-sample (400 ms) windows of keyed CW in noise.
     * Pure noise peaks at 2.2-3.4 times its own spectral mean, so 3.0 admitted roughly
     * one noise window in five. Raising it as far as 8.0 then rejected comfortably
     * copyable signals: keyed CW measures 7.6-9.0 at 0 dB SNR and only 5.2-6.7 at -3 dB.
     *
     * 4.5 gives zero false positives across 40 noise windows while keeping the weaker
     * end of usable signals. The asymmetry is deliberate: a false tone is worse than a
     * missed one, because it moves a perfectly good signal out of the model's range,
     * whereas a miss just leaves the audio alone until a stronger window arrives.
     *
     * Windows dominated by keying gaps (a slow fist, under ~25% tone) sit at 2.4 and are
     * indistinguishable from noise at any threshold; those are skipped, not guessed at.
     */
    const val MIN_PROMINENCE = 4.5

    /** Hilbert transformer length. Odd so the group delay is a whole sample. */
    private const val HILBERT_TAPS = 63

    /** Frequency resolution of [detectToneHz], in Hz. */
    private const val DETECT_STEP_HZ = 12.5

    /** Windowed Hilbert transformer: h[n] = 2/(pi*n) for odd n, 0 otherwise. */
    private val hilbertKernel: FloatArray = FloatArray(HILBERT_TAPS) { i ->
        val n = i - HILBERT_TAPS / 2
        val ideal = if (n == 0 || n % 2 == 0) 0.0 else 2.0 / (PI * n)
        // Hamming window; without it the truncated kernel ripples badly.
        val window = 0.54 - 0.46 * cos(2.0 * PI * i / (HILBERT_TAPS - 1))
        (ideal * window).toFloat()
    }

    /** Group delay of [hilbertKernel], applied to the real path to keep them aligned. */
    private const val HILBERT_DELAY = HILBERT_TAPS / 2

    /** Taps in the band-pass that follows the mixer. Odd, for a symmetric linear phase. */
    private const val BANDPASS_TAPS = 95

    private const val BANDPASS_DELAY = BANDPASS_TAPS / 2

    /**
     * Windowed-sinc band-pass over the model's analysis window.
     *
     * Applied after the shift, and the reason a clipped sample can no longer scatter energy
     * across the display: the mixer runs above unity for any ordinary input, and while
     * [TARGET_HZ] happens to sit at a quarter of the sample rate today - so odd harmonics
     * of a clipped tone fold back onto the tone itself - that is a coincidence of two
     * constants, not a property of the design. This also removes whatever image the
     * single-sideband mix leaves behind.
     *
     * Difference of two low-pass sincs, Hamming-windowed to hold the sidelobes down.
     * Measured: 58-60 dB rejection at 300 Hz and 1500 Hz, 0.09 dB ripple over 450-1150 Hz,
     * and a 47-sample linear-phase group delay that delays the keying without distorting it.
     */
    private val bandpassKernel: FloatArray = run {
        val lo = CwDeepSpectrogram.MIN_FREQ_HZ / CwDeepSpectrogram.SAMPLE_RATE
        val hi = CwDeepSpectrogram.MAX_FREQ_HZ / CwDeepSpectrogram.SAMPLE_RATE
        FloatArray(BANDPASS_TAPS) { i ->
            val n = i - BANDPASS_DELAY
            val ideal = if (n == 0) {
                2.0 * (hi - lo)
            } else {
                (sin(2.0 * PI * hi * n) - sin(2.0 * PI * lo * n)) / (PI * n)
            }
            val window = 0.54 - 0.46 * cos(2.0 * PI * i / (BANDPASS_TAPS - 1))
            (ideal * window).toFloat()
        }
    }

    /** Outcome of inspecting a chunk of audio. */
    data class Analysis(
        /** Detected tone in Hz, or null when the audio is noise. */
        val toneHz: Float?,
        /** True when [toneHz] sits outside the model's window and can be shifted. */
        val needsShift: Boolean,
        /** Hz the tone would be moved by; 0 when no shift applies. */
        val shiftHz: Float
    )

    /**
     * Estimate the dominant tone by scanning [MIN_DETECTABLE_HZ]..Nyquist with a
     * Goertzel-style single-bin DFT.
     *
     * Deliberately not reusing [CwDeepSpectrogram]: that clips to the model window,
     * which is exactly the region an out-of-range tone is *not* in.
     *
     * @return the peak frequency, or null when nothing stands out from the noise.
     */
    fun detectToneHz(audio: FloatArray, sampleRate: Int): Float? {
        if (audio.size < 64) return null
        val nyquist = sampleRate / 2.0
        // A Hann window stops the scan from smearing energy across neighbours.
        val window = FloatArray(audio.size) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (audio.size - 1))).toFloat()
        }

        var bestHz = 0.0
        var bestMagnitude = 0.0
        var total = 0.0
        var bins = 0

        var hz = MIN_DETECTABLE_HZ
        while (hz <= nyquist) {
            var real = 0.0
            var imag = 0.0
            val omega = 2.0 * PI * hz / sampleRate
            for (i in audio.indices) {
                val value = audio[i] * window[i]
                real += value * cos(omega * i)
                imag -= value * sin(omega * i)
            }
            val magnitude = hypot(real, imag) / audio.size
            total += magnitude
            bins++
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude
                bestHz = hz
            }
            hz += DETECT_STEP_HZ
        }

        if (bins == 0 || bestMagnitude <= 0.0) return null
        val mean = total / bins
        // Pure noise has a flat spectrum, so the peak barely beats the mean.
        if (mean <= 0.0 || bestMagnitude < mean * MIN_PROMINENCE) return null
        return bestHz.toFloat()
    }

    /**
     * Decide whether [audio] needs shifting, without modifying it.
     *
     * A tone already inside the window is left alone: shifting it would add filter
     * ringing and rounding for no benefit, and the model handles it natively.
     */
    fun analyse(audio: FloatArray, sampleRate: Int): Analysis {
        val tone = detectToneHz(audio, sampleRate)
            ?: return Analysis(toneHz = null, needsShift = false, shiftHz = 0f)
        val inWindow = tone >= CwDeepSpectrogram.MIN_FREQ_HZ && tone <= CwDeepSpectrogram.MAX_FREQ_HZ
        if (inWindow) return Analysis(toneHz = tone, needsShift = false, shiftHz = 0f)
        return Analysis(
            toneHz = tone,
            needsShift = true,
            shiftHz = (TARGET_HZ - tone).toFloat()
        )
    }

    /**
     * Shift [audio] by [shiftHz] using single-sideband mixing.
     *
     * The Hilbert transformer suppresses the negative-frequency half, so only the
     * wanted sideband survives; see the class docs for the measured alternative.
     * Returns a new array; [audio] is not modified.
     *
     * Stateless: [audio] is treated as an isolated signal, so the first and last
     * [HILBERT_DELAY] samples convolve against zeros instead of the neighbouring
     * audio. Fine for a whole buffer, but it corrupts 62 of every 320 samples when
     * called per capture chunk, so streaming callers must use [Streaming].
     */
    fun shift(audio: FloatArray, shiftHz: Float, sampleRate: Int): FloatArray {
        if (shiftHz == 0f || audio.isEmpty()) return audio

        // Quadrature path: audio convolved with the Hilbert kernel.
        val quadrature = FloatArray(audio.size)
        for (i in audio.indices) {
            var sum = 0f
            for (k in hilbertKernel.indices) {
                val j = i - k + HILBERT_DELAY
                if (j >= 0 && j < audio.size) sum += hilbertKernel[k] * audio[j]
            }
            quadrature[i] = sum
        }

        // Re{(inPhase + j*quadrature) * e^(j*2*pi*shift*t)}
        val out = FloatArray(audio.size)
        val step = 2.0 * PI * shiftHz / sampleRate
        for (i in audio.indices) {
            val phase = step * i
            out[i] = (audio[i] * cos(phase) - quadrature[i] * sin(phase)).toFloat()
        }
        // Same chain as Streaming, so the two paths stay comparable: scale to fit rather
        // than clip, then filter to the window. Clipping would generate harmonics, and a
        // third harmonic can fall inside the window where the filter cannot remove it.
        Normaliser().applyTo(out)
        return bandPassWhole(out)
    }

    /** Band-pass an entire buffer; the streaming path keeps tap history instead. */
    private fun bandPassWhole(audio: FloatArray): FloatArray {
        val out = FloatArray(audio.size)
        for (i in audio.indices) {
            var sum = 0f
            for (k in bandpassKernel.indices) {
                val j = i - k + BANDPASS_DELAY
                if (j >= 0 && j < audio.size) sum += bandpassKernel[k] * audio[j]
            }
            out[i] = clampToUnit(sum.toDouble())
        }
        return out
    }

    /**
     * Chunk-by-chunk shifter that carries the state [shift] cannot.
     *
     * Two things must survive across calls for concatenated chunks to form a clean
     * signal:
     *
     * 1. **Filter history.** The Hilbert FIR spans [HILBERT_TAPS] samples, so the
     *    first outputs of a chunk need the previous chunk's tail. Without it those
     *    samples convolve against zeros; measured on 320-sample chunks that distorts
     *    62 of them (19%) and inflates envelope ripple to 8.7x the whole-buffer
     *    baseline.
     * 2. **Mixer phase.** Restarting the local oscillator at zero every chunk puts a
     *    phase step at every boundary.
     *
     * One difference from [shift] remains and is unavoidable: output sample `i` ideally
     * needs input up to `i + HILBERT_DELAY`, which for the last samples of a chunk has
     * not been captured yet. Those trailing taps therefore see zeros. Measured against
     * a whole-buffer shift the divergence is confined to the final 3 samples of each
     * 320-sample chunk and disappears immediately after the boundary — under 1% of the
     * audio, versus a 20 WPM dot spanning 192 samples. Buffering a chunk to remove it
     * would add 10 ms of latency for no decoding benefit.
     *
     * Not thread-safe: the decoder drives it from a single capture coroutine.
     */
    class Streaming {

        private val history = FloatArray(HILBERT_TAPS - 1)
        private var phase = 0.0
        private val bandHistory = FloatArray(BANDPASS_TAPS - 1)
        private val normaliser = Normaliser()

        /** Shift one chunk, continuing the filter and oscillator state. */
        fun process(chunk: FloatArray, shiftHz: Float, sampleRate: Int): FloatArray {
            if (shiftHz == 0f || chunk.isEmpty()) {
                // Still advance the history, so enabling a shift later starts from real
                // audio rather than the silence left over from before.
                pushHistory(chunk)
                return chunk
            }

            // Convolve over [history || chunk] so every output sees real samples.
            val combined = FloatArray(history.size + chunk.size)
            history.copyInto(combined)
            chunk.copyInto(combined, history.size)

            val out = FloatArray(chunk.size)
            val step = 2.0 * PI * shiftHz / sampleRate
            for (i in chunk.indices) {
                val centre = history.size + i
                var quadrature = 0f
                for (k in hilbertKernel.indices) {
                    val j = centre - k + HILBERT_DELAY
                    if (j >= 0 && j < combined.size) quadrature += hilbertKernel[k] * combined[j]
                }
                val currentPhase = phase + step * i
                val mixed = combined[centre] * cos(currentPhase) - quadrature * sin(currentPhase)
                out[i] = mixed.toFloat()
            }

            // Scale to fit before filtering, rather than clipping after. Clipping generates
            // odd harmonics and the band-pass can only remove the ones that land outside
            // the window - a third harmonic can land inside it, where the filter has no
            // business touching it and the model would read it as a second tone.
            normaliser.applyTo(out)
            val filtered = bandPass(out)

            // Keep the phase bounded; letting it grow loses float precision.
            phase = (phase + step * chunk.size) % (2.0 * PI)
            pushHistory(chunk)
            return filtered
        }

        /**
         * Band-pass [chunk] to the model's window, carrying tap history across calls.
         *
         * Same reason the Hilbert filter keeps history: without the previous chunk's tail
         * the first outputs convolve against zeros and every boundary gets a transient.
         */
        private fun bandPass(chunk: FloatArray): FloatArray {
            val combined = FloatArray(bandHistory.size + chunk.size)
            bandHistory.copyInto(combined)
            chunk.copyInto(combined, bandHistory.size)

            val out = FloatArray(chunk.size)
            for (i in chunk.indices) {
                val centre = bandHistory.size + i
                var sum = 0f
                for (k in bandpassKernel.indices) {
                    val j = centre - k + BANDPASS_DELAY
                    if (j >= 0 && j < combined.size) sum += bandpassKernel[k] * combined[j]
                }
                out[i] = clampToUnit(sum.toDouble())
            }

            // Keep the newest BANDPASS_TAPS - 1 samples of the pre-filter signal.
            val keep = minOf(bandHistory.size, chunk.size)
            if (keep < bandHistory.size) {
                bandHistory.copyInto(bandHistory, 0, keep, bandHistory.size)
            }
            chunk.copyInto(bandHistory, bandHistory.size - keep, chunk.size - keep, chunk.size)
            return out
        }

        /** Clear filter history and phase, e.g. after a decoder reset. */
        fun reset() {
            history.fill(0f)
            phase = 0.0
            bandHistory.fill(0f)
            normaliser.reset()
        }

        /** Keep the most recent [history] samples of the stream. */
        private fun pushHistory(chunk: FloatArray) {
            if (chunk.isEmpty()) return
            if (chunk.size >= history.size) {
                chunk.copyInto(history, 0, chunk.size - history.size, chunk.size)
            } else {
                history.copyInto(history, 0, chunk.size, history.size)
                chunk.copyInto(history, history.size - chunk.size)
            }
        }
    }

    /**
     * Convenience wrapper: analyse [audio] and shift it only when the tone is
     * outside the model window.
     *
     * @return the audio to feed the model (the original array when no shift was
     *   needed) paired with the [Analysis] that produced the decision, so callers
     *   can log what happened.
     */
    fun shiftIfOutsideWindow(audio: FloatArray, sampleRate: Int): Pair<FloatArray, Analysis> {
        val analysis = analyse(audio, sampleRate)
        if (!analysis.needsShift) return audio to analysis
        return shift(audio, analysis.shiftHz, sampleRate) to analysis
    }

    /**
     * Peak-following gain, so a shifted tone is scaled to fit rather than clipped flat.
     *
     * The Hilbert kernel has an L1 gain of 2.51, so an input of normal strength leaves the
     * mixer above unity and hard clipping squared the waveform off. That distortion is
     * mostly hidden today by a coincidence - [TARGET_HZ] is a quarter of the sample rate,
     * so every odd harmonic of a clipped tone folds back onto the tone itself - but the
     * keying envelope is still flattened, and a different target would scatter harmonics
     * across the band instead. Scaling avoids both.
     *
     * The gain decays slowly and recovers slowly so it does not pump within a keyed
     * element, and it is shared across chunks because a per-chunk gain would step at
     * every boundary.
     */
    internal class Normaliser {
        private var gain = 1.0

        /** Fit [samples] inside +-1 in place, adjusting the shared gain as needed. */
        fun applyTo(samples: FloatArray) {
            var peak = 0.0
            for (v in samples) {
                val a = abs(v.toDouble())
                if (a > peak) peak = a
            }
            // A peak needing less gain than we have takes effect at once: overshoot is
            // what clipping used to destroy, so it is corrected without waiting.
            val needed = if (peak > 1e-9) 1.0 / peak else 1.0
            gain = if (needed < gain) needed else gain + (needed - gain) * RECOVERY
            gain = gain.coerceAtMost(1.0)
            for (i in samples.indices) {
                // Still bounded: the gain is derived from this chunk's peak, but the
                // recovery ramp can leave a later sample slightly over.
                samples[i] = clampToUnit(samples[i] * gain)
            }
        }

        fun reset() {
            gain = 1.0
        }

        private companion object {
            /** Per-chunk fraction of the way back towards unity gain. */
            const val RECOVERY = 0.05
        }
    }

    /**
     * Keep a mixed sample inside the +/-1.0 range the spectrogram assumes.
     *
     * The Hilbert kernel has an L1 gain of 2.51, so summing the in-phase and quadrature
     * paths can exceed unity even for a full-scale sine (measured 1.05 at 1500 Hz, 2.35
     * for a square wave). The spectrogram takes log1p of the magnitude, so an overshoot
     * is not fatal, but it shifts the level the model was trained on.
     */
    private fun clampToUnit(value: Double): Float = when {
        value > 1.0 -> 1f
        value < -1.0 -> -1f
        else -> value.toFloat()
    }

    /** True when [toneHz] lies inside the model's analysis window. */
    fun isInsideWindow(toneHz: Float): Boolean =
        toneHz >= CwDeepSpectrogram.MIN_FREQ_HZ && toneHz <= CwDeepSpectrogram.MAX_FREQ_HZ
}
