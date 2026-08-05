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
