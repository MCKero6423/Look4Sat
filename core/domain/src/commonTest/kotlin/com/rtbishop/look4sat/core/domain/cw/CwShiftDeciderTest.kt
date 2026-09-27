package com.rtbishop.look4sat.core.domain.cw

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the real [CwShiftDecider] with the real [CwToneShifter.analyse].
 *
 * This suite exists because an earlier version of the same rule lived inside the decoder,
 * where tests could only restate it. Mutation testing then showed four injected defects -
 * removing the silence guard, comparing shifts instead of tones, never setting the anchor,
 * and inverting the hysteresis comparison - all left the suite green. Every test below
 * targets one of those, so each is now a real tripwire.
 */
class CwShiftDeciderTest {

    private val sampleRate = CwDeepSpectrogram.SAMPLE_RATE
    private val hysteresisHz = CwShiftDecider.DEFAULT_HYSTERESIS_HZ

    private fun steadyTone(hz: Double, samples: Int = 1280): FloatArray =
        FloatArray(samples) { i -> sin(2.0 * PI * hz * i / sampleRate).toFloat() }

    private fun noise(samples: Int = 1280, seed: Int = 1, level: Double = 0.02): FloatArray {
        val random = Random(seed)
        return FloatArray(samples) { ((random.nextDouble() - 0.5) * 2 * level).toFloat() }
    }

    private fun analyse(audio: FloatArray) = CwToneShifter.analyse(audio, sampleRate)

    private fun feed(decider: CwShiftDecider, audio: FloatArray) = decider.accept(analyse(audio))

    // --- Mutant (a): the silence guard ---------------------------------------------

    @Test
    fun `silence retains an established shift`() {
        val decider = CwShiftDecider()
        val established = feed(decider, steadyTone(1400.0))
        assertEquals(CwShiftDecider.Outcome.SHIFTED, established.outcome)
        assertTrue(established.shiftHz != 0f, "a 1400 Hz tone must produce a shift")

        val silent = feed(decider, noise())
        assertEquals(
            CwShiftDecider.Outcome.NO_TONE,
            silent.outcome,
            "silence must be reported as no tone, not as a zero shift"
        )
        assertEquals(
            established.shiftHz,
            silent.shiftHz,
            absoluteTolerance = 0f,
            "silence must not change the shift"
        )
        assertFalse(silent.changed, "a silent window is not a change")
        assertEquals(
            established.shiftHz,
            decider.shiftHz,
            absoluteTolerance = 0f,
            "the decider's state must still hold the shift"
        )
    }

    @Test
    fun `a run of silence does not erode the shift`() {
        val decider = CwShiftDecider()
        val established = feed(decider, steadyTone(1400.0)).shiftHz

        repeat(8) { i ->
            val decision = feed(decider, noise(seed = i + 2))
            assertEquals(
                established,
                decision.shiftHz,
                absoluteTolerance = 0f,
                "silent window $i changed the shift"
            )
        }
        assertEquals(established, decider.shiftHz, absoluteTolerance = 0f)
        assertNotNull(decider.anchorToneHz, "the anchor must survive silence")
    }

    // --- Mutants (b) and (c): hysteresis anchored on the tone ----------------------

    @Test
    fun `an estimate hopping across the window edge does not re-shift`() {
        // 1200.0 Hz is inside the window (shift 0); 1212.5 Hz, one scan bin away, is
        // outside (a large shift). A shift-space comparison lapses here because one side
        // is zero, which is exactly where the jump is largest.
        val decider = CwShiftDecider()
        val first = feed(decider, steadyTone(1212.5))
        assertEquals(CwShiftDecider.Outcome.SHIFTED, first.outcome)

        val hop = feed(decider, steadyTone(1200.0))
        assertEquals(
            CwShiftDecider.Outcome.WITHIN_HYSTERESIS,
            hop.outcome,
            "a one-bin hop back across the edge must be absorbed"
        )
        assertEquals(first.shiftHz, hop.shiftHz, absoluteTolerance = 0f, "the shift must not move")
        assertFalse(hop.changed)
    }

    @Test
    fun `the anchor is set from the tone that produced the shift`() {
        val decider = CwShiftDecider()
        assertNull(decider.anchorToneHz, "no anchor before the first detection")

        feed(decider, steadyTone(1400.0))
        assertEquals(
            1400.0,
            decider.anchorToneHz!!.toDouble(),
            absoluteTolerance = 25.0,
            "the anchor must be the detected tone"
        )

        // An in-window tone must anchor too, otherwise a tone drifting from inside the
        // window to outside would be measured against a stale reference.
        feed(decider, steadyTone(700.0))
        assertEquals(
            700.0,
            decider.anchorToneHz!!.toDouble(),
            absoluteTolerance = 25.0,
            "an in-window tone must also become the anchor"
        )
        assertEquals(0f, decider.shiftHz, absoluteTolerance = 0f, "an in-window tone needs no shift")
    }

    @Test
    fun `hysteresis is measured against the anchor, not the previous estimate`() {
        // Walk in 25 Hz steps: each step is under the 40 Hz margin, so a comparison
        // against the previous estimate would never fire. Anchored, the shift updates
        // once the accumulated move clears the margin.
        val decider = CwShiftDecider()
        feed(decider, steadyTone(1300.0))
        val anchorAtStart = decider.anchorToneHz!!

        var tone = 1325.0
        var updates = 0
        while (tone <= 1450.0) {
            if (feed(decider, steadyTone(tone)).changed) updates++
            tone += 25.0
        }
        assertTrue(
            updates >= 1,
            "accumulated drift must eventually re-shift; anchor started at $anchorAtStart " +
                "and the shift updated $updates times"
        )
    }

