package com.rtbishop.look4sat.core.domain.predict

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Moon's Greenwich Hour Angle must be a reduced angle: callers turn it into a
 * sub-lunar longitude with `if (gha <= 180) -gha else 360 - gha`, which only
 * yields a valid longitude when gha is within 0..360.
 *
 * Regression: the GMST polynomial feeding the hour angle is never reduced (about
 * 3.5e6 degrees at present), so the old `if (gha < 0) gha += 360` guard never
 * fired and every sub-lunar longitude landed millions of degrees off the map.
 */
class CelestialComputerTest {

    private val observer = GeoPos(22.314066, 108.706575)

    private fun subLunarLongitude(gha: Double) = if (gha <= 180.0) -gha else 360.0 - gha

    @Test
    fun `moon hour angle stays within one revolution`() {
        // Sample a full synodic month at 37-minute steps so the sweep crosses
        // every hour-angle quadrant many times over.
        var timeMillis = 1786060800_000L // 2026-08-07T00:00:00Z
        val stepMillis = 37 * 60 * 1000L
        val endMillis = timeMillis + 30L * 86400_000L
        var samples = 0
        while (timeMillis < endMillis) {
            val gha = CelestialComputer.getMoonPosition(observer, timeMillis).gha
            assertTrue("gha=$gha out of 0..360 at $timeMillis", gha >= 0.0 && gha < 360.0)
            val longitude = subLunarLongitude(gha)
            assertTrue(
                "sub-lunar longitude=$longitude out of -180..180 at $timeMillis",
                longitude >= -180.0 && longitude <= 180.0
            )
            samples++
            timeMillis += stepMillis
        }
        assertTrue("expected a meaningful sweep, got $samples samples", samples > 1000)
    }

    @Test
    fun `moon hour angle advances westward over an hour`() {
        // The Moon's hour angle grows by roughly 14.5 degrees per hour, so an
        // hour apart the two readings must differ but stay reduced.
        val base = 1786060800_000L
        val first = CelestialComputer.getMoonPosition(observer, base).gha
        val later = CelestialComputer.getMoonPosition(observer, base + 3_600_000L).gha
        assertTrue("first=$first", first >= 0.0 && first < 360.0)
        assertTrue("later=$later", later >= 0.0 && later < 360.0)
        val delta = ((later - first) % 360.0 + 360.0) % 360.0
        assertTrue("hourly advance was $delta degrees", delta in 10.0..20.0)
    }
}
