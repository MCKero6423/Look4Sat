package com.rtbishop.look4sat.core.domain.cw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * The shifter exists so pitches outside the model's 400-1200 Hz window can still be
 * decoded. These tests pin the two properties that make it safe to enable:
 * in-window audio is returned untouched, and shifted audio contains one clean tone.
 */
class CwToneShifterTest {

    private val sampleRate = CwDeepSpectrogram.SAMPLE_RATE

    /** Keyed CW-like tone: a gated sine with smooth edges, plus noise. */
    private fun cwTone(hz: Double, samples: Int = 1600, noise: Double = 0.02): FloatArray {
        val random = Random(42)
        return FloatArray(samples) { i ->
            // Gate on for 60 ms, off for 30 ms, repeating - roughly 20 WPM keying.
            val cyclePos = (i % (sampleRate * 90 / 1000))
            val gate = if (cyclePos < sampleRate * 60 / 1000) 1.0 else 0.0
            val value = gate * sin(2.0 * PI * hz * i / sampleRate)
            (value + (random.nextDouble() - 0.5) * 2 * noise).toFloat()
        }
    }

    /** Relative magnitude at [hz] using a single-bin DFT with a Hann window. */
    private fun magnitudeAt(audio: FloatArray, hz: Double): Double {
        var real = 0.0
        var imag = 0.0
        val omega = 2.0 * PI * hz / sampleRate
        for (i in audio.indices) {
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / (audio.size - 1))
            val value = audio[i] * window
            real += value * cos(omega * i)
            imag -= value * sin(omega * i)
        }
        return hypot(real, imag) / audio.size
    }

    /** Scan 100 Hz..Nyquist and return the strongest bin plus everything above a ratio. */
    private fun peaks(audio: FloatArray, minRatio: Double = 0.3): Pair<Double, List<Double>> {
        val magnitudes = mutableListOf<Pair<Double, Double>>()
        var hz = 100.0
        while (hz <= sampleRate / 2.0) {
            magnitudes += hz to magnitudeAt(audio, hz)
            hz += 12.5
        }
        val strongest = magnitudes.maxByOrNull { it.second }!!
        val others = magnitudes
            .filter { it.first != strongest.first && it.second >= strongest.second * minRatio }
            // Collapse adjacent bins of the same lobe; only distinct tones matter.
            .filter { abs(it.first - strongest.first) > 50.0 }
            .map { it.first }
        return strongest.first to others
    }

    @Test
    fun `detects tones across the audible range`() {
        for (tone in listOf(150.0, 300.0, 500.0, 700.0, 800.0, 1100.0, 1300.0, 1500.0)) {
            val detected = CwToneShifter.detectToneHz(cwTone(tone), sampleRate)
            assertNotNull("no tone detected at $tone Hz", detected)
            assertEquals("detected pitch off at $tone Hz", tone, detected!!.toDouble(), 25.0)
        }
    }

    @Test
    fun `reports no tone for noise`() {
        val random = Random(7)
        val noise = FloatArray(1600) { ((random.nextDouble() - 0.5) * 2).toFloat() }
        assertNull("noise must not be mistaken for a tone", CwToneShifter.detectToneHz(noise, sampleRate))
    }

    @Test
    fun `in-window tones are returned untouched`() {
        for (tone in listOf(400.0, 500.0, 700.0, 800.0, 1100.0, 1200.0)) {
            val audio = cwTone(tone)
            val (result, analysis) = CwToneShifter.shiftIfOutsideWindow(audio, sampleRate)
            assertFalse("$tone Hz is inside the window, must not shift", analysis.needsShift)
            assertEquals("no shift expected at $tone Hz", 0f, analysis.shiftHz, 0f)
            // Same instance: the caller's array must not even be copied.
            assertSame("in-window audio must be passed through", audio, result)
        }
    }

    @Test
    fun `out-of-window tones move to the target with no competing tone`() {
        for (tone in listOf(150.0, 200.0, 250.0, 300.0, 350.0, 1300.0, 1400.0, 1500.0)) {
            val audio = cwTone(tone)
            val (result, analysis) = CwToneShifter.shiftIfOutsideWindow(audio, sampleRate)
            assertTrue("$tone Hz is outside the window, must shift", analysis.needsShift)

            val (strongest, competing) = peaks(result)
            assertEquals(
                "$tone Hz did not land on the target",
                CwToneShifter.TARGET_HZ, strongest, 30.0
            )
            assertTrue(
                "$tone Hz left a competing tone at $competing (single-sideband mixing failed)",
                competing.isEmpty()
            )
            assertTrue(
                "shifted tone must land inside the model window",
                CwToneShifter.isInsideWindow(strongest.toFloat())
            )
        }
    }

    @Test
    fun `shift with zero offset returns the same array`() {
        val audio = cwTone(800.0)
        assertSame(audio, CwToneShifter.shift(audio, 0f, sampleRate))
    }

    @Test
    fun `shift preserves length and stays finite`() {
        val audio = cwTone(1500.0)
        val shifted = CwToneShifter.shift(audio, -700f, sampleRate)
        assertEquals("length must be preserved", audio.size, shifted.size)
        assertTrue("output must be finite", shifted.all { it.isFinite() })
    }

    @Test
    fun `empty input is handled`() {
        val empty = FloatArray(0)
        assertSame(empty, CwToneShifter.shift(empty, -700f, sampleRate))
        assertNull(CwToneShifter.detectToneHz(empty, sampleRate))
        val (result, analysis) = CwToneShifter.shiftIfOutsideWindow(empty, sampleRate)
        assertSame(empty, result)
        assertFalse(analysis.needsShift)
    }

    @Test
    fun `shifted audio survives the spectrogram with energy inside the window`() {
        // End-to-end: a 1500 Hz tone is invisible to the model, the shifted one is not.
        val audio = cwTone(1500.0, samples = 3200)

        val rawSpectrogram = CwDeepSpectrogram.compute(audio)
        val rawEnergy = rawSpectrogram.sumOf { frame -> frame.sumOf { it.toDouble() } }

        val (shifted, analysis) = CwToneShifter.shiftIfOutsideWindow(audio, sampleRate)
        assertTrue(analysis.needsShift)
        val shiftedSpectrogram = CwDeepSpectrogram.compute(shifted)
        val shiftedEnergy = shiftedSpectrogram.sumOf { frame -> frame.sumOf { it.toDouble() } }

        assertTrue(
            "shifting must put more energy in the model window (raw=$rawEnergy shifted=$shiftedEnergy)",
            shiftedEnergy > rawEnergy * 1.5
        )
        assertEquals(
            "bin count must stay compatible with the model",
            CwDeepSpectrogram.FREQUENCY_BINS, shiftedSpectrogram[0].size
        )
    }
}