    // --- Mutant (d): the comparison direction --------------------------------------

    @Test
    fun `a large retune is followed while small moves are absorbed`() {
        val decider = CwShiftDecider()
        val before = feed(decider, steadyTone(1400.0)).shiftHz

        // Well inside the margin: must be absorbed.
        val small = feed(decider, steadyTone(1412.5))
        assertEquals(CwShiftDecider.Outcome.WITHIN_HYSTERESIS, small.outcome)
        assertEquals(before, small.shiftHz, absoluteTolerance = 0f)

        // Well beyond it: must be followed. An inverted comparison would absorb this and
        // react to the small move instead.
        val large = feed(decider, steadyTone(1000.0))
        assertTrue(
            large.changed,
            "a 400 Hz retune must change the shift (was $before, now ${large.shiftHz})"
        )
        assertEquals(
            CwShiftDecider.Outcome.NO_SHIFT_NEEDED,
            large.outcome,
            "a 1000 Hz tone is inside the window, so no shift is needed"
        )
        assertEquals(0f, large.shiftHz, absoluteTolerance = 0f)
    }

    @Test
    fun `an edge tone settles instead of thrashing`() {
        val decider = CwShiftDecider()
        var changes = 0
        // Estimates hopping around the 1200 Hz edge, the worst case for a shift-space rule.
        val hops = listOf(1200.0, 1212.5, 1200.0, 1187.5, 1212.5, 1200.0, 1225.0, 1200.0)
        repeat(4) {
            for (hz in hops) {
                if (feed(decider, steadyTone(hz)).changed) changes++
            }
        }
        assertTrue(
            changes <= 3,
            "an edge tone must settle; the shift changed $changes times in ${hops.size * 4} detections"
        )
    }

    // --- Drift and state consistency ----------------------------------------------

    @Test
    fun `slow drift keeps the shifted tone inside the model window`() {
        val decider = CwShiftDecider()
        var tone = 1300.0
        var worstOffset = 0.0
        while (tone <= 1550.0) {
            val decision = feed(decider, steadyTone(tone))
            val landed = tone + decision.shiftHz
            worstOffset = maxOf(worstOffset, abs(landed - CwToneShifter.TARGET_HZ))
            assertTrue(
                CwToneShifter.isInsideWindow(landed.toFloat()),
                "a ${tone}Hz tone landed at ${landed}Hz, outside the model window"
            )
            tone += 12.5
        }
        assertTrue(
            worstOffset <= hysteresisHz + 12.5,
            "staleness must stay near the margin, worst offset was $worstOffset Hz"
        )
    }

    @Test
    fun `reset clears both the shift and the anchor together`() {
        val decider = CwShiftDecider()
        feed(decider, steadyTone(1400.0))
        assertTrue(decider.shiftHz != 0f)
        assertNotNull(decider.anchorToneHz)

        decider.reset()
        assertEquals(0f, decider.shiftHz, absoluteTolerance = 0f, "reset must clear the shift")
        assertNull(decider.anchorToneHz, "reset must clear the anchor")

        // After a reset the next tone must be acted on rather than absorbed.
        val decision = feed(decider, steadyTone(1400.0))
        assertEquals(CwShiftDecider.Outcome.SHIFTED, decision.outcome)
        assertTrue(decision.changed)
    }

    @Test
    fun `a non-zero shift always has an anchor`() {
        // An inconsistent pair would make hysteresis behave differently depending on how
        // the state was reached, so pin the invariant across a mixed sequence.
        val decider = CwShiftDecider()
        val sequence = listOf(
            steadyTone(1400.0), noise(), steadyTone(1412.5), steadyTone(300.0),
            noise(seed = 5), steadyTone(700.0), steadyTone(1500.0), noise(seed = 9)
        )
        for ((index, audio) in sequence.withIndex()) {
            feed(decider, audio)
            if (decider.shiftHz != 0f) {
                assertNotNull(
                    decider.anchorToneHz,
                    "step $index left a shift of ${decider.shiftHz}Hz with no anchor"
                )
            }
        }
    }

    @Test
    fun `shift always lands the tone on the target`() {
        for (hz in listOf(150.0, 250.0, 300.0, 1250.0, 1400.0, 1500.0)) {
            val decider = CwShiftDecider()
            val decision = feed(decider, steadyTone(hz))
            assertEquals(
                CwToneShifter.TARGET_HZ,
                hz + decision.shiftHz,
                absoluteTolerance = 30.0,
                "a ${hz}Hz tone must be shifted to the window centre"
            )
        }
    }

    @Test
    fun `in-window tones are never shifted`() {
        for (hz in listOf(400.0, 500.0, 800.0, 1100.0, 1200.0)) {
            val decider = CwShiftDecider()
            val decision = feed(decider, steadyTone(hz))
            assertEquals(
                CwShiftDecider.Outcome.NO_SHIFT_NEEDED,
                decision.outcome,
                "a ${hz}Hz tone is inside the window and must not be shifted"
            )
            assertEquals(0f, decision.shiftHz, absoluteTolerance = 0f)
        }
    }
}
