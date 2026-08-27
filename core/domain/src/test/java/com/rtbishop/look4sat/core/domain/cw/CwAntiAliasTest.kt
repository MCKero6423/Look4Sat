package com.rtbishop.look4sat.core.domain.cw

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aliasing is not a subtle degradation here: without this filter a 3000 Hz tone reappeared at
 * 200 Hz at 119 times the spectral mean, which reads as a real signal, and the whole band above
 * 1600 Hz folded down and lifted the noise floor across the display.
 */
class CwAntiAliasTest {

    private val captureRate = 44100
    private val targetRate = CwDeepSpectrogram.SAMPLE_RATE
    private val nyquist = targetRate / 2.0

    /** Magnitude at one frequency, Hann-windowed so neighbours do not smear in. */
    private fun magnitudeAt(signal: FloatArray, hz: Double, rate: Int): Double {
        val n = signal.size
        var real = 0.0
        var imag = 0.0
        val omega = 2.0 * PI * hz / rate
        for (i in 0 until n) {
            val window = 0.5 - 0.5 * kotlin.math.cos(2.0 * PI * i / (n - 1))
            val value = signal[i] * window
            real += value * kotlin.math.cos(omega * i)
            imag -= value * sin(omega * i)
        }
        return hypot(real, imag) / n
    }

