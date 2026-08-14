package com.rtbishop.look4sat.feature.map

import com.rtbishop.look4sat.core.domain.predict.NearEarthObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The map info panel must describe the pass that is happening now, or the next
 * one if none is.
 *
 * Regression: selection used to filter on `OrbitalPass.progress < 1`. That field
 * defaults to 0 and is only ever updated on PassesViewModel's private copy, so
 * the predicate matched every pass and the map always described the satellite's
 * first pass - leaving the countdown frozen at 00:00:00 once it had ended.
 */
class CurrentOrNextPassTest {

    private val hour = 3_600_000L
    private val iss = 25544
    private val noaa = 33591

    private fun orbitalData(catnum: Int) = OrbitalData(
        name = "sat-$catnum",
        epoch = 21320.51955234,
        meanmo = 15.48582035,
        eccn = 0.0004694,
        incl = 51.6447,
        raan = 309.4881,
        argper = 203.6966,
        meanan = 299.8876,
        catnum = catnum,
        bstar = 0.31985e-4
    )

    private fun pass(catnum: Int, aos: Long, los: Long) = OrbitalPass(
        aosTime = aos,
        losTime = los,
        orbitalObject = NearEarthObject(orbitalData(catnum))
    )

    private val issFirst = pass(iss, 10 * hour, 10 * hour + 600_000)
    private val issSecond = pass(iss, 12 * hour, 12 * hour + 600_000)
    private val issThird = pass(iss, 14 * hour, 14 * hour + 600_000)
    private val other = pass(noaa, 11 * hour, 11 * hour + 600_000)
    private val passes = listOf(issFirst, other, issSecond, issThird)

    @Test
    fun `before the first pass returns the first pass`() {
        assertEquals(issFirst, selectCurrentOrNextPass(passes, iss, (9.5 * hour).toLong()))
    }

    @Test
    fun `during a pass returns that pass`() {
        assertEquals(issFirst, selectCurrentOrNextPass(passes, iss, 10 * hour + 300_000))
        assertEquals(issSecond, selectCurrentOrNextPass(passes, iss, 12 * hour + 300_000))
    }

    @Test
    fun `after a pass ends returns the following pass`() {
        // The case the progress predicate got wrong: it kept returning issFirst.
        assertEquals(issSecond, selectCurrentOrNextPass(passes, iss, (10.5 * hour).toLong()))
        assertEquals(issThird, selectCurrentOrNextPass(passes, iss, 13 * hour))
    }

    @Test
    fun `after the last pass returns null`() {
        assertNull(selectCurrentOrNextPass(passes, iss, 15 * hour))
    }

    @Test
    fun `other satellites are ignored`() {
        assertEquals(other, selectCurrentOrNextPass(passes, noaa, 11 * hour + 60_000))
        assertNull(selectCurrentOrNextPass(passes, noaa, 12 * hour))
    }

    @Test
    fun `exact aos belongs to the pass and exact los does not`() {
        assertEquals(issFirst, selectCurrentOrNextPass(passes, iss, 10 * hour))
        assertEquals(issSecond, selectCurrentOrNextPass(passes, iss, 10 * hour + 600_000))
    }
}
