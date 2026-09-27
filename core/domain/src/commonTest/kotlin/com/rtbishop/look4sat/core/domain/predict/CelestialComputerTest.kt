/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.domain.predict

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

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
            assertTrue(gha >= 0.0 && gha < 360.0, "gha=$gha out of 0..360 at $timeMillis")
            val longitude = subLunarLongitude(gha)
            assertTrue(
                longitude >= -180.0 && longitude <= 180.0,
                "sub-lunar longitude=$longitude out of -180..180 at $timeMillis"
            )
            samples++
            timeMillis += stepMillis
        }
        assertTrue(samples > 1000, "expected a meaningful sweep, got $samples samples")
    }

    private data class RiseSetCase(
        val name: String,
        val observer: GeoPos,
        val startIso: String
    )

    @Test
    fun `findSunRiseSet returns distinct sunrise and sunset for representative locations`() {
        val cases = listOf(
            RiseSetCase("Equator at March equinox", GeoPos(0.0, 0.0), "2026-03-20T00:00:00Z"),
            RiseSetCase("Equator at September equinox", GeoPos(0.0, 0.0), "2026-09-23T00:00:00Z"),
            RiseSetCase("Sydney winter", GeoPos(-33.8688, 151.2093), "2026-06-21T00:00:00Z"),
            RiseSetCase("Buenos Aires winter", GeoPos(-34.6037, -58.3816), "2026-06-21T00:00:00Z"),
            RiseSetCase("Cape Town winter", GeoPos(-33.9249, 18.4241), "2026-06-21T00:00:00Z"),
            RiseSetCase("London summer", GeoPos(51.5074, -0.1278), "2026-06-21T00:00:00Z")
        )

        cases.forEach { testCase ->
            val result = CelestialComputer.findSunRiseSet(testCase.observer, testCase.startIso.toMillis())
            val daylightDuration = result.setTimeMillis - result.riseTimeMillis

            assertTrue(result.riseTimeMillis > 0L, "${testCase.name}: sunrise should be non-zero")
            assertTrue(result.setTimeMillis > 0L, "${testCase.name}: sunset should be non-zero")
            assertTrue(result.setTimeMillis > result.riseTimeMillis, "${testCase.name}: sunset should be after sunrise")
            assertTrue(daylightDuration > HOUR_MILLIS, "${testCase.name}: daylight duration should be longer than 1 hour")
            assertTrue(daylightDuration < DAY_MILLIS, "${testCase.name}: daylight duration should be shorter than 24 hours")

            val riseElevation = CelestialComputer.getSunPosition(testCase.observer, result.riseTimeMillis).elevation
            val setElevation = CelestialComputer.getSunPosition(testCase.observer, result.setTimeMillis).elevation
            assertEquals(SUNRISE_SET_THRESHOLD, riseElevation, absoluteTolerance = 0.02, "${testCase.name}: sunrise should converge near the standard threshold")
            assertEquals(SUNRISE_SET_THRESHOLD, setElevation, absoluteTolerance = 0.02, "${testCase.name}: sunset should converge near the standard threshold")
        }
    }

    @Test
    fun `findSunRiseSet does not return the same instant for equinox regression cases`() {
        listOf("2026-03-20T00:00:00Z", "2026-09-23T00:00:00Z").forEach { startIso ->
            val result = CelestialComputer.findSunRiseSet(GeoPos(0.0, 0.0), startIso.toMillis())
            val separationMillis = abs(result.setTimeMillis - result.riseTimeMillis)

            assertTrue(separationMillis > HOUR_MILLIS, "$startIso: sunrise and sunset should be separated")
        }
    }

    /** ISO-8601 to epoch millis; kotlin.time.Instant is the multiplatform stand-in for the JVM's Instant. */
    private fun String.toMillis(): Long = Instant.parse(this).toEpochMilliseconds()

    private companion object {
        private const val SUNRISE_SET_THRESHOLD = -0.8333
        private const val HOUR_MILLIS = 60L * 60L * 1000L
        private const val DAY_MILLIS = 24L * HOUR_MILLIS
    }
}
