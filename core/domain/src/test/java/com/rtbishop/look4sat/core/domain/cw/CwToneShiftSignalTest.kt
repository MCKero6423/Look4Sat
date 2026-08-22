package com.rtbishop.look4sat.core.domain.cw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Signal-level properties of the shifter: the range the spectrogram expects, the
 * detector's threshold trade-off, and behaviour on inputs a phone mic can really produce.
 *
 * The decision rule that consumes these estimates is covered by [CwShiftDeciderTest].
 */
class CwToneShiftSignalTest {

    private val sampleRate = CwDeepSpectrogram.SAMPLE_RATE

    /** Keyed CW: gated tone with noise, 60 ms on / 30 ms off, roughly 20 WPM. */
    private fun keyedTone(hz: Double, samples: Int = 1280, seed: Int = 1, noise: Double = 0.02): FloatArray {
        val random = Random(seed)
        val period = sampleRate * 90 / 1000
        return FloatArray(samples) { i ->
            val gate = if (i % period < sampleRate * 60 / 1000) 1.0 else 0.0
            (gate * sin(2.0 * PI * hz * i / sampleRate) +
                (random.nextDouble() - 0.5) * 2 * noise).toFloat()
        }
    }

    private fun noiseOnly(samples: Int = 1280, seed: Int = 2, level: Double = 1.0): FloatArray {
        val random = Random(seed)
        return FloatArray(samples) { ((random.nextDouble() - 0.5) * 2 * level).toFloat() }
    }

    /**
     * The prominence threshold sits between two measured populations and both sides
     * matter. Too low and noise is mistaken for a tone, which moves a good signal out of
     * the model's range; too high and copyable weak signals are never shifted, which is
     * the very failure the feature exists to prevent.
     */
    @Test
    fun `prominence threshold rejects noise without rejecting weak signals`() {
        var falsePositives = 0
        repeat(20) { seed ->
            if (CwToneShifter.detectToneHz(noiseOnly(seed = seed + 500), sampleRate) != null) {
                falsePositives++
            }
        }
        assertEquals("noise must never be reported as a tone", 0, falsePositives)

        // Noise at 0.7 against a unit-amplitude tone is roughly 3 dB SNR: audible,
        // decodable, and the region an over-tight threshold silently discards.
        for (hz in listOf(300.0, 800.0, 1400.0)) {
            val detected = CwToneShifter.detectToneHz(keyedTone(hz, noise = 0.7), sampleRate)
            assertEquals(
                "a weak but usable ${hz}Hz signal must be detected, not rejected as noise",
                hz, detected!!.toDouble(), 25.0
            )
        }

        assertTrue(
            "MIN_PROMINENCE ${CwToneShifter.MIN_PROMINENCE} must clear the measured noise " +
                "ceiling of ~3.4",
            CwToneShifter.MIN_PROMINENCE > 3.4
        )
        assertTrue(
            "MIN_PROMINENCE ${CwToneShifter.MIN_PROMINENCE} must not reject weak signals; " +
                "keyed CW measures 7.6-9.0 at 0 dB SNR and 5.2-6.7 at -3 dB",
            CwToneShifter.MIN_PROMINENCE < 5.2
        )
    }

    /**
     * The Hilbert kernel's L1 gain is 2.51, so summing the in-phase and quadrature paths
     * overshoots: a full-scale square wave measured 2.35 and even a plain sine 1.05. The
     * spectrogram takes log1p of the magnitude, so an overshoot is not fatal, but it
     * moves the level away from what the model was trained on.
     */
    @Test
    fun `shifted output stays within the range the spectrogram expects`() {
        val shifter = CwToneShifter.Streaming()
        val shiftHz = (CwToneShifter.TARGET_HZ - 1500.0).toFloat()

        val square = FloatArray(1280) { if ((it / 8) % 2 == 0) 1f else -1f }
        val shiftedSquare = shifter.process(square, shiftHz, sampleRate)
        assertTrue(
            "a full-scale square wave overshot: peak was ${shiftedSquare.maxOf { abs(it) }}",
            shiftedSquare.all { abs(it) <= 1f }
        )

        shifter.reset()
        val sine = FloatArray(1280) { i -> sin(2.0 * PI * 1500.0 * i / sampleRate).toFloat() }
        val shiftedSine = shifter.process(sine, shiftHz, sampleRate)
        assertTrue(
            "a full-scale sine overshot: peak was ${shiftedSine.maxOf { abs(it) }}",
            shiftedSine.all { abs(it) <= 1f }
        )

        // Limiting must not flatten the signal away: the tone still has to be there.
        val detected = CwToneShifter.detectToneHz(shiftedSine, sampleRate)
        assertEquals(
            "limiting must preserve the shifted tone",
            CwToneShifter.TARGET_HZ, detected!!.toDouble(), 30.0
        )
    }

    @Test
    fun `stateless shift also stays in range`() {
        val square = FloatArray(1280) { if ((it / 8) % 2 == 0) 1f else -1f }
        val shifted = CwToneShifter.shift(square, -700f, sampleRate)
        assertTrue(
            "peak was ${shifted.maxOf { abs(it) }}",
            shifted.all { abs(it) <= 1f }
        )
    }

    @Test
    fun `detector tolerates pathological input`() {
        // A wrong shift moves a perfectly good tone out of range, so a bogus estimate is
        // worse than none: these inputs must produce the right tone or nothing at all.
        for (offset in listOf(0.5, 1.0, 5.0, 50.0)) {
            val biased = FloatArray(1280) { i ->
                (offset + sin(2.0 * PI * 800.0 * i / sampleRate)).toFloat()
            }
            val detected = CwToneShifter.detectToneHz(biased, sampleRate)
            assertEquals(
                "a DC offset of $offset must not hide the tone",
                800.0, detected!!.toDouble(), 25.0
            )
        }

        assertNull(
            "all zeros must not report a tone",
            CwToneShifter.detectToneHz(FloatArray(1280), sampleRate)
        )

        for (size in listOf(0, 1, 2, 63)) {
            assertNull(
                "a $size-sample buffer is too short to detect from",
                CwToneShifter.detectToneHz(FloatArray(size), sampleRate)
            )
        }

        val withNan = FloatArray(1280) { i ->
            if (i == 640) Float.NaN else sin(2.0 * PI * 800.0 * i / sampleRate).toFloat()
        }
        assertNull(
            "a NaN sample must yield no tone rather than a garbage shift",
            CwToneShifter.detectToneHz(withNan, sampleRate)
        )

        // Clipping must not let a harmonic outrank the fundamental.
        for (drive in listOf(1.0, 4.0, 20.0, 200.0)) {
            val clipped = FloatArray(1280) { i ->
                (drive * sin(2.0 * PI * 500.0 * i / sampleRate)).coerceIn(-1.0, 1.0).toFloat()
            }
            val detected = CwToneShifter.detectToneHz(clipped, sampleRate)
            assertEquals(
                "at ${drive}x drive the fundamental must still win",
                500.0, detected!!.toDouble(), 25.0
            )
        }
    }
}
