package com.rtbishop.look4sat.core.domain.cw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Regression guards for the two decision defects an audit measured in the decoder's
 * detection loop. Both silently defeated the feature, so both are pinned here.
 *
 * The decoder's rule is reproduced by [decide] because `runDetection` needs a Context
 * and a loaded ONNX model; the inputs it consumes ([CwToneShifter.analyse]) are the real
 * thing, and the numbers below come from the same audio the audit used.
 */
class CwToneShiftDecisionTest {

    private val sampleRate = CwDeepSpectrogram.SAMPLE_RATE
    private val hysteresisHz = 40f // CwDeepDecoder.SHIFT_HYSTERESIS_HZ

    /** State the decoder carries between detections. */
    private data class ShiftState(val shiftHz: Float = 0f, val anchorToneHz: Float? = null)

    /**
     * The decision `CwDeepDecoder.runDetection` makes, and the property under test:
     * silence retains the shift, and hysteresis is measured in tone space against the
     * pitch that produced the active shift.
     */
    private fun decide(state: ShiftState, audio: FloatArray): ShiftState {
        val analysis = CwToneShifter.analyse(audio, sampleRate)
        val toneHz = analysis.toneHz ?: return state
        val anchor = state.anchorToneHz
        if (anchor != null && abs(toneHz - anchor) < hysteresisHz) return state
        return ShiftState(analysis.shiftHz, toneHz)
    }

    /** Keyed CW: gated tone with noise, 60 ms on / 30 ms off, roughly 20 WPM. */
    private fun keyedTone(hz: Double, samples: Int, seed: Int = 1, noise: Double = 0.02): FloatArray {
        val random = Random(seed)
        val period = sampleRate * 90 / 1000
        return FloatArray(samples) { i ->
            val gate = if (i % period < sampleRate * 60 / 1000) 1.0 else 0.0
            (gate * sin(2.0 * PI * hz * i / sampleRate) +
                (random.nextDouble() - 0.5) * 2 * noise).toFloat()
        }
    }

    private fun silence(samples: Int, seed: Int = 2, noise: Double = 0.02): FloatArray {
        val random = Random(seed)
        return FloatArray(samples) { ((random.nextDouble() - 0.5) * 2 * noise).toFloat() }
    }

    private fun steadyTone(hz: Double, samples: Int = 1280): FloatArray =
        FloatArray(samples) { i -> sin(2.0 * PI * hz * i / sampleRate).toFloat() }

    @Test
    fun `a keying gap must not collapse an established shift`() {
        // Establish a shift from a real out-of-window tone.
        var state = decide(ShiftState(), steadyTone(1400.0))
        val established = state.shiftHz
        assertTrue("a 1400 Hz tone must produce a shift", established != 0f)

        // A detection window landing in a keying gap sees no tone. Retaining the shift is
        // the point: dropping it left the next ~2 s buffered unshifted, i.e. outside the
        // model's range and invisible to it. Measured 11 such windows in 90 detections.
        state = decide(state, silence(1280))
        assertEquals(
            "silence must not change the shift",
            established, state.shiftHz, 0f
        )

        // Several gaps in a row must not erode it either.
        repeat(5) { state = decide(state, silence(1280, seed = it + 3)) }
        assertEquals(
            "repeated silence must not erode the shift",
            established, state.shiftHz, 0f
        )
    }

    @Test
    fun `silence is reported as no tone rather than a zero shift`() {
        val analysis = CwToneShifter.analyse(silence(1280), sampleRate)
        assertNull("noise must not be mistaken for a tone", analysis.toneHz)
        assertFalse("no tone means no shift decision", analysis.needsShift)
    }

    @Test
    fun `an estimate hopping across the window edge must not keep re-shifting`() {
        // 1200.0 Hz is inside the window (shift 0); 1212.5 Hz, one scan bin away, is
        // outside (a large shift). Comparing shifts made the guard lapse exactly here,
        // because one side has shift 0. Measured: 10 window drops in 10 detections.
        var state = decide(ShiftState(), steadyTone(1212.5))
        val first = state
        assertTrue("1212.5 Hz is outside the window", first.shiftHz != 0f)

        state = decide(state, steadyTone(1200.0))
        assertEquals(
            "a one-bin hop back across the edge must not change the shift",
            first.shiftHz, state.shiftHz, 0f
        )
        assertEquals(
            "the anchor must stay put too",
            first.anchorToneHz!!, state.anchorToneHz!!, 0f
        )
    }