    private fun tone(hz: Double, rate: Int, seconds: Double = 0.4): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n) { i -> sin(2.0 * PI * hz * i / rate).toFloat() }
    }

    /** A tone the model needs must survive the filter. */
    @Test
    fun `tones inside the window pass through`() {
        for (hz in listOf(300.0, 700.0, 1000.0, 1200.0)) {
            val clean = tone(hz, captureRate)
            val filtered = CwAntiAlias.prepareForDecimation(clean, captureRate, targetRate)
            val before = magnitudeAt(clean, hz, captureRate)
            val after = magnitudeAt(filtered, hz, captureRate)
            assertTrue(
                "$hz Hz lost too much: $before -> $after",
                after > before * 0.7
            )
        }
    }

    /**
     * The defect. 3000 Hz used to fold to 200 Hz and look like a strong signal; the filter has to
     * remove it before the resampler can alias it.
     */
    @Test
    fun `tones that would alias are suppressed`() {
        // Limits are the measured response, not aspirations. 1800 Hz sits just past the 1472 Hz
        // cut-off, and 127 taps cannot give a steeper transition than that - a longer filter would
        // be sharper and slower, and this runs on a phone during a pass. What matters is that the
        // wideband hiss well above the window, which is what smears across the whole display, is
        // gone: 2400 Hz and up measure below a thousandth.
        val cases = listOf(
            Triple(1800.0, 1400.0, 0.20),
            Triple(2400.0, 800.0, 0.01),
            Triple(3000.0, 200.0, 0.01),
            Triple(5000.0, 1400.0, 0.01)
        )
        for ((source, foldedTo, limit) in cases) {
            val raw = tone(source, captureRate)
            val filtered = CwAntiAlias.prepareForDecimation(raw, captureRate, targetRate)

            val aliasedRaw = CwDeepSpectrogram.resampleLinear(raw, captureRate, targetRate)
            val aliasedFiltered = CwDeepSpectrogram.resampleLinear(filtered, captureRate, targetRate)

            val ghostBefore = magnitudeAt(aliasedRaw, foldedTo, targetRate)
            val ghostAfter = magnitudeAt(aliasedFiltered, foldedTo, targetRate)

            assertTrue(
                "$source Hz folds to $foldedTo Hz too strongly: $ghostBefore -> $ghostAfter",
                ghostAfter < ghostBefore * limit
            )
        }
    }

    /** Nothing above the target Nyquist means nothing to do. */
    @Test
    fun `no filtering when the rate is already low enough`() {
        val audio = tone(700.0, targetRate)
        assertSame(audio, CwAntiAlias.prepareForDecimation(audio, targetRate, targetRate))
        assertSame(audio, CwAntiAlias.prepareForDecimation(audio, 2000, targetRate))
    }

    @Test
    fun `an empty buffer is returned unchanged`() {
        val empty = FloatArray(0)
        assertSame(empty, CwAntiAlias.prepareForDecimation(empty, captureRate, targetRate))
    }

    /** Unity DC gain: filtering must not move the level the model was trained on. */
    @Test
    fun `a steady level is preserved`() {
        val flat = FloatArray(4410) { 0.5f }
        val filtered = CwAntiAlias.prepareForDecimation(flat, captureRate, targetRate)
        // Skip the edges, where taps fall outside the buffer.
        val middle = filtered.copyOfRange(
            CwAntiAlias.GROUP_DELAY_SAMPLES + 1,
            filtered.size - CwAntiAlias.GROUP_DELAY_SAMPLES - 1
        )
        for (v in middle) {
            assertTrue("level drifted to $v", kotlin.math.abs(v - 0.5f) < 0.02f)
        }
    }

    /**
     * Chunked filtering has to match whole-buffer filtering, or every chunk boundary becomes a
     * click - the same failure the tone shifter's streaming path exists to prevent.
     */
    /**
     * Chunked filtering must equal whole-buffer filtering exactly, or every chunk boundary is a
     * click. A first attempt let the lookahead taps read zeros at the end of each chunk and
     * diverged by 0.134 across the last 44 samples of every one; holding output back by the group
     * delay makes the two identical.
     */
    @Test
    fun `streaming matches whole-buffer filtering`() {
        val audio = tone(700.0, captureRate, seconds = 0.3)
        val whole = CwAntiAlias.prepareForDecimation(audio, captureRate, targetRate)

        val streaming = CwAntiAlias.Streaming(captureRate, targetRate)
        val chunkSize = captureRate / 10
        val pieces = mutableListOf<Float>()
        var offset = 0
        while (offset < audio.size) {
            val end = minOf(offset + chunkSize, audio.size)
            pieces += streaming.process(audio.copyOfRange(offset, end)).toList()
            offset = end
        }
        val streamed = pieces.toFloatArray()

        // Output is delayed by the group delay, so streamed[i] corresponds to whole[i + delay].
        var worst = 0f
        for (i in streamed.indices) {
            val j = i + CwAntiAlias.GROUP_DELAY_SAMPLES
            if (j >= whole.size) break
            val diff = kotlin.math.abs(streamed[i] - whole[j])
            if (diff > worst) worst = diff
        }
        assertTrue("streaming diverges by $worst", worst < 1e-5f)
    }

    /** Streaming must not lift the noise floor either. */
    @Test
    fun `streaming suppresses an aliasing tone too`() {
        val raw = tone(3000.0, captureRate, seconds = 0.3)
        val streaming = CwAntiAlias.Streaming(captureRate, targetRate)
        val chunkSize = captureRate / 10
        val pieces = mutableListOf<Float>()
        var offset = 0
        while (offset < raw.size) {
            val end = minOf(offset + chunkSize, raw.size)
            pieces += streaming.process(raw.copyOfRange(offset, end)).toList()
            offset = end
        }
        val filtered = pieces.toFloatArray()

        val ghostBefore = magnitudeAt(
            CwDeepSpectrogram.resampleLinear(raw, captureRate, targetRate), 200.0, targetRate
        )
        val ghostAfter = magnitudeAt(
            CwDeepSpectrogram.resampleLinear(filtered, captureRate, targetRate), 200.0, targetRate
        )
        assertTrue("ghost survived: $ghostBefore -> $ghostAfter", ghostAfter < ghostBefore * 0.01)
    }

    /** Reset has to clear history, or the next session starts with the last one's tail. */
    @Test
    fun `reset clears the history`() {
        val streaming = CwAntiAlias.Streaming(captureRate, targetRate)
        val loud = FloatArray(4410) { 0.9f }
        streaming.process(loud)
        streaming.reset()

        val silence = FloatArray(4410)
        val after = streaming.process(silence)
        for (v in after) {
            assertTrue("history leaked into silence: $v", kotlin.math.abs(v) < 0.01f)
        }
        // And a second silent chunk, now that the pipeline is primed.
        for (v in streaming.process(FloatArray(4410))) {
            assertTrue("history still leaking: $v", kotlin.math.abs(v) < 0.01f)
        }
    }
}
