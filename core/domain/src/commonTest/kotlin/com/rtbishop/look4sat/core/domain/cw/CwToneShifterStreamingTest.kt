package com.rtbishop.look4sat.core.domain.cw

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [CwToneShifter.Streaming] exists because the decoder shifts one ~320-sample chunk at
 * a time. Shifting each chunk in isolation makes the Hilbert FIR convolve against zeros
 * at both edges, which distorted 62 of every 320 samples and inflated envelope ripple
 * to 8.7x the whole-buffer baseline. These tests fail if that state handling regresses.
 */
class CwToneShifterStreamingTest {

    private val sampleRate = CwDeepSpectrogram.SAMPLE_RATE

    /** ~100 ms of audio once resampled to 3200 Hz, matching what the decoder receives. */
    private val chunkSize = 320

    private fun continuousTone(hz: Double, samples: Int): FloatArray =
        FloatArray(samples) { i -> sin(2.0 * PI * hz * i / sampleRate).toFloat() }

    /** RMS envelope; a steady tone must produce a flat one. */
    private fun envelope(audio: FloatArray, window: Int = 48): List<Double> {
        val out = mutableListOf<Double>()
        var i = 0
        while (i + window <= audio.size) {
            var sum = 0.0
            for (j in i until i + window) sum += audio[j].toDouble() * audio[j]
            out += sqrt(sum / window)
            i += window / 2
        }
        return out
    }

    /** Coefficient of variation of the envelope, as a percentage. */
    private fun ripple(audio: FloatArray, skip: Int = 0): Double {
        val env = envelope(audio.copyOfRange(skip, audio.size))
        val mean = env.average()
        if (mean == 0.0) return 0.0
        val variance = env.sumOf { (it - mean) * (it - mean) } / env.size
        return sqrt(variance) / mean * 100.0
    }

    private fun processInChunks(audio: FloatArray, shiftHz: Float): FloatArray {
        val shifter = CwToneShifter.Streaming()
        val out = FloatArray(audio.size)
        var offset = 0
        while (offset < audio.size) {
            val end = minOf(offset + chunkSize, audio.size)
            val chunk = audio.copyOfRange(offset, end)
            shifter.process(chunk, shiftHz, sampleRate).copyInto(out, offset)
            offset = end
        }
        return out
    }

    @Test
    fun `chunked streaming keeps a steady tone flat`() {
        val audio = continuousTone(1500.0, chunkSize * 20)
        val shiftHz = (CwToneShifter.TARGET_HZ - 1500.0).toFloat()

        val streamed = processInChunks(audio, shiftHz)

        // Skip the filter's start-up transient: with no history the first taps are cold.
        val skip = 128
        val streamedRipple = ripple(streamed, skip)

        // Absolute, not relative to the whole-buffer figure: clamping pins a full-scale
        // tone at exactly 1.0, so the whole-buffer ripple collapses to ~0.001% and any
        // ratio against it explodes. What matters is the absolute number - a 20 WPM dot
        // spans 192 samples, so sub-2% envelope ripple cannot move a keying decision.
        // Measured 0.79% with state carried across chunks; dropping the filter history
        // takes it to several percent, and dropping the phase far higher.
        assertTrue(
            streamedRipple < 2.0,
            "streaming envelope ripple ${streamedRipple}% is too high; chunk-edge " +
                "filter state or mixer phase is not being carried"
        )
    }

    /**
     * Streaming must match whole-buffer shifting everywhere except the last
     * [lookahead] samples of each chunk.
     *
     * That exception is causal, not a defect: producing output sample `i` needs input
     * up to `i + HILBERT_DELAY`, which for the tail of a chunk has not been captured
     * yet. A whole-buffer call sees those samples; a live stream cannot. Measured, the
     * divergence is confined to the final 3 samples of each 320-sample chunk (under 1%
     * of the audio) and vanishes immediately after the boundary, which is why the
     * decoder accepts it rather than delaying output by 10 ms.
     */
    @Test
    fun `chunked output matches whole-buffer output except the causal tail`() {
        val audio = continuousTone(1500.0, chunkSize * 12)
        val shiftHz = (CwToneShifter.TARGET_HZ - 1500.0).toFloat()

        val whole = CwToneShifter.shift(audio, shiftHz, sampleRate)
        val streamed = processInChunks(audio, shiftHz)

        val lookahead = 32 // HILBERT_TAPS / 2, rounded up
        val skip = 128 // filter start-up transient
        var worstInterior = 0.0
        var worstTail = 0.0
        for (i in skip until audio.size) {
            val distanceToBoundary = chunkSize - (i % chunkSize)
            val delta = abs(whole[i] - streamed[i]).toDouble()
            if (distanceToBoundary <= lookahead) {
                worstTail = maxOf(worstTail, delta)
            } else {
                worstInterior = maxOf(worstInterior, delta)
            }
        }

        assertTrue(
            worstInterior < 0.01,
            "away from chunk tails the two must agree; worst divergence was " +
                "$worstInterior, so filter history or mixer phase is not being carried"
        )
        // The tail is allowed to differ, but not wildly: a broken implementation would
        // diverge by the full signal amplitude rather than a fraction of it.
        assertTrue(
            worstTail < 0.5,
            "chunk-tail divergence $worstTail exceeds the causal lookahead budget"
        )
    }