    @Test
    fun `an edge tone with noise settles instead of thrashing`() {
        // The audit measured 35 window drops in 60 detections for a 1205 Hz tone.
        var state = ShiftState()
        var changes = 0
        repeat(30) { i ->
            val next = decide(state, keyedTone(1205.0, 1280, seed = i + 10))
            if (next.shiftHz != state.shiftHz) changes++
            state = next
        }
        assertTrue(
            "an edge tone must settle; the shift changed $changes times in 30 detections",
            changes <= 3
        )
    }

    @Test
    fun `a real retune is still followed`() {
        var state = decide(ShiftState(), steadyTone(1400.0))
        val before = state.shiftHz

        // 200 Hz is far beyond the hysteresis margin: the decoder must re-centre.
        state = decide(state, steadyTone(1200.0 - 400.0))
        assertTrue(
            "a 200 Hz retune must change the shift (was $before, now ${state.shiftHz})",
            state.shiftHz != before
        )
    }

    @Test
    fun `slow drift keeps the shifted tone inside the window`() {
        // Hysteresis anchors on the tone that set the shift, so staleness is bounded by
        // the margin rather than accumulating. Walk 1300 -> 1500 Hz in 12.5 Hz steps.
        var state = decide(ShiftState(), steadyTone(1300.0))
        var tone = 1300.0
        var worstLanding = 0.0
        while (tone <= 1500.0) {
            state = decide(state, steadyTone(tone))
            val landed = tone + state.shiftHz
            worstLanding = maxOf(worstLanding, abs(landed - CwToneShifter.TARGET_HZ))
            assertTrue(
                "a ${tone}Hz tone landed at ${landed}Hz, outside the model window",
                CwToneShifter.isInsideWindow(landed.toFloat())
            )
            tone += 12.5
        }
        assertTrue(
            "drift staleness must stay near the hysteresis margin, was $worstLanding Hz",
            worstLanding <= hysteresisHz + 12.5
        )
    }

    @Test
    fun `shifted output stays within the range the spectrogram expects`() {
        // The Hilbert kernel's L1 gain is 2.51, so mixing can exceed unity: a full-scale
        // square wave measured 2.35 before clamping, and even a plain sine reached 1.05.
        val shifter = CwToneShifter.Streaming()
        val shiftHz = (CwToneShifter.TARGET_HZ - 1500.0).toFloat()

        val square = FloatArray(1280) { if ((it / 8) % 2 == 0) 1f else -1f }
        val shiftedSquare = shifter.process(square, shiftHz, sampleRate)
        assertTrue(
            "a full-scale square wave must not overshoot: peak was " +
                "${shiftedSquare.maxOf { abs(it) }}",
            shiftedSquare.all { abs(it) <= 1f }
        )

        shifter.reset()
        val sine = FloatArray(1280) { i -> sin(2.0 * PI * 1500.0 * i / sampleRate).toFloat() }
        val shiftedSine = shifter.process(sine, shiftHz, sampleRate)
        assertTrue(
            "a full-scale sine must not overshoot: peak was ${shiftedSine.maxOf { abs(it) }}",
            shiftedSine.all { abs(it) <= 1f }
        )

        // Clamping must not flatten the signal: the tone still has to be there.
        val detected = CwToneShifter.detectToneHz(shiftedSine, sampleRate)
        assertEquals(
            "clamping must preserve the shifted tone",
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
        // From the audit: DC offsets, clipping and short buffers must not produce a
        // bogus shift, since a wrong shift moves a perfectly good tone out of range.
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

        val zeros = FloatArray(1280)
        assertNull("all zeros must not report a tone", CwToneShifter.detectToneHz(zeros, sampleRate))

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
    }
}
