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

    /**
     * A shifted tone must not be clipped, because clipping generates odd harmonics and
     * the third one can land inside the window where the band-pass cannot remove it.
     *
     * The Hilbert kernel's L1 gain is 2.51, so an ordinary input drives the mixer well
     * past unity: at amplitude 0.7 the pre-scaling peak is about 1.76.
     */
    @Test
    fun `a shifted tone is scaled to fit rather than clipped`() {
        val audio = FloatArray(3200) { (0.7 * sin(2.0 * PI * 1500.0 * it / 3200.0)).toFloat() }
        val (shifted, _) = CwToneShifter.shiftIfOutsideWindow(audio, sampleRate)

        var atRail = 0
        for (v in shifted) if (abs(v) >= 0.999f) atRail++
        // A handful of samples may sit on the rail through rounding; a clipped waveform
        // parks there for a large share of every cycle.
        assertTrue("$atRail samples clipped, expected almost none", atRail < shifted.size / 100)

        val peak = shifted.maxOf { abs(it) }
        assertTrue("output must stay bounded, peak was $peak", peak <= 1.0f)
        assertTrue("output must not be scaled into silence, peak was $peak", peak > 0.1f)
    }

    /**
     * Energy outside the model's window must be gone after a shift, whatever the mixer
     * left behind - images, harmonics, or the far sideband.
     */
    @Test
    fun `shifting leaves no energy outside the model window`() {
        val audio = FloatArray(3200) { (0.7 * sin(2.0 * PI * 1500.0 * it / 3200.0)).toFloat() }
        val (shifted, _) = CwToneShifter.shiftIfOutsideWindow(audio, sampleRate)

        val binHz = sampleRate.toDouble() / CwDeepSpectrogram.FFT_LENGTH
        var inBand = 0.0
        var outOfBand = 0.0
        for (bin in 0..CwDeepSpectrogram.FFT_LENGTH / 2) {
            val hz = bin * binHz
            val power = magnitudeAt(shifted, hz).let { it * it }
            if (hz >= CwDeepSpectrogram.MIN_FREQ_HZ && hz <= CwDeepSpectrogram.MAX_FREQ_HZ) {
                inBand += power
            } else {
                outOfBand += power
            }
        }
        val leakage = outOfBand / (inBand + outOfBand) * 100.0
        assertTrue("%.2f%% of the energy is outside the window".format(leakage), leakage < 1.0)
    }

    /**
     * The band-pass removes harmonics that fall outside the window, but a third harmonic
     * can fall inside it - at a 700 Hz target it folds to 1100 Hz, squarely in range. So
     * the target must stay where its harmonics are harmless, and this pins that: at a
     * quarter of the sample rate every odd harmonic folds back onto the tone itself.
     */
    @Test
    fun `the shift target keeps clipping harmonics off the band`() {
        assertEquals(
            "TARGET_HZ must stay at a quarter of the sample rate",
            sampleRate / 4.0, CwToneShifter.TARGET_HZ, 0.001
        )
        for (harmonic in listOf(3, 5, 7, 9)) {
            var folded = CwToneShifter.TARGET_HZ * harmonic % sampleRate
            if (folded > sampleRate / 2.0) folded = sampleRate - folded
            assertEquals(
                "harmonic $harmonic folds to $folded Hz, not back onto the tone",
                CwToneShifter.TARGET_HZ, folded, 0.001
            )
        }
    }

    /**
     * The decoder runs this scan even with shifting switched off, purely to tell the
     * operator why nothing is decoding. That only works if the scan reaches past the
     * model's window: the spectrogram's own pitch readout cannot, being confined to the
     * window by construction, and it reports edge leakage as though it were the tone.
     */
    @Test
    fun `the scan reports tones the model window excludes`() {
        for (tone in listOf(120.0, 250.0, 1400.0, 1500.0)) {
            val analysis = CwToneShifter.analyse(cwTone(tone), sampleRate)
            val reported = analysis.toneHz
            assertNotNull("$tone Hz went undetected, so the UI has nothing to report", reported)
            assertEquals("$tone Hz was misreported", tone, reported!!.toDouble(), 30.0)
            assertFalse(
                "$tone Hz must read as outside the window",
                CwToneShifter.isInsideWindow(reported)
            )
        }
    }

    /**
     * The waterfall draws a marker at [CwToneShifter.TARGET_HZ] to show the operator where
     * a shifted tone is being delivered. Moving the target outside the model's window, or
     * moving the window off the target, would leave that marker pointing at a frequency
     * nothing arrives at — and nothing else in the build would object.
     */
    @Test
    fun `the shift target sits inside the model window, clear of its edges`() {
        assertTrue(
            "TARGET_HZ ${CwToneShifter.TARGET_HZ} is outside the model window " +
                "${CwDeepSpectrogram.MIN_FREQ_HZ}-${CwDeepSpectrogram.MAX_FREQ_HZ} Hz",
            CwToneShifter.isInsideWindow(CwToneShifter.TARGET_HZ.toFloat())
        )
        // Clear of the edges by a decent margin, so a tone landing a little off target
        // still lands inside: a target hugging an edge would make the shift pointless.
        val margin = (CwDeepSpectrogram.MAX_FREQ_HZ - CwDeepSpectrogram.MIN_FREQ_HZ) / 4
        assertTrue(
            "TARGET_HZ ${CwToneShifter.TARGET_HZ} is within $margin Hz of a window edge",
            CwToneShifter.TARGET_HZ >= CwDeepSpectrogram.MIN_FREQ_HZ + margin &&
                CwToneShifter.TARGET_HZ <= CwDeepSpectrogram.MAX_FREQ_HZ - margin
        )
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