    @Test
    fun `shifted chunks land on the target frequency`() {
        val audio = continuousTone(1500.0, chunkSize * 16)
        val shiftHz = (CwToneShifter.TARGET_HZ - 1500.0).toFloat()
        val streamed = processInChunks(audio, shiftHz)

        val detected = CwToneShifter.detectToneHz(streamed, sampleRate)
        assertEquals(
            CwToneShifter.TARGET_HZ,
            detected!!.toDouble(),
            absoluteTolerance = 30.0,
            "streamed audio must end up at the target pitch"
        )
    }

    @Test
    fun `zero shift passes chunks through untouched`() {
        val shifter = CwToneShifter.Streaming()
        val chunk = continuousTone(800.0, chunkSize)
        assertSame(
            chunk,
            shifter.process(chunk, 0f, sampleRate),
            "a zero shift must not copy or alter the chunk"
        )
    }

    @Test
    fun `history survives a run of zero-shift chunks`() {
        // Feeding audio while disabled must still fill the history, so that enabling
        // the shift mid-stream does not convolve against leftover silence.
        val shifter = CwToneShifter.Streaming()
        val audio = continuousTone(1500.0, chunkSize * 6)
        val shiftHz = (CwToneShifter.TARGET_HZ - 1500.0).toFloat()

        // First three chunks with no shift, then start shifting.
        var offset = 0
        repeat(3) {
            shifter.process(audio.copyOfRange(offset, offset + chunkSize), 0f, sampleRate)
            offset += chunkSize
        }
        val firstShifted = shifter.process(
            audio.copyOfRange(offset, offset + chunkSize), shiftHz, sampleRate
        )

        // With history primed the very first shifted chunk should already be clean;
        // a cold filter would show a large amplitude dip at its start.
        val head = envelope(firstShifted.copyOfRange(0, 96)).average()
        val tail = envelope(firstShifted.copyOfRange(firstShifted.size - 96, firstShifted.size)).average()
        assertTrue(
            head > tail * 0.7,
            "first shifted chunk starts at $head but settles at $tail; history was not kept"
        )
    }

    @Test
    fun `reset clears state so the next chunk starts cold`() {
        val shifter = CwToneShifter.Streaming()
        val audio = continuousTone(1500.0, chunkSize * 4)
        val shiftHz = (CwToneShifter.TARGET_HZ - 1500.0).toFloat()

        var offset = 0
        repeat(3) {
            shifter.process(audio.copyOfRange(offset, offset + chunkSize), shiftHz, sampleRate)
            offset += chunkSize
        }
        shifter.reset()

        val afterReset = shifter.process(
            audio.copyOfRange(offset, offset + chunkSize), shiftHz, sampleRate
        )
        // Cold filter: the leading samples are attenuated relative to the settled tail.
        val head = envelope(afterReset.copyOfRange(0, 64)).average()
        val tail = envelope(afterReset.copyOfRange(afterReset.size - 64, afterReset.size)).average()
        assertTrue(
            head < tail,
            "reset must clear history, so the head ($head) should be quieter than " +
                "the settled tail ($tail)"
        )
    }

    @Test
    fun `handles chunks larger than the history window`() {
        val shifter = CwToneShifter.Streaming()
        val big = continuousTone(1500.0, 5000)
        val shiftHz = (CwToneShifter.TARGET_HZ - 1500.0).toFloat()
        val out = shifter.process(big, shiftHz, sampleRate)
        assertEquals(big.size, out.size)
        assertTrue(out.all { it.isFinite() }, "output must be finite")
    }

    @Test
    fun `handles chunks smaller than the history window`() {
        val shifter = CwToneShifter.Streaming()
        val shiftHz = (CwToneShifter.TARGET_HZ - 1500.0).toFloat()
        // 16-sample chunks are far below the 62-sample history; the ring must still work.
        val audio = continuousTone(1500.0, 16 * 40)
        var offset = 0
        val collected = FloatArray(audio.size)
        while (offset < audio.size) {
            val chunk = audio.copyOfRange(offset, offset + 16)
            shifter.process(chunk, shiftHz, sampleRate).copyInto(collected, offset)
            offset += 16
        }
        assertTrue(collected.all { it.isFinite() }, "output must be finite")
        val detected = CwToneShifter.detectToneHz(collected, sampleRate)
        assertEquals(
            CwToneShifter.TARGET_HZ,
            detected!!.toDouble(),
            absoluteTolerance = 40.0,
            "even tiny chunks must end up at the target pitch"
        )
    }
}
