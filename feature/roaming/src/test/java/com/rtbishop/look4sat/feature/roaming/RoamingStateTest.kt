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
package com.rtbishop.look4sat.feature.roaming

import org.junit.Assert.assertEquals
import org.junit.Test

class RoamingStateTest {

    // User-verified coordinates (QTH Locator 2.0 screenshot): 22.314066 / 108.706575
    private fun stateOf(lat: Double, lon: Double) =
        roamingStateFromLocation(lat, lon, System.currentTimeMillis(), "21:")

    @Test
    fun `user coordinates produce OL42ih45`() {
        val state = stateOf(22.314066, 108.706575)
        assertEquals("OL42ih45", state.loc)
    }

    @Test
    fun `red marker from ih pair matches reference lookup`() {
        val state = stateOf(22.314066, 108.706575)
        // Original lookup: lon 3rd pair 'i' -> leftMargin 65dp, lat 3rd pair 'h' -> topMargin 137dp
        assertEquals(65, state.markerLeft)
        assertEquals(137, state.markerTop)
    }

    @Test
    fun `grid nine from OL42 matches reference`() {
        val state = stateOf(22.314066, 108.706575)
        val expected = listOf(
            "OL33", "OL43", "OL53",
            "OL32", "OL42", "OL52",
            "OL31", "OL41", "OL51"
        )
        assertEquals(expected, state.grids)
    }

    @Test
    fun `latitude dms formatting matches reference`() {
        val state = stateOf(22.314066, 108.706575)
        assertEquals("纬度  22° 18' 50\" N", state.latLabel)
        assertEquals("22.314066° ", state.latValue)
        assertEquals("经度 108° 42' 23\" E", state.lonLabel)
        assertEquals("108.706575° ", state.lonValue)
    }

    @Test
    fun `time is hour prefix plus fix minutes`() {
        // fixTime = 2026-08-03 21:12:13 UTC+8 -> minutes 12
        val fix = 1785820333000L // 2026-08-03 21:12:13 +0800
        val state = roamingStateFromLocation(22.314066, 108.706575, fix, "21:")
        assertEquals("21:12", state.time)
    }

    @Test
    fun `west edge borrows lon letter`() {
        // lon 0.5 lat 22.314066: digit pair 02, west edge parseInt==0
        val state = stateOf(22.314066, 0.5)
        assertEquals(8, state.loc.length)
        // Left column lon 0->9, lon letter J-1=I: IL92
        assertEquals("IL92", state.grids[3])
    }

    @Test
    fun `north edge carries lat letter`() {
        // lat 29.5 lon 108.706575: digit pair 49? -> lat digit 9 -> north edge
        val state = stateOf(29.5, 108.706575)
        assertEquals(8, state.loc.length)
        // Top row lat +1 -> 0, lat letter +1 (M)
        assertEquals("OM40", state.grids[1])
    }

    @Test
    fun `east edge carries lon letter`() {
        // lon 119.5 lat 22.314066: lon digit 9 -> east edge
        val state = stateOf(22.314066, 119.5)
        assertEquals(8, state.loc.length)
        // Top-right lon 9->0, lon letter O+1=P, lat 2+1=3: PL03
        assertEquals("PL03", state.grids[2])
    }

    @Test
    fun `south edge borrows lat letter`() {
        // lat 0.5 lon 108.706575: lat digit 0 -> south edge
        val state = stateOf(0.5, 108.706575)
        assertEquals(8, state.loc.length)
        // Bottom-center lat 0->9, lat letter J-1=I: OI49
        assertEquals("OI49", state.grids[7])
    }

    @Test
    fun `boundary values wrap like reference`() {
        // (90, 180) -> RR99xx99 (original range-lookup behavior)
        val state = stateOf(90.0, 180.0)
        assertEquals("RR99xx99", state.loc)
    }

    @Test
    fun `exact cell boundaries belong to the upper cell`() {
        // The decompiled range table closes both adjacent cells (-20..0 then
        // 0..20) and `when` takes the first match, so an exact boundary used to
        // fall back into the previous cell: (0,0) encoded as II99xx99.
        assertEquals("JJ00aa00", stateOf(0.0, 0.0).loc)
        assertEquals("JJ01ma00", stateOf(1.0, 1.0).loc)
        assertEquals("OL42aa00", stateOf(22.0, 108.0).loc)
        assertEquals("OL42gm00", stateOf(22.5, 108.5).loc)
        assertEquals("OL42dg00", stateOf(22.25, 108.25).loc)
    }

    @Test
    fun `matches the independent Maidenhead converter across the grid`() {
        // Two independent implementations of the same standard must agree;
        // divergence previously affected every exact-boundary coordinate.
        var lat = -90.0
        while (lat <= 90.0) {
            var lon = -180.0
            while (lon <= 180.0) {
                val ported = stateOf(lat, lon).loc
                val reference =
                    com.rtbishop.look4sat.core.domain.utility.positionToQth(lat, lon, 8)
                assertEquals("mismatch at ($lat, $lon)", reference?.lowercase(), ported.lowercase())
                lon += 5.0
            }
            lat += 5.0
        }
    }

    @Test
    fun `neighbour grid stays inside the A to R alphabet worldwide`() {
        // The ported edge branches stepped field letters with raw character
        // arithmetic and produced cells like "@A91" (A-1) or "SS00" (R+1).
        var lat = -89.5
        while (lat <= 89.5) {
            var lon = -179.5
            while (lon <= 179.5) {
                stateOf(lat, lon).grids.forEach { cell ->
                    assertEquals("cell length at ($lat, $lon): $cell", 4, cell.length)
                    assert(cell[0] in 'A'..'R' && cell[1] in 'A'..'R') {
                        "field letters out of range at ($lat, $lon): $cell"
                    }
                    assert(cell[2].isDigit() && cell[3].isDigit()) {
                        "square digits out of range at ($lat, $lon): $cell"
                    }
                }
                lon += 7.0
            }
            lat += 7.0
        }
    }

    @Test
    fun `neighbour grid carries the field letter on every moved cell`() {
        // North edge: latitude square 9 -> 0 must advance the latitude field for
        // all three cells of the top row, not just the middle one.
        assertEquals(
            listOf("AB00", "AB10", "AB20", "AA09", "AA19", "AA29", "AA08", "AA18", "AA28"),
            stateOf(-80.5, -177.5).grids
        )
        // South-west corner of the world wraps in both axes.
        assertEquals(
            listOf("RA91", "AA01", "AA11", "RA90", "AA00", "AA10", "RR99", "AR09", "AR19"),
            stateOf(-89.5, -179.5).grids
        )
    }

    @Test
    fun `gps state after fix matches showLocation`() {
        val state = stateOf(22.314066, 108.706575)
        assertEquals(true, state.gpsOn)
        assertEquals(false, state.gpsOff)
        assertEquals(false, state.showSettings)
        assertEquals(false, state.showProgress)
        assertEquals(false, state.showNotice)
        assertEquals(true, state.showDate)
    }
}
