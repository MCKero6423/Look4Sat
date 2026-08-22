package com.rtbishop.look4sat.core.domain.cw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pool feeds [CwToneShifter.detectToneHz], which measures a waveform, so the
 * samples it hands over must be the most recent audio in chronological order. Getting
 * the ring wrap wrong would splice the waveform and corrupt every pitch estimate
 * silently - no downstream assertion would notice, which is why these tests drive the
 * real class rather than restating its logic.
 */
class CwDetectionPoolTest {

    private val capacity = 1280

    /** Chunk of a monotonic ramp, so any reordering is visible. */
    private fun ramp(from: Int, count: Int) = FloatArray(count) { (from + it).toFloat() }

    private fun assertAscending(values: FloatArray) {
        for (i in 1 until values.size) {
            assertEquals(
                "sample $i breaks the ramp, so the ring wrap is wrong",
                values[i - 1] + 1f, values[i], 0f
            )
        }
    }

    @Test
    fun `reports readiness only once capacity is reached`() {
        val pool = CwDetectionPool(capacity)
        assertFalse("an empty pool is not ready", pool.isReady)
        assertEquals(0, pool.size)

        // Three 320-sample chunks are 960 samples: still short.
        repeat(3) { pool.add(ramp(it * 320, 320)) }
        assertEquals(960, pool.size)
        assertFalse("960 of $capacity samples is not ready", pool.isReady)

        pool.add(ramp(960, 320))
        assertEquals(capacity, pool.size)
        assertTrue("a full pool must report ready", pool.isReady)
    }

    @Test
    fun `drains a partial fill without stale slots`() {
        val pool = CwDetectionPool(capacity)
        pool.add(ramp(500, 320))

        val drained = pool.drain()
        assertEquals("only what was added may come back", 320, drained.size)
        assertEquals(500f, drained.first(), 0f)
        assertEquals(819f, drained.last(), 0f)
        assertAscending(drained)
        assertEquals("draining empties the pool", 0, pool.size)
    }

    @Test
    fun `drains exactly the most recent samples once wrapped`() {
        val pool = CwDetectionPool(capacity)
        // 10 chunks of 320 = 3200 samples through a 1280-sample pool.
        repeat(10) { pool.add(ramp(it * 320, 320)) }

        val drained = pool.drain()
        assertEquals(capacity, drained.size)
        assertEquals("the newest sample fed must be last", 3199f, drained.last(), 0f)
        assertEquals("the oldest retained sample must be first", (3200 - capacity).toFloat(), drained.first(), 0f)
        assertAscending(drained)
    }

    @Test
    fun `keeps only the tail of an oversized chunk`() {
        val pool = CwDetectionPool(capacity)
        pool.add(ramp(0, 5000))

        val drained = pool.drain()
        assertEquals(capacity, drained.size)
        assertEquals(4999f, drained.last(), 0f)
        assertEquals((5000 - capacity).toFloat(), drained.first(), 0f)
        assertAscending(drained)
    }

    @Test
    fun `handles single-sample chunks`() {
        val pool = CwDetectionPool(capacity)
        // Far more single-sample adds than the capacity, exercising every wrap position.
        repeat(2000) { pool.add(floatArrayOf(it.toFloat())) }

        val drained = pool.drain()
        assertEquals(capacity, drained.size)
        assertEquals(1999f, drained.last(), 0f)
        assertEquals((2000 - capacity).toFloat(), drained.first(), 0f)
        assertAscending(drained)
    }

    @Test
    fun `is reusable after draining`() {
        val pool = CwDetectionPool(capacity)
        repeat(5) { pool.add(ramp(it * 320, 320)) }
        pool.drain()

        // A second pass must not inherit anything from the first.
        pool.add(ramp(9000, 320))
        val drained = pool.drain()
        assertEquals(320, drained.size)
        assertEquals(9000f, drained.first(), 0f)
        assertEquals(9319f, drained.last(), 0f)
        assertAscending(drained)
    }

    @Test
    fun `clear discards pooled audio`() {
        val pool = CwDetectionPool(capacity)
        pool.add(ramp(0, 640))
        pool.clear()

        assertEquals(0, pool.size)
        assertFalse(pool.isReady)
        pool.add(ramp(7000, 320))
        val drained = pool.drain()
        assertEquals("cleared samples must not reappear", 320, drained.size)
        assertEquals(7000f, drained.first(), 0f)
    }

    @Test
    fun `empty chunks are ignored`() {
        val pool = CwDetectionPool(capacity)
        pool.add(ramp(0, 320))
        pool.add(FloatArray(0))
        assertEquals("an empty chunk must not change the pool", 320, pool.size)
        assertAscending(pool.drain())
    }

    @Test
    fun `chunk exactly the size of the pool is kept whole`() {
        val pool = CwDetectionPool(capacity)
        pool.add(ramp(100, capacity))

        val drained = pool.drain()
        assertEquals(capacity, drained.size)
        assertEquals(100f, drained.first(), 0f)
        assertEquals((100 + capacity - 1).toFloat(), drained.last(), 0f)
        assertAscending(drained)
    }

    @Test
    fun `pooled audio is long enough for the detector to resolve a pitch`() {
        // The pool exists to make detection possible at all; prove the pooled length
        // actually works rather than only that the plumbing moves samples around.
        val sampleRate = CwDeepSpectrogram.SAMPLE_RATE
        val pool = CwDetectionPool(capacity)
        var phase = 0
        repeat(4) {
            pool.add(FloatArray(320) { i ->
                kotlin.math.sin(2.0 * Math.PI * 1500.0 * (phase + i) / sampleRate).toFloat()
            })
            phase += 320
        }
        assertTrue(pool.isReady)

        val detected = CwToneShifter.detectToneHz(pool.drain(), sampleRate)
        assertEquals(
            "four pooled capture chunks must be enough to detect a 1500 Hz tone",
            1500.0, detected!!.toDouble(), 25.0
        )
    }

    @Test
    fun `rejects a non-positive capacity`() {
        for (bad in listOf(0, -1, -1280)) {
            try {
                CwDetectionPool(bad)
                throw AssertionError("capacity $bad should have been rejected")
            } catch (expected: IllegalArgumentException) {
                // The decoder derives capacity from a constant; a zero would otherwise
                // fail later as a division by zero in the ring arithmetic.
            }
        }
    }
}
