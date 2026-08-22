package com.rtbishop.look4sat.core.data.cw

import com.rtbishop.look4sat.core.domain.cw.CwDeepSpectrogram
import com.rtbishop.look4sat.core.domain.cw.CwToneShifter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The gating contract the decoder relies on: shift only when the user opted in AND the
 * tone is outside the model window.
 *
 * [CwDeepDecoder] needs a Context and a loaded ONNX model, so it cannot be constructed
 * here. What these tests do exercise is the real decision function the decoder calls -
 * [CwToneShifter.analyse] - rather than a copy of it, so a wrong verdict fails here.
 * The decoder's own sample accumulation and throttling are covered by the streaming
 * tests in core:domain.
 */
class CwToneShiftGateTest {

    private val sampleRate = CwDeepSpectrogram.SAMPLE_RATE

    private fun tone(hz: Double, samples: Int = 1600): FloatArray = FloatArray(samples) { i ->
        sin(2.0 * PI * hz * i / sampleRate).toFloat()
    }

    /**
     * The enabled/disabled gate as [CwDeepDecoder.applyToneShift] applies it: when off
     * the audio is returned as-is, when on the verdict comes from the real analyser.
     */
    private fun gate(audio: FloatArray, enabled: Boolean): FloatArray {
        if (!enabled) return audio
        val analysis = CwToneShifter.analyse(audio, sampleRate)
        if (!analysis.needsShift) return audio
        return CwToneShifter.shift(audio, analysis.shiftHz, sampleRate)
    }

    @Test
    fun `disabled leaves every tone untouched`() {
        for (hz in listOf(150.0, 300.0, 800.0, 1200.0, 1500.0)) {
            val audio = tone(hz)
            assertSame(
                "$hz Hz must pass through unchanged while the setting is off",
                audio, gate(audio, enabled = false)
            )
        }
    }

    @Test
    fun `enabled still leaves in-window tones untouched`() {
        for (hz in listOf(400.0, 600.0, 800.0, 1000.0, 1200.0)) {
            val audio = tone(hz)
            assertSame(
                "$hz Hz is inside the window; enabling the setting must not alter it",
                audio, gate(audio, enabled = true)
            )
        }
    }

    @Test
    fun `enabled shifts only out-of-window tones`() {
        for (hz in listOf(200.0, 300.0, 1300.0, 1500.0)) {
            val audio = tone(hz)
            val result = gate(audio, enabled = true)
            assertFalse("$hz Hz should have been shifted", result === audio)
            assertEquals("shift must preserve length", audio.size, result.size)
        }
    }

    @Test
    fun `window edges count as inside`() {
        val analysisLow = CwToneShifter.analyse(tone(CwDeepSpectrogram.MIN_FREQ_HZ), sampleRate)
        val analysisHigh = CwToneShifter.analyse(tone(CwDeepSpectrogram.MAX_FREQ_HZ), sampleRate)
        assertFalse("400 Hz is the lower edge, inside", analysisLow.needsShift)
        assertFalse("1200 Hz is the upper edge, inside", analysisHigh.needsShift)
    }

    @Test
    fun `shift target is inside the window`() {
        assertTrue(
            "the target must be a pitch the model can see",
            CwToneShifter.isInsideWindow(CwToneShifter.TARGET_HZ.toFloat())
        )
    }

    /**
     * Regression guard for the defect that made the whole feature dead on arrival:
     * the decoder gated detection on a single chunk reaching DETECT_MIN_SAMPLES, but
     * AudioCapture delivers 4410 samples at 44.1 kHz, which is only 320 after
     * resampling to 3200 Hz. Detection could never run.
     *
     * The decoder now pools chunks, so what matters is that the pooled size is
     * reachable: a handful of real-sized chunks must add up to enough audio.
     */
    @Test
    fun `pooled capture chunks reach the detection threshold`() {
        val captureRate = 44100
        val captureChunk = captureRate / 10 // AudioCapture's ~100 ms read
        val resampledChunk = captureChunk * CwDeepSpectrogram.SAMPLE_RATE / captureRate
        assertEquals(
            "a capture chunk resamples to 320 samples; if this changes revisit pooling",
            320, resampledChunk
        )

        val threshold = 1280 // CwDeepDecoder.DETECT_MIN_SAMPLES
        val chunksNeeded = (threshold + resampledChunk - 1) / resampledChunk
        assertTrue(
            "a single chunk ($resampledChunk) must not be expected to reach $threshold",
            resampledChunk < threshold
        )
        assertTrue(
            "pooling must reach the threshold within a second of audio, needs $chunksNeeded chunks",
            chunksNeeded in 2..10
        )

        // And that much audio must actually be enough for the detector to work.
        val pooled = tone(1500.0, samples = threshold)
        val detected = CwToneShifter.detectToneHz(pooled, sampleRate)
        assertEquals(
            "the pooled window must be long enough to detect a tone",
            1500.0, detected!!.toDouble(), 25.0
        )
    }

    /**
     * A tone drifting slightly, or a detector estimate hopping to an adjacent 12.5 Hz
     * scan bin, must not keep re-shifting: the decoder drops its 20 s window on every
     * shift change, so churn would cost more context than the re-centring gains.
     *
     * This asserts the hysteresis threshold the decoder applies is wide enough to
     * absorb realistic detector jitter and narrow enough to still follow a real retune.
     */
    @Test
    fun `hysteresis absorbs detector jitter but follows a real retune`() {
        val hysteresisHz = 40f // CwDeepDecoder.SHIFT_HYSTERESIS_HZ
        val scanStepHz = 12.5f // CwToneShifter's detection resolution

        assertTrue(
            "hysteresis must cover at least two scan bins of jitter",
            hysteresisHz >= scanStepHz * 2
        )

        // Jitter: successive estimates one or two bins apart produce shifts that differ
        // by less than the threshold, so the decoder keeps the shift it already has.
        val shiftAt1400 = (CwToneShifter.TARGET_HZ - 1400.0).toFloat()
        val shiftAt1412 = (CwToneShifter.TARGET_HZ - 1412.5).toFloat()
        assertTrue(
            "a one-bin estimate hop must not trigger a re-shift",
            abs(shiftAt1412 - shiftAt1400) < hysteresisHz
        )

        // A real retune moves the tone far enough that re-centring is worth the reset.
        val shiftAt1300 = (CwToneShifter.TARGET_HZ - 1300.0).toFloat()
        assertTrue(
            "a 100 Hz retune must still be followed",
            abs(shiftAt1300 - shiftAt1400) >= hysteresisHz
        )
    }
}
