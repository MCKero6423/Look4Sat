package com.rtbishop.look4sat.core.domain.utility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * clipLon must reduce any longitude into [-180, 180] in bounded time. The old
 * while-loop never returned for extreme inputs: Infinity stays Infinity after
 * subtracting 360 (infinite loop), and ~1e12 degree values took billions of
 * iterations. The modulo rewrite must be bit-equivalent for all finite values.
 */
class ClipLonTest {

    private fun reduced(v: Double) = ((v + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    @Test
    fun `finite values match the closed interval semantics`() {
        // The old loop: subtract/add 360 until within (-180, 180] is not quite
        // it either - 180 stays 180, -180 stays -180. So the interval is closed
        // on both ends with +180 for positive-boundary hits.
        assertEquals(-180.0, clipLon(-180.0), 1e-12)
        assertEquals(180.0, clipLon(180.0), 1e-12)
        assertEquals(0.0, clipLon(360.0), 1e-12)
        assertEquals(180.0, clipLon(540.0), 1e-12)
        assertEquals(-180.0, clipLon(-540.0), 1e-12)
        assertEquals(-179.999, clipLon(180.001), 1e-9)
        assertEquals(179.999, clipLon(-180.001), 1e-9)
    }

    @Test
    fun `modulo rewrite is equivalent to the loop across the domain`() {
        // Sweep the same range the old implementation covered in a probe:
        // every 0.01 degree from -10000 to 10000 must agree with the reference
        // reduction to within 1e-9.
        var v = -10000.0
        while (v <= 10000.0) {
            val reference = clipLonByLoop(v)
            assertEquals(reference, clipLon(v), 1e-9)
            v += 0.01
        }
    }

    @Test
    fun `extreme and non-finite inputs return immediately`() {
        // These used to hang the calling thread (Infinity loop) or take seconds.
        assertEquals(Double.POSITIVE_INFINITY, clipLon(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, clipLon(Double.NEGATIVE_INFINITY), 0.0)
        assertTrue(clipLon(Double.NaN).isNaN())
        assertFalse(clipLon(1e15).isNaN())
        assertTrue(clipLon(1e15) in -180.0..180.0)
    }

    /** The old while-loop implementation, kept as the reference for equivalence. */
    private fun clipLonByLoop(longitude: Double): Double {
        var result = longitude
        while (result < -180.0) result += 360.0
        while (result > 180.0) result -= 360.0
        return minOf(maxOf(result, -180.0), 180.0)
    }
}
